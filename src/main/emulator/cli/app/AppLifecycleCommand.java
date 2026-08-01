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
		Json request = Json.object();
		for (int i = 1; i < invocation.tokens().size(); i++) {
			String token = invocation.tokens().get(i);
			if ("--expect-revision".equals(token)) {
				if (request.has("expectRevision")) {
					throw CliParsing.duplicateOption(token, action, json);
				}
				if (i + 1 >= invocation.tokens().size()) {
					throw usage(json);
				}
				request.set(
					"expectRevision",
					CliParsing.parseRevision(invocation.tokens().get(++i), action, json));
			} else if ("--timeout".equals(token)) {
				if (request.has("timeoutMs")) {
					throw CliParsing.duplicateOption(token, action, json);
				}
				if (i + 1 >= invocation.tokens().size()) {
					throw usage(json);
				}
				int timeout = CliParsing.parseIntegerArgument(
					invocation.tokens().get(++i), "--timeout", action, json);
				request.set(
					"timeoutMs",
					CliParsing.requireInclusiveRange(
						timeout, 0, AutomationLimits.MAX_WAIT_MS, "--timeout", action, json));
			} else {
				throw usage(json);
			}
		}

		ControllerStatus status = ControllerLifecycle.requireRunningController(action, json);
		Json payload = CliResponses.normalizePublicJson(ControllerCalls.callController(
			ControllerStatusService.controllerClient(status),
			"app." + action,
			request,
			action,
			json));

		return new CommandResult(
			action,
			payload.at("paused", false).asBoolean() ? "MIDlet paused." : "MIDlet resumed.",
			payload,
			json);
	}
}
