package emulator.automation.worker;

import emulator.Emulator;
import emulator.automation.shared.AutomationErrorCodes;
import emulator.graphics2D.CopyUtils;
import emulator.graphics2D.IImage;
import emulator.ui.IScreen;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Screen;
import mjson.Json;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.graphics.Transform;
import org.eclipse.swt.widgets.Control;

/** Keeps one immutable copy of the most recently completed display frame. */
public final class WorkerFrameCapture {
	private static final Object FRAME_LOCK = new Object();
	private static Displayable currentOwner;
	private static Frame completedFrame;
	private static long displayGeneration;
	private static long frameId;

	private WorkerFrameCapture() {
	}

	private static final class Frame {
		final Displayable owner;
		final IImage source;
		final int width, height, contentWidth, contentHeight;
		final int[] pixels;
		final long id;

		Frame(Displayable owner, IImage source, int width, int height,
				int contentWidth, int contentHeight, int[] pixels, long id) {
			this.owner = owner;
			this.source = source;
			this.width = width;
			this.height = height;
			this.contentWidth = contentWidth;
			this.contentHeight = contentHeight;
			this.pixels = pixels;
			this.id = id;
		}
	}

	private static Displayable current() {
		Display display = Emulator.getCurrentDisplay();
		return display == null ? null : display.getCurrent();
	}

	private static void synchronizeOwner() {
		Displayable owner = current();
		if (currentOwner != owner) {
			currentOwner = owner;
			completedFrame = null;
			displayGeneration++;
			FRAME_LOCK.notifyAll();
		}
	}

	/** Called on display changes, including a return to a previously shown Canvas. */
	public static void displayChanged() {
		synchronized (FRAME_LOCK) {
			synchronizeOwner();
		}
	}

	/** Called at the actual Display.current assignment, before asynchronous show/paint work. */
	public static void displayChanging(Displayable owner) {
		synchronized (FRAME_LOCK) {
			currentOwner = owner;
			completedFrame = null;
			displayGeneration++;
			FRAME_LOCK.notifyAll();
		}
	}

	/** A newly allocated screen buffer has no completed frame yet. */
	public static void geometryChanged() {
		synchronized (FRAME_LOCK) {
			IScreen screen = Emulator.getEmulator().getScreen();
			if (completedFrame == null || completedFrame.source != screen.getScreenImg()) {
				completedFrame = null;
				displayGeneration++;
			}
			FRAME_LOCK.notifyAll();
		}
	}

	/** Called by the painter, with its actual owner, before it can start another frame. */
	public static void publish(Displayable owner, IImage image) {
		publish(owner, image, paintStarted(owner));
	}

	/** Binds a paint to the display switch generation at which its callback started. */
	public static long paintStarted(Displayable owner) {
		synchronized (FRAME_LOCK) {
			synchronizeOwner();
			return owner == currentOwner ? displayGeneration : -1L;
		}
	}

	public static void publish(Displayable owner, IImage image, long generation) {
		if (!AutomationWorkerRuntime.isEnabled() || owner == null || image == null) return;
		synchronized (FRAME_LOCK) {
			synchronizeOwner();
			if (owner != currentOwner || generation != displayGeneration) return;
		}
		int width = image.getWidth();
		int height = image.getHeight();
		int contentWidth = owner.getWidth();
		int contentHeight = owner.getHeight();
		int[] pixels = image.getData().clone();
		synchronized (FRAME_LOCK) {
			synchronizeOwner();
			IScreen screen = Emulator.getEmulator().getScreen();
			if (generation != displayGeneration || owner != currentOwner
					|| image != screen.getScreenImg()
					|| width != screen.getWidth() || height != screen.getHeight()
					|| contentWidth != owner.getWidth() || contentHeight != owner.getHeight()) return;
			completedFrame = new Frame(owner, image, width, height,
				contentWidth, contentHeight, pixels, ++frameId);
			FRAME_LOCK.notifyAll();
		}
	}

	public static long frameId() {
		synchronized (FRAME_LOCK) {
			return frameId;
		}
	}

	/** Waiting must happen on an RPC thread, never the frontend or LCDUI event thread. */
	public static Json capture(final Displayable expected, long timeoutMs) {
		return captureAfter(expected, -1L, timeoutMs);
	}

