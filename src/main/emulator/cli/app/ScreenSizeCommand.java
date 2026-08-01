package emulator.cli.app;

import emulator.automation.shared.AutomationLimits;
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
		int optionIndex = 1;
		if (!rotate) {
			if (invocation.tokens().size() < 2) {
				throw usage(json);
			}
			int[] size = CliParsing.parseSize(invocation.tokens().get(1), commandName(), json);
			request.set("width", size[0]);
			request.set("height", size[1]);
			optionIndex = 2;
		}

		for (int i = optionIndex; i < invocation.tokens().size(); i++) {
			String token = invocation.tokens().get(i);
			if ("--wait-frame".equals(token)) {
				if (request.at("waitFrame", false).asBoolean()) {
					throw CliParsing.duplicateOption(token, commandName(), json);
				}
				request.set("waitFrame", true);
			} else if ("--expect-revision".equals(token)) {
				if (request.has("expectRevision")) {
					throw CliParsing.duplicateOption(token, commandName(), json);
				}
				if (i + 1 >= invocation.tokens().size()) {
					throw usage(json);
				}
				request.set(
					"expectRevision",
					CliParsing.parseRevision(invocation.tokens().get(++i), commandName(), json));
			} else if ("--timeout".equals(token)) {
				if (request.has("timeoutMs")) {
					throw CliParsing.duplicateOption(token, commandName(), json);
				}
				if (i + 1 >= invocation.tokens().size()) {
					throw usage(json);
				}
				int timeout = CliParsing.parseIntegerArgument(
					invocation.tokens().get(++i), "--timeout", commandName(), json);
				request.set(
					"timeoutMs",
					CliParsing.requireInclusiveRange(
						timeout, 0, AutomationLimits.MAX_WAIT_MS, "--timeout", commandName(), json));
			} else {
				throw usage(json);
			}
		}

		ControllerStatus status = ControllerLifecycle.requireRunningController(commandName(), json);
		Json payload = CliResponses.normalizePublicJson(ControllerCalls.callController(
			ControllerStatusService.controllerClient(status),
			rotate ? "app.screen.rotate" : "app.screen.resize",
			request,
			commandName(),
			json));

		String text = "Screen size: "
			+ payload.at("width", 0).asInteger() + "x" + payload.at("height", 0).asInteger()
			+ " (was " + payload.at("oldWidth", 0).asInteger() + "x"
			+ payload.at("oldHeight", 0).asInteger() + ").";

		return new CommandResult(commandName(), text, payload, json);
	}
}
