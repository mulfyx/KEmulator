package emulator.cli.app;

import emulator.automation.shared.AutomationErrorCodes;
import emulator.automation.shared.OperationDeadline;
import emulator.cli.controller.*;
import emulator.cli.core.*;
import mjson.Json;

public final class CloseCommand implements CliCommand {
	public CommandPath path() {
		return CommandPath.of("close");
	}

	public CommandResult run(final CliInvocation invocation) throws Exception {
		ControllerLifecycle.requireTokenCount(invocation.tokens(), 1, "close", invocation.json());
		final boolean json = invocation.json();
		final OperationDeadline deadline = invocation.deadline(10000L);

		return ControllerLifecycle.withLifecycleLock(deadline, "close", json, new java.util.concurrent.Callable<CommandResult>() {
			public CommandResult call() throws Exception {
				ControllerStatus status = ControllerStatusService.readControllerStatus(deadline);
				if (ControllerStatusService.isForeignPidState(status)) {
					ControllerStatusService.deleteStateFiles();
					Json payload = status.toJson().set("closed", false).set("reason", "not_running");

					return new CommandResult("close", payload, json);
				}

				boolean actionable = status.running || status.degraded || Boolean.TRUE.equals(status.pidAlive);
				if (!status.exists || !actionable) {
					ControllerStatusService.deleteStateFiles();
					Json payload = status.toJson().set("closed", false).set("reason", "not_running");

					return new CommandResult("close", payload, json);
				}

				if (status.degraded || (Boolean.TRUE.equals(status.pidAlive) && !status.running)) {
					throw new KemuCliException(
						AutomationErrorCodes.CONTROLLER_UNREACHABLE,
						"Controller is unreachable. Use kemu stop to terminate the session.",
						"close",
						json);
				}

				ControllerClient client = ControllerStatusService.controllerClient(status);
				Json payload = AgentCalls.call(invocation, client, deadline, "app.close", Json.object(), "close");
				payload = AgentCalls.context(invocation, client, deadline, payload, "close");

				return new CommandResult(
					"close",
					payload,
					json);
			}
		});
	}
}
