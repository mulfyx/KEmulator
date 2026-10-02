package emulator.cli.controller;

import emulator.automation.shared.AutomationErrorCodes;
import emulator.automation.shared.OperationDeadline;
import emulator.cli.core.CliErrorCodes;
import emulator.cli.core.*;
import emulator.cli.output.CliTextRenderer;
import emulator.cli.support.KemuPaths;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.Callable;
import mjson.Json;

public final class ControllerLifecycle {
	private ControllerLifecycle() {
	}

	public static ControllerStatus ensureController(
		StartOptions options, boolean autoStart, String commandName, boolean json) throws Exception {
		return ensureController(options, autoStart, commandName, json,
			OperationDeadline.afterMillis(emulator.cli.support.CliDefaults.START_TIMEOUT_MS));
	}

	public static ControllerStatus ensureController(
		StartOptions options, boolean autoStart, String commandName, boolean json, OperationDeadline deadline)
		throws Exception {
		return ControllerProcessLauncher.ensureController(options, autoStart, commandName, json, deadline);
	}

	public static ControllerStatus requireRunningController(String commandName, boolean json) throws Exception {
		return requireRunningController(commandName, json, OperationDeadline.afterMillis(10000L));
	}

	public static ControllerStatus requireRunningController(
		String commandName, boolean json, OperationDeadline deadline) throws Exception {
		ControllerStatus status = ControllerStatusService.readControllerStatus(deadline);
		if (ControllerStatusService.isForeignPidState(status)) {
			ControllerStatusService.deleteStateFiles();
			status = ControllerStatusService.readControllerStatus(deadline);
		}

		if (status.degraded) {
			throw new KemuCliException(
				AutomationErrorCodes.CONTROLLER_UNREACHABLE,
				"Controller process exists but is unreachable. Use 'kemu stop' to terminate this session.",
				commandName,
				json);
		}

		if (!status.running) {
			throw new KemuCliException(
				CliErrorCodes.CONTROLLER_NOT_RUNNING, "Controller is not running.", commandName, json);
		}

		return status;
	}

	public static <T> T withLifecycleLock(Callable<T> action) throws Exception {
		return withLifecycleLock(OperationDeadline.afterMillis(emulator.cli.support.CliDefaults.START_TIMEOUT_MS),
			"controller", false, action);
	}

	public static <T> T withLifecycleLock(
		OperationDeadline deadline, String commandName, boolean json, Callable<T> action) throws Exception {
		Files.createDirectories(KemuPaths.automationRunDir());
		FileChannel channel = FileChannel.open(
			KemuPaths.automationControllerLock(), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
		try {
			FileLock lock = null;
			while (lock == null) {
				if (deadline.timedOut()) {
					throw new KemuCliException(AutomationErrorCodes.TIMEOUT,
						"Timed out waiting for the session lifecycle lock.", commandName, json,
						Json.object().set("reason", "lifecycle-lock-timeout").set("effectUnknown", false));
				}
				try {
					lock = channel.tryLock();
				} catch (OverlappingFileLockException ignored) {
				}
				if (lock == null) Thread.sleep(Math.min(50L, deadline.remainingMillis()));
			}
			try {
				return action.call();
			} finally {
				lock.release();
			}
		} finally {
			channel.close();
		}
	}

	public static StartOptions parseStartOptions(
		List<String> tokens,
		int startIndex,
		String commandName,
		boolean json,
		boolean requirePathAlreadyConsumed,
		boolean applyDefaults) {
		return ControllerOptionParsers.parseStartOptions(
			tokens, startIndex, commandName, json, requirePathAlreadyConsumed, applyDefaults);
	}

	public static void requireTokenCount(List<String> tokens, int count, String commandName, boolean json) {
		if (tokens.size() != count) {
			throw new KemuCliException(
				CliErrorCodes.USAGE_ERROR, CliTextRenderer.usageText(commandName), commandName, json);
		}
	}
}
