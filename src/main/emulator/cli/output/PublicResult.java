package emulator.cli.output;

import emulator.cli.core.CommandResult;
import emulator.cli.support.KemuPaths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import mjson.Json;

/** The single public projection consumed by both output renderers. */
public final class PublicResult {
	private PublicResult() { }

	private static Json object(Json value) {
		return value != null && value.isObject() ? value : Json.object();
	}

	private static String string(Json value) {
		return value == null || value.isNull() ? "" : value.asString();
	}

	private static Json state(Json raw) {
		if (raw.has("state") && raw.at("state").isObject()) return raw.at("state");
		if (raw.has("ui")) return raw;
		if (raw.has("current") && raw.at("current").isObject()) {
			Json nested = raw.at("current").at("state", Json.nil());
			if (nested.isObject()) return nested;
		}
		return Json.object();
	}

	public static Json observation(Json snapshot) {
		Json ui = snapshot.at("ui", Json.nil());
		if (!ui.isObject()) return Json.nil();
		Json result = ui.dup();
		result.set("size", Json.object().set("width", snapshot.at("width", 0))
			.set("height", snapshot.at("height", 0)));
		return result;
	}

	private static Json context(Json raw, Json snapshot) {
		Json current = object(raw.at("current", raw));
		Json sourceApp = object(raw.at("app", current.at("app", Json.object())));
		String name = string(sourceApp.at("displayName", sourceApp.at("name", snapshot.at("jarName", ""))));
		String appStatus = "none";
		if (current.has("failure") || raw.has("workerFailure")) appStatus = "failed";
		else if (snapshot.at("paused", false).asBoolean()) appStatus = "paused";
		else if (snapshot.at("ready", false).asBoolean()
			|| object(current.at("worker", Json.nil())).at("ready", false).asBoolean()) appStatus = "ready";
		else if ("starting".equals(string(raw.at("status", "")))
			|| current.at("active", false).asBoolean() || raw.has("worker")) appStatus = "starting";
		if (raw.has("appStatus")) appStatus = raw.at("appStatus").asString();
		String sessionStatus = raw.has("running")
			? (raw.at("running", false).asBoolean() ? "running" : "stopped") : "running";
		if (raw.at("degraded", false).asBoolean()) sessionStatus = "unresponsive";
		Json result = Json.object().set("session", Json.object().set("id", KemuPaths.sessionId())
			.set("status", sessionStatus)).set("app", Json.object().set("name", name).set("status", appStatus));
		Json permission = snapshot.at("permissionRequest", raw.at("permissionRequest", Json.nil()));
		if (permission.isObject()) result.set("permission", permission.dup().set("actions", Json.array("allow", "deny")));
		Json failure = raw.at("workerFailure", current.at("failure", raw.at("statusFailure", Json.nil())));
		if (failure.isObject()) result.set("failure", cleanDetails(failure));
		if (raw.has("reason")) result.set("reason", raw.at("reason"));
		return result;
	}

	public static Json envelope(CommandResult command) {
		Json raw = object(command.payload);
		Json snapshot = state(raw);
		String name = command.commandName;
		Json result;
		if ("help".equals(name)) {
			result = raw.dup();
			result.delAt("rootDir");
		} else if ("inspect".equals(name)) {
			result = Json.object().set("name", raw.at("displayName", ""))
				.set("vendor", raw.at("vendor", Json.nil())).set("version", raw.at("version", Json.nil()))
				.set("sourceKind", raw.at("sourceKind", "")).set("path", raw.at("inputPath", ""));
			Json midlets = Json.array();
			for (Json midlet : raw.at("midlets", Json.array()).asJsonList())
				midlets.add(Json.object().set("index", midlet.at("index")).set("name", midlet.at("name")));
			result.set("midlets", midlets);
		} else if (name.startsWith("logs")) {
			result = Json.object().set("cursor", raw.at("cursor", ""));
			if (raw.has("lines")) result.set("lines", raw.at("lines"));
		} else if (name.startsWith("storage")) {
			result = Json.object().set("session", Json.object().set("id", KemuPaths.sessionId()))
				.set("scope", raw.at("scope", "state")).set("action", raw.at("action", ""));
			if (raw.has("archive")) result.set("archive", raw.at("archive"));
		} else {
			result = context(raw, snapshot);
			Json view = observation(snapshot);
			if (view.isObject()) {
				if ("screenshot".equals(name)) {
					view.delAt("nodes"); view.delAt("commands");
				}
				result.set("observation", view);
			}
			if (raw.has("action")) result.set("action", raw.at("action"));
			else if (raw.has("delivery")) {
				Json action = raw.at("delivery").dup().set("operation", name);
				for (String key : Arrays.asList("state", "permissionRequest", "pending", "status", "revision", "pressSequence", "releaseSequence", "admitted")) action.delAt(key);
				for (String key : Arrays.asList("key", "x", "y", "points"))
					if (raw.has(key)) action.set(key, raw.at(key));
				result.set("action", action);
			}
			else if (name.startsWith("permission")) result.set("action", Json.object().set("operation", name)
				.set("allow", raw.at("allow", false)));
			if (name.startsWith("wait")) {
				result.set("matched", raw.at("matched", false)).set("condition", raw.at("condition", name.substring(5)));
				for (String key : Arrays.asList("cursor", "text", "exitCode", "frameId", "elapsedMs"))
					if (raw.has(key)) result.set(key, raw.at(key));
			}
		}
		boolean pending = raw.at("pending", false).asBoolean()
			|| object(raw.at("delivery", Json.nil())).at("pending", false).asBoolean()
			|| "open".equals(name) && "pending-permission".equals(raw.at("status", "").asString());
		Json envelope = Json.object().set("command", name).set("outcome", pending ? "pending" : "done")
			.set("result", result);
		if (command.verbose) envelope.set("diagnostics", raw.dup());
		return envelope;
	}

	private static Json cleanDetails(Json raw) {
		if (!raw.isObject()) return raw.dup();
		Set<String> hidden = new HashSet<String>(Arrays.asList("jvmOptions", "controllerJvmOptions", "commandLine",
			"logTail", "classpath", "worker", "properties", "lastState", "state", "pid", "port", "host"));
		Json result = Json.object();
		for (String key : raw.asJsonMap().keySet()) {
			if (hidden.contains(key)) continue;
			Json value = raw.at(key);
			result.set(key, value.isObject() ? cleanDetails(value) : value.dup());
		}
		Json snapshot = raw.at("lastState", raw.at("state", Json.nil()));
		if (snapshot.isObject()) {
			Json view = observation(snapshot);
			if (view.isObject()) result.set("observation", view);
		}
		return result;
	}

	public static Json error(String command, String code, String message, Json details, boolean verbose) {
		Json error = Json.object().set("code", code).set("message", message);
		if (details != null && !details.isNull()) error.set("details", cleanDetails(details));
		Json result = Json.object().set("command", command).set("outcome", "error").set("error", error);
		if (verbose && details != null && !details.isNull()) result.set("diagnostics", details.dup());
		return result;
	}
}
