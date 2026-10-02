package emulator.cli.controller;

import emulator.cli.core.CliErrorCodes;
import emulator.cli.core.CliCommand;
import emulator.cli.core.CliInvocation;
import emulator.cli.core.CommandPath;
import emulator.cli.core.CommandResult;
import emulator.cli.core.KemuCliException;
import emulator.automation.shared.OperationDeadline;
import emulator.cli.app.AgentCalls;
import mjson.Json;

public final class LogsReadCommand implements CliCommand {
	public CommandPath path() {
		return CommandPath.of("logs");
	}

	public CommandResult run(CliInvocation invocation) throws Exception {
		boolean json = invocation.json();
		String since = null;
		boolean jsonl = false;
		for (int i = 1; i < invocation.tokens().size(); i++) {
			String token = invocation.tokens().get(i);
			if ("--since".equals(token)) {
				if (since != null) {
					throw emulator.cli.parse.CliParsing.duplicateOption(token, "logs", json);
				}
				if (i + 1 >= invocation.tokens().size()) {
					throw usage(json);
				}
				since = invocation.tokens().get(++i);
				if ("--".equals(since)) {
					if (i + 1 >= invocation.tokens().size()) throw usage(json);
					since = invocation.tokens().get(++i);
				}
				if (since.trim().length() == 0) throw new KemuCliException(
					emulator.automation.shared.AutomationErrorCodes.INVALID_REQUEST,
					"Log cursor must not be empty.", "logs", json);
			} else if ("--jsonl".equals(token)) {
				if (jsonl) {
					throw emulator.cli.parse.CliParsing.duplicateOption(token, "logs", json);
				}
				jsonl = true;
			} else {
				throw usage(json);
			}
		}
		OperationDeadline deadline = invocation.deadline(10000L);
		ControllerStatus status = ControllerLifecycle.requireRunningController("logs", json, deadline);
		Json request = Json.object();
		if (since != null) {
			request.set("since", since);
		}
		Json payload = AgentCalls.call(invocation, ControllerStatusService.controllerClient(status), deadline,
			"logs.read", request, "logs");
		if (jsonl) payload.set("jsonl", true);
		return new CommandResult("logs", payload, json);
	}

	private KemuCliException usage(boolean json) {
		return new KemuCliException(
			CliErrorCodes.USAGE_ERROR,
			emulator.cli.output.CliTextRenderer.usageText("logs"),
			"logs",
			json);
	}
}
