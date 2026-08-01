package emulator.cli.app;

import emulator.automation.shared.AutomationLimits;
import emulator.cli.controller.*;
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

public final class RunUiCommand implements CliCommand {
	public CommandPath path() {
		return CommandPath.of("command", "run");
	}

	private KemuCliException usage(boolean json) {
		return new KemuCliException(
			CliErrorCodes.USAGE_ERROR,
			CliTextRenderer.usageText("command run"),
			"command run",
			json);
	}

	public CommandResult run(CliInvocation invocation) throws Exception {
		boolean json = invocation.json();
		Integer id = null;
		String label = null;
		Long expectRevision = null;
		boolean waitNextDisplay = false;
		Integer timeoutMs = null;
		for (int i = 2; i < invocation.tokens().size(); i++) {
			String token = invocation.tokens().get(i);
			if ("--id".equals(token)) {
				if (id != null) {
					throw CliParsing.duplicateOption(token, "command run", json);
				}
				if (label != null || i + 1 >= invocation.tokens().size()) {
					throw usage(json);
				}
				id = Integer.valueOf(CliParsing.parseIntegerArgument(
					invocation.tokens().get(++i), "--id", "command run", json));
			} else if ("--label".equals(token)) {
				if (label != null) {
					throw CliParsing.duplicateOption(token, "command run", json);
				}
				if (id != null || i + 1 >= invocation.tokens().size()) {
					throw usage(json);
				}
				label = invocation.tokens().get(++i);
			} else if ("--expect-revision".equals(token)) {
				if (expectRevision != null) {
					throw CliParsing.duplicateOption(token, "command run", json);
				}
				if (i + 1 >= invocation.tokens().size()) {
					throw usage(json);
				}
				expectRevision = Long.valueOf(
					CliParsing.parseRevision(invocation.tokens().get(++i), "command run", json));
			} else if ("--wait-next-display".equals(token)) {
				if (waitNextDisplay) {
					throw CliParsing.duplicateOption(token, "command run", json);
				}
				waitNextDisplay = true;
			} else if ("--timeout".equals(token)) {
				if (timeoutMs != null) {
					throw CliParsing.duplicateOption(token, "command run", json);
				}
				if (i + 1 >= invocation.tokens().size()) {
					throw usage(json);
				}
				int timeout = CliParsing.parseIntegerArgument(
					invocation.tokens().get(++i), "--timeout", "command run", json);
				timeoutMs = Integer.valueOf(CliParsing.requireInclusiveRange(
					timeout, 0, AutomationLimits.MAX_WAIT_MS, "--timeout", "command run", json));
			} else {
				throw usage(json);
			}
		}

		if (id == null && label == null) {
			throw usage(json);
		}

		ControllerStatus status = ControllerLifecycle.requireRunningController("command run", json);
		Json request = Json.object().set("waitNextDisplay", waitNextDisplay);
		if (timeoutMs != null) {
			request.set("timeoutMs", timeoutMs.intValue());
		}
		if (id != null) {
			request.set("id", id.intValue());
		}
		if (label != null) {
			request.set("label", label);
		}
		if (expectRevision != null) {
			request.set("expectRevision", expectRevision.longValue());
		}
		Json payload = CliResponses.normalizePublicJson(ControllerCalls.callController(
			ControllerStatusService.controllerClient(status),
			"app.command.run",
			request,
			"command run",
			json));

		return new CommandResult("command run", CliTextRenderer.renderCommandRun(payload), payload, json);
	}
}
