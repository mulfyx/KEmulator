package emulator.automation.controller;

import emulator.automation.shared.AutomationErrorCodes;
import emulator.automation.shared.OperationDeadline;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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

	private static Json probeSession(WorkerProcess worker, OperationDeadline deadline) {
		try {
			long remaining = deadline.remainingMillis();
			return WorkerProtocolClient.call(worker, "session", Json.object()
				.set("timeoutMs", remaining)
				.set("_transportTimeoutMs", Math.min(remaining, SESSION_PROBE_INTERVAL_MS)), NO_TERMINATE_HANDLER);
		} catch (IOException ignored) {
			return null;
		} catch (RuntimeException ignored) {
			return null;
		}
	}

	static Json waitUntilReady(WorkerProcess worker, long timeoutMs) throws Exception {
		OperationDeadline deadline = OperationDeadline.afterMillis(timeoutMs);
		Json lastSession = null;
		while (!deadline.timedOut()) {
			if (!worker.process.isAlive()) {
				throw workerExitFailure(worker);
			}
			// A first Displayable is usable even while startApp is still
			// running. The marker only records startApp's return and cannot
			// decide whether this launch has reached its public threshold.
			Json session = probeSession(worker, deadline);
			if (session != null) {
				lastSession = WorkerDiagnostics.stripImage(session);
				if (session.at("ready", false).asBoolean()
					|| (session.has("displayable") && !session.at("displayable").isNull())
					|| (session.has("permissionRequest") && !session.at("permissionRequest").isNull())) {
					return session;
				}
			}
			long remaining = deadline.remainingMillis();
			if (remaining > 0L) {
				Thread.sleep(Math.min(remaining, SESSION_PROBE_INTERVAL_MS));
			}
		}
		if (!worker.process.isAlive()) {
			throw workerExitFailure(worker);
		}

		Json timeoutDetails = Json.object()
			.set("timeoutMs", timeoutMs)
			.set("elapsedMs", deadline.elapsedMillis())
			.set("reason", "first-display-timeout")
			.set("workerRetained", true);
		if (lastSession != null) {
			timeoutDetails.set("lastSession", lastSession);
		}
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
