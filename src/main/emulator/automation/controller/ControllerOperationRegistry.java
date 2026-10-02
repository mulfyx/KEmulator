package emulator.automation.controller;

import emulator.automation.shared.AutomationErrorCodes;
import emulator.automation.shared.AutomationException;
import emulator.automation.shared.OperationDeadline;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import mjson.Json;

final class ControllerOperationRegistry {

	interface ShutdownHandler {
		void requestShutdown();
	}

	private interface ControllerAction {
		Json run(Json request) throws Exception;
	}

	private final ControllerWorkerSession workerSession;
	private final ShutdownHandler shutdownHandler;
	private final ReentrantLock requestQueueLock = new ReentrantLock(true);
	private final Map<String, ControllerOperation> commands = new HashMap<String, ControllerOperation>();

	ControllerOperationRegistry(ControllerWorkerSession workerSession, ShutdownHandler shutdownHandler) {
		this.workerSession = workerSession;
		this.shutdownHandler = shutdownHandler;
		registerCommands();
	}

	/** Worker op names derive mechanically from controller op names. */
	private static String workerOpFor(String controllerOp) {
		return controllerOp.substring("app.".length()).replace('.', '-');
	}

	private void registerCommand(final String op, final DispatchMode dispatchMode, final ControllerAction action) {
		commands.put(op, new ControllerOperation() {
			public String op() {
				return op;
			}

			public DispatchMode dispatchMode() {
				return dispatchMode;
			}

			public Json execute(Json args) throws Exception {
				return action.run(args == null ? Json.object() : args);
			}
		});
	}

	private void registerWorkerProxy(final String op) {
		final String workerOp = workerOpFor(op);
		registerCommand(op, DispatchMode.QUEUED, new ControllerAction() {
			public Json run(Json request) throws Exception {
				return workerSession.proxyWorker(workerOp, request);
			}
		});
	}

