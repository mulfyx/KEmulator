package emulator.cli;

import emulator.cli.core.CliErrorCodes;
import emulator.cli.app.*;
import emulator.cli.controller.*;
import emulator.cli.core.*;
import emulator.cli.library.*;
import emulator.cli.output.PublicResult;
import emulator.cli.output.CliTextRenderer;
import mjson.Json;

public final class KemuMain {
	private static final CliApp CLI_APP = createCliApp();

	private KemuMain() {
	}

	public static void main(String[] args) {
		boolean json = CliApp.flagRequested(args, "--json");
		boolean verbose = CliApp.flagRequested(args, "--verbose");
		try {
			CommandResult result = CLI_APP.run(args);
			if (result.streamed) { System.exit(CliExitCodes.OK); return; }
			Json envelope = PublicResult.envelope(result);
		if (result.json) writeJson(envelope);
		else if ("logs".equals(result.commandName) && result.payload.at("jsonl", false).asBoolean()) {
			for (Json line : envelope.at("result").at("lines", Json.array()).asJsonList()) writeJson(line);
		} else System.out.println(CliTextRenderer.render(envelope));
			System.exit("pending".equals(envelope.at("outcome").asString()) ? 5 : CliExitCodes.OK);
		} catch (KemuCliException e) {
			Json envelope = PublicResult.error(e.commandName, e.code, e.getMessage(), e.payload, verbose);
			if (json || e.json) writeJson(envelope); else System.err.println(CliTextRenderer.render(envelope));

			System.exit(e.exitCode);
		} catch (Exception e) {
			Json envelope = PublicResult.error(null, CliErrorCodes.INTERNAL_ERROR, e.toString(), null, verbose);
			if (json) writeJson(envelope); else System.err.println(CliTextRenderer.render(envelope));

			System.exit(CliExitCodes.RUNTIME);
		}
	}

	private static CliApp createCliApp() {
		CommandRegistry registry = new CommandRegistry();
		HelpCommand helpCommand = new HelpCommand(registry);
		BridgeCommand bridgeCommand = new BridgeCommand();
		registry.add(helpCommand);
		registry.add(bridgeCommand);
		registry.add(new StatusCommand());
		registry.add(new StopCommand());
		registry.add(new LogsCursorCommand());
		registry.add(new LogsReadCommand());
		registry.add(new InspectCommand());
		registry.add(new OpenCommand());
		registry.add(new CloseCommand());
		registry.add(new AgentObserveCommand());
		registry.add(new AgentRefCommand("activate"));
		registry.add(new AgentRefCommand("select"));
		registry.add(new AgentRefCommand("set"));
		registry.add(new ScreenshotCommand());
		for (String type : new String[]{"screen", "ready", "exit", "frame", "permission", "log"})
			registry.add(new AgentWaitCommand(type));
		registry.add(new KeyActionCommand("press"));
		registry.add(new KeyActionCommand("hold"));
		registry.add(new KeyActionCommand("down"));
		registry.add(new KeyActionCommand("up"));
		registry.add(new PointerActionCommand("tap"));
		registry.add(new PointerActionCommand("down"));
		registry.add(new PointerActionCommand("up"));
		registry.add(new DragCommand());
		registry.add(new AppLifecycleCommand("pause"));
		registry.add(new AppLifecycleCommand("resume"));
		registry.add(new ScreenSizeCommand(false));
		registry.add(new ScreenSizeCommand(true));
		registry.add(new PermissionCommand());
		registry.add(new SessionStorageCommand("rms", "reset"));
		registry.add(new SessionStorageCommand("rms", "export"));
		registry.add(new SessionStorageCommand("rms", "import"));
		registry.add(new SessionStorageCommand("storage", "snapshot"));
		registry.add(new SessionStorageCommand("storage", "restore"));

		CliApp app = new CliApp(registry, helpCommand);
		bridgeCommand.attach(app);

		return app;
	}

	private static void writeJson(Json json) {
		System.out.println(json.toString());
	}

}
