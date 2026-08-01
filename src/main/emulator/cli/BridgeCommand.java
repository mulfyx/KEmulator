package emulator.cli;

import emulator.cli.core.CliApp;
import emulator.cli.core.CliCommand;
import emulator.cli.core.CliErrorCodes;
import emulator.cli.core.CliInvocation;
import emulator.cli.core.CommandPath;
import emulator.cli.core.CommandResult;
import emulator.cli.core.KemuCliException;
import emulator.cli.output.CliResponses;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import mjson.Json;

/**
 * JSONL bridge: one long-lived CLI process serving many commands.
 *
 * Each stdin line is {"id": any, "argv": ["open", "app.jar", ...]}; each
 * response line is the normal envelope plus the echoed "id". The session id
 * is fixed per bridge process; EOF terminates the bridge with exit 0.
 */
final class BridgeCommand implements CliCommand {
	private CliApp app;
	private boolean active;

	void attach(CliApp app) {
		this.app = app;
	}

	public CommandPath path() {
		return CommandPath.of("bridge");
	}

	private static Json invalidRequest(Json id, String message) {
		return CliResponses.errorEnvelope("bridge", CliErrorCodes.USAGE_ERROR, message, null)
			.set("id", id);
	}

	private Json serve(Json id, Json request) {
		Json argvJson = request.at("argv");
		if (argvJson == null || !argvJson.isArray()) {
			return invalidRequest(id, "Bridge request requires argv as an array of strings.");
		}

		ArrayList<String> argv = new ArrayList<String>();
		for (Json token : argvJson.asJsonList()) {
			if (!token.isString()) {
				return invalidRequest(id, "Bridge argv entries must be strings.");
			}

			String value = token.asString();
			if ("--".equals(value)) {
				argv.add(value);
				continue;
			}

			if (argv.contains("--") ) {
				argv.add(value);
				continue;
			}

			if ("--session-id".equals(value)) {
				return invalidRequest(id, "The session id is fixed per bridge process.");
			}

			if ("bridge".equals(value) && argv.isEmpty()) {
				return invalidRequest(id, "bridge cannot be nested.");
			}

			argv.add(value);
		}

		Json response;
		try {
			CommandResult result = app.run(argv.toArray(new String[argv.size()]));
			response = CliResponses.successEnvelope(result.commandName, result.payload);
		} catch (KemuCliException failure) {
			response = CliResponses.errorEnvelope(
				failure.commandName, failure.code, failure.getMessage(), failure.payload);
		} catch (Exception failure) {
			response = CliResponses.errorEnvelope(
				null, CliErrorCodes.INTERNAL_ERROR, failure.toString(), null);
		}

		return response.set("id", id);
	}

	public CommandResult run(CliInvocation invocation) throws IOException {
		if (invocation.tokens().size() != 1) {
			throw new KemuCliException(
				CliErrorCodes.USAGE_ERROR,
				emulator.cli.output.CliTextRenderer.usageText("bridge"),
				"bridge",
				invocation.json());
		}

		if (active) {
			throw new KemuCliException(
				CliErrorCodes.USAGE_ERROR, "bridge cannot be nested.", "bridge", invocation.json());
		}

		active = true;
		BufferedReader in = new BufferedReader(
			new InputStreamReader(System.in, StandardCharsets.UTF_8));
		PrintStream out = System.out;
		try {
			String line;
			while ((line = in.readLine()) != null) {
				if (line.trim().length() == 0) {
					continue;
				}

				Json response;
				Json id = Json.nil();
				try {
					Json request = Json.read(line);
					if (!request.isObject()) {
						response = invalidRequest(id, "Bridge request must be a JSON object.");
					} else {
						id = request.at("id", Json.nil());
						response = serve(id, request);
					}
				} catch (RuntimeException failure) {
					response = invalidRequest(id, "Invalid bridge request line: " + failure.getMessage());
				}

				out.println(response.toString());
				out.flush();
			}
		} finally {
			active = false;
		}

		// Responses were already streamed; the bridge itself stays silent so
		// line-oriented consumers never see a trailing id-less envelope.
		return new CommandResult("bridge", "", Json.object(), false);
	}
}
