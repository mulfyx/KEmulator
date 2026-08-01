package emulator.cli.core;

import java.util.ArrayList;
import java.util.List;

public final class CommandRegistry {
	private final List<CliCommand> commands = new ArrayList<CliCommand>();

	public void add(CliCommand command) {
		commands.add(command);
	}

	public CliCommand resolve(List<String> tokens) {
		CliCommand best = null;
		int bestLength = -1;
		for (CliCommand command : commands) {
			CommandPath path = command.path();
			if (!path.matches(tokens)) {
				continue;
			}

			if (path.length() > bestLength) {
				best = command;
				bestLength = path.length();
			}
		}

		return best;
	}

	public List<String> commandPaths() {
		List<String> paths = new ArrayList<String>();
		for (CliCommand command : commands) {
			paths.add(command.path().asString());
		}
		java.util.Collections.sort(paths);

		return paths;
	}

	public CliCommand resolveExact(List<String> tokens) {
		CliCommand command = resolve(tokens);
		if (command == null) {
			return null;
		}

		return command.path().length() == tokens.size() ? command : null;
	}
}
