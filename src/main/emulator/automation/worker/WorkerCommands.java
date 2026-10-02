package emulator.automation.worker;

import emulator.Emulator;
import emulator.EventQueue;
import emulator.automation.shared.AutomationErrorCodes;
import emulator.automation.shared.AutomationException;
import emulator.automation.shared.OperationDeadline;
import emulator.ui.TargetedCommand;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Vector;
import java.util.concurrent.Callable;
import javax.microedition.lcdui.AutomationStateExtractor;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;
import mjson.Json;

final class WorkerCommands {
	private static final Object LOCK = new Object();
	private static Map<Integer, TargetedCommand> commandRegistry = new LinkedHashMap<Integer, TargetedCommand>();
	private static long nextInvocationId = 1L;

	private WorkerCommands() {
	}

	interface NativeAction {
		Json apply();
	}

	/** Guard, mutation and callback share one LCDUI queue entry. */
	static Json dispatchNative(final Json request, final String operation, final String ref,
			final NativeAction action) {
		final OperationDeadline deadline = OperationDeadline.fromRequest(request, 5000L);
		final Json receipt = Json.object().set("operation", operation).set("ref", ref);
		final Json notAdmitted = Json.object().set("admitted", false).set("performed", false)
			.set("effectUnknown", false).set("effect", "none").set("action", receipt);
		final EventQueue queue = Emulator.getEventQueue();
		if (queue == null) throw new AutomationException(AutomationErrorCodes.APP_INPUT_UNAVAILABLE,
			"LCDUI event queue is not available", notAdmitted);
		WorkerPermissions.PendingPermission blocked = WorkerPermissions.snapshot();
		if (blocked != null) throw new AutomationException(AutomationErrorCodes.INPUT_BLOCKED,
			"Answer the current permission before submitting another action",
			notAdmitted.set("permissionRequest", blocked.toJson()));
		if (deadline.timedOut()) throw new AutomationException(AutomationErrorCodes.TIMEOUT,
			"Action deadline expired before dispatch", notAdmitted
				.set("timeoutMs", deadline.timeoutMillis()).set("elapsedMs", deadline.elapsedMillis()));
		final long invocationId = nextInvocationId();
		final java.util.concurrent.CountDownLatch completed = new java.util.concurrent.CountDownLatch(1);
		final java.util.concurrent.atomic.AtomicBoolean started = new java.util.concurrent.atomic.AtomicBoolean();
		final Json[] applied = new Json[1];
		final Throwable[] failure = new Throwable[1];
		long cursor = WorkerEventModel.cursor();
		queue.callSerially(new Runnable() {
			public void run() {
				started.set(true);
				try {
					applied[0] = action.apply();
				} catch (Throwable error) {
					failure[0] = error;
				} finally {
					completed.countDown();
					WorkerEventModel.stateChanged(failure[0] == null ? "command-finished" : "command-failed",
						Json.object().set("invocationId", invocationId).set("operation", operation).set("ref", ref));
				}
			}
		});
		try {
			while (completed.getCount() != 0L) {
				WorkerPermissions.PendingPermission permission = WorkerPermissions.snapshot();
				if (started.get() && permission != null) {
					Json pending = Json.object().set("pending", true).set("status", "pending-permission")
						.set("action", receipt).set("admitted", true).set("performed", Json.nil())
						.set("effectUnknown", true).set("permissionRequest", permission.toJson());
					try {
						pending.set("state", WorkerSessionSnapshot.build(false, deadline.remainingMillis()));
					} catch (RuntimeException unavailable) {
						pending.set("snapshotError", Json.object()
							.set("code", unavailable instanceof AutomationException
								? ((AutomationException) unavailable).code : AutomationErrorCodes.WORKER_FAILURE)
							.set("message", unavailable.getMessage()));
					}
					return pending.set("elapsedMs", deadline.elapsedMillis());
				}
				long remainingMs = deadline.remainingMillis();
				if (remainingMs == 0L) throw new AutomationException(AutomationErrorCodes.TIMEOUT,
					"Timed out waiting for LCDUI action; observe before retrying",
					Json.object().set("effect", "unknown").set("queued", true).set("admitted", true)
						.set("effectUnknown", true).set("performed", Json.nil())
						.set("started", started.get()).set("action", receipt)
						.set("timeoutMs", deadline.timeoutMillis()).set("elapsedMs", deadline.elapsedMillis()));
				WorkerEventModel.awaitEventAfter(cursor, remainingMs);
				cursor = WorkerEventModel.cursor();
			}
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new AutomationException(AutomationErrorCodes.WORKER_FAILURE,
				"Interrupted while waiting for LCDUI action",
				Json.object().set("effect", "unknown").set("action", receipt).set("admitted", true)
					.set("effectUnknown", true).set("performed", Json.nil()).set("queued", true)
					.set("started", started.get()).set("timeoutMs", deadline.timeoutMillis())
					.set("elapsedMs", deadline.elapsedMillis()), interrupted);
		}
		if (failure[0] != null) {
			if (failure[0] instanceof AutomationException) throw (AutomationException) failure[0];
			throw new AutomationException(AutomationErrorCodes.WORKER_FAILURE,
				"LCDUI action failed: " + failure[0].getMessage(),
				Json.object().set("errorType", failure[0].getClass().getName()).set("action", receipt), failure[0]);
		}
		if (applied[0] != null) {
			for (Map.Entry<String, Json> fact : applied[0].asJsonMap().entrySet()) receipt.set(fact.getKey(), fact.getValue());
		}
		try {
			Json state = WorkerSessionSnapshot.build(false, deadline.remainingMillis());
			return Json.object().set("action", receipt).set("elapsedMs", deadline.elapsedMillis())
				.set("admitted", true).set("performed", true).set("effectUnknown", false).set("state", state);
		} catch (RuntimeException unavailable) {
			AutomationException snapshotFailure = unavailable instanceof AutomationException
				? (AutomationException) unavailable : null;
			Json details = snapshotFailure != null && snapshotFailure.details != null
					&& snapshotFailure.details.isObject() ? snapshotFailure.details.dup() : Json.object();
			details.set("action", receipt).set("admitted", true).set("performed", true)
				.set("effectUnknown", false).set("effect", "applied")
				.set("timeoutMs", deadline.timeoutMillis()).set("elapsedMs", deadline.elapsedMillis());
			throw new AutomationException(snapshotFailure == null ? AutomationErrorCodes.WORKER_FAILURE : snapshotFailure.code,
				"LCDUI action completed, but its observation is unavailable: " + unavailable.getMessage(),
				details, unavailable);
		}
	}

