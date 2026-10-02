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
import emulator.cli.parse.CliParsing;
import mjson.Json;

/** pointer tap / down / up: tap is the full stroke, down+up allow holds. */
public final class PointerActionCommand implements CliCommand {
	private final String action;
	private final CommandPath path;

	public PointerActionCommand(String action) {
		this.action = action;
		this.path = CommandPath.of("pointer", action);
	}

	public CommandPath path() {
		return path;
	}

	private String commandName() {
		return "pointer " + action;
	}

	private KemuCliException usage(boolean json) {
		return new KemuCliException(
			CliErrorCodes.USAGE_ERROR,
			CliTextRenderer.usageText(commandName()),
			commandName(),
			json);
	}

	public CommandResult run(CliInvocation invocation) throws Exception {
		boolean json = invocation.json();
		if (invocation.tokens().size() < 4) {
			throw usage(json);
		}

		int coordinateIndex = "--".equals(invocation.tokens().get(2)) ? 3 : 2;
		if (invocation.tokens().size() < coordinateIndex + 2
			|| coordinateIndex == 3 && invocation.tokens().size() != 5) throw usage(json);
		int x = CliParsing.parseIntegerArgument(invocation.tokens().get(coordinateIndex), "<x>", commandName(), json);
		int y = CliParsing.parseIntegerArgument(invocation.tokens().get(coordinateIndex + 1), "<y>", commandName(), json);
		CliParsing.requireInclusiveRange(x, 0, Integer.MAX_VALUE, "<x>", commandName(), json);
		CliParsing.requireInclusiveRange(y, 0, Integer.MAX_VALUE, "<y>", commandName(), json);
		boolean observe = false;
		for (int i = coordinateIndex + 2; i < invocation.tokens().size(); i++) {
			if (!"--observe".equals(invocation.tokens().get(i))) {
				throw usage(json);
			}
			if (observe) {
				throw CliParsing.duplicateOption("--observe", commandName(), json);
			}
			observe = true;
		}

		OperationDeadline deadline = invocation.deadline(AutomationLimits.DEFAULT_TIMEOUT_MS);
		ControllerClient client = AgentCalls.client(invocation, commandName(), deadline);
		Json payload = AgentCalls.call(invocation, client, deadline,
			"app.pointer." + action,
			Json.object().set("x", x).set("y", y).set("waitDispatched", true), commandName());
		payload = AgentCalls.afterAction(invocation, client, deadline, commandName(), payload, observe);

		return new CommandResult(
			commandName(), payload, json);
	}
}
