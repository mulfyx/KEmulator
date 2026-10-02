package emulator.cli.controller;

import emulator.cli.core.CliCommand;
import emulator.cli.core.CliInvocation;
import emulator.cli.core.CommandPath;
import emulator.cli.core.CommandResult;
import emulator.automation.shared.OperationDeadline;
import emulator.cli.app.AgentCalls;
import mjson.Json;

public final class LogsCursorCommand implements CliCommand {
	public CommandPath path() {
		return CommandPath.of("logs", "cursor");
	}

	public CommandResult run(CliInvocation invocation) throws Exception {
		ControllerLifecycle.requireTokenCount(invocation.tokens(), 2, "logs cursor", invocation.json());
		OperationDeadline deadline = invocation.deadline(10000L);
		ControllerStatus status = ControllerLifecycle.requireRunningController("logs cursor", invocation.json(), deadline);
		Json payload = AgentCalls.call(invocation, ControllerStatusService.controllerClient(status), deadline,
			"logs.cursor", Json.object(), "logs cursor");
		return new CommandResult(
			"logs cursor",
			payload,
			invocation.json());
	}
}
