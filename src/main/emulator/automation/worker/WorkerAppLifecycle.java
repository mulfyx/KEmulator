package emulator.automation.worker;

import emulator.Emulator;
import emulator.EventQueue;
import emulator.automation.shared.AutomationErrorCodes;
import emulator.automation.shared.AutomationException;
import emulator.automation.shared.OperationDeadline;
import java.util.concurrent.TimeoutException;
import mjson.Json;

/**
 * MIDlet lifecycle control: pause and resume the running MIDlet the way a
 * handset does on an interruption. Done requires all callbacks to return;
 * a permission suspends the original operation. Repeating the current state
 * does not queue another lifecycle event.
 */
final class WorkerAppLifecycle {
	private static final long STATE_WAIT_MS = 5000L;

	private WorkerAppLifecycle() {
	}

	private static EventQueue requireQueue() {
		EventQueue queue = Emulator.getEventQueue();
		if (queue == null) {
			throw new AutomationException(
				AutomationErrorCodes.APP_INPUT_UNAVAILABLE,
				"LCDUI event queue is not available");
		}

		return queue;
	}

	private static Json apply(Json request, boolean pause) {
		OperationDeadline deadline = OperationDeadline.fromRequest(request, STATE_WAIT_MS);
		EventQueue queue = requireQueue();
		RevisionGuard.check(request);
		long oldRevision = WorkerEventModel.revision();
		boolean admitted = false;
		try {
			EventQueue.LifecycleAction action = queue.requestPaused(pause, deadline.remainingMillis());
			admitted = action != null;
			while (action != null && !action.await(0L)) {
				WorkerPermissions.PendingPermission permission = WorkerPermissions.snapshotForLifecycle(action);
				if (permission != null) {
					return receipt(queue, pause, oldRevision, deadline)
						.set("pending", true).set("status", "pending-permission")
						.set("permissionRequest", permission.toJson());
				}
				if (deadline.timedOut()) throw new TimeoutException("MIDlet lifecycle callback did not finish");
				action.await(Math.min(20L, deadline.remainingMillis()));
			}
			if (action != null && action.failure() != null) {
				throw new AutomationException(AutomationErrorCodes.WORKER_FAILURE,
					"MIDlet lifecycle callback failed", Json.object().set("operation", pause ? "pause" : "resume"), action.failure());
			}
			if (action != null) {
				WorkerEventModel.stateChanged(pause ? "app-paused" : "app-resumed", null);
				WorkerCommands.invalidate();
			}
		} catch (TimeoutException e) {
			throw new AutomationException(
				AutomationErrorCodes.TIMEOUT,
				"Timed out waiting for the MIDlet to become " + (pause ? "paused" : "resumed"),
				Json.object().set("timeoutMs", deadline.timeoutMillis()).set("elapsedMs", deadline.elapsedMillis())
					.set("paused", queue.isPaused()).set("effectUnknown", admitted).set("admitted", admitted)
					.set("performed", admitted ? Json.nil() : Json.make(false)));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AutomationException(
				AutomationErrorCodes.TIMEOUT,
				"Interrupted while waiting for the MIDlet lifecycle state",
				Json.object().set("effectUnknown", admitted).set("admitted", admitted),
				e);
		}

		return receipt(queue, pause, oldRevision, deadline);
	}

	private static Json receipt(EventQueue queue, boolean pause, long oldRevision, OperationDeadline deadline) {
		return Json.object()
			.set("action", Json.object().set("operation", pause ? "pause" : "resume"))
			.set("oldRevision", oldRevision)
			.set("newRevision", WorkerEventModel.revision())
			.set("paused", queue.isPaused())
			.set("elapsedMs", deadline.elapsedMillis())
			.set("state", WorkerSessionSnapshot.build(false));
	}

	static Json pause(Json request) {
		return apply(request, true);
	}

	static Json resume(Json request) {
		return apply(request, false);
	}
}
