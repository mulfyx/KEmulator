package emulator.cli.library;

import emulator.cli.core.CliErrorCodes;
import emulator.cli.core.*;
import emulator.cli.output.CliTextRenderer;

public final class InspectCommand implements CliCommand {
	public CommandPath path() {
		return CommandPath.of("inspect");
	}

	public CommandResult run(CliInvocation invocation) throws Exception {
		int pathIndex = invocation.tokens().size() > 1 && "--".equals(invocation.tokens().get(1)) ? 2 : 1;
		if (invocation.tokens().size() != pathIndex + 1
			|| pathIndex == 1 && invocation.tokens().get(pathIndex).startsWith("--")) {
			throw new KemuCliException(
				CliErrorCodes.USAGE_ERROR,
				CliTextRenderer.usageText("inspect"),
				"inspect",
				invocation.json());
		}

		java.nio.file.Path input = emulator.cli.parse.CliParsing.resolveUserPath(
			invocation.tokens().get(pathIndex));
		InspectionResult result = CliAppInspector.inspect(input, "inspect", invocation.json());

		return new CommandResult(
			"inspect", result.toJson(), invocation.json());
	}
}
