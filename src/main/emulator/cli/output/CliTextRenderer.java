package emulator.cli.output;

import java.util.LinkedHashMap;
import java.util.Map;
import mjson.Json;

/** Human rendering of the same public envelope emitted by --json. */
public final class CliTextRenderer {
	private static final Map<String, String> USAGE = new LinkedHashMap<String, String>();
	static {
		USAGE.put("help", "help [COMMAND...]");
		USAGE.put("open", "open APP [--midlet N] [--headless|--visible] [--size WxH] [--data-dir DIR] [--rms-dir DIR] [--file-root DIR] [--reset-state] [--reset-file-root] [--worker-xmx SIZE]");
		USAGE.put("inspect", "inspect APP");
		USAGE.put("observe", "observe [--screenshot FILE]");
		USAGE.put("screenshot", "screenshot [FILE]");
		for (String name : new String[]{"status", "close", "stop", "pause", "resume", "rotate", "bridge"}) USAGE.put(name, name);
		USAGE.put("activate", "activate REF");
		USAGE.put("select", "select REF [--off]");
		USAGE.put("set", "set REF VALUE");
		USAGE.put("resize", "resize WIDTHxHEIGHT");
		USAGE.put("key", "key <press|hold|down|up> KEY [--duration MS] [--observe]");
		for (String action : new String[]{"press", "hold", "down", "up"}) USAGE.put("key " + action, "key " + action + " KEY [--duration MS] [--observe]");
		USAGE.put("pointer", "pointer <tap|down|up> X Y [--observe]");
		for (String action : new String[]{"tap", "down", "up"}) USAGE.put("pointer " + action, "pointer " + action + " X Y [--observe]");
		USAGE.put("drag", "drag X1 Y1 X2 Y2 ... [--delay MS] [--observe]");
		USAGE.put("wait", "wait <screen|ready|exit|frame|permission|log> [OPTIONS]");
		USAGE.put("wait screen", "wait screen [--kind KIND] [--title TITLE] [--title-regex REGEX] [--text TEXT]");
		USAGE.put("wait ready", "wait ready"); USAGE.put("wait exit", "wait exit");
		USAGE.put("wait frame", "wait frame [--after FRAME_ID]");
		USAGE.put("wait permission", "wait permission [--name NAME]");
		USAGE.put("wait log", "wait log --regex REGEX [--since CURSOR]");
		USAGE.put("permission", "permission <allow|deny> REF [--remember]");
		USAGE.put("permission allow", "permission allow REF [--remember]");
		USAGE.put("permission deny", "permission deny REF");
		USAGE.put("logs", "logs [--since CURSOR] [--jsonl]"); USAGE.put("logs cursor", "logs cursor");
		USAGE.put("storage", "storage <snapshot|restore> FILE\n  kemu storage rms <reset|export|import> [FILE]");
		USAGE.put("storage snapshot", "storage snapshot FILE"); USAGE.put("storage restore", "storage restore FILE");
		USAGE.put("storage rms", "storage rms <reset|export|import> [FILE]");
		for (String action : new String[]{"reset", "export", "import"}) USAGE.put("storage rms " + action, "storage rms " + action + ("reset".equals(action) ? "" : " FILE"));
	}
	private CliTextRenderer() { }

	public static boolean hasUsageTopic(String topic) { return USAGE.containsKey(topic); }

	public static String usageText() {
		return "KEmulator agent CLI (Linux)\n"
			+ "Workflow: open APP -> observe -> activate/select/set REF -> wait/observe -> close -> stop\n\n"
			+ "  kemu open APP\n  kemu inspect APP\n  kemu observe\n  kemu activate REF\n"
			+ "  kemu select REF\n  kemu set REF VALUE\n  kemu key <press|hold|down|up> KEY\n"
			+ "  kemu pointer <tap|down|up> X Y\n  kemu drag X1 Y1 X2 Y2 ...\n"
			+ "  kemu wait <screen|ready|exit|frame|permission|log>\n  kemu permission <allow|deny> REF\n"
			+ "  kemu status\n  kemu close\n  kemu stop\n  kemu pause\n  kemu resume\n"
			+ "  kemu resize WxH\n  kemu rotate\n  kemu screenshot [FILE]\n  kemu logs [OPTIONS]\n"
			+ "  kemu storage <snapshot|restore> FILE\n  kemu storage rms <reset|export|import> [FILE]\n  kemu bridge\n  kemu help [COMMAND...]\n"
			+ "\nGlobal: --session NAME (or KEMU_SESSION), --json, --verbose, --timeout MS\n"
			+ "Use kemu help COMMAND for options. Default output is a complete useful UI, not runtime diagnostics.";
	}

