package emulator.cli.app;

import emulator.automation.shared.OperationDeadline;
import emulator.cli.controller.ControllerClient;
import emulator.cli.core.*;
import java.util.List;
import mjson.Json;

public final class AgentRefCommand implements CliCommand {
	private final String operation;
	public AgentRefCommand(String operation) { this.operation = operation; }
	public CommandPath path() { return CommandPath.of(operation); }
	public CommandResult run(CliInvocation invocation) throws Exception {
		List<String> tokens = invocation.tokens();
		if (tokens.size() < 2) throw usage(invocation);
		String ref = tokens.get(1);
		Json args = Json.object().set("ref", ref);
		if ("set".equals(operation)) {
			int index = tokens.size() > 2 && "--".equals(tokens.get(2)) ? 3 : 2;
			if (tokens.size() != index + 1) throw usage(invocation);
			args.set("value", tokens.get(index));
		} else if ("select".equals(operation)) {
			if (tokens.size() == 3 && "--off".equals(tokens.get(2))) args.set("off", true);
			else if (tokens.size() != 2) throw usage(invocation);
		} else if (tokens.size() != 2) throw usage(invocation);
		OperationDeadline deadline = invocation.deadline(10000L);
		ControllerClient client = AgentCalls.client(invocation, operation, deadline);
		try {
			Json result = AgentCalls.call(invocation, client, deadline, "app.ref." + operation, args, operation);
			result = AgentCalls.afterAction(invocation, client, deadline, operation, result, true);
			return new CommandResult(operation, result, invocation.json());
		} catch (KemuCliException error) {
			if ("STALE_REF".equals(error.code) && !deadline.timedOut()) {
				Json details = error.payload == null || !error.payload.isObject() ? Json.object() : error.payload.dup();
				try { details.set("lastState", AgentCalls.observe(invocation, client, deadline, operation, null, false)); }
				catch (Exception unavailable) { }
				throw new KemuCliException(error.code, error.getMessage(), operation, invocation.json(), details);
			}
			throw error;
		}
	}
	private KemuCliException usage(CliInvocation invocation) {
		return new KemuCliException(CliErrorCodes.USAGE_ERROR,
			emulator.cli.output.CliTextRenderer.usageText(operation), operation, invocation.json());
	}
}
