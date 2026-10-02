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

public final class ScreenSizeCommand implements CliCommand {
	private final boolean rotate;
	private final CommandPath path;

	public ScreenSizeCommand(boolean rotate) {
		this.rotate = rotate;
		this.path = CommandPath.of(rotate ? "rotate" : "resize");
	}

	public CommandPath path() {
		return path;
	}

	private String commandName() {
		return rotate ? "rotate" : "resize";
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
		Json request = Json.object();
		int sizeIndex = !rotate && invocation.tokens().size() > 1
			&& "--".equals(invocation.tokens().get(1)) ? 2 : 1;
		if (invocation.tokens().size() != (rotate ? 1 : sizeIndex + 1)) throw usage(json);
		if (!rotate) {
			int[] size = CliParsing.parseSize(invocation.tokens().get(sizeIndex), commandName(), json);
			request.set("width", size[0]);
			request.set("height", size[1]);
		}

		OperationDeadline deadline = invocation.deadline(AutomationLimits.DEFAULT_TIMEOUT_MS);
		ControllerClient client = AgentCalls.client(invocation, commandName(), deadline);
		Json payload = AgentCalls.call(invocation, client, deadline,
			rotate ? "app.screen.rotate" : "app.screen.resize", request, commandName());
		payload.set("action", Json.object().set("operation", commandName())
			.set("width", payload.at("width")).set("height", payload.at("height")));
		payload = AgentCalls.afterAction(invocation, client, deadline, commandName(), payload, true);

		return new CommandResult(commandName(), payload, json);
	}
}
