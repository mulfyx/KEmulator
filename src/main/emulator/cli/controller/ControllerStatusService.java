package emulator.cli.controller;

import emulator.automation.shared.AutomationErrorCodes;
import emulator.automation.shared.OperationDeadline;
import emulator.automation.shared.ProcessIdentity;
import emulator.cli.core.CliErrorCodes;
import emulator.cli.core.KemuCliException;
import emulator.cli.support.KemuPaths;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.DirectoryStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import mjson.Json;

public final class ControllerStatusService {
	private ControllerStatusService() {
	}

	public static final class LogTail {
		public final boolean exists;
		public final String tail;

		LogTail(boolean exists, String tail) {
			this.exists = exists;
			this.tail = tail;
		}
	}

	public static ControllerClient controllerClient(ControllerStatus status) {
		return new ControllerClient(status.host, status.port == null ? -1 : status.port.intValue());
	}

	public static String controllerLogPath(ControllerStatus status) {
		if (status != null && status.logFile != null) {
			return status.logFile;
		}

		return KemuPaths.automationControllerLog().toAbsolutePath().normalize().toString();
	}

	public static void deleteStateFiles() throws IOException {
		Files.deleteIfExists(KemuPaths.automationControllerState());
	}

	private static String trimTrailingLineBreaks(String value) {
		int start = 0;
		int end = value.length();
		while (start < end && (value.charAt(start) == '\n' || value.charAt(start) == '\r')) {
			start++;
		}

		while (end > start && (value.charAt(end - 1) == '\n' || value.charAt(end - 1) == '\r')) {
			end--;
		}

		return value.substring(start, end);
	}

	public static LogTail readLogTail(Path path, int maxLines) throws IOException {
		if (path == null || !Files.isRegularFile(path)) {
			return new LogTail(false, "");
		}

		if (maxLines <= 0) {
			return new LogTail(true, "");
		}

		long fileSize = Files.size(path);
		if (fileSize <= 0L) {
			return new LogTail(true, "");
		}

		RandomAccessFile reader = new RandomAccessFile(path.toFile(), "r");
		try {
			long start = 0L;
			long position = fileSize - 1L;
			int seenLines = 0;
			while (position >= 0L) {
				reader.seek(position);
				if (reader.read() == '\n' && position < fileSize - 1L) {
					seenLines++;
					if (seenLines >= maxLines) {
						start = position + 1L;
						break;
					}
				}

				position--;
			}

			long length = fileSize - start;
			if (length > Integer.MAX_VALUE) {
				start = fileSize - Integer.MAX_VALUE;
				length = Integer.MAX_VALUE;
			}

			byte[] bytes = new byte[(int) length];
			reader.seek(start);
			reader.readFully(bytes);

			return new LogTail(true, trimTrailingLineBreaks(new String(bytes, StandardCharsets.UTF_8)));
		} finally {
			reader.close();
		}
	}

	public static String readLastLines(Path path, int maxLines) throws IOException {
		return readLogTail(path, maxLines).tail;
	}

	public static boolean isLinux() {
		return System.getProperty("os.name", "")
			.toLowerCase(java.util.Locale.US)
			.contains("linux");
	}

	public static boolean isWindows() {
		return System.getProperty("os.name", "")
			.toLowerCase(java.util.Locale.US)
			.contains("win");
	}

	public static boolean isPidAlive(String pid) {
		return isLinux() ? readProcess(pid) != null : Boolean.TRUE.equals(ProcessIdentity.isAlive(pid));
	}

	public static boolean isForeignPidState(ControllerStatus status) {
		return status != null
			&& Boolean.TRUE.equals(status.pidAlive)
			&& Boolean.FALSE.equals(status.pidIdentityMatches);
	}

	private static boolean probeController(ControllerStatus status, OperationDeadline deadline) {
		if (status == null || status.host == null || status.port == null || deadline.timedOut()
			|| !Boolean.TRUE.equals(status.pidIdentityMatches)) {
			return false;
		}

		try {
			controllerClient(status).callTool("health",
				Json.object().set("timeoutMs", Math.min(300L, deadline.remainingMillis())));
			return true;
		} catch (Exception ignored) {
			return false;
		}
	}

