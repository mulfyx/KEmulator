package emulator.cli.core;

import mjson.Json;

public final class CommandResult {
	public final String commandName;
	public final Json payload;
	public final boolean json;
	public final boolean verbose;
	public final boolean streamed;

	public CommandResult(String commandName, Json payload, boolean json) {
		this(commandName, payload, json, false, false);
	}

	private CommandResult(String commandName, Json payload, boolean json, boolean verbose, boolean streamed) {
		this.commandName = commandName;
		this.payload = payload;
		this.json = json;
		this.verbose = verbose;
		this.streamed = streamed;
	}

	public CommandResult presentation(boolean json, boolean verbose) {
		return new CommandResult(commandName, payload, json, verbose, streamed);
	}

	public static CommandResult streamed(String commandName) {
		return new CommandResult(commandName, Json.object(), false, false, true);
	}
}