	public static String usageText(String topic) {
		String usage = USAGE.get(topic);
		if (usage == null) return usageText();
		String result = "Usage: kemu " + usage + "\nGlobal: --session NAME, --json, --verbose, --timeout MS";
		if (topic.startsWith("key")) result += "\nKeys: 0-9, *, #, UP, DOWN, LEFT, RIGHT, FIRE, LSK, RSK.";
		return result;
	}

	private static String text(Json value) { return value == null || value.isNull() ? "" : value.isString() ? value.asString() : value.toString(); }
	private static String quoted(Json value) { return Json.make(text(value)).toString(); }
	private static void line(StringBuilder out, String value) { if (value.length() > 0) out.append(value).append('\n'); }

	private static void nodes(StringBuilder out, Json nodes, String indent) {
		if (!nodes.isArray()) return;
		for (Json node : nodes.asJsonList()) {
			StringBuilder row = new StringBuilder(indent);
			row.append(node.at("selected", false).asBoolean() ? "> " : "  ");
			String ref = text(node.at("ref", Json.nil()));
			if (ref.length() > 0) row.append(ref).append(' ');
			row.append(text(node.at("role", "text")));
			if (node.has("label")) row.append(' ').append(quoted(node.at("label")));
			if (node.has("value")) row.append(": ").append(quoted(node.at("value")));
			if (node.has("actions")) row.append(" [").append(join(node.at("actions"))).append(']');
			if (node.has("constraints")) row.append(" ").append(join(node.at("constraints")));
			if (node.has("maxLength")) row.append(" max ").append(node.at("maxLength"));
			if (node.has("max")) row.append(" range ").append(node.at("min", 0)).append("..").append(node.at("max"));
			if (node.has("softkey")) row.append(" softkey=").append(text(node.at("softkey")));
			for (String key : new String[]{"owner", "type", "selection", "caret", "inputMode", "mode", "state", "canDeselect", "capabilities", "size", "image"})
				if (node.has(key)) row.append(' ').append(key).append('=').append(text(node.at(key)));
			if (node.at("focused", false).asBoolean()) row.append(" focused");
			line(out, row.toString());
			if (node.has("nodes")) nodes(out, node.at("nodes"), indent + "  ");
		}
	}

	private static String join(Json values) {
		if (!values.isArray()) return text(values);
		StringBuilder out = new StringBuilder();
		for (Json value : values.asJsonList()) { if (out.length() > 0) out.append(", "); out.append(text(value)); }
		return out.toString();
	}

	private static void permission(StringBuilder out, Json permission) {
		if (!permission.isObject()) return;
		String ref = text(permission.at("ref", permission.at("id", Json.nil())));
		line(out, "Permission pending " + ref + (permission.has("id") ? " (request " + text(permission.at("id")) + ")" : "")
			+ ": " + text(permission.at("name", "")));
		line(out, text(permission.at("message", "")));
		line(out, "Answer: kemu permission allow " + ref + " | kemu permission deny " + ref);
		line(out, "The suspended action continues after the answer; do not repeat it.");
	}

