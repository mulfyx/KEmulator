package emulator.cli.output;

import emulator.cli.core.*;
import mjson.Json;

public final class CliResponses {
	private CliResponses() {
	}

	public static Json normalizePublicJson(Json value) {
		if (value == null || value.isNull()) {
			return Json.nil();
		}

		if (value.isObject()) {
			Json result = Json.object();
			for (String key : value.asJsonMap().keySet()) {
				result.set(key, normalizePublicJson(value.at(key)));
			}

			return result;
		}

		if (value.isArray()) {
			Json array = Json.array();
			for (Json item : value.asJsonList()) {
				array.add(normalizePublicJson(item));
			}

			return array;
		}

		return value.dup();
	}

	private static Json publicWorker(Json worker) {
		if (worker == null || !worker.isObject()) {
			return Json.nil();
		}

		return Json.object()
			.set("pid", worker.at("pid", Json.nil()))
			.set("alive", worker.at("alive", Json.nil()))
			.set("ready", worker.at("ready", Json.nil()))
			.set("sessionId", worker.at("sessionId", Json.nil()))
			.set("logPath", worker.at("logPath", Json.nil()));
	}

	public static Json publicizeOpenResult(Json input) {
		Json payload = normalizePublicJson(input);
		Json result = Json.object();
		if (payload.has("app")) {
			result.set("app", payload.at("app"));
		}

		if (payload.has("inputPath")) {
			result.set("inputPath", payload.at("inputPath"));
		}

		result.set("worker", publicWorker(payload.at("worker")));
		result.set("status", payload.at("status", Json.nil()));
		result.set("state", payload.at("state", Json.nil()));

		return result;
	}

	public static Json successEnvelope(String commandName, Json payload) {
		return Json.object().set("ok", true).set("command", commandName).set("result", payload);
	}

	public static Json errorEnvelope(String commandName, String code, String message, Json payload) {
		Json error = Json.object().set("code", code).set("message", message);
		if (payload != null && !payload.isNull()) {
			error.set("details", payload);
		}

		return Json.object().set("ok", false).set("command", commandName).set("error", error);
	}

	/** One canonical projection: {active, app, state} for state and observe. */
	public static Json buildSnapshotPayload(Json current, Json snapshot) {
		if (!current.at("active", false).asBoolean()) {
			return Json.object().set("active", false);
		}

		return Json.object()
			.set("active", true)
			.set("app", current.at("app"))
			.set("state", normalizePublicJson(snapshot));
	}
}
