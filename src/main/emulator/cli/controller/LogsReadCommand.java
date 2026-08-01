package emulator.cli.controller;

import emulator.cli.core.CliErrorCodes;
import emulator.cli.core.CliCommand;
import emulator.cli.core.CliInvocation;
import emulator.cli.core.CommandPath;
import emulator.cli.core.CommandResult;
import emulator.cli.core.KemuCliException;
import emulator.cli.output.CliResponses;
import mjson.Json;

public final class LogsReadCommand implements CliCommand {
	public CommandPath path() {
		return CommandPath.of("logs", "read");
	}

	public CommandResult run(CliInvocation invocation) throws Exception {
		boolean json = invocation.json();
		String since = null;
		boolean jsonl = false;
		for (int i = 2; i < invocation.tokens().size(); i++) {
			String token = invocation.tokens().get(i);
			if ("--since".equals(token)) {
				if (since != null) {
					throw emulator.cli.parse.CliParsing.duplicateOption(token, "logs read", json);
				}
				if (i + 1 >= invocation.tokens().size()) {
					throw usage(json);
				}
				since = invocation.tokens().get(++i);
			} else if ("--jsonl".equals(token)) {
				if (jsonl) {
					throw emulator.cli.parse.CliParsing.duplicateOption(token, "logs read", json);
				}
				jsonl = true;
			} else {
				throw usage(json);
			}
		}
		ControllerStatus status = ControllerLifecycle.requireRunningController("logs read", json);
		Json request = Json.object();
		if (since != null) {
			request.set("since", since);
		}
		Json payload = CliResponses.normalizePublicJson(ControllerCalls.callController(
			ControllerStatusService.controllerClient(status),
			"logs.read",
			request,
			"logs read",
			json));
		String text = payload.at("text", "").asString();
		if (jsonl) {
			StringBuilder output = new StringBuilder();
			for (Json line : payload.at("lines", Json.array()).asJsonList()) {
				if (output.length() > 0) {
					output.append('\n');
				}
				output.append(line.toString());
			}
			text = output.toString();
		}
		return new CommandResult("logs read", text, payload, json);
	}

	private KemuCliException usage(boolean json) {
		return new KemuCliException(
			CliErrorCodes.USAGE_ERROR,
			emulator.cli.output.CliTextRenderer.usageText("logs read"),
			"logs read",
			json);
	}
}
