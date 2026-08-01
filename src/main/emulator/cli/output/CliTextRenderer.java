package emulator.cli.output;

import emulator.automation.shared.TextValues;
import emulator.cli.controller.ControllerStatus;
import emulator.cli.library.*;
import emulator.cli.support.KemuPaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import mjson.Json;

public final class CliTextRenderer {
	private CliTextRenderer() {
	}

	private static String trimTrailingNewline(StringBuilder value) {
		if (value.length() > 0 && value.charAt(value.length() - 1) == '\n') {
			value.setLength(value.length() - 1);
		}

		return value.toString();
	}

	private static boolean hasDevRuntime(Path root) {
		return Files.isRegularFile(root.resolve("out/classes-linux/emulator/cli/KEmulator.class"))
			&& Files.isRegularFile(root.resolve("out/classes-linux/emulator/cli/KemuMain.class"))
			&& Files.isRegularFile(
				root.resolve("out/classes-linux/emulator/automation/controller/AutomationControllerMain.class"))
			&& Files.isRegularFile(
				root.resolve("out/classes-linux/emulator/automation/worker/AutomationWorkerMain.class"));
	}

	private static boolean hasReleaseRuntime(Path root) {
		return Files.isRegularFile(root.resolve("KEmulator.jar"))
			|| Files.isRegularFile(root.resolve("dist/release-linux/KEmulator.jar"));
	}

	private static String joinRuntimeChoices(ArrayList<String> values) {
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < values.size(); i++) {
			if (i > 0) {
				out.append('|');
			}

			out.append(values.get(i));
		}

