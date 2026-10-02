package emulator.automation.worker;

import emulator.Emulator;
import emulator.EventQueue;
import emulator.KeyMapping;
import emulator.automation.shared.AutomationErrorCodes;
import emulator.automation.shared.AutomationException;
import emulator.automation.shared.AutomationLimits;
import emulator.automation.shared.OperationDeadline;
import java.util.ArrayList;
import java.util.List;
import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;
import mjson.Json;

final class WorkerInputActions {
	private WorkerInputActions() {
	}

	static int resolveKeyCode(String key, Json codeValue) {
		if (codeValue != null && !codeValue.isNull()) {
			try {
				return codeValue.asInteger();
			} catch (RuntimeException ignored) {
			}
		}

		if (key == null) {
			throw new AutomationException(AutomationErrorCodes.INVALID_REQUEST, "press-key requires key or code");
		}

		String normalized = key.trim().toUpperCase().replace('-', '_');
		if (normalized.startsWith("NUM") && normalized.length() == 4) {
			return Character.forDigit(normalized.charAt(3) - '0', 10);
		}

		if (normalized.startsWith("NUM_") && normalized.length() == 5) {
			return Character.forDigit(normalized.charAt(4) - '0', 10);
		}

		if ("0".equals(normalized))
			return Canvas.KEY_NUM0;
		if ("1".equals(normalized))
			return Canvas.KEY_NUM1;
		if ("2".equals(normalized))
			return Canvas.KEY_NUM2;
		if ("3".equals(normalized))
			return Canvas.KEY_NUM3;
		if ("4".equals(normalized))
			return Canvas.KEY_NUM4;
		if ("5".equals(normalized))
			return Canvas.KEY_NUM5;
		if ("6".equals(normalized))
			return Canvas.KEY_NUM6;
		if ("7".equals(normalized))
			return Canvas.KEY_NUM7;
		if ("8".equals(normalized))
			return Canvas.KEY_NUM8;
		if ("9".equals(normalized))
			return Canvas.KEY_NUM9;
		if ("STAR".equals(normalized) || "*".equals(normalized))
			return Canvas.KEY_STAR;
		if ("POUND".equals(normalized) || "HASH".equals(normalized) || "#".equals(normalized))
			return Canvas.KEY_POUND;
		if ("UP".equals(normalized))
			return KeyMapping.getArrowKeyFromDevice(Canvas.UP);
		if ("DOWN".equals(normalized))
			return KeyMapping.getArrowKeyFromDevice(Canvas.DOWN);
		if ("LEFT".equals(normalized))
			return KeyMapping.getArrowKeyFromDevice(Canvas.LEFT);
		if ("RIGHT".equals(normalized))
			return KeyMapping.getArrowKeyFromDevice(Canvas.RIGHT);
		if ("FIRE".equals(normalized) || "MIDDLE".equals(normalized) || "OK".equals(normalized))
			return KeyMapping.getArrowKeyFromDevice(Canvas.FIRE);
		if ("LSK".equals(normalized) || "SOFT_LEFT".equals(normalized) || "S1".equals(normalized))
			return KeyMapping.soft1();
		if ("RSK".equals(normalized) || "SOFT_RIGHT".equals(normalized) || "S2".equals(normalized))
			return KeyMapping.soft2();
		throw new AutomationException(
			AutomationErrorCodes.UNKNOWN_KEY,
			"Unknown key: " + key,
			Json.object().set("key", key));
	}


	private static String classifyKey(int code) {
		Display display = Emulator.getCurrentDisplay();
		Displayable current = display == null ? null : display.getCurrent();
		if (KeyMapping.isLeftSoft(code) || KeyMapping.isRightSoft(code)) return "nokia-softkey";
		if (current instanceof Canvas) return "raw-canvas-key";
		if (current instanceof javax.microedition.lcdui.List
			&& (code == KeyMapping.getArrowKeyFromDevice(Canvas.UP)
				|| code == KeyMapping.getArrowKeyFromDevice(Canvas.DOWN)
				|| code == KeyMapping.getArrowKeyFromDevice(Canvas.LEFT)
				|| code == KeyMapping.getArrowKeyFromDevice(Canvas.RIGHT))) return "list-navigation";
		return "native-lcdui-key";
	}

	private static EventQueue requireQueue() {
		EventQueue queue = Emulator.getEventQueue();
		Display display = Emulator.getCurrentDisplay();
		if (queue == null || display == null || display.getCurrent() == null) {
			throw new AutomationException(AutomationErrorCodes.APP_INPUT_UNAVAILABLE, "Application input is not available.");
		}
		return queue;
	}

	/** One operation's sequences; EventQueue retains only callbacks still in flight. */
	private static final class InputStroke {
		final EventQueue queue;
		final List<Integer> sequences = new ArrayList<Integer>();
		final boolean composite;
		volatile int pressSequence;
		volatile int releaseSequence;
		volatile boolean releaseQueued;

