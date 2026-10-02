package emulator.automation.worker;

import emulator.Emulator;
import emulator.automation.shared.AutomationErrorCodes;
import emulator.automation.shared.AutomationException;
import emulator.automation.shared.OperationDeadline;
import java.util.concurrent.Callable;
import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Displayable;
import mjson.Json;

/** Metadata and published pixels are observed without blocking the UI thread on paint. */
final class WorkerAgentObservation {
	private WorkerAgentObservation() { }

	static Json observe(Json request) {
		return capture(request, null);
	}

	static Json waitFrame(Json request) {
		Long after = request.has("afterFrame") ? Long.valueOf(WorkerTargets.frameSequence(request.at("afterFrame").asString()))
			: Long.valueOf(WorkerFrameCapture.frameId());
		Json snapshot = capture(request.dup().set("captureCanvas", true).set("includeImage", true), after);
		return Json.object().set("matched", true).set("condition", "frame")
			.set("frameId", snapshot.at("frameId")).set("state", snapshot);
	}

	private static Json capture(Json request, Long after) {
		final OperationDeadline deadline = OperationDeadline.fromRequest(request, 10000L);
		while (true) {
			final Displayable[] expected = new Displayable[1];
			final long[] epoch = new long[1];
			Json snapshot = WorkerFrontendThread.call(new Callable<Json>() {
				public Json call() {
					expected[0] = WorkerTargets.current();
					epoch[0] = WorkerTargets.epoch();
					return WorkerSessionSnapshot.build(false, deadline.remainingMillis());
				}
			}, deadline.remainingMillis());
			boolean image = request.at("includeImage", false).asBoolean()
				|| request.at("captureCanvas", true).asBoolean() && expected[0] instanceof Canvas;
			if (expected[0] == null || !image) return snapshot;
			Json frame = after == null
				? WorkerFrameCapture.capture(expected[0], deadline.remainingMillis())
				: WorkerFrameCapture.captureAfter(expected[0], after.longValue(), deadline.remainingMillis());
			if (expected[0] != WorkerTargets.current() || epoch[0] != WorkerTargets.epoch()) {
				if (!deadline.timedOut()) continue;
				throw new AutomationException(AutomationErrorCodes.FRAME_NOT_READY,
					"Display changed while observing", Json.object().set("lastState", snapshot));
			}
			if (frame.has("error")) {
				Json error = frame.at("error");
				Json details = error.at("details", Json.object()).dup().set("lastState", snapshot);
				throw new AutomationException(error.at("code", AutomationErrorCodes.FRAME_NOT_READY).asString(),
					error.at("message", "Current display has no completed frame").asString(), details);
			}
			snapshot.set("imageBase64", frame.at("imageBase64"));
			snapshot.set("width", frame.at("width"));
			snapshot.set("height", frame.at("height"));
			snapshot.set("frameId", WorkerTargets.frameRef(frame.at("frameId").asLong()));
			return snapshot;
		}
	}
}