	public static ControllerStatus readControllerStatus() throws Exception {
		return readControllerStatus(OperationDeadline.afterMillis(10000L));
	}

	public static ControllerStatus readControllerStatus(OperationDeadline deadline) throws Exception {
		Path automation = KemuPaths.automationControllerState();
		if (!Files.exists(automation)) {
			return ControllerStatus.empty();
		}

		ControllerStatus status;
		try {
			status = ControllerStatus.fromStateFile("automation", automation);
		} catch (IOException e) {
			status = ControllerStatus.corrupt("automation", automation, e.getMessage());
		}

		if (isLinux()) {
			ProcessSnapshot process = readProcess(status.pid);
			status.pidAlive = Boolean.valueOf(process != null);
			status.pidIdentityMatches = process == null ? Boolean.FALSE
				: status.pidStartTicks == null ? null : Boolean.valueOf(status.pidStartTicks.equals(process.startTicks)
					&& controllerArguments(process.arguments)
					&& KemuPaths.sessionId().equals(status.sessionId));
		} else {
			status.pidAlive = ProcessIdentity.isAlive(status.pid);
			status.pidIdentityMatches = ProcessIdentity.matches(status.pid, status.pidStartTimeMs, status.pidStartTicks);
		}
		status.reachable = probeController(status, deadline);
		status.running = status.reachable;
		status.degraded = !status.reachable && Boolean.TRUE.equals(status.pidAlive) && !isForeignPidState(status);

		return status;
	}

	public static boolean waitForControllerStop(ControllerStatus status, long timeoutMs) throws Exception {
		OperationDeadline deadline = OperationDeadline.afterMillis(timeoutMs);
		ProcessSnapshot process = status == null ? null : readProcess(status.pid);
		return process == null || waitForProcesses(Arrays.asList(process), deadline);
	}

	public static void cleanupUnreachableController(ControllerStatus status, String commandName, boolean json)
		throws Exception {
		if (status == null || !status.exists) return;
		if (Boolean.TRUE.equals(status.pidAlive) && !isForeignPidState(status)) {
			throw new KemuCliException(AutomationErrorCodes.CONTROLLER_UNREACHABLE,
				"Controller is unreachable. Use 'kemu stop' to terminate this session.", commandName, json);
		}
		deleteStateFiles();
	}

	private static final class ProcessSnapshot {
		final String pid;
		final String startTicks;
		final String parentPid;
		final List<String> arguments;

		ProcessSnapshot(String pid, String startTicks, String parentPid, List<String> arguments) {
			this.pid = pid;
			this.startTicks = startTicks;
			this.parentPid = parentPid;
			this.arguments = arguments;
		}
	}

	private static ProcessSnapshot readProcess(String pid) {
		if (pid == null || !pid.matches("[1-9][0-9]*")) return null;
		try {
			Path processDir = Paths.get("/proc", pid);
			String stat = new String(Files.readAllBytes(processDir.resolve("stat")), StandardCharsets.UTF_8);
			int start = stat.lastIndexOf(") ");
			if (start < 0) return null;
			String[] fields = stat.substring(start + 2).trim().split("\\s+");
			if (fields.length <= 19 || "Z".equals(fields[0]) || "X".equals(fields[0])) return null;
			String command = new String(Files.readAllBytes(processDir.resolve("cmdline")), StandardCharsets.UTF_8);
			return new ProcessSnapshot(pid, fields[19], fields[1], Arrays.asList(command.split("\\x00")));
		} catch (IOException ignored) {
			return null;
		}
	}

	private static boolean argumentPair(List<String> arguments, String key, String value) {
		for (int i = 0; i + 1 < arguments.size(); i++) {
			if (key.equals(arguments.get(i)) && value.equals(arguments.get(i + 1))) return true;
		}
		return false;
	}

	private static boolean controllerArguments(List<String> arguments) {
		return arguments.contains("emulator.automation.controller.AutomationControllerMain")
			&& arguments.contains("-Dkemu.session.id=" + KemuPaths.sessionId())
			&& argumentPair(arguments, "--state-file", KemuPaths.automationControllerState().toString());
	}