		InputStroke(EventQueue queue, boolean composite) {
			this.queue = queue;
			this.composite = composite;
		}

		synchronized void add(int sequence) {
			sequences.add(Integer.valueOf(sequence));
		}

		synchronized int[] sequenceSnapshot() {
			int[] result = new int[sequences.size()];
			for (int i = 0; i < result.length; i++) result[i] = sequences.get(i).intValue();
			return result;
		}

		boolean dispatched() {
			if (composite && !releaseQueued) return false;
			for (int sequence : sequenceSnapshot()) {
				if (!queue.isInputDispatched(sequence)) return false;
			}
			return true;
		}

		Json receipt(String kind, String phase) {
			Json receipt = Json.object().set("kind", kind).set("dispatched", dispatched())
				.set("admitted", true).set("revision", WorkerEventModel.revision());
			if (phase != null) receipt.set("phase", phase);
			if (pressSequence > 0) receipt.set("pressSequence", pressSequence)
				.set("pressDispatched", queue.isInputDispatched(pressSequence));
			if (releaseSequence > 0) receipt.set("releaseSequence", releaseSequence)
				.set("releaseDispatched", queue.isInputDispatched(releaseSequence));
			if (composite) receipt.set("releaseScheduled", true)
				.set("releaseDispatched", releaseQueued && queue.isInputDispatched(releaseSequence));
			return receipt;
		}
	}

	private static OperationDeadline inputDeadline(Json request) {
		OperationDeadline deadline = OperationDeadline.fromRequest(request, AutomationLimits.DEFAULT_TIMEOUT_MS);
		if (deadline.timedOut()) {
			throw new AutomationException(AutomationErrorCodes.TIMEOUT, "Input budget expired before admission",
				Json.object().set("effectUnknown", false).set("admitted", false).set("performed", false));
		}
		return deadline;
	}

	private static Json awaitStroke(InputStroke stroke, OperationDeadline deadline,
		boolean waitDispatched, String kind, String phase) {
		if (waitDispatched) {
			while (!stroke.dispatched()) {
				WorkerPermissions.PendingPermission permission = WorkerPermissions.snapshotForInput(stroke.sequenceSnapshot());
				if (permission != null) {
					return stroke.receipt(kind, phase).set("pending", true).set("status", "pending-permission")
						.set("permissionRequest", permission.toJson()).set("state", WorkerSessionSnapshot.build(false));
				}
				if (deadline.timedOut()) throw inputTimeout(stroke, deadline, "Timed out waiting for input callbacks");
				try {
					Thread.sleep(Math.min(20L, deadline.remainingMillis()));
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw inputTimeout(stroke, deadline, "Interrupted while waiting for input callbacks");
				}
			}
		}
		return stroke.receipt(kind, phase);
	}

	private static AutomationException inputTimeout(InputStroke stroke, OperationDeadline deadline, String message) {
		Json sequences = Json.array();
		for (int sequence : stroke.sequenceSnapshot()) sequences.add(sequence);
		Json details = Json.object().set("effectUnknown", true).set("admitted", true).set("performed", Json.nil())
			.set("timeoutMs", deadline.timeoutMillis()).set("elapsedMs", deadline.elapsedMillis()).set("sequences", sequences);
		if (stroke.composite) details.set("releaseScheduled", true);
		return new AutomationException(AutomationErrorCodes.TIMEOUT, message, details);
	}

	private static void scheduleRelease(final InputStroke stroke, final int code,
		final int[][] points, final int delayMs) {
		Thread releaseThread = new Thread(new Runnable() {
			public void run() {
				int x = points == null ? 0 : points[0][0];
				int y = points == null ? 0 : points[0][1];
				try {
					Thread.sleep(delayMs);
					if (points != null) {
						for (int i = 1; i < points.length; i++) {
							x = points[i][0]; y = points[i][1];
							stroke.add(stroke.queue.mouseDragTracked(x, y, 0));
							Thread.sleep(delayMs);
						}
					}
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				} finally {
					stroke.releaseSequence = points == null ? stroke.queue.keyReleaseTracked(code)
						: stroke.queue.mouseUpTracked(x, y, 0);
					stroke.add(stroke.releaseSequence);
					stroke.releaseQueued = true;
				}
			}
		}, "KEmulator-Automation-Input-Release");
		releaseThread.setDaemon(true);
		try {
			releaseThread.start();
		} catch (RuntimeException e) {
			stroke.releaseSequence = points == null ? stroke.queue.keyReleaseTracked(code)
				: stroke.queue.mouseUpTracked(points[0][0], points[0][1], 0);
			stroke.add(stroke.releaseSequence);
			stroke.releaseQueued = true;
			throw e;
		}
	}

