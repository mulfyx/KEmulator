package emulator.automation.controller;

import emulator.automation.shared.AutomationErrorCodes;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.concurrent.TimeUnit;
import mjson.Json;

final class WorkerReadinessProbe {
	private static final long SESSION_PROBE_INTERVAL_MS = 250L;
	private static final WorkerProtocolClient.TimeoutHandler NO_TERMINATE_HANDLER =
		new WorkerProtocolClient.TimeoutHandler() {
			public void terminate(WorkerProcess worker) {
			}
		};

	private WorkerReadinessProbe() {
	}

	private static Integer readExitCode(WorkerProcess worker) {
		try {
			if (worker.exitPath != null && Files.isRegularFile(worker.exitPath)) {
				String content = new String(Files.readAllBytes(worker.exitPath), StandardCharsets.UTF_8).trim();
				if (content.startsWith("exitCode=")) {
					return Integer.valueOf(Integer.parseInt(content.substring("exitCode=".length()).trim()));
				}
			}
		} catch (RuntimeException ignored) {
		} catch (IOException ignored) {
		}

		try {
			return Integer.valueOf(worker.process.exitValue());
		} catch (IllegalThreadStateException ignored) {
			return null;
		}
	}

	private static String causeHintFromLog(WorkerProcess worker) {
		String tail;
		try {
			tail = WorkerDiagnostics.readLastLines(worker.logPath, 60);
		} catch (IOException ignored) {
			return null;
		}

		String hint = null;
		for (String line : tail.split("\n")) {
			String trimmed = line.trim();
			if (trimmed.length() == 0 || trimmed.startsWith("at ")) {
				continue;
			}

			if (trimmed.contains("Exception") || trimmed.contains("Error")) {
				hint = trimmed;
			}
		}

		return hint;
	}

	private static RuntimeException workerExitFailure(WorkerProcess worker) {
		Json details = Json.object().set("reason", "worker-exited");
		Integer exitCode = readExitCode(worker);
		if (exitCode != null) {
			details.set("exitCode", exitCode.intValue());
		}

		String causeHint = causeHintFromLog(worker);
		if (causeHint != null) {
			details.set("causeHint", causeHint);
		}

		StringBuilder message = new StringBuilder("Worker exited before becoming ready");
		if (exitCode != null) {
			message.append(" (exit code ").append(exitCode.intValue()).append(')');
		}

		if (causeHint != null) {
			message.append(": ").append(causeHint);
		}

		return WorkerDiagnostics.workerFailure(
			AutomationErrorCodes.WORKER_FAILURE, message.toString(), worker, details);
	}

	private static Json probeSession(WorkerProcess worker) {
		try {
			return WorkerProtocolClient.call(worker, "session", Json.object(), NO_TERMINATE_HANDLER);
		} catch (IOException ignored) {
			return null;
		} catch (RuntimeException ignored) {
			return null;
		}
	}

	static Json waitUntilReady(WorkerProcess worker, long timeoutMs) throws Exception {
		long start = System.nanoTime();
		long deadline = start + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
		WatchService watchService = worker.readyPath.getFileSystem().newWatchService();
		worker.readyPath.getParent().register(
			watchService,
			StandardWatchEventKinds.ENTRY_CREATE,
			StandardWatchEventKinds.ENTRY_MODIFY);
		try {
			while (true) {
				if (!worker.process.isAlive()) {
					throw workerExitFailure(worker);
				}

				if (Files.isRegularFile(worker.readyPath)) {
					Json session = WorkerProtocolClient.call(
						worker, "session", Json.object(), WorkerProcessTerminator.timeoutHandler());
					if (session.at("ready", false).asBoolean()) {
						return session;
					}
					throw WorkerDiagnostics.workerFailure(
						AutomationErrorCodes.OPEN_TIMEOUT,
						"Worker ready marker was written before the MIDlet display became ready",
						worker,
						Json.object().set("lastSession", WorkerDiagnostics.stripImage(session)));
				}

				// startApp() may be blocked on a permission request before the
				// ready marker exists; surface that instead of timing out.
				Json session = probeSession(worker);
				if (session != null
					&& session.has("permissionRequest")
					&& !session.at("permissionRequest").isNull()) {
					return WorkerDiagnostics.stripImage(session);
				}

				long remaining = deadline - System.nanoTime();
				if (remaining <= 0L) {
					break;
				}
				WatchKey key = watchService.poll(
					Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(SESSION_PROBE_INTERVAL_MS)),
					TimeUnit.NANOSECONDS);
				if (key != null) {
					key.pollEvents();
					key.reset();
				}
			}
		} finally {
			watchService.close();
		}

		Json timeoutDetails = Json.object()
			.set("timeoutMs", timeoutMs)
			.set("elapsedMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start))
			.set("readyPath", worker.readyPath.toString());
		String causeHint = causeHintFromLog(worker);
		if (causeHint != null) {
			timeoutDetails.set("causeHint", causeHint);
		}

		throw WorkerDiagnostics.workerFailure(
			AutomationErrorCodes.OPEN_TIMEOUT,
			"Timed out waiting for worker readiness",
			worker,
			timeoutDetails);
	}
}
