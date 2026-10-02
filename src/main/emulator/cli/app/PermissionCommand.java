package emulator.cli.app;

import emulator.automation.shared.OperationDeadline;
import emulator.cli.controller.*;
import emulator.cli.core.*;
import emulator.cli.output.CliTextRenderer;
import mjson.Json;

public final class PermissionCommand implements CliCommand {
	public CommandPath path() {
		return CommandPath.of("permission");
	}

	public CommandResult run(CliInvocation invocation) throws Exception {
		if (invocation.tokens().size() < 3 || invocation.tokens().size() > 4) {
			throw new KemuCliException(
				CliErrorCodes.USAGE_ERROR,
				CliTextRenderer.usageText("permission"),
				"permission",
				invocation.json());
		}

		boolean allow;
		if ("allow".equals(invocation.tokens().get(1))) {
			allow = true;
		} else if ("deny".equals(invocation.tokens().get(1))) {
			allow = false;
		} else {
			throw new KemuCliException(
				CliErrorCodes.USAGE_ERROR,
				CliTextRenderer.usageText("permission"),
				"permission",
				invocation.json());
		}

		String ref = invocation.tokens().get(2);
		if (!ref.startsWith("@") || ref.length() == 1) throw usage(invocation);
		boolean remember = invocation.tokens().size() == 4;
		if (remember && (!allow || !"--remember".equals(invocation.tokens().get(3)))) throw usage(invocation);
		OperationDeadline deadline = invocation.deadline(10000L);
		ControllerClient client = ControllerStatusService.controllerClient(
			ControllerLifecycle.requireRunningController("permission", invocation.json(), deadline));
		Json payload = AgentCalls.call(invocation, client, deadline, "app.permission",
			Json.object().set("ref", ref).set("allow", allow).set("mode", remember ? "always" : "once"), "permission");
		payload.set("action", Json.object().set("operation", allow ? "allow" : "deny").set("ref", ref)
			.set("allow", allow).set("remember", remember));
		payload = AgentCalls.afterAction(invocation, client, deadline, "permission", payload, true);
		return new CommandResult("permission", payload, invocation.json());
	}

	private KemuCliException usage(CliInvocation invocation) {
		return new KemuCliException(
			CliErrorCodes.USAGE_ERROR,
			CliTextRenderer.usageText("permission"),
			"permission",
			invocation.json());
	}
}
