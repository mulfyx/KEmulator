package emulator.automation.worker;

import emulator.Emulator;
import emulator.EventQueue;
import emulator.automation.shared.AutomationErrorCodes;
import emulator.automation.shared.AutomationException;
import java.util.concurrent.TimeUnit;
import mjson.Json;

/**
 * MIDlet lifecycle control: pause and resume the running MIDlet the way a
 * handset does on an interruption. Both operations acknowledge only after
 * the event queue reports the requested state, so callers never race.
 */
final class WorkerAppLifecycle {
	private static final long STATE_WAIT_MS = 5000L;
	private static final long POLL_INTERVAL_MS = 20L;

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

	private static void awaitPaused(EventQueue queue, boolean expected, long timeoutMs) {
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
		while (queue.isPaused() != expected) {
			if (System.nanoTime() >= deadline) {
				throw new AutomationException(
					AutomationErrorCodes.TIMEOUT,
					"Timed out waiting for the MIDlet to become "
						+ (expected ? "paused" : "resumed"),
					Json.object().set("timeoutMs", timeoutMs).set("paused", queue.isPaused()));
			}

			try {
				Thread.sleep(POLL_INTERVAL_MS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new AutomationException(
					AutomationErrorCodes.WORKER_FAILURE,
					"Interrupted while waiting for the MIDlet lifecycle state",
					null,
					e);
			}
		}
	}

	private static Json apply(Json request, boolean pause) {
		long start = System.nanoTime();
		EventQueue queue = requireQueue();
		RevisionGuard.check(request);
		long oldRevision = WorkerEventModel.revision();
		queue.queue(pause ? EventQueue.EVENT_PAUSE : EventQueue.EVENT_RESUME);
		awaitPaused(queue, pause, request.at("timeoutMs", STATE_WAIT_MS).asLong());
		WorkerEventModel.stateChanged(pause ? "app-paused" : "app-resumed", null);
		WorkerCommands.invalidate();

		return Json.object()
			.set("oldRevision", oldRevision)
			.set("newRevision", WorkerEventModel.revision())
			.set("paused", queue.isPaused())
			.set("elapsedMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start))
			.set("state", WorkerSessionSnapshot.build(false));
	}

	static Json pause(Json request) {
		return apply(request, true);
	}

	static Json resume(Json request) {
		return apply(request, false);
	}
}