	private static boolean workerArguments(ProcessSnapshot worker, String controllerPid, String controllerTicks) {
		return controllerPid != null && controllerTicks != null
			&& sessionWorkerArguments(worker.arguments)
			&& argumentPair(worker.arguments, "-automationcontrollerpid", controllerPid)
			&& argumentPair(worker.arguments, "-automationcontrollerstartticks", controllerTicks);
	}

	private static boolean sessionWorkerArguments(List<String> arguments) {
		return arguments.contains("emulator.automation.worker.AutomationWorkerMain")
			&& arguments.contains("-Dkemu.session.id=" + KemuPaths.sessionId())
			&& workerLogBelongsToSession(arguments);
	}

	private static boolean workerLogBelongsToSession(List<String> arguments) {
		for (int i = 0; i + 1 < arguments.size(); i++) {
			if ("--log-file".equals(arguments.get(i))) {
				return Paths.get(arguments.get(i + 1)).toAbsolutePath().normalize()
					.startsWith(KemuPaths.automationLogsDir().toAbsolutePath().normalize());
			}
		}
		return false;
	}

	private static boolean xvfbWrapper(ProcessSnapshot process) {
		for (String argument : process.arguments) {
			if ("xvfb-run".equals(argument) || argument.endsWith("/xvfb-run")) return true;
		}
		return false;
	}

	private static Map<String, String> processEnvironment(ProcessSnapshot process) {
		Map<String, String> result = new LinkedHashMap<String, String>();
		try {
			String environment = new String(Files.readAllBytes(Paths.get("/proc", process.pid, "environ")),
				StandardCharsets.UTF_8);
			for (String entry : environment.split("\\x00")) {
				if (entry.startsWith("DISPLAY=") || entry.startsWith("XAUTHORITY=")) {
					int equals = entry.indexOf('=');
					result.put(entry.substring(0, equals), entry.substring(equals + 1));
				}
			}
		} catch (IOException ignored) {
		}
		return result;
	}

	private static boolean privateDisplay(ProcessSnapshot display, Map<String, String> environment) {
		if (display.arguments.isEmpty()) return false;
		String authority = environment.get("XAUTHORITY");
		String name = environment.get("DISPLAY");
		return authority != null && authority.contains("/xvfb-run.") && name != null
			&& ("Xvfb".equals(display.arguments.get(0)) || display.arguments.get(0).endsWith("/Xvfb"))
			&& display.arguments.contains(name) && argumentPair(display.arguments, "-auth", authority);
	}

