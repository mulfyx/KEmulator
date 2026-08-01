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
import emulator.cli.parse.CliParsing;
import mjson.Json;

public final class ConditionWaitCommand implements CliCommand {
	private final String type;
	private final CommandPath path;

	public ConditionWaitCommand(String type) {
		this.type = type;
		this.path = CommandPath.of("wait", type);
	}

	public CommandPath path() {
		return path;
	}

	private String commandName() {
		return "wait " + type;
	}

	private KemuCliException usage(boolean json) {
		return new KemuCliException(
			CliErrorCodes.USAGE_ERROR,
			"Invalid options for " + commandName() + '.',
			commandName(),
			json);
	}

	private void requireValue(CliInvocation invocation, int index, boolean json) {
		if (index + 1 >= invocation.tokens().size()) {
			throw usage(json);
		}
	}

	public CommandResult run(CliInvocation invocation) throws Exception {
		boolean json = invocation.json();
		Json request = Json.object().set("type", type);
		for (int i = 2; i < invocation.tokens().size(); i++) {
			String token = invocation.tokens().get(i);
			if ("--timeout".equals(token)) {
				if (request.has("timeoutMs")) {
					throw CliParsing.duplicateOption(token, commandName(), json);
				}
				requireValue(invocation, i, json);
				int timeoutMs = CliParsing.parseIntegerArgument(
					invocation.tokens().get(++i), "--timeout", commandName(), json);
				request.set(
					"timeoutMs",
					CliParsing.requireInclusiveRange(
						timeoutMs, 0, AutomationLimits.MAX_WAIT_MS, "--timeout", commandName(), json));
			} else if ("display".equals(type) && "--kind".equals(token)) {
				if (request.has("kind")) {
					throw CliParsing.duplicateOption(token, commandName(), json);
				}
				requireValue(invocation, i, json);
				request.set("kind", invocation.tokens().get(++i));
			} else if ("display".equals(type) && "--title".equals(token)) {
				if (request.has("title")) {
					throw CliParsing.duplicateOption(token, commandName(), json);
				}
				requireValue(invocation, i, json);
				request.set("title", invocation.tokens().get(++i));
			} else if ("display".equals(type) && "--title-regex".equals(token)) {
				if (request.has("titleRegex")) {
					throw CliParsing.duplicateOption(token, commandName(), json);
				}
				requireValue(invocation, i, json);
				request.set("titleRegex", invocation.tokens().get(++i));
			} else if ("display".equals(type) && "--selected-index".equals(token)) {
				if (request.has("selectedIndex")) {
					throw CliParsing.duplicateOption(token, commandName(), json);
				}
				requireValue(invocation, i, json);
				request.set(
					"selectedIndex",
					CliParsing.parseIntegerArgument(
						invocation.tokens().get(++i), "--selected-index", commandName(), json));
			} else if (("display".equals(type) || "frame".equals(type))
				&& "--after-revision".equals(token)) {
				if (request.has("afterRevision")) {
					throw CliParsing.duplicateOption(token, commandName(), json);
				}
				requireValue(invocation, i, json);
				request.set(
					"afterRevision",
					CliParsing.parseLongArgument(
						invocation.tokens().get(++i), "--after-revision", commandName(), json));
			} else if ("permission".equals(type) && "--name".equals(token)) {
				if (request.has("name")) {
					throw CliParsing.duplicateOption(token, commandName(), json);
				}
				requireValue(invocation, i, json);
				request.set("name", invocation.tokens().get(++i));
			} else {
				throw usage(json);
			}
		}
		if ("display".equals(type)
			&& !request.has("kind")
			&& !request.has("title")
			&& !request.has("titleRegex")
			&& !request.has("selectedIndex")
			&& !request.has("afterRevision")) {
			throw usage(json);
		}
		ControllerStatus status = ControllerLifecycle.requireRunningController(commandName(), json);
		String operation = "worker-exit".equals(type)
			? "app.wait.worker-exit"
			: "app.wait.condition";
		Json payload = CliResponses.normalizePublicJson(ControllerCalls.callController(
			ControllerStatusService.controllerClient(status),
			operation,
			request,
			commandName(),
			json));
		return new CommandResult(
			commandName(),
			"Condition matched: " + type + '.',
			payload,
			json);
	}
}
