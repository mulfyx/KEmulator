package emulator.cli.core;

import mjson.Json;

public final class KemuCliException extends RuntimeException {
	public final String code;
	public final int exitCode;
	public final String commandName;
	public final boolean json;
	public final Json payload;

	public KemuCliException(String code, String message, String commandName, boolean json, Json payload) {
		super(message);
		this.code = code;
		// The exit code is a pure function of the error code, computed in
		// exactly one place.
		this.exitCode = CliErrorMapping.exitCodeFor(code);
		this.commandName = commandName;
		this.json = json;
		this.payload = payload;
	}

	public KemuCliException(String code, String message, String commandName, boolean json) {
		this(code, message, commandName, json, null);
	}
}