	private static List<ProcessSnapshot> sessionProcesses(
		ControllerStatus status, OperationDeadline deadline, String commandName, boolean json) throws Exception {
		List<ProcessSnapshot> all = new ArrayList<ProcessSnapshot>();
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(Paths.get("/proc"))) {
			for (Path entry : entries) {
				requireBudget(deadline, commandName, json, "process-discovery-timeout");
				ProcessSnapshot process = readProcess(entry.getFileName().toString());
				if (process != null) all.add(process);
			}
		}
		List<ProcessSnapshot> controllers = new ArrayList<ProcessSnapshot>();
		List<ProcessSnapshot> wrappers = new ArrayList<ProcessSnapshot>();
		for (ProcessSnapshot process : all) {
			if (!controllerArguments(process.arguments)) continue;
			if (xvfbWrapper(process)) wrappers.add(process);
			else controllers.add(process);
		}
		Map<String, ProcessSnapshot> owned = new LinkedHashMap<String, ProcessSnapshot>();
		for (ProcessSnapshot process : all) {
			// The exact session log subtree also identifies an orphan whose
			// controller has already removed its state file.
			boolean worker = controllers.isEmpty() && (status == null || !status.exists)
				&& sessionWorkerArguments(process.arguments)
				|| status != null && workerArguments(process, status.pid, status.pidStartTicks);
			for (ProcessSnapshot controller : controllers) {
				worker |= workerArguments(process, controller.pid, controller.startTicks);
			}
			if (worker) owned.put(process.pid, process);
		}
		for (ProcessSnapshot process : controllers) owned.put(process.pid, process);
		List<Map<String, String>> environments = new ArrayList<Map<String, String>>();
		for (ProcessSnapshot process : owned.values()) environments.add(processEnvironment(process));
		for (ProcessSnapshot process : all) {
			for (Map<String, String> environment : environments) {
				if (privateDisplay(process, environment)) owned.put(process.pid, process);
			}
			for (ProcessSnapshot wrapper : wrappers) {
				if (wrapper.pid.equals(process.parentPid) && !process.arguments.isEmpty()
					&& process.arguments.get(0).endsWith("/Xvfb")) {
					owned.put(process.pid, process);
				}
			}
		}
		for (ProcessSnapshot process : wrappers) owned.put(process.pid, process);
		return new ArrayList<ProcessSnapshot>(owned.values());
	}

	private static boolean stillSameProcess(ProcessSnapshot expected) {
		ProcessSnapshot current = readProcess(expected.pid);
		return current != null && expected.startTicks.equals(current.startTicks)
			&& expected.arguments.equals(current.arguments);
	}

	private static boolean waitForProcesses(List<ProcessSnapshot> processes, OperationDeadline deadline)
		throws InterruptedException {
		while (true) {
			boolean alive = false;
			for (ProcessSnapshot process : processes) alive |= stillSameProcess(process);
			if (!alive) return true;
			if (deadline.timedOut()) return false;
			Thread.sleep(Math.min(50L, deadline.remainingMillis()));
		}
	}

	private static void requireBudget(OperationDeadline deadline, String commandName, boolean json, String reason) {
		if (deadline.timedOut()) throw new KemuCliException(AutomationErrorCodes.TIMEOUT,
			"Operation budget expired while stopping the session.", commandName, json,
			Json.object().set("reason", reason));
	}

	private static void signal(
		ProcessSnapshot process, String signal, OperationDeadline deadline, String commandName, boolean json)
		throws Exception {
		if (!stillSameProcess(process)) return;
		requireBudget(deadline, commandName, json, "stop-timeout");
		Process kill = new ProcessBuilder("kill", signal, process.pid).start();
		if (!kill.waitFor(deadline.remainingMillis(), TimeUnit.MILLISECONDS)) {
			kill.destroyForcibly();
			throw new KemuCliException(AutomationErrorCodes.TIMEOUT,
				"Timed out signalling a verified session process.", commandName, json);
		}
	}

	/** Capture ownership before shutdown so worker/display cleanup remains safe after controller exit. */
	public static boolean stopSession(
		ControllerStatus status, OperationDeadline deadline, String commandName, boolean json) throws Exception {
		if (!isLinux()) throw new KemuCliException(AutomationErrorCodes.CONTROLLER_UNREACHABLE,
			"Verified session process cleanup currently requires Linux.", commandName, json);
		List<ProcessSnapshot> processes = sessionProcesses(status, deadline, commandName, json);
		if (processes.isEmpty()) {
			if (status != null && Boolean.TRUE.equals(status.pidAlive) && !isForeignPidState(status)) {
				throw new KemuCliException(AutomationErrorCodes.CONTROLLER_UNREACHABLE,
					"Controller ownership cannot be verified safely. Session was retained.", commandName, json);
			}
			return false;
		}
		if (status != null && status.running && Boolean.TRUE.equals(status.pidIdentityMatches)) {
			try {
				controllerClient(status).callTool("shutdown", Json.object()
					.set("timeoutMs", Math.min(1000L, deadline.remainingMillis() / 3L)));
			} catch (IOException ignored) {
			}
		} else {
			for (ProcessSnapshot process : processes) signal(process, "-TERM", deadline, commandName, json);
		}
		long graceMs = Math.min(5000L, deadline.remainingMillis() / 2L);
		if (!waitForProcesses(processes, OperationDeadline.afterMillis(graceMs))) {
			for (ProcessSnapshot process : processes) signal(process, "-KILL", deadline, commandName, json);
			if (!waitForProcesses(processes, deadline)) {
				throw new KemuCliException(CliErrorCodes.STOP_FAILED,
					"Verified session processes did not stop within the operation budget.", commandName, json);
			}
		}
		return true;
	}
}
