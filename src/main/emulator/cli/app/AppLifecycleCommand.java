package emulator.cli.app;

import emulator.automation.shared.AutomationLimits;
import emulator.automation.shared.OperationDeadline;
import emulator.cli.controller.ControllerClient;
import emulator.cli.core.CliCommand;
import emulator.cli.core.CliErrorCodes;
import emulator.cli.core.CliInvocation;
import emulator.cli.core.CommandPath;
import emulator.cli.core.CommandResult;
import emulator.cli.core.KemuCliException;
import emulator.cli.output.CliTextRenderer;
import mjson.Json;

/** MIDlet lifecycle: pause and resume the running MIDlet. */
public final class AppLifecycleCommand implements CliCommand {
	private final String action;
	private final CommandPath path;

	public AppLifecycleCommand(String action) {
		this.action = action;
		this.path = CommandPath.of(action);
	}

	public CommandPath path() {
		return path;
	}

	private KemuCliException usage(boolean json) {
		return new KemuCliException(
			CliErrorCodes.USAGE_ERROR, CliTextRenderer.usageText(action), action, json);
	}

	public CommandResult run(CliInvocation invocation) throws Exception {
		boolean json = invocation.json();
		if (invocation.tokens().size() != 1) throw usage(json);
		OperationDeadline deadline = invocation.deadline(AutomationLimits.DEFAULT_TIMEOUT_MS);
		ControllerClient client = AgentCalls.client(invocation, action, deadline);
		Json payload = AgentCalls.call(invocation, client, deadline, "app." + action, Json.object(), action);
		payload = AgentCalls.afterAction(invocation, client, deadline, action, payload, true);

		return new CommandResult(
			action,
			payload,
			json);
	}
}