	static void invalidate() {
		synchronized (LOCK) {
			commandRegistry = new LinkedHashMap<Integer, TargetedCommand>();
		}
	}

	private static long nextInvocationId() {
		synchronized (LOCK) {
			return nextInvocationId++;
		}
	}

	private static Json awaitCommandOutcome(
		long invocationId, long afterCursor, long timeoutMs) throws InterruptedException {
		long deadline = System.nanoTime()
			+ java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMs));
		long cursor = afterCursor;
		while (true) {
			Json events = WorkerEventModel.eventsSince(cursor);
			Json permissionEvent = null;
			for (Json event : events.asJsonList()) {
				cursor = Math.max(cursor, event.at("cursor", cursor).asLong());
				String eventName = event.at("event", "").asString();
				if (("command-finished".equals(eventName)
					|| "command-failed".equals(eventName))
					&& event.at("invocationId", -1L).asLong() == invocationId) {
					return event;
				}
				if ("permission-requested".equals(eventName)) {
					permissionEvent = event;
				}
			}
			if (permissionEvent != null && WorkerPermissions.snapshot() != null) {
				return permissionEvent;
			}

			long remainingNanos = deadline - System.nanoTime();
			if (remainingNanos <= 0L) {
				return null;
			}
			long remainingMs = Math.max(
				1L,
				java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remainingNanos));
			if (!WorkerEventModel.awaitEventAfter(cursor, remainingMs)) {
				return null;
			}
		}
	}

	static Json observe(
		Displayable current, WorkerPermissions.PendingPermission permission, Vector<TargetedCommand> commands) {
		javax.microedition.lcdui.Command leftSoft = AutomationStateExtractor.getLeftSoftCommand(current);
		javax.microedition.lcdui.Command rightSoft = AutomationStateExtractor.getRightSoftCommand(current);
		java.util.List<javax.microedition.lcdui.Command> menuCommands =
			new java.util.ArrayList<javax.microedition.lcdui.Command>();
		for (TargetedCommand menuCommand : AutomationStateExtractor.buildCommands(current)) {
			if (menuCommand != null && menuCommand.command != null) {
				menuCommands.add(menuCommand.command);
			}
		}

		LinkedHashMap<Integer, TargetedCommand> nextRegistry = new LinkedHashMap<Integer, TargetedCommand>();
		Json items = Json.array();
		int id = 1;
		for (TargetedCommand command : commands) {
			if (command == null) {
				continue;
			}

			nextRegistry.put(Integer.valueOf(id), command);
			id++;
		}

		synchronized (LOCK) {
			commandRegistry = nextRegistry;
		}

		id = 1;
		for (TargetedCommand command : commands) {
			if (command == null) {
				continue;
			}

			Json item = Json.object();
			item.set("id", id);
			item.set("text", command.text);
			item.set("choice", command.isChoice());
			item.set("selected", command.wasSelected);
			if (command.command != null) {
				item.set("label", command.command.getLabel());
				item.set("type", command.command.getCommandType());
				item.set("priority", command.command.getPriority());
				if (command.command == leftSoft) {
					item.set("softkey", "left");
				} else if (command.command == rightSoft) {
					item.set("softkey", "right");
					// Reachable only through the right softkey in the UI, but
					// invokable by id through automation.
					item.set("softkeyOnly", !menuCommands.contains(command.command));
				}
			}

			items.add(item);
			id++;
		}

		return items;
	}

	private static void refreshFromCurrentDisplay() {
		WorkerFrontendThread.call(new Callable<Object>() {
			public Object call() {
				Display display = Emulator.getCurrentDisplay();
				Displayable current = display == null ? null : display.getCurrent();
					observe(
					current,
					WorkerPermissions.snapshot(),
					AutomationStateExtractor.buildAutomationCommands(current));

				return null;
			}
		});
	}

	private static TargetedCommand findByLabel(String label) {
		TargetedCommand match = null;
		for (TargetedCommand candidate : commandRegistry.values()) {
			String candidateLabel = candidate.command == null
				? candidate.text
				: candidate.command.getLabel();
			if (!label.equals(candidateLabel) && !label.equals(candidate.text)) {
				continue;
			}
			if (match != null) {
				throw new AutomationException(
					AutomationErrorCodes.INVALID_REQUEST,
					"Command label is ambiguous: " + label,
					Json.object().set("label", label));
			}
			match = candidate;
		}
		return match;
	}

	static Json select(Json request) {
		int id = request.at("id", -1).asInteger();
		String label = request.has("label") && !request.at("label").isNull()
			? request.at("label").asString()
			: null;
		if (id < 0 && (label == null || label.length() == 0)) {
			throw new AutomationException(
				AutomationErrorCodes.INVALID_REQUEST,
				"select-command requires id or label");
		}

		refreshFromCurrentDisplay();
		long oldRevision = WorkerEventModel.revision();
		RevisionGuard.check(request);
		final TargetedCommand command;
		synchronized (LOCK) {
			command = id >= 0
				? commandRegistry.get(Integer.valueOf(id))
				: findByLabel(label);
		}

		if (command == null) {
			throw new AutomationException(
				AutomationErrorCodes.UNKNOWN_COMMAND_ID,
				id >= 0 ? "Unknown command id: " + id : "Unknown command label: " + label,
				Json.object().set("id", id).set("label", label));
		}

		Json oldState = WorkerSessionSnapshot.build(false);
		int oldDisplayIdentity = WorkerLcduiActions.currentDisplayIdentity();
		String oldDisplaySignature = oldState.at("displayable").toString();
		long timeoutMs = request.at("timeoutMs", 5000L).asLong();
		long startedAt = System.nanoTime();
		final long invocationId = nextInvocationId();
		long eventCursor = WorkerEventModel.cursor();
		final Long requiredRevision = RevisionGuard.expected(request);
		try {
			if (!command.enqueueAndWait(timeoutMs, new Runnable() {
				public void run() {
					long currentRevision = WorkerEventModel.revision();
					if (requiredRevision != null
						&& requiredRevision.longValue() != currentRevision) {
						throw RevisionGuard.stale(requiredRevision.longValue(), currentRevision);
					}
					if (!command.isCurrentTarget()) {
						throw new AutomationException(
							AutomationErrorCodes.STALE_REVISION,
							"LCDUI command target is no longer current",
							Json.object().set("currentRevision", currentRevision));
					}
				}
			}, new EventQueue.CommandDispatchListener() {
				public void commandFinished(Throwable failure) {
					Json details = Json.object()
						.set("invocationId", invocationId)
						.set("commandId", id)
						.set(
							"label",
							command.command == null
								? command.text
								: command.command.getLabel());
					if (failure == null) {
						WorkerEventModel.stateChanged("command-finished", details);
						return;
					}
					details
						.set("errorType", failure.getClass().getName())
						.set("message", failure.getMessage());
					WorkerEventModel.stateChanged("command-failed", details);
				}
			})) {
				throw new AutomationException(
					AutomationErrorCodes.TIMEOUT,
					"Timed out waiting to enqueue LCDUI command",
					Json.object()
						.set("timeoutMs", timeoutMs)
						.set("oldRevision", oldRevision)
						.set("lastState", WorkerSessionSnapshot.build(false)));
			}
			long elapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
				System.nanoTime() - startedAt);
			long remainingMs = timeoutMs - elapsedMs;
			Json outcome = remainingMs <= 0L
				? null
				: awaitCommandOutcome(invocationId, eventCursor, remainingMs);
			if (outcome == null) {
				throw new AutomationException(
					AutomationErrorCodes.TIMEOUT,
					"Timed out waiting for LCDUI command completion",
					Json.object()
						.set("timeoutMs", timeoutMs)
						.set("elapsedMs", elapsedMs)
						.set("oldRevision", oldRevision)
						.set("lastState", WorkerSessionSnapshot.build(false)));
			}
			if ("command-failed".equals(outcome.at("event", "").asString())) {
				throw new AutomationException(
					AutomationErrorCodes.WORKER_FAILURE,
					"LCDUI command handler failed",
					Json.object()
						.set("invocationId", invocationId)
						.set("errorType", outcome.at("errorType", "java.lang.Throwable").asString())
						.set("message", outcome.at("message", "").asString())
						.set("lastState", WorkerSessionSnapshot.build(false)));
			}
			if ("permission-requested".equals(outcome.at("event", "").asString())) {
				invalidate();
				Json state = WorkerSessionSnapshot.build(false);
				WorkerPermissions.PendingPermission permission = WorkerPermissions.snapshot();
				long pendingElapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
					System.nanoTime() - startedAt);
				return Json.object()
					.set("pending", true)
					.set("status", "pending-permission")
					.set("id", id)
					.set("label", command.command == null ? null : command.command.getLabel())
					.set("text", command.text)
					.set("oldRevision", oldRevision)
					.set("newRevision", state.at("revision", WorkerEventModel.revision()).asLong())
					.set("elapsedMs", pendingElapsedMs)
					.set("waitNextDisplayRequested", request.at("waitNextDisplay", false).asBoolean())
					.set("permissionRequest", permission == null ? null : permission.toJson())
					.set("state", state);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AutomationException(
				AutomationErrorCodes.WORKER_FAILURE,
				"Interrupted while waiting for LCDUI command dispatch",
				null,
				e);
		}
		invalidate();

		Json transition = null;
		if (request.at("waitNextDisplay", false).asBoolean()) {
			long elapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
				System.nanoTime() - startedAt);
			long remainingMs = timeoutMs - elapsedMs;
			if (remainingMs <= 0L) {
				throw new AutomationException(
					AutomationErrorCodes.TIMEOUT,
					"Timed out waiting for the next LCDUI display",
					Json.object()
						.set("timeoutMs", timeoutMs)
						.set("elapsedMs", elapsedMs)
						.set("oldRevision", oldRevision)
						.set("lastState", WorkerSessionSnapshot.build(false)));
			}
			transition = WorkerWaits.waitForNextDisplay(
				oldDisplayIdentity,
				oldDisplaySignature,
				remainingMs);
		}
		Json state = WorkerSessionSnapshot.build(false);
		long elapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
			System.nanoTime() - startedAt);

		Json result = Json.object()
			.set("id", id)
			.set("label", command.command == null ? null : command.command.getLabel())
			.set("text", command.text)
			.set("oldRevision", oldRevision)
			.set("newRevision", state.at("revision", WorkerEventModel.revision()).asLong())
			.set("elapsedMs", elapsedMs)
			.set("state", state);
		if (transition != null) {
			result.set("transition", transition);
		}
		return result;
	}
}