	private static void view(StringBuilder out, Json view) {
		if (!view.isObject()) return;
		String kind = text(view.at("kind", "none"));
		line(out, kind + " " + quoted(view.at("title", "")));
		if (view.has("size")) line(out, "Screen: " + view.at("size").at("width", 0) + "x" + view.at("size").at("height", 0));
		if (view.has("contentSize")) line(out, "Content: " + view.at("contentSize").at("width", 0) + "x" + view.at("contentSize").at("height", 0));
		if (view.has("ticker")) line(out, "Ticker: " + text(view.at("ticker")));
		if (view.has("selection")) line(out, "Selection: " + text(view.at("selection")));
		if (view.has("timeout")) line(out, "Alert timeout: " + text(view.at("timeout")) + " ms");
		nodes(out, view.at("nodes", Json.array()), "");
		if (view.has("indicator")) { line(out, "Indicator:"); nodes(out, Json.array(view.at("indicator")), ""); }
		if (!view.at("commands", Json.array()).asJsonList().isEmpty()) { line(out, "Commands:"); nodes(out, view.at("commands"), ""); }
		Json image = view.at("image", Json.nil());
		if (image.isObject()) line(out, "PNG: " + text(image.at("path")) + " (" + image.at("width") + "x" + image.at("height") + ", frame " + text(image.at("frameId")) + ")");
	}

	public static String render(Json envelope) {
		StringBuilder out = new StringBuilder();
		if ("error".equals(text(envelope.at("outcome")))) {
			Json error = envelope.at("error");
			line(out, "Error " + text(error.at("code")) + ": " + text(error.at("message")));
			Json details = error.at("details", Json.object());
			if (details.at("effectUnknown", false).asBoolean()) line(out, "The action was sent; its effect is unknown. Observe before retrying.");
			if (details.has("observation")) view(out, details.at("observation"));
			Json facts = details.dup(); facts.delAt("observation");
			if (!facts.asJsonMap().isEmpty()) line(out, facts.toString());
		} else {
			Json result = envelope.at("result", Json.object());
			permission(out, result.at("permission", Json.nil()));
			String command = text(envelope.at("command"));
			if (result.has("usage")) line(out, text(result.at("usage")));
			else if ("inspect".equals(command)) {
				line(out, "App: " + text(result.at("name"))); line(out, "Path: " + text(result.at("path")));
				line(out, "Source: " + text(result.at("sourceKind")));
				line(out, "Vendor: " + text(result.at("vendor"))); line(out, "Version: " + text(result.at("version")));
				for (Json midlet : result.at("midlets", Json.array()).asJsonList()) line(out, "  " + midlet.at("index") + ". " + text(midlet.at("name")));
			} else {
				Json session = result.at("session", Json.nil());
				if (session.isObject()) line(out, "Session " + text(session.at("id")) + ": " + text(session.at("status", "")));
				Json app = result.at("app", Json.nil());
				if (app.isObject()) line(out, "App: " + text(app.at("name", "")) + " (" + text(app.at("status", "none")) + ")");
				if (result.has("failure")) line(out, "Worker failure: " + result.at("failure"));
				if (result.has("reason")) line(out, "Reason: " + text(result.at("reason")));
				if (result.has("action")) line(out, ("pending".equals(text(envelope.at("outcome"))) ? "Pending: " : "Applied: ") + result.at("action"));
				if (result.has("matched")) line(out, "Matched: " + text(result.at("condition")));
				if (result.has("exitCode")) line(out, "Exit code: " + text(result.at("exitCode")));
				if (result.has("elapsedMs")) line(out, "Elapsed: " + text(result.at("elapsedMs")) + " ms");
				if (result.has("text")) line(out, text(result.at("text")));
				for (Json log : result.at("lines", Json.array()).asJsonList()) line(out, text(log.at("line", log.at("text", ""))));
				if (result.has("cursor")) line(out, "Cursor: " + text(result.at("cursor")));
				if (result.has("archive")) line(out, text(result.at("action", "Storage")) + ": " + text(result.at("archive")));
				view(out, result.at("observation", Json.nil()));
			}
		}
		if (envelope.has("diagnostics")) line(out, "Diagnostics: " + envelope.at("diagnostics"));
		if (out.length() > 0) out.setLength(out.length() - 1);
		return out.toString();
	}
}
