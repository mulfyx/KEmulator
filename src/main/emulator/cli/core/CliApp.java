package emulator.cli.core;

import emulator.cli.output.CliTextRenderer;
import java.util.ArrayList;
import java.util.List;

public final class CliApp {
	private final CommandRegistry registry;
	private final CliCommand helpCommand;
	public CliApp(CommandRegistry registry, CliCommand helpCommand) { this.registry = registry; this.helpCommand = helpCommand; }

	public static boolean flagRequested(String[] args, String flag) {
		for (int i = 0; i < args.length; i++) {
			if ("--".equals(args[i])) break;
			if (flag.equals(args[i])) return true;
			if ("--session".equals(args[i]) || "--timeout".equals(args[i])) i++;
		}
		return false;
	}

	private static String join(List<String> tokens) {
		StringBuilder result = new StringBuilder();
		for (String token : tokens) { if (result.length() > 0) result.append(' '); result.append(token); }
		return result.toString();
	}

	public CommandResult run(String[] args) throws Exception {
		boolean json = flagRequested(args, "--json");
		boolean verbose = flagRequested(args, "--verbose");
		String session = null;
		Long timeout = null;
		List<String> tokens = new ArrayList<String>();
		boolean literal = false;
		for (int i = 0; i < args.length; i++) {
			String token = args[i];
			if ("--".equals(token)) { literal = true; tokens.add(token); continue; }
			if (!literal && ("--json".equals(token) || "--verbose".equals(token))) continue;
			if (!literal && "--session".equals(token)) {
				if (session != null || ++i >= args.length) throw usage("Expected one --session value.", json);
				session = args[i]; continue;
			}
			if (!literal && "--timeout".equals(token)) {
				if (timeout != null || ++i >= args.length) throw usage("Expected one --timeout value.", json);
				try { timeout = Long.valueOf(args[i]); } catch (NumberFormatException invalid) { throw usage("--timeout must be milliseconds.", json); }
				if (timeout.longValue() < 0L || timeout.longValue() > 600000L) throw usage("--timeout must be between 0 and 600000 ms.", json);
				continue;
			}
			tokens.add(token);
		}
		if (session == null) session = System.getProperty("kemu.session.id");
		if (session == null) session = System.getenv("KEMU_SESSION");
		if (session != null && session.length() > 0) {
			if (!session.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) throw usage("Invalid --session; use 1-64 letters, digits, dot, underscore or dash.", json);
			System.setProperty("kemu.session.id", session);
		}
		CliInvocation invocation = new CliInvocation(tokens, json, verbose, timeout);
		if (tokens.isEmpty() || "help".equals(tokens.get(0)) || "--help".equals(tokens.get(0)) || "-h".equals(tokens.get(0)))
			return helpCommand.run(invocation).presentation(json, verbose);
		if (!tokens.contains("--")) {
			String last = tokens.get(tokens.size() - 1);
			List<String> topic = new ArrayList<String>(tokens.subList(0, tokens.size() - 1));
			boolean explicit = "--help".equals(last) || "-h".equals(last);
			boolean group = registry.resolve(topic) == null && CliTextRenderer.hasUsageTopic(join(topic));
			if ((explicit && (group || registry.resolveExact(topic) != null)) || "help".equals(last) && group)
				return helpCommand.run(new CliInvocation(topic, json, verbose, timeout)).presentation(json, verbose);
		}
		CliCommand command = registry.resolve(tokens);
		if (command == null) {
			String name = tokens.get(0);
			if (CliTextRenderer.hasUsageTopic(name)) throw new KemuCliException(CliErrorCodes.USAGE_ERROR, CliTextRenderer.usageText(name), name, json);
			throw new KemuCliException(CliErrorCodes.UNKNOWN_COMMAND, "Unknown command: " + name, name, json);
		}
		return command.run(invocation).presentation(json, verbose);
	}

	private static KemuCliException usage(String message, boolean json) { return new KemuCliException(CliErrorCodes.USAGE_ERROR, message, null, json); }
}
