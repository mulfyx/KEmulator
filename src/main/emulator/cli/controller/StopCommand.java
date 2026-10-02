package emulator.cli.controller;

import emulator.automation.shared.OperationDeadline;
import emulator.cli.core.*;
import java.util.concurrent.Callable;
import mjson.Json;

public final class StopCommand implements CliCommand {
	public CommandPath path() {
		return CommandPath.of("stop");
	}

	public CommandResult run(final CliInvocation invocation) throws Exception {
		ControllerLifecycle.requireTokenCount(invocation.tokens(), 1, "stop", invocation.json());
		final OperationDeadline deadline = invocation.deadline(15000L);
		return ControllerLifecycle.withLifecycleLock(deadline, "stop", invocation.json(), new Callable<CommandResult>() {
			public CommandResult call() throws Exception {
				ControllerStatus status = ControllerStatusService.readControllerStatus(deadline);
				boolean stopped = ControllerStatusService.stopSession(status, deadline, "stop", invocation.json());
				ControllerStatusService.deleteStateFiles();
				Json payload = Json.object().set("running", false).set("active", false).set("stopped", stopped);
				if (!stopped) payload.set("reason", "not-running");
				return new CommandResult("stop", payload, invocation.json());
			}
		});
	}
}
