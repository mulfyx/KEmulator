package emulator.cli.app;

import emulator.automation.shared.AutomationErrorCodes;
import emulator.automation.shared.OperationDeadline;
import emulator.cli.core.CliErrorCodes;
import emulator.cli.controller.ControllerStatus;
import emulator.cli.controller.ControllerStatusService;
import emulator.cli.controller.ControllerLifecycle;
import emulator.cli.core.CliCommand;
import emulator.cli.core.CliInvocation;
import emulator.cli.core.CommandPath;
import emulator.cli.core.CommandResult;
import emulator.cli.core.KemuCliException;
import emulator.cli.support.KemuPaths;
import emulator.cli.support.SessionStorageArchives;
import emulator.cli.support.SessionStoragePaths;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import mjson.Json;

public final class SessionStorageCommand implements CliCommand {
	private final String scope;
	private final String action;

	public SessionStorageCommand(String scope, String action) {
		this.scope = scope;
		this.action = action;
	}

	public CommandPath path() {
		return "rms".equals(scope) ? CommandPath.of("storage", "rms", action) : CommandPath.of("storage", action);
	}

	private String commandName() {
		return "storage " + ("rms".equals(scope) ? "rms " : "") + action;
	}

	private void requireInactive(CliInvocation invocation, OperationDeadline deadline) throws Exception {
		ControllerStatus status = ControllerStatusService.readControllerStatus(deadline);
		if (!status.running) {
			if (status.degraded || Boolean.TRUE.equals(status.pidAlive) && !ControllerStatusService.isForeignPidState(status)) {
				throw new KemuCliException(AutomationErrorCodes.CONTROLLER_UNREACHABLE,
					"Cannot verify that storage is inactive. Stop the unreachable session first.",
					commandName(), invocation.json(), status.toJson());
			}
			return;
		}
		Json current = AgentCalls.call(invocation, ControllerStatusService.controllerClient(status), deadline,
			"app.current", Json.object(), commandName());
		if (current.at("active", false).asBoolean()) {
			throw new KemuCliException(
				CliErrorCodes.APP_ACTIVE,
				"Close the active app before changing or archiving session storage.",
				commandName(),
				invocation.json(),
				current);
		}
	}

	private KemuCliException storageFailure(CliInvocation invocation, IOException error) {
		return new KemuCliException(
			CliErrorCodes.STORAGE_ERROR,
			error.getMessage(),
			commandName(),
			invocation.json());
	}

	public CommandResult run(final CliInvocation invocation) throws Exception {
		int prefixSize = "rms".equals(scope) ? 3 : 2;
		int archiveIndex = invocation.tokens().size() > prefixSize
			&& "--".equals(invocation.tokens().get(prefixSize)) ? prefixSize + 1 : prefixSize;
		int expectedTokens = "reset".equals(action) ? prefixSize : archiveIndex + 1;
		if (invocation.tokens().size() != expectedTokens || !"reset".equals(action)
			&& archiveIndex == prefixSize && invocation.tokens().get(archiveIndex).startsWith("--")) {
			throw new KemuCliException(
				CliErrorCodes.USAGE_ERROR,
				emulator.cli.output.CliTextRenderer.usageText(commandName()),
				commandName(),
				invocation.json());
		}
		final Path archive = "reset".equals(action) ? null
			: emulator.cli.parse.CliParsing.resolveUserPath(invocation.tokens().get(archiveIndex));
		final OperationDeadline deadline = invocation.deadline(10000L);
		return ControllerLifecycle.withLifecycleLock(deadline, commandName(), invocation.json(),
			new java.util.concurrent.Callable<CommandResult>() {
			public CommandResult call() throws Exception {
				return changeStorage(invocation, deadline, archive);
			}
		});
	}

	private CommandResult changeStorage(CliInvocation invocation, OperationDeadline deadline, Path archive) throws Exception {
		requireInactive(invocation, deadline);
		try {
			SessionStoragePaths paths = SessionStoragePaths.read();
			Map<String, Path> roots = "rms".equals(scope)
				? SessionStorageArchives.rmsRoots(paths) : SessionStorageArchives.stateRoots(paths);
			if ("reset".equals(action)) {
				SessionStorageArchives.reset(paths.rmsDir);
			} else if ("export".equals(action) || "snapshot".equals(action)) {
				SessionStorageArchives.exportArchive(archive, roots);
			} else {
				SessionStorageArchives.importArchive(archive, roots);
			}
			Json payload = Json.object().set("sessionId", KemuPaths.sessionId())
				.set("scope", "rms".equals(scope) ? "rms" : "state").set("action", action)
				.set("dataDir", paths.dataDir.toString()).set("rmsDir", paths.rmsDir.toString())
				.set("fileRoot", paths.fileRoot.toString());
			if (archive != null) payload.set("archive", archive.toString());
			return new CommandResult(commandName(), payload, invocation.json());
		} catch (IOException error) {
			throw storageFailure(invocation, error);
		}
	}
}
