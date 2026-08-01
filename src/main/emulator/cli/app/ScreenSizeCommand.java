package emulator.cli.app;

import emulator.automation.shared.AutomationLimits;
import emulator.cli.controller.ControllerCalls;
import emulator.cli.controller.ControllerLifecycle;
import emulator.cli.controller.ControllerStatus;
import emulator.cli.controller.ControllerStatusService;
import emulator.cli.core.CliCommand;
import emulator.cli.core.CliExitCodes;
import emulator.cli.core.CliInvocation;
import emulator.cli.core.CommandPath;
import emulator.cli.core.CommandResult;
import emulator.cli.core.KemuCliException;
import emulator.cli.output.CliResponses;
import emulator.cli.output.CliTextRenderer;
import emulator.cli.parse.CliParsing;
import java.util.Locale;
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
			"USAGE_ERROR",
			CliTextRenderer.usageText(commandName()),
			CliExitCodes.USAGE,
			commandName(),
			json);
	}

	private long parseRevision(String value, boolean json) {
		try {
			long revision = Long.parseLong(value);
			if (revision < 0L) {
				throw usage(json);
			}
			return revision;
		} catch (NumberFormatException e) {
			throw usage(json);
		}
	}

	public CommandResult run(CliInvocation invocation) throws Exception {
		boolean json = invocation.json();
		Json request = Json.object();
		int optionIndex = 1;
		if (!rotate) {
			if (invocation.tokens().size() < 2) {
				throw usage(json);
			}
			String[] parts = invocation.tokens().get(1).toLowerCase(Locale.US).split("x");
			if (parts.length != 2) {
				throw usage(json);
			}
			int width = CliParsing.parseIntegerArgument(parts[0], "width", commandName(), json);
			int height = CliParsing.parseIntegerArgument(parts[1], "height", commandName(), json);
			if (width < 1 || height < 1) {
				throw usage(json);
			}
			request.set("width", width);
			request.set("height", height);
			optionIndex = 2;
		}

		for (int i = optionIndex; i < invocation.tokens().size(); i++) {
			String token = invocation.tokens().get(i);
			if ("--wait-frame".equals(token)) {
				if (request.at("waitFrame", false).asBoolean()) {
					throw usage(json);
				}
				request.set("waitFrame", true);
			} else if ("--expect-revision".equals(token)) {
				if (i + 1 >= invocation.tokens().size() || request.has("expectRevision")) {
					throw usage(json);
				}
				request.set("expectRevision", parseRevision(invocation.tokens().get(++i), json));
			} else if ("--timeout".equals(token)) {
				if (i + 1 >= invocation.tokens().size() || request.has("timeoutMs")) {
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

		if (!request.has("timeoutMs")) {
			request.set("timeoutMs", 5000);
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
