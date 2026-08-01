package emulator.cli.app;

import emulator.cli.controller.ControllerCalls;
import emulator.cli.controller.ControllerLifecycle;
import emulator.cli.controller.ControllerStatus;
import emulator.cli.controller.ControllerStatusService;
import emulator.cli.core.CliCommand;
import emulator.cli.core.CliErrorCodes;
import emulator.cli.core.CliInvocation;
import emulator.cli.core.CommandPath;
import emulator.cli.core.CommandResult;
import emulator.cli.core.KemuCliException;
import emulator.cli.output.CliResponses;
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

		int x = CliParsing.parseIntegerArgument(invocation.tokens().get(2), "<x>", commandName(), json);
		int y = CliParsing.parseIntegerArgument(invocation.tokens().get(3), "<y>", commandName(), json);
		boolean waitDispatched = false;
		for (int i = 4; i < invocation.tokens().size(); i++) {
			if (!"--wait-dispatched".equals(invocation.tokens().get(i))) {
				throw usage(json);
			}
			if (waitDispatched) {
				throw CliParsing.duplicateOption("--wait-dispatched", commandName(), json);
			}
			waitDispatched = true;
		}

		ControllerStatus status = ControllerLifecycle.requireRunningController(commandName(), json);
		Json payload = CliResponses.normalizePublicJson(ControllerCalls.callController(
			ControllerStatusService.controllerClient(status),
			"app.pointer." + action,
			Json.object().set("x", x).set("y", y).set("waitDispatched", waitDispatched),
			commandName(),
			json));

		return new CommandResult(
			commandName(), "Pointer " + action + " at " + x + "," + y + ".", payload, json);
	}
}
