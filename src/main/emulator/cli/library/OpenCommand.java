package emulator.cli.library;

import emulator.automation.shared.AutomationErrorCodes;
import emulator.automation.shared.AutomationLimits;
import emulator.automation.shared.OperationDeadline;
import emulator.cli.app.AgentCalls;
import emulator.cli.controller.*;
import emulator.cli.core.*;
import emulator.cli.support.KemuPaths;
import emulator.cli.support.SessionStoragePaths;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import mjson.Json;

public final class OpenCommand implements CliCommand {
	public CommandPath path() { return CommandPath.of("open"); }

	public CommandResult run(final CliInvocation invocation) throws Exception {
		final OpenOptions options = OpenOptionParsers.parse(invocation.tokens(), invocation.json());
		final OperationDeadline deadline = invocation.deadline(AutomationLimits.DEFAULT_OPEN_TIMEOUT_MS);
		// Inspection cannot create a session or reset any persistent data.
		InspectionResult inspection = CliAppInspector.inspect(options.inputPath, "open", invocation.json());
		CliAppInspector.validateOpenTarget(inspection, options.midletIndex, "open", invocation.json());
		return ControllerLifecycle.withLifecycleLock(deadline, "open", invocation.json(), new Callable<CommandResult>() {
			public CommandResult call() throws Exception {
				ControllerStatus status = ControllerLifecycle.ensureController(
					options.startOptions, true, "open", invocation.json(), deadline);
				ControllerClient client = ControllerStatusService.controllerClient(status);
				Path dataDir = options.dataDir == null ? KemuPaths.dataDir() : options.dataDir;
				SessionStoragePaths storage = SessionStoragePaths.of(dataDir,
					options.rmsDir == null ? dataDir.resolve("rms") : options.rmsDir,
					options.fileRoot == null ? dataDir.resolve("files") : options.fileRoot);
				Json args = Json.object().set("path", options.inputPath.toString())
					.set("dataDir", storage.dataDir.toString()).set("rmsDir", storage.rmsDir.toString())
					.set("fileRoot", storage.fileRoot.toString()).set("dataDirExplicit", options.dataDir != null)
					.set("rmsDirExplicit", options.rmsDir != null).set("fileRootExplicit", options.fileRoot != null)
					.set("resetState", options.resetState).set("resetFileRoot", options.resetFileRoot)
					.set("waitReady", true).set("sessionId", KemuPaths.sessionId());
				if (options.midletIndex != null) args.set("midlet", options.midletIndex.intValue());
				if (options.workerXmx != null) args.set("workerXmx", options.workerXmx);
				if (options.startOptions.width != null) {
					args.set("screenWidth", options.startOptions.width.intValue());
					args.set("screenHeight", options.startOptions.height.intValue());
				}
				Json result;
				try {
					result = AgentCalls.call(invocation, client, deadline, "app.open-path", args, "open");
				} catch (KemuCliException failure) {
					// The retained worker has already prepared these roots. A rejected
					// launch must preserve the prior storage configuration.
					if (AutomationErrorCodes.OPEN_TIMEOUT.equals(failure.code) && failure.payload != null
						&& failure.payload.at("workerRetained", false).asBoolean()) storage.write();
					throw failure;
				}
				storage.write();
				result.set("inputPath", options.inputPath.toString());
				Json snapshot = AgentCalls.observe(invocation, client, deadline, "open", null, false);
				result.set("state", snapshot).set("current", snapshot.at("current"));
				if (snapshot.has("permissionRequest") && snapshot.at("permissionRequest").isObject()) {
					result.set("pending", true).set("status", "pending-permission");
				}
				return new CommandResult("open", result, invocation.json());
			}
		});
	}
}
