package emulator.cli.app;

import emulator.cli.core.CliErrorCodes;
import emulator.automation.shared.AutomationErrorCodes;
import emulator.automation.shared.AutomationLimits;
import emulator.automation.shared.OperationDeadline;
import emulator.cli.controller.ControllerClient;
import emulator.cli.core.CliCommand;
import emulator.cli.core.CliInvocation;
import emulator.cli.core.CommandPath;
import emulator.cli.core.CommandResult;
import emulator.cli.core.KemuCliException;
import emulator.cli.parse.CliParsing;
import java.util.Locale;
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
		boolean halfStroke = "down".equals(action) || "up".equals(action);
		int keyIndex = "--".equals(invocation.tokens().get(2)) ? 3 : 2;
		if (keyIndex >= invocation.tokens().size()
			|| keyIndex == 3 && invocation.tokens().size() != 4) throw usage(json);
		String key = invocation.tokens().get(keyIndex);
		validateKey(key, json);
		int durationMs = "hold".equals(action)
			? AutomationLimits.DEFAULT_KEY_HOLD_DURATION_MS
			: AutomationLimits.DEFAULT_KEY_PRESS_DURATION_MS;
		boolean sawDuration = false;
		boolean observe = false;
		for (int i = keyIndex + 1; i < invocation.tokens().size(); i++) {
			String token = invocation.tokens().get(i);
			if ("--duration".equals(token) && !halfStroke) {
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
			} else if ("--observe".equals(token)) {
				if (observe) {
					throw CliParsing.duplicateOption(token, "key " + action, json);
				}
				observe = true;
			} else {
				throw usage(json);
			}
		}
		Json request = Json.object()
			.set("key", key)
			.set("waitDispatched", true);
		if (!halfStroke) {
			request.set("durationMs", durationMs);
		}

		OperationDeadline deadline = invocation.deadline(AutomationLimits.DEFAULT_TIMEOUT_MS);
		String operation = halfStroke ? "app.key." + action : "app.key";
		ControllerClient client = AgentCalls.client(invocation, "key " + action, deadline);
		Json payload = AgentCalls.call(invocation, client, deadline, operation, request, "key " + action);
		payload = AgentCalls.afterAction(invocation, client, deadline, "key " + action, payload, observe);
		return new CommandResult("key " + action, payload, json);
	}

	private void validateKey(String key, boolean json) {
		String normalized = key.trim().toUpperCase(Locale.ROOT).replace('-', '_');
		if (normalized.matches("(?:NUM_?)?[0-9]|\\*|#|STAR|POUND|HASH|UP|DOWN|LEFT|RIGHT|FIRE|MIDDLE|OK|LSK|RSK|SOFT_LEFT|SOFT_RIGHT|S1|S2")) return;
		throw new KemuCliException(AutomationErrorCodes.UNKNOWN_KEY, "Unknown key: " + key,
			"key " + action, json, Json.object().set("key", key));
	}
}
