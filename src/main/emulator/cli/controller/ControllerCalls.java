package emulator.cli.controller;

import emulator.automation.shared.AutomationErrorCodes;
import emulator.cli.core.*;
import emulator.cli.output.CliResponses;
import java.io.IOException;
import mjson.Json;

public final class ControllerCalls {
	private ControllerCalls() {
	}

	public static Json callController(
		ControllerClient client, String name, Json arguments, String commandName, boolean json) throws Exception {
		try {
			return client.callTool(name, arguments);
		} catch (ControllerException e) {
			throw new KemuCliException(
				e.code,
				e.getMessage(),
				commandName,
				json,
				CliResponses.normalizePublicJson(e.payload));
		} catch (IOException e) {
			throw new KemuCliException(
				AutomationErrorCodes.CONTROLLER_UNREACHABLE,
				e.getMessage(),
				commandName,
				json);
		}
	}

	public static Json currentAppResult(ControllerClient client, String commandName, boolean json) throws Exception {
		return CliResponses.normalizePublicJson(
			callController(client, "app.current", Json.object(), commandName, json));
	}

	public static void throwIfWorkerFailure(Json current, String commandName, boolean json) {
		if (current == null || current.isNull() || !current.isObject() || !current.has("failure")) {
			return;
		}

		Json failure = current.at("failure");
		if (failure == null || failure.isNull() || !failure.isObject()) {
			return;
		}

		String code = failure.has("code") ? failure.at("code").asString() : AutomationErrorCodes.WORKER_FAILURE;
		String message = failure.has("message") ? failure.at("message").asString() : "Worker exited unexpectedly";
		Json details = failure.has("details") ? CliResponses.normalizePublicJson(failure.at("details")) : null;
		throw new KemuCliException(code, message, commandName, json, details);
	}
}
