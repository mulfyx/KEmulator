package emulator.automation.controller;

import emulator.automation.shared.AutomationErrorCodes;
import emulator.automation.shared.AutomationLimits;
import emulator.automation.shared.OperationDeadline;
import emulator.automation.shared.TextValues;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import mjson.Json;

final class WorkerProtocolClient {
	private static final int WORKER_CONNECT_TIMEOUT_MS = 1500;
	private static final long TRANSPORT_ALLOWANCE_MS = 2000L;

	interface TimeoutHandler {
		void terminate(WorkerProcess worker);
	}

	private WorkerProtocolClient() {
	}

	private static Json callInternal(
		WorkerProcess worker,
		String operation,
		Json request,
		boolean controlPath,
		TimeoutHandler timeoutHandler) throws IOException {
		if (worker == null || worker.process == null || !worker.process.isAlive()) {
			throw WorkerDiagnostics.workerRemoteFailure(
				worker, AutomationErrorCodes.WORKER_FAILURE, "Worker is not running", null);
		}

		OperationDeadline deadline = OperationDeadline.fromRequest(request, AutomationLimits.DEFAULT_TIMEOUT_MS);
		boolean sent = false;
		// The controller admits mutations through its bounded command queue.
		// RPC connections must remain independent so a blocked callback cannot
		// prevent observing it or answering its permission request.
		Socket socket = new Socket();
		try {
			if (deadline.timedOut() && !(deadline.timeoutMillis() == 0L && isReadOnly(operation))) {
				throw WorkerDiagnostics.workerRemoteFailure(worker, AutomationErrorCodes.TIMEOUT,
					"Operation budget expired before contacting the worker",
					Json.object().set("operation", operation).set("reason", "budget-expired")
						.set("effectUnknown", false).set("performed", false));
			}
			socket.connect(new InetSocketAddress("127.0.0.1", worker.port),
				(int) Math.max(1L, Math.min(WORKER_CONNECT_TIMEOUT_MS, deadline.remainingMillis())));
			Json remainingRequest = deadline.withRemaining(request);
			long readTimeout = deadline.remainingMillis() + TRANSPORT_ALLOWANCE_MS;
			if (request != null && request.has("_transportTimeoutMs")) {
				readTimeout = Math.min(readTimeout, request.at("_transportTimeoutMs").asLong());
				remainingRequest.delAt("_transportTimeoutMs");
			}
			socket.setSoTimeout((int) Math.max(1L, Math.min(Integer.MAX_VALUE, readTimeout)));
			Json envelope = Json.object()
				.set("id", worker.nextRequestId.getAndIncrement())
				.set("op", operation)
				.set("args", remainingRequest);
			OutputStream out = socket.getOutputStream();
			sent = true;
			out.write((envelope.toString() + '\n').getBytes(StandardCharsets.UTF_8));
			out.flush();
			BufferedReader reader = new BufferedReader(
				new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
			String response = reader.readLine();
			if (TextValues.isBlank(response)) {
				throw WorkerDiagnostics.workerRemoteFailure(
					worker,
					AutomationErrorCodes.WORKER_FAILURE,
					"Worker returned empty protocol response",
					null);
			}

			Json parsed;
			try {
				parsed = Json.read(response);
			} catch (RuntimeException e) {
				throw WorkerDiagnostics.workerRemoteFailure(
					worker,
					AutomationErrorCodes.WORKER_FAILURE,
					"Worker returned invalid JSON response",
					Json.object().set("response", WorkerDiagnostics.truncateResponse(response)));
			}

			if (!parsed.isObject()) {
				throw WorkerDiagnostics.workerRemoteFailure(
					worker,
					AutomationErrorCodes.WORKER_FAILURE,
					"Worker returned non-object JSON",
					Json.object().set("response", WorkerDiagnostics.truncateResponse(response)));
			}

			if (!parsed.at("ok", false).asBoolean()) {
				throw WorkerDiagnostics.workerRemoteFailure(worker, parsed.at("error"), "Worker error");
			}

			return parsed.at("result", Json.object());
		} catch (java.net.SocketTimeoutException e) {
			if (!worker.process.isAlive()) {
				throw WorkerDiagnostics.workerRemoteFailure(worker, AutomationErrorCodes.WORKER_FAILURE,
					"Worker exited while handling " + operation,
					Json.object().set("operation", operation).set("reason", "worker-exited"));
			}
			throw WorkerDiagnostics.workerRemoteFailure(
				worker,
				AutomationErrorCodes.TIMEOUT,
				"Worker timed out while handling " + operation,
				Json.object().set("operation", operation)
					.set("reason", sent ? "worker-response-timeout" : "worker-connect-timeout")
					.set("effectUnknown", sent && !isReadOnly(operation))
					.set("performed", sent ? Json.nil() : Json.make(false)));
		} finally {
			try {
				socket.close();
			} catch (IOException ignored) {
			}
		}
	}

	private static boolean isReadOnly(String operation) {
		return "health".equals(operation) || "session".equals(operation) || "observe".equals(operation)
			|| "agent-observe".equals(operation) || "screenshot".equals(operation)
			|| "wait-condition".equals(operation) || "commands".equals(operation) || "events-read".equals(operation);
	}

	static Json call(
		WorkerProcess worker,
		String operation,
		Json request,
		TimeoutHandler timeoutHandler) throws IOException {
		return callInternal(worker, operation, request, false, timeoutHandler);
	}

	static Json callControl(
		WorkerProcess worker,
		String operation,
		Json request,
		TimeoutHandler timeoutHandler) throws IOException {
		return callInternal(worker, operation, request, true, timeoutHandler);
	}
}