	static Json pressKey(int code, int durationMs, boolean waitDispatched, boolean waitRelease, Json request) {
		OperationDeadline deadline = inputDeadline(request);
		InputStroke stroke = new InputStroke(requireQueue(), true);
		String kind = classifyKey(code);
		stroke.pressSequence = stroke.queue.keyPressTracked(code);
		stroke.add(stroke.pressSequence);
		scheduleRelease(stroke, code, null, durationMs);
		return awaitStroke(stroke, deadline, waitDispatched || waitRelease, kind, null);
	}
	static Json pressKey(int code, int durationMs, boolean waitDispatched, boolean waitRelease) {
		return pressKey(code, durationMs, waitDispatched, waitRelease, Json.object());
	}

	private static Json keyHalf(int code, boolean down, boolean waitDispatched, Json request) {
		OperationDeadline deadline = inputDeadline(request);
		InputStroke stroke = new InputStroke(requireQueue(), false);
		String kind = classifyKey(code);
		int sequence = down ? stroke.queue.keyPressTracked(code) : stroke.queue.keyReleaseTracked(code);
		if (down) stroke.pressSequence = sequence; else stroke.releaseSequence = sequence;
		stroke.add(sequence);
		return awaitStroke(stroke, deadline, waitDispatched, kind, down ? "down" : "up");
	}
	static Json keyDown(int code, boolean waitDispatched, Json request) {
		return keyHalf(code, true, waitDispatched, request);
	}
	static Json keyDown(int code, boolean waitDispatched) { return keyDown(code, waitDispatched, Json.object()); }
	static Json keyUp(int code, boolean waitDispatched, Json request) {
		return keyHalf(code, false, waitDispatched, request);
	}
	static Json keyUp(int code, boolean waitDispatched) { return keyUp(code, waitDispatched, Json.object()); }

	private static Json pointerHalf(int x, int y, boolean down, boolean waitDispatched, Json request) {
		OperationDeadline deadline = inputDeadline(request);
		InputStroke stroke = new InputStroke(requireQueue(), false);
		int sequence = down ? stroke.queue.mouseDownTracked(x, y, 0) : stroke.queue.mouseUpTracked(x, y, 0);
		if (down) stroke.pressSequence = sequence; else stroke.releaseSequence = sequence;
		stroke.add(sequence);
		return awaitStroke(stroke, deadline, waitDispatched, "pointer-event", down ? "down" : "up");
	}
	static Json pointerDown(int x, int y, boolean waitDispatched, Json request) {
		return pointerHalf(x, y, true, waitDispatched, request);
	}
	static Json pointerDown(int x, int y, boolean waitDispatched) { return pointerDown(x, y, waitDispatched, Json.object()); }
	static Json pointerUp(int x, int y, boolean waitDispatched, Json request) {
		return pointerHalf(x, y, false, waitDispatched, request);
	}
	static Json pointerUp(int x, int y, boolean waitDispatched) { return pointerUp(x, y, waitDispatched, Json.object()); }

	static Json tap(int x, int y, boolean waitDispatched, Json request) {
		OperationDeadline deadline = inputDeadline(request);
		InputStroke stroke = new InputStroke(requireQueue(), true);
		stroke.pressSequence = stroke.queue.mouseDownTracked(x, y, 0);
		stroke.add(stroke.pressSequence);
		scheduleRelease(stroke, 0, new int[][] {{x, y}}, 30);
		return awaitStroke(stroke, deadline, waitDispatched, "pointer-event", null);
	}
	static Json tap(int x, int y, boolean waitDispatched) { return tap(x, y, waitDispatched, Json.object()); }

	static Json drag(Json points, int delayMs, boolean waitDispatched, Json request) {
		OperationDeadline deadline = inputDeadline(request);
		int[][] coordinates = new int[points.asJsonList().size()][2];
		if (coordinates.length < 2) {
			throw new AutomationException(AutomationErrorCodes.INVALID_REQUEST, "drag requires at least two points");
		}
		for (int i = 0; i < coordinates.length; i++) {
			coordinates[i][0] = points.at(i).at("x", -1).asInteger();
			coordinates[i][1] = points.at(i).at("y", -1).asInteger();
			if (coordinates[i][0] < 0 || coordinates[i][1] < 0) {
				throw new AutomationException(AutomationErrorCodes.INVALID_REQUEST, "drag point requires x and y");
			}
		}
		InputStroke stroke = new InputStroke(requireQueue(), true);
		stroke.pressSequence = stroke.queue.mouseDownTracked(coordinates[0][0], coordinates[0][1], 0);
		stroke.add(stroke.pressSequence);
		scheduleRelease(stroke, 0, coordinates, delayMs);
		return awaitStroke(stroke, deadline, waitDispatched, "pointer-event", null);
	}
	static Json drag(Json points, int delayMs, boolean waitDispatched) {
		return drag(points, delayMs, waitDispatched, Json.object());
	}
}
