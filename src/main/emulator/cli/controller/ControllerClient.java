package emulator.cli.controller;

import emulator.automation.shared.AutomationErrorCodes;
import emulator.automation.shared.AutomationErrorPayloads;
import emulator.automation.shared.AutomationRemoteException;
import emulator.automation.shared.OperationDeadline;
import emulator.automation.shared.TextValues;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import mjson.Json;

public final class ControllerClient {
	private static final int CONNECT_TIMEOUT_MS = 3000;
	private static final int HEALTH_READ_TIMEOUT_MS = 1500;
	private static final int SHUTDOWN_READ_TIMEOUT_MS = 5000;
	private static final int DEFAULT_READ_TIMEOUT_MS = 120000;

	private final String host;
	private final int port;

	public ControllerClient(String host, int port) {
		this.host = host;
		this.port = port;
	}

	private String truncateResponse(String responseBody) {
		if (responseBody == null) {
			return "(null)";
		}

		String normalized = responseBody.trim();

		return normalized.length() > 160 ? normalized.substring(0, 160) + "..." : normalized;
	}

	private int readTimeoutFor(String operation, Json request) {
		if ("health".equals(operation)) {
			return (int) Math.max(1L, Math.min(HEALTH_READ_TIMEOUT_MS, request.at("timeoutMs", HEALTH_READ_TIMEOUT_MS).asLong()));
		}

		if ("shutdown".equals(operation)) {
			return (int) Math.max(1L, Math.min(SHUTDOWN_READ_TIMEOUT_MS, request.at("timeoutMs", SHUTDOWN_READ_TIMEOUT_MS).asLong()));
		}

		if (request != null
			&& request.isObject()
			&& request.has("timeoutMs")
			&& !request.at("timeoutMs").isNull()) {
			// The CLI slack must exceed the controller->worker slack so the
			// inner deadline always fires first.
			long requested = request.at("timeoutMs").asLong() + 5000L;
			return (int) Math.max(1L, Math.min(Integer.MAX_VALUE, requested));
		}
		if (request != null && request.isObject() && request.has("openTimeoutMs")) {
			return (int) Math.max(1L, Math.min(Integer.MAX_VALUE,
				request.at("openTimeoutMs").asLong() + 5000L));
		}

		return DEFAULT_READ_TIMEOUT_MS;
	}

	private void mergeDetails(Json target, Json extraDetails) {
		if (extraDetails == null || extraDetails.isNull()) {
			return;
		}

		if (!extraDetails.isObject()) {
			target.set("context", extraDetails);

			return;
		}

		for (String key : extraDetails.asJsonMap().keySet()) {
			target.set(key, extraDetails.at(key));
		}
	}

	private Json requestContext(String operation, Json extraDetails) {
		Json details = Json.object().set("host", host).set("port", port).set("operation", operation);
		mergeDetails(details, extraDetails);

		return details;
	}

	private ControllerException controllerFailure(String code, String message, Json details) {
		return new ControllerException(code, message, details);
	}

	private ControllerException controllerFailure(String code, String message, Json details, Throwable cause) {
		return new ControllerException(code, message, details, cause);
	}

	private Json post(String operation, Json request) throws IOException {
		long defaultBudget =
			"health".equals(operation) ? HEALTH_READ_TIMEOUT_MS
				: "shutdown".equals(operation) ? SHUTDOWN_READ_TIMEOUT_MS : DEFAULT_READ_TIMEOUT_MS;
		if (request != null && request.has("openTimeoutMs")) {
			defaultBudget = request.at("openTimeoutMs").asLong();
		}
		OperationDeadline deadline = OperationDeadline.fromRequest(request, defaultBudget);
		Socket socket = new Socket();
		boolean sent = false;
		try {
			try {
				socket.connect(new InetSocketAddress(host, port),
					(int) Math.max(1L, Math.min(CONNECT_TIMEOUT_MS, deadline.remainingMillis())));
				Json remainingRequest = deadline.withRemaining(request);
				socket.setSoTimeout(readTimeoutFor(operation, remainingRequest));
				Json envelope = Json.object()
					.set("id", UUID.randomUUID().toString())
					.set("op", operation)
					.set("args", remainingRequest);
				OutputStream out = socket.getOutputStream();
				sent = true;
				out.write((envelope.toString() + '\n').getBytes(StandardCharsets.UTF_8));
				out.flush();
				BufferedReader reader = new BufferedReader(
					new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
				String responseBody = reader.readLine();
				if (TextValues.isBlank(responseBody)) {
					if ("shutdown".equals(operation)) {
						return Json.object().set("ok", true);
					}

					throw controllerFailure(
						AutomationErrorCodes.CONTROLLER_ERROR,
						"Empty controller response",
						requestContext(operation, null));
				}

				Json response;
				try {
					response = Json.read(responseBody);
				} catch (RuntimeException e) {
					throw controllerFailure(
						AutomationErrorCodes.CONTROLLER_ERROR,
						"Invalid controller JSON response",
						requestContext(operation, Json.object().set("response", truncateResponse(responseBody))),
						e);
				}

				if (!response.isObject()) {
					throw controllerFailure(
						AutomationErrorCodes.CONTROLLER_ERROR,
						"Controller returned non-object JSON",
						requestContext(operation, Json.object().set("response", truncateResponse(responseBody))));
				}

				if (!response.at("ok", false).asBoolean()) {
					AutomationRemoteException remote = AutomationErrorPayloads.remoteException(
						response.at("error"), AutomationErrorCodes.CONTROLLER_ERROR, "Controller error");
					throw controllerFailure(
						remote.code, remote.getMessage(), requestContext(operation, remote.details), remote);
				}

				return response.at("result", Json.object());
			} catch (ControllerException e) {
				throw e;
			} catch (java.net.SocketTimeoutException e) {
				// The controller is alive; the operation outlived the socket
				// read window. That is a timeout, not unreachability.
				throw controllerFailure(
					AutomationErrorCodes.TIMEOUT,
					"Timed out waiting for controller operation: " + operation,
					requestContext(operation, Json.object().set("cause", e.getMessage())
						.set("effectUnknown", sent && !isReadOnly(operation))
						.set("reason", sent ? "controller-response-timeout" : "controller-connect-timeout")),
					e);
			} catch (IOException e) {
				throw controllerFailure(
					AutomationErrorCodes.CONTROLLER_UNREACHABLE,
					"Failed to reach controller at " + host + ":" + port,
					requestContext(operation, Json.object().set("cause", e.getMessage())),
					e);
			}
		} finally {
			try {
				socket.close();
			} catch (IOException ignored) {
			}
		}
	}

	private static boolean isReadOnly(String operation) {
		return "health".equals(operation) || "app.state".equals(operation) || "app.session".equals(operation)
			|| "app.current".equals(operation) || "app.observe".equals(operation)
			|| "app.agent.observe".equals(operation) || "app.screenshot".equals(operation)
			|| operation.startsWith("app.wait") || operation.startsWith("logs");
	}

	public boolean ping() throws IOException {
		post("health", Json.object());

		return true;
	}

	public void shutdown() throws IOException {
		post("shutdown", Json.object());
	}

	public Json callTool(String operation, Json arguments) throws IOException {
		return post(operation, arguments == null ? Json.object() : arguments);
	}
}
