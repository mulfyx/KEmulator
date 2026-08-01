package emulator.cli.app;

import emulator.cli.core.CliErrorCodes;
import emulator.automation.shared.AutomationLimits;
import emulator.cli.controller.ControllerCalls;
import emulator.cli.controller.ControllerLifecycle;
import emulator.cli.controller.ControllerStatus;
import emulator.cli.controller.ControllerStatusService;
import emulator.cli.core.CliCommand;
import emulator.cli.core.CliInvocation;
import emulator.cli.core.CommandPath;
import emulator.cli.core.CommandResult;
import emulator.cli.core.KemuCliException;
import emulator.cli.output.CliResponses;
import emulator.cli.parse.CliParsing;
import mjson.Json;

public final class KeyActionCommand implements CliCommand {
	private final String action;

	public KeyActionCommand(String action) {
		this.action = action;
	}

	public CommandPath path() {
		return CommandPath.of("key", action);
	}

	private KemuCliException usage(boolean json) {
		return new KemuCliException(
			CliErrorCodes.USAGE_ERROR,
			"Invalid options for key " + action + '.',
			"key " + action,
			json);
	}

	public CommandResult run(CliInvocation invocation) throws Exception {
		boolean json = invocation.json();
		if (invocation.tokens().size() < 3) {
			throw usage(json);
		}
		String key = invocation.tokens().get(2);
		int durationMs = "hold".equals(action)
			? AutomationLimits.DEFAULT_KEY_HOLD_DURATION_MS
			: AutomationLimits.DEFAULT_KEY_PRESS_DURATION_MS;
		boolean sawDuration = false;
		boolean waitDispatched = false;
		boolean waitRelease = false;
		for (int i = 3; i < invocation.tokens().size(); i++) {
			String token = invocation.tokens().get(i);
			if ("--duration".equals(token)) {
				if (sawDuration) {
					throw CliParsing.duplicateOption(token, "key " + action, json);
				}
				if (i + 1 >= invocation.tokens().size()) {
					throw usage(json);
				}
				sawDuration = true;
				durationMs = CliParsing.parseIntegerArgument(
					invocation.tokens().get(++i),
					"--duration",
					"key " + action,
					json);
				durationMs = CliParsing.requireInclusiveRange(
					durationMs,
					AutomationLimits.MIN_KEY_DURATION_MS,
					AutomationLimits.MAX_KEY_DURATION_MS,
					"--duration",
					"key " + action,
					json);
			} else if ("--wait-dispatched".equals(token)) {
				if (waitDispatched) {
					throw CliParsing.duplicateOption(token, "key " + action, json);
				}
				waitDispatched = true;
			} else if ("--wait-release".equals(token) && "hold".equals(action)) {
				if (waitRelease) {
					throw CliParsing.duplicateOption(token, "key " + action, json);
				}
				waitRelease = true;
			} else {
				throw usage(json);
			}
		}
		Json request = Json.object()
			.set("key", key)
			.set("durationMs", durationMs)
			.set("waitDispatched", waitDispatched)
			.set("waitRelease", waitRelease);
		ControllerStatus status = ControllerLifecycle.requireRunningController("key " + action, json);
		Json payload = CliResponses.normalizePublicJson(ControllerCalls.callController(
			ControllerStatusService.controllerClient(status),
			"app.key",
			request,
			"key " + action,
			json));
		return new CommandResult("key " + action, "Pressed " + key + ".", payload, json);
	}
}