		return out.toString();
	}

	private static String runtimeUsageChoices() {
		String configured = TextValues.trimToNull(System.getProperty("kemu.bootstrap.availableRuntimes"));
		if (configured != null) {
			return configured;
		}

		ArrayList<String> available = new ArrayList<String>();
		Path root = KemuPaths.rootDir();
		if (hasDevRuntime(root)) {
			available.add("dev-linux");
		}

		if (hasReleaseRuntime(root)) {
			available.add("release");
		}

		if (!available.isEmpty()) {
			return joinRuntimeChoices(available);
		}

		String bootstrapRuntime = TextValues.trimToNull(System.getProperty("kemu.bootstrap.runtime"));
		if (bootstrapRuntime != null) {
			return bootstrapRuntime;
		}

		return "dev-linux|release";
	}

	private static final String[] ROOT_TOPICS = {
		"help", "bridge", "start", "status", "stop", "logs", "inspect", "open", "close",
		"state", "rms", "observe", "events", "screenshot", "wait", "key",
		"pointer", "drag", "list", "choice", "gauge", "text-field", "text-box",
		"resize", "rotate", "pause", "resume", "date-field", "command run", "permission",
	};

	public static String usageText() {
		StringBuilder out = new StringBuilder("Usage:\n");
		for (String topic : ROOT_TOPICS) {
			for (String line : usageLine(topic).split("\n")) {
				out.append("  kemu ").append(line.replaceFirst("^\\s*kemu ", "")).append('\n');
			}
		}

		out.append('\n')
			.append("Notes:\n")
			.append("  CLI automation contract is currently Linux-only.\n")
			.append("  Use `kemu <command> --help` or `kemu help <command...>` for command-specific usage.\n")
			.append("  Path-first workflow is canonical: inspect/open <path>.\n");

		return out.toString();
	}

	private static String usageLine(String topic) {
		if ("help".equals(topic))
			return "kemu help [command...] [--json]";
		if ("bridge".equals(topic))
			return "kemu bridge  (JSONL: one {\"id\", \"argv\": [...]} request per stdin line)";
		if ("start".equals(topic))
			return "kemu start [--headless|--visible] [--runtime " + runtimeUsageChoices() + "] [--size WxH] [--json]";
		if ("status".equals(topic))
			return "kemu status [--json]";
		if ("stop".equals(topic))
			return "kemu stop [--force] [--json]";
		if ("logs".equals(topic))
			return "kemu logs cursor [--json]\n"
				+ "       kemu logs read [--since CURSOR] [--jsonl] [--json]";
		if ("logs cursor".equals(topic))
			return "kemu logs cursor [--json]";
		if ("logs read".equals(topic))
			return "kemu logs read [--since CURSOR] [--jsonl] [--json]";
		if ("inspect".equals(topic))
			return "kemu inspect <path> [--json]";
		if ("open".equals(topic))
			return "kemu open <path> [--midlet N] [--headless|--visible] [--runtime " + runtimeUsageChoices()
				+ "] [--size WxH] [--data-dir DIR] [--rms-dir DIR] [--file-root DIR]"
				+ " [--reset-state] [--reset-file-root] [--worker-xmx SIZE]"
				+ " [--wait-ready] [--open-timeout MS] [--json]";
		if ("close".equals(topic))
			return "kemu close [--json]";
		if ("state".equals(topic))
			return "kemu state [--json]\n"
				+ "       kemu state <snapshot|restore> FILE [--json]";
		if ("rms".equals(topic))
			return "kemu rms reset [--json]\n"
				+ "       kemu rms <export|import> FILE [--json]";
		if ("observe".equals(topic))
			return "kemu observe [--screenshot FILE] [--json]";
		if ("events".equals(topic))
			return "kemu events read [--since CURSOR] [--jsonl] [--json]";
		if ("screenshot".equals(topic))
			return "kemu screenshot FILE [--json]";
		if ("wait".equals(topic))
			return "kemu wait display [--kind KIND] [--title TITLE] [--title-regex REGEX]"
				+ " [--selected-index N] [--after-revision REV] [--timeout MS] [--json]\n"
				+ "       kemu wait <worker-ready|worker-exit|idle> [--timeout MS] [--json]\n"
				+ "       kemu wait frame --after-revision REV [--timeout MS] [--json]\n"
				+ "       kemu wait permission [--name NAME] [--timeout MS] [--json]\n"
				+ "       kemu wait log --regex REGEX [--since CURSOR] [--timeout MS] [--json]";
		if ("wait display".equals(topic))
			return "kemu wait display [--kind KIND] [--title TITLE] [--title-regex REGEX]"
				+ " [--selected-index N] [--after-revision REV] [--timeout MS] [--json]";
		if ("wait worker-ready".equals(topic)
			|| "wait worker-exit".equals(topic)
			|| "wait idle".equals(topic))
			return "kemu " + topic + " [--timeout MS] [--json]";
		if ("wait frame".equals(topic))
			return "kemu wait frame --after-revision REV [--timeout MS] [--json]";
		if ("wait log".equals(topic))
			return "kemu wait log --regex REGEX [--since CURSOR] [--timeout MS] [--json]";
		if ("wait permission".equals(topic))
			return "kemu wait permission [--name NAME] [--timeout MS] [--json]";
		if ("key".equals(topic))
			return "kemu key press <key> [--duration MS] [--wait-dispatched] [--json]\n"
				+ "       kemu key hold <key> [--duration MS] [--wait-dispatched] [--wait-release] [--json]\n"
				+ "       kemu key <down|up> <key> [--wait-dispatched] [--json]";
		if ("key down".equals(topic) || "key up".equals(topic))
			return "kemu " + topic + " <key> [--wait-dispatched] [--json]";
		if ("key press".equals(topic))
			return "kemu key press <key> [--duration MS] [--wait-dispatched] [--json]";
		if ("key hold".equals(topic))
			return "kemu key hold <key> [--duration MS] [--wait-dispatched] [--wait-release] [--json]";
		if ("pointer".equals(topic))
			return "kemu pointer tap <x> <y> [--wait-dispatched] [--json]\n"
				+ "       kemu pointer <down|up> <x> <y> [--wait-dispatched] [--json]";
		if ("pointer tap".equals(topic) || "pointer down".equals(topic) || "pointer up".equals(topic))
			return "kemu " + topic + " <x> <y> [--wait-dispatched] [--json]";
		if ("drag".equals(topic))
			return "kemu drag <x1> <y1> <x2> <y2> [<x3> <y3> ...] [--delay MS] [--json]";
		if ("list".equals(topic))
			return "kemu list select INDEX [--expect-revision REV] [--timeout MS] [--json]\n"
				+ "       kemu list move <up|down> [--count N] [--expect-revision REV] [--timeout MS] [--json]";
		if ("list select".equals(topic))
			return "kemu list select INDEX [--expect-revision REV] [--timeout MS] [--json]";
		if ("list move".equals(topic))
			return "kemu list move <up|down> [--count N] [--expect-revision REV] [--timeout MS] [--json]";
		if ("choice".equals(topic) || "choice set".equals(topic))
			return "kemu choice set INDEX [--item-index INDEX] [--expect-revision REV] [--timeout MS] [--json]";
		if ("gauge".equals(topic) || "gauge set".equals(topic))
			return "kemu gauge set VALUE [--item-index INDEX] [--expect-revision REV] [--timeout MS] [--json]";
		if ("text-field".equals(topic) || "text-field set".equals(topic))
			return "kemu text-field set TEXT [--item-index INDEX] [--expect-revision REV] [--timeout MS] [--json]";
		if ("text-box".equals(topic) || "text-box set".equals(topic))
			return "kemu text-box set TEXT [--expect-revision REV] [--timeout MS] [--json]";
		if ("date-field".equals(topic) || "date-field set".equals(topic))
			return "kemu date-field set EPOCH_MS [--item-index INDEX] [--expect-revision REV]"
				+ " [--timeout MS] [--json]";
		if ("pause".equals(topic) || "resume".equals(topic))
			return "kemu " + topic + " [--expect-revision REV] [--timeout MS] [--json]";
		if ("resize".equals(topic))
			return "kemu resize WIDTHxHEIGHT [--expect-revision REV] [--wait-frame] [--timeout MS] [--json]";
		if ("rotate".equals(topic))
			return "kemu rotate [--expect-revision REV] [--wait-frame] [--timeout MS] [--json]";
		if ("command".equals(topic))
			return "kemu command run <--id ID|--label LABEL> [--expect-revision REV]"
				+ " [--wait-next-display] [--timeout MS] [--json]";
		if ("command run".equals(topic))
			return "kemu command run <--id ID|--label LABEL> [--expect-revision REV]"
				+ " [--wait-next-display] [--timeout MS] [--json]";
		if ("permission".equals(topic))
			return "kemu permission <allow [--once|--always]|deny> [id] [--json]";
		if ("events read".equals(topic))
			return "kemu events read [--since CURSOR] [--jsonl] [--json]";
		if ("rms reset".equals(topic))
			return "kemu rms reset [--json]";
		if ("rms export".equals(topic) || "rms import".equals(topic))
			return "kemu " + topic + " FILE [--json]";
		if ("state snapshot".equals(topic) || "state restore".equals(topic))
			return "kemu " + topic + " FILE [--json]";
		return null;
	}

	public static boolean hasUsageTopic(String topic) {
		return usageLine(topic) != null;
	}

	public static String usageText(String topic) {
		if (topic == null || topic.length() == 0) {
			return usageText();
		}

		String usage = usageLine(topic);
		if (usage == null) {
			return usageText();
		}

		return "Usage: " + usage + "\nNote: CLI automation contract is currently Linux-only.";
	}

	public static String renderStatus(ControllerStatus status) {
		return renderStatus(status, status.toJson());
	}

	public static String renderStatus(ControllerStatus status, Json payload) {
		if (!status.exists) {
			return "No controller state file found.";
		}

		StringBuilder out = new StringBuilder();
		out.append("Controller state: ").append(status.source).append('\n');
		out.append("State file: ").append(status.stateFile).append('\n');
		out.append("Running: ").append(status.running).append('\n');
		out.append("Reachable: ").append(status.reachable).append('\n');
		if (status.pidAlive != null) {
			out.append("PID alive: ").append(status.pidAlive.booleanValue()).append('\n');
		}

		if (status.degraded) {
			out.append("Degraded: true").append('\n');
		}

		if (status.pid != null) {
			out.append("Controller PID: ").append(status.pid).append('\n');
		}

		Json controllerJvmOptions = payload.at("controllerJvmOptions", Json.array());
		if (controllerJvmOptions != null && controllerJvmOptions.isArray()) {
			out.append("Controller JVM options: ").append(controllerJvmOptions).append('\n');
		}

		if (status.endpoint() != null) {
			out.append("Endpoint: ").append(status.endpoint()).append('\n');
		}

		if (status.mode != null) {
			out.append("Mode: ").append(status.mode).append('\n');
		}

		if (status.runtime != null) {
			out.append("Runtime: ").append(status.runtime).append('\n');
		}

		if (status.screen != null) {
			out.append("Screen: ").append(status.screen).append('\n');
		}

		if (status.logFile != null) {
			out.append("Log file: ").append(status.logFile).append('\n');
		}

		if (payload.at("active", false).asBoolean()) {
			Json worker = payload.at("worker", Json.object());
			out.append("Worker PID: ").append(worker.at("pid", Json.nil())).append('\n');
			out.append("Worker JVM options: ").append(worker.at("jvmOptions", Json.array())).append('\n');
			out.append("MIDlet/emulated heap: ").append(worker.at("emulatedHeap", Json.nil())).append('\n');
			out.append("Data dir: ").append(worker.at("dataDir", Json.nil())).append('\n');
			out.append("RMS dir: ").append(worker.at("rmsDir", Json.nil())).append('\n');
			out.append("File root: ").append(worker.at("fileRoot", Json.nil())).append('\n');
		}

		if (status.loadError != null) {
			out.append("Load error: ").append(status.loadError).append('\n');
		}

		return trimTrailingNewline(out);
	}

	public static String renderInspection(InspectionResult result) {
		StringBuilder out = new StringBuilder();
		out.append("Path: ").append(result.inputPath).append('\n');
		out.append("Kind: ").append(result.sourceKind).append('\n');
		out.append("Display name: ").append(result.displayName).append('\n');
		if (result.jarPath != null) {
			out.append("Jar path: ").append(result.jarPath).append('\n');
		}

		if (result.jadPath != null) {
			out.append("Descriptor path: ").append(result.jadPath).append('\n');
		}

		if (result.vendor != null) {
			out.append("Vendor: ").append(result.vendor).append('\n');
		}

		if (result.version != null) {
			out.append("Version: ").append(result.version).append('\n');
		}

		if (result.midlets.isEmpty()) {
			out.append("Midlets: none");
		} else {
			out.append("Midlets:").append('\n');
			for (MidletEntry midlet : result.midlets) {
				out.append("  ")
					.append(midlet.index)
					.append(". ")
					.append(midlet.name)
					.append(" -> ")
					.append(midlet.className)
					.append('\n');
			}

			if (result.selectedMidletClass != null) {
				out.append("Selected MIDlet class: ")
					.append(result.selectedMidletClass)
					.append('\n');
			}
		}

		return trimTrailingNewline(out);
	}

	public static String renderOpen(Json payload) {
		StringBuilder out = new StringBuilder();
		Json app = payload.at("app");
		if (app != null && app.isObject()) {
			out.append("Opened: ")
				.append(
					app.at("displayName", Json.nil()).isNull()
						? "(unknown)"
						: app.at("displayName").asString())
				.append('\n');
		}

		if (payload.has("status") && !payload.at("status").isNull()) {
			out.append("Status: ").append(payload.at("status").asString()).append('\n');
		}

		Json state = payload.at("state", Json.nil());
		out.append("Ready: ")
			.append(!state.isNull() && state.at("ready", false).asBoolean())
			.append('\n');
		Json displayable = state.isNull() ? Json.nil() : state.at("displayable", Json.nil());
		out.append("Title: ")
			.append(
				displayable.isNull() || displayable.at("title", Json.nil()).isNull()
					? ""
					: displayable.at("title").asString())
			.append('\n');

		return trimTrailingNewline(out);
	}

	public static String renderState(Json wrapper) {
		if (!wrapper.at("active", false).asBoolean()) {
			return "No active app.";
		}

		Json payload = wrapper.at("state", Json.object());
		StringBuilder out = new StringBuilder();
		Json app = wrapper.at("app");
		if (app != null && app.isObject()) {
			out.append("App: ")
				.append(
					app.at("displayName", Json.nil()).isNull()
						? "(unknown)"
						: app.at("displayName").asString())
				.append('\n');
		}

		out.append("Ready: ").append(payload.at("ready", false).asBoolean()).append('\n');
		out.append("Midlet started: ")
			.append(payload.at("midletStarted", false).asBoolean())
			.append('\n');
		Json displayable = payload.at("displayable");
		if (displayable != null && !displayable.isNull()
			&& displayable.has("title") && !displayable.at("title").isNull()) {
			out.append("Title: ").append(displayable.at("title").asString()).append('\n');
		}
		if (displayable != null && !displayable.isNull()
			&& displayable.has("kind") && !displayable.at("kind").isNull()) {
			out.append("Displayable: ")
				.append(displayable.at("kind").asString())
				.append('\n');
		}

		if (payload.has("permissionRequest") && !payload.at("permissionRequest").isNull()) {
			Json permission = payload.at("permissionRequest");
			out.append("Permission pending: id=").append(permission.at("id").asInteger());
			if (permission.has("message") && !permission.at("message").isNull()) {
				out.append(" message=").append(permission.at("message").asString());
			}

			out.append('\n');
		}

		return trimTrailingNewline(out);
	}

	public static String renderObserve(Json wrapper) {
		if (wrapper.has("active") && !wrapper.at("active", false).asBoolean()) {
			return "No active app.";
		}

		Json payload = wrapper.at("state", Json.object());
		StringBuilder out = new StringBuilder();
		out.append("Ready: ").append(payload.at("ready", false).asBoolean()).append('\n');
		out.append("Midlet started: ")
			.append(payload.at("midletStarted", false).asBoolean())
			.append('\n');
			Json displayable = payload.at("displayable", Json.nil());
			if (!displayable.isNull() && displayable.has("kind") && !displayable.at("kind").isNull()) {
				out.append("Displayable: ")
					.append(displayable.at("kind").asString())
					.append('\n');
			}

			if (!displayable.isNull() && displayable.has("title") && !displayable.at("title").isNull()) {
				out.append("Title: ").append(displayable.at("title").asString()).append('\n');
			}

			Json commands = displayable.isNull()
				? Json.array()
				: displayable.at("commands", Json.array());
			out.append("Commands: ").append(commands.asJsonList().size()).append('\n');

		for (Json command : commands.asJsonList()) {
			out.append("  [").append(command.at("id").asInteger()).append("] ");
			String text = command.at("text", Json.nil()).isNull()
				? ""
				: command.at("text").asString();
			if (text.length() == 0
				&& command.has("label")
				&& !command.at("label").isNull()) {
				text = command.at("label").asString();
			}

			if (text.length() == 0) {
				text = "(unnamed command)";
			}

			out.append(text).append('\n');
		}

		if (payload.has("permissionRequest") && !payload.at("permissionRequest").isNull()) {
			Json permission = payload.at("permissionRequest");
			out.append("Permission pending: id=").append(permission.at("id").asInteger());
			if (permission.has("message") && !permission.at("message").isNull()) {
				out.append(" message=").append(permission.at("message").asString());
			}

			out.append('\n');
		}

		return trimTrailingNewline(out);
	}

	public static String renderCommandRun(Json payload) {
		String text = payload.at("text", "").asString();
		if (text != null && text.length() > 0) {
			return "Command invoked: " + text;
		}

		return "Command invoked.";
	}

	public static String renderPermission(Json payload) {
		return payload.at("allow", false).asBoolean() ? "Permission allowed." : "Permission denied.";
	}
}
