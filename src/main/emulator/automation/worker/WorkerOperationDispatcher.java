package emulator.automation.worker;

import emulator.automation.shared.AutomationErrorCodes;
import emulator.automation.shared.AutomationException;
import emulator.automation.shared.AutomationLimits;
import mjson.Json;

final class WorkerOperationDispatcher {
	interface ShutdownRequester {
		void requestRuntimeShutdown(String reason);
	}

	private WorkerOperationDispatcher() {
	}

	static Json dispatch(String op, Json request, ShutdownRequester shutdownRequester) {
		if (request.has("timeoutMs") && !request.at("timeoutMs").isNull()) {
			long timeoutMs = request.at("timeoutMs").asLong();
			if (timeoutMs < 0L || timeoutMs > AutomationLimits.MAX_WAIT_MS) {
				throw new AutomationException(
					AutomationErrorCodes.INVALID_REQUEST,
					"timeoutMs must be between 0 and " + AutomationLimits.MAX_WAIT_MS);
			}
		}

		if ("health".equals(op) || "session".equals(op)) {
			return WorkerSessionSnapshot.build(false);
		}

		if ("observe".equals(op)) {
			return WorkerSessionSnapshot.build(request.at("includeImage", false).asBoolean());
		}

		if ("key".equals(op)) {
			long start = System.nanoTime();
			String key = request.at("key") == null ? null : request.at("key").asString();
			int code = WorkerInputActions.resolveKeyCode(key, request.at("code"));
			int durationMs = request.at(
				"durationMs", AutomationLimits.DEFAULT_KEY_PRESS_DURATION_MS).asInteger();
			if (durationMs < AutomationLimits.MIN_KEY_DURATION_MS
				|| durationMs > AutomationLimits.MAX_KEY_DURATION_MS) {
				throw new AutomationException(
					AutomationErrorCodes.INVALID_REQUEST,
					"key duration must be between " + AutomationLimits.MIN_KEY_DURATION_MS
						+ " and " + AutomationLimits.MAX_KEY_DURATION_MS + " ms");
			}

			Json delivery = WorkerInputActions.pressKey(
				code,
				durationMs,
				request.at("waitDispatched", false).asBoolean(),
				request.at("waitRelease", false).asBoolean());
			WorkerCommands.invalidate();

			return Json.object()
				.set("key", key)
				.set("code", code)
				.set("delivery", delivery)
				.set("elapsedMs", java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
					System.nanoTime() - start));
		}

		if ("pointer-tap".equals(op)) {
			long start = System.nanoTime();
			int x = request.at("x", -1).asInteger();
			int y = request.at("y", -1).asInteger();
			if (x < 0 || y < 0) {
				throw new AutomationException(AutomationErrorCodes.INVALID_REQUEST, "pointer-tap requires x and y");
			}

			Json delivery = WorkerInputActions.tap(
				x,
				y,
				request.at("waitDispatched", false).asBoolean());
			WorkerCommands.invalidate();

			return Json.object()
				.set("x", x)
				.set("y", y)
				.set("delivery", delivery)
				.set("elapsedMs", java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
					System.nanoTime() - start));
		}

		if ("drag".equals(op)) {
			long start = System.nanoTime();
			Json points = request.at("points");
			if (points == null || !points.isArray()) {
				throw new AutomationException(AutomationErrorCodes.INVALID_REQUEST, "drag requires points");
			}

			int delayMs = request.at("delayMs", AutomationLimits.DEFAULT_DRAG_DELAY_MS).asInteger();
			if (delayMs < AutomationLimits.MIN_DRAG_DELAY_MS
				|| delayMs > AutomationLimits.MAX_DRAG_DELAY_MS) {
				throw new AutomationException(
					AutomationErrorCodes.INVALID_REQUEST,
					"drag delay must be between " + AutomationLimits.MIN_DRAG_DELAY_MS
						+ " and " + AutomationLimits.MAX_DRAG_DELAY_MS + " ms");
			}

			Json delivery = WorkerInputActions.drag(
				points,
				delayMs,
				request.at("waitDispatched", false).asBoolean());
			WorkerCommands.invalidate();

			return Json.object()
				.set("points", points.asJsonList().size())
				.set("delivery", delivery)
				.set("elapsedMs", java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
					System.nanoTime() - start));
		}

		if ("command-run".equals(op)) {
			return WorkerCommands.select(request);
		}

		if ("wait-condition".equals(op)) {
			return WorkerWaits.waitFor(request);
		}

		if ("events-read".equals(op)) {
			return WorkerWaits.readEvents(request);
		}

		if ("list-select".equals(op)) {
			return WorkerLcduiActions.listSelect(request);
		}

		if ("list-move".equals(op)) {
			return WorkerLcduiActions.listMove(request);
		}

		if ("choice-set".equals(op)) {
			return WorkerLcduiActions.choiceSet(request);
		}

		if ("gauge-set".equals(op)) {
			return WorkerLcduiActions.gaugeSet(request);
		}

		if ("text-field-set".equals(op)) {
			return WorkerLcduiActions.textFieldSet(request);
		}

		if ("text-box-set".equals(op)) {
			return WorkerLcduiActions.textBoxSet(request);
		}

		if ("screen-resize".equals(op)) {
			return WorkerScreenActions.resize(request);
		}

		if ("screen-rotate".equals(op)) {
			return WorkerScreenActions.rotate(request);
		}

		if ("permission".equals(op)) {
			int id = request.at("id", -1).asInteger();
			boolean allow = request.at("allow", false).asBoolean();
			String mode = request.at("mode", "once").asString();

			Json result = WorkerPermissions.resolve(id, allow, mode);
			WorkerCommands.invalidate();
			WorkerEventModel.stateChanged(
				"permission-resolved",
				Json.object().set("id", result.at("id")).set("allow", allow).set("mode", mode));

			return result;
		}

		if ("shutdown".equals(op)) {
			shutdownRequester.requestRuntimeShutdown("shutdown");

			return Json.object();
		}

		throw new AutomationException(AutomationErrorCodes.INVALID_REQUEST, "Unknown worker operation: " + op);
	}
}
