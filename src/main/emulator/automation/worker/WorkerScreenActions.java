package emulator.automation.worker;

import emulator.Emulator;
import emulator.automation.shared.AutomationErrorCodes;
import emulator.automation.shared.AutomationException;
import emulator.ui.IEmulatorFrontend;
import emulator.ui.IScreen;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import mjson.Json;

final class WorkerScreenActions {
	// EventQueue.sizeChanged() packs each dimension into 12 bits.
	private static final int MAX_DIMENSION = 4095;

	private WorkerScreenActions() {
	}

	private static IScreen requireScreen() {
		IEmulatorFrontend frontend = Emulator.getEmulator();
		IScreen screen = frontend == null ? null : frontend.getScreen();
		if (screen == null) {
			throw new AutomationException(
				AutomationErrorCodes.APP_INPUT_UNAVAILABLE,
				"Emulator screen is not available");
		}

		return screen;
	}

	private static void checkRevision(Json request) {
		if (!request.has("expectRevision") || request.at("expectRevision").isNull()) {
			return;
		}
		long expected = request.at("expectRevision").asLong();
		long current = WorkerEventModel.revision();
		if (expected != current) {
			throw new AutomationException(
				AutomationErrorCodes.STALE_REVISION,
				"Stale revision: " + expected + ", current: " + current,
				Json.object().set("expectedRevision", expected).set("currentRevision", current));
		}
	}

	private static void validateDimension(int value, String name) {
		if (value < 1 || value > MAX_DIMENSION) {
			throw new AutomationException(
				AutomationErrorCodes.INVALID_REQUEST,
				name + " must be between 1 and " + MAX_DIMENSION + ": " + value,
				Json.object().set(name, value));
		}
	}

	private static Json applySize(final Json request, final Integer requestedWidth, final Integer requestedHeight) {
		long start = System.nanoTime();
		final long frameRevisionBefore = WorkerEventModel.frameRevision();
		Json applied = WorkerFrontendThread.call(new Callable<Json>() {
			public Json call() {
				IScreen screen = requireScreen();
				checkRevision(request);
				int oldWidth = screen.getWidth();
				int oldHeight = screen.getHeight();
				int width = requestedWidth == null ? oldHeight : requestedWidth.intValue();
				int height = requestedHeight == null ? oldWidth : requestedHeight.intValue();
				validateDimension(width, "width");
				validateDimension(height, "height");
				long oldRevision = WorkerEventModel.revision();
				screen.setSize(width, height);
				long newRevision = WorkerEventModel.stateChanged(
					"screen-resized",
					Json.object()
						.set("oldWidth", oldWidth)
						.set("oldHeight", oldHeight)
						.set("width", screen.getWidth())
						.set("height", screen.getHeight()));
				screen.repaint();

				return Json.object()
					.set("oldWidth", oldWidth)
					.set("oldHeight", oldHeight)
					.set("width", screen.getWidth())
					.set("height", screen.getHeight())
					.set("oldRevision", oldRevision)
					.set("newRevision", newRevision);
			}
		});

		WorkerCommands.invalidate();
		if (request.at("waitFrame", false).asBoolean()) {
			long timeoutMs = request.at("timeoutMs", 5000L).asLong();
			try {
				if (!WorkerEventModel.awaitFrameAfter(frameRevisionBefore, timeoutMs)) {
					throw new AutomationException(
						AutomationErrorCodes.TIMEOUT,
						"Timed out waiting for a frame after the resize",
						Json.object()
							.set("timeoutMs", timeoutMs)
							.set("applied", applied)
							.set("frameRevision", WorkerEventModel.frameRevision()));
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new AutomationException(
					AutomationErrorCodes.WORKER_FAILURE,
					"Interrupted while waiting for a frame after the resize",
					null,
					e);
			}
		}

		applied.set("frameRevision", WorkerEventModel.frameRevision());
		applied.set("elapsedMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
		applied.set("state", WorkerSessionSnapshot.build(false));

		return applied;
	}

	static Json resize(Json request) {
		int width = request.at("width", -1).asInteger();
		int height = request.at("height", -1).asInteger();
		validateDimension(width, "width");
		validateDimension(height, "height");

		return applySize(request, Integer.valueOf(width), Integer.valueOf(height));
	}

	static Json rotate(Json request) {
		// Width and height are swapped atomically on the frontend thread.
		return applySize(request, null, null);
	}
}