	public static Json captureAfter(final Displayable expected, long afterFrame, long timeoutMs) {
		if (expected instanceof Screen && ((Screen) expected)._isSWT()) {
			Json capture = captureNative((Screen) expected, timeoutMs);
			return capture.has("error") || capture.at("frameId").asLong() > afterFrame ? capture
				: unavailable(expected, "No frame newer than the requested frame has completed.");
		}
		long started = System.nanoTime();
		long budget = TimeUnit.MILLISECONDS.toNanos(Math.max(0, timeoutMs));
		Frame frame;
		synchronized (FRAME_LOCK) {
			for (;;) {
				synchronizeOwner();
				if (expected == null || expected != currentOwner) {
					return unavailable(expected, "The display changed before its frame could be captured.");
				}
				IScreen screen = Emulator.getEmulator().getScreen();
				frame = completedFrame;
				if (frame != null && frame.owner == expected
						&& frame.id > afterFrame
						&& frame.source == screen.getScreenImg()
						&& frame.width == screen.getWidth() && frame.height == screen.getHeight()
						&& frame.contentWidth == expected.getWidth()
						&& frame.contentHeight == expected.getHeight()) break;
				long remaining = budget - (System.nanoTime() - started);
				if (remaining <= 0 || org.eclipse.swt.widgets.Display.getCurrent() != null
						|| (Emulator.getEventQueue() != null && Emulator.getEventQueue().isEventThread())) {
					return unavailable(expected, afterFrame < 0
						? "The current display has no completed frame yet."
						: "No newer completed frame is available for the current display.");
				}
				try {
					FRAME_LOCK.wait(remaining / 1000000L, (int) (remaining % 1000000L));
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					return unavailable(expected, "Waiting for the current frame was interrupted.");
				}
			}
		}
		return encode(frame);
	}

	private static Json captureNative(final Screen expected, long timeoutMs) {
		try {
			Frame frame = WorkerFrontendThread.call(new Callable<Frame>() {
				public Frame call() {
					if (current() != expected) return null;
					long generation = paintStarted(expected);
					Object content = expected._getSwtContent();
					if (!(content instanceof Control)) return null;
					Control control = (Control) content;
					if (control.isDisposed()) return null;
					IScreen screen = Emulator.getEmulator().getScreen();
					int width = screen.getWidth(), height = screen.getHeight();
					Rectangle bounds = control.getBounds();
					if (bounds.width < 1 || bounds.height < 1) return null;
					Image image = new Image(control.getDisplay(), width, height);
					GC graphics = new GC(image);
					Transform transform = new Transform(control.getDisplay());
					try {
						transform.scale((float) width / bounds.width, (float) height / bounds.height);
						graphics.setTransform(transform);
						if (!control.print(graphics) || current() != expected) return null;
						BufferedImage copy = CopyUtils.toAwtForCapture(image.getImageData());
						int[] pixels = copy.getRGB(0, 0, width, height, null, 0, width);
						synchronized (FRAME_LOCK) {
							synchronizeOwner();
							if (currentOwner != expected || generation != displayGeneration) return null;
							return new Frame(expected, null, width, height,
								expected.getWidth(), expected.getHeight(), pixels, ++frameId);
						}
					} finally {
						transform.dispose();
						graphics.dispose();
						image.dispose();
					}
				}
			}, timeoutMs);
			return frame == null ? unavailable(expected, "The current native screen could not be captured.")
				: encode(frame);
		} catch (RuntimeException failure) {
			return unavailable(expected, "The current native screen could not be captured.");
		}
	}

	private static Json encode(Frame frame) {
		try {
			BufferedImage image = new BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_ARGB);
			image.setRGB(0, 0, frame.width, frame.height, frame.pixels, 0, frame.width);
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			if (!ImageIO.write(image, "png", output)) throw new IOException("PNG encoder unavailable");
			return Json.object().set("imageBase64", Base64.getEncoder().encodeToString(output.toByteArray()))
				.set("width", frame.width).set("height", frame.height).set("frameId", frame.id);
		} catch (IOException failure) {
			return unavailable(frame.owner, "The completed frame could not be encoded as PNG.");
		}
	}

	private static Json unavailable(Displayable expected, String message) {
		return Json.object().set("error", Json.object().set("code", AutomationErrorCodes.FRAME_NOT_READY)
			.set("message", message).set("details", Json.object()
				.set("width", expected == null ? 0 : expected.getWidth())
				.set("height", expected == null ? 0 : expected.getHeight())
				.set("frameId", frameId())));
	}
}