	Json dispatch(String op, Json request) throws Exception {
		OperationDeadline deadline = OperationDeadline.fromRequest(request, 10000L);
		ControllerOperation command = commands.get(op);
		if (command == null) {
			throw new AutomationException(AutomationErrorCodes.INVALID_REQUEST, "Unknown controller operation: " + op);
		}

		if (command.dispatchMode() == DispatchMode.PRIORITY) {
			return command.execute(deadline.withRemaining(request));
		}

		// Bounded queueing: a wedged queued operation must produce a
		// structured error instead of an opaque socket timeout.
		boolean acquired;
		try {
			acquired = requestQueueLock.tryLock(deadline.remainingMillis(), TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AutomationException(
				AutomationErrorCodes.TIMEOUT, "Interrupted while queued for controller dispatch", null, e);
		}
		if (!acquired) {
			throw new AutomationException(
				AutomationErrorCodes.TIMEOUT,
				"Controller request queue is busy",
				Json.object().set("operation", op).set("queueWaitMs", deadline.elapsedMillis())
					.set("admitted", false).set("effectUnknown", false));
		}
		try {
			return command.execute(deadline.withRemaining(request));
		} finally {
			requestQueueLock.unlock();
		}
	}

	private void registerCommands() {
		registerCommand("health", DispatchMode.PRIORITY, new ControllerAction() {
			public Json run(Json request) {
				return Json.object();
			}
		});
		registerCommand("shutdown", DispatchMode.PRIORITY, new ControllerAction() {
			public Json run(Json request) {
				shutdownHandler.requestShutdown();

				return Json.object();
			}
		});
		registerCommand("app.current", DispatchMode.PRIORITY, new ControllerAction() {
			public Json run(Json request) {
				return workerSession.currentGame();
			}
		});
		// PRIORITY: open-path can block for the whole readiness wait and does
		// its own locking, so queued operations stay available while a worker
		// starts.
		registerCommand("app.open-path", DispatchMode.PRIORITY, new ControllerAction() {
			public Json run(Json request) throws Exception {
				String path = request.at("path") == null ? null : request.at("path").asString();
				Integer midlet = request.at("midlet") == null
					? null
					: Integer.valueOf(request.at("midlet").asInteger());

				return workerSession.openPath(path, midlet, request);
			}
		});
		registerCommand("app.close", DispatchMode.PRIORITY, new ControllerAction() {
			public Json run(Json request) throws Exception {
				return workerSession.closeGame();
			}
		});
		registerCommand("app.session", DispatchMode.QUEUED, new ControllerAction() {
			public Json run(Json request) throws Exception {
				return workerSession.sessionInfo(request);
			}
		});
		registerCommand("app.observe", DispatchMode.QUEUED, new ControllerAction() {
			public Json run(Json request) throws Exception {
				return workerSession.observe(request == null ? Json.object() : request);
			}
		});
		registerCommand("app.screenshot", DispatchMode.QUEUED, new ControllerAction() {
			public Json run(Json request) throws Exception {
				return workerSession.captureSnapshot(request);
			}
		});
		registerCommand("app.permission", DispatchMode.PRIORITY, new ControllerAction() {
			public Json run(Json request) throws Exception {
				return workerSession.proxyWorkerControl(workerOpFor("app.permission"), request);
			}
		});
		// PRIORITY: caller-controlled wait duration must not hold the queue.
		registerCommand("app.wait.condition", DispatchMode.PRIORITY, new ControllerAction() {
			public Json run(Json request) throws Exception {
				return workerSession.proxyWorker(workerOpFor("app.wait.condition"), request);
			}
		});
		registerCommand("app.wait.worker-exit", DispatchMode.PRIORITY, new ControllerAction() {
			public Json run(Json request) throws Exception {
				return workerSession.waitWorkerExit(request);
			}
		});
		registerWorkerProxy("app.key");
		registerCommand("app.agent.observe", DispatchMode.PRIORITY, new ControllerAction() {
			public Json run(Json request) throws Exception { return workerSession.proxyWorker("agent-observe", request); }
		});
		registerCommand("app.agent.wait.frame", DispatchMode.PRIORITY, new ControllerAction() {
			public Json run(Json request) throws Exception { return workerSession.proxyWorker("agent-wait-frame", request); }
		});
		registerWorkerProxy("app.ref.activate");
		registerWorkerProxy("app.ref.select");
		registerWorkerProxy("app.ref.set");
		registerWorkerProxy("app.key.down");
		registerWorkerProxy("app.key.up");
		registerWorkerProxy("app.pointer.tap");
		registerWorkerProxy("app.pointer.down");
		registerWorkerProxy("app.pointer.up");
		registerWorkerProxy("app.drag");
		registerWorkerProxy("app.command.run");
		registerWorkerProxy("app.list.select");
		registerWorkerProxy("app.list.move");
		registerWorkerProxy("app.choice.set");
		registerWorkerProxy("app.gauge.set");
		registerWorkerProxy("app.text-field.set");
		registerWorkerProxy("app.text-box.set");
		registerWorkerProxy("app.date-field.set");
		registerWorkerProxy("app.pause");
		registerWorkerProxy("app.resume");
		registerWorkerProxy("app.screen.resize");
		registerWorkerProxy("app.screen.rotate");
		registerWorkerProxy("app.events.read");
		registerCommand("logs.cursor", DispatchMode.PRIORITY, new ControllerAction() {
			public Json run(Json request) throws Exception {
				return workerSession.workerLogCursor();
			}
		});
		registerCommand("logs.read", DispatchMode.PRIORITY, new ControllerAction() {
			public Json run(Json request) throws Exception {
				return workerSession.workerLogsRead(request);
			}
		});
		registerCommand("logs.wait", DispatchMode.PRIORITY, new ControllerAction() {
			public Json run(Json request) throws Exception {
				return workerSession.waitWorkerLog(request);
			}
		});
	}
}
