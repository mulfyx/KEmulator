package emulator.cli.app;

import emulator.automation.shared.OperationDeadline;
import emulator.cli.core.*;
import emulator.cli.parse.CliParsing;
import java.nio.file.Path;
import mjson.Json;

public final class AgentObserveCommand implements CliCommand {
	public CommandPath path() { return CommandPath.of("observe"); }
	public CommandResult run(CliInvocation invocation) throws Exception {
		Path destination = null;
		if (invocation.tokens().size() != 1) {
			if (invocation.tokens().size() != 3 || !"--screenshot".equals(invocation.tokens().get(1)))
				throw new KemuCliException(CliErrorCodes.USAGE_ERROR, "Usage: kemu observe [--screenshot FILE]", "observe", invocation.json());
			destination = CliParsing.resolveUserPath(invocation.tokens().get(2));
		}
		OperationDeadline deadline = invocation.deadline(10000L);
		Json snapshot = AgentCalls.observe(invocation, AgentCalls.client(invocation, "observe", deadline), deadline, "observe", destination, false);
		return new CommandResult("observe", snapshot, invocation.json());
	}
}
