package emulator.cli.app;

import emulator.automation.shared.OperationDeadline;
import emulator.cli.controller.*;
import emulator.cli.core.*;
import emulator.cli.support.KemuPaths;
import java.nio.file.Files;
import java.nio.file.Path;
import mjson.Json;

/** Deadline-aware requests and artifact handling shared by public operations. */
public final class AgentCalls {
	private AgentCalls() { }

	public static Json call(CliInvocation invocation, ControllerClient client, OperationDeadline deadline,
			String operation, Json args, String command) throws Exception {
		return ControllerCalls.callController(client, operation, deadline.withRemaining(args), command, invocation.json());
	}

	public static ControllerClient client(CliInvocation invocation, String command) throws Exception {
		return client(invocation, command, invocation.deadline(10000L));
	}

	public static ControllerClient client(CliInvocation invocation, String command, OperationDeadline deadline) throws Exception {
		return ControllerStatusService.controllerClient(ControllerLifecycle.requireRunningController(command, invocation.json(), deadline));
	}

	public static Json context(CliInvocation invocation, ControllerClient client, OperationDeadline deadline,
			Json raw, String command) throws Exception {
		raw.set("current", call(invocation, client, deadline, "app.current", Json.object(), command));
		return raw;
	}

	public static Json observe(CliInvocation invocation, ControllerClient client, OperationDeadline deadline,
			String command, Path destination, boolean forceImage) throws Exception {
		Json raw = call(invocation, client, deadline, "app.agent.observe",
			Json.object().set("captureCanvas", true).set("includeImage", forceImage || destination != null), command);
		saveImage(raw, destination, invocation, command);
		return context(invocation, client, deadline, raw, command);
	}

	public static void saveImage(Json raw, Path destination, CliInvocation invocation, String command) throws Exception {
		if (!raw.has("imageBase64")) return;
		Path path = destination;
		if (path == null) {
			Files.createDirectories(KemuPaths.automationCapturesDir());
			path = Files.createTempFile(KemuPaths.automationCapturesDir(), "frame-", ".png");
		}
		try { ScreenshotCommand.saveImage(raw, path, command, invocation.json()); }
		catch (RuntimeException error) { if (destination == null) Files.deleteIfExists(path); throw error; }
		Json image = Json.object().set("path", raw.at("path"))
			.set("width", raw.at("width", 0)).set("height", raw.at("height", 0))
			.set("frameId", raw.at("frameId", Json.nil()));
		if (raw.has("ui") && raw.at("ui").isObject()) raw.at("ui").set("image", image);
	}

	public static Json afterAction(CliInvocation invocation, ControllerClient client, OperationDeadline deadline,
			String command, Json receipt, boolean observation) throws Exception {
		if (observation) {
			Json snapshot;
			try { snapshot = observe(invocation, client, deadline, command, null, false); }
			catch (KemuCliException error) {
				Json details = error.payload == null || !error.payload.isObject() ? Json.object() : error.payload.dup();
				if (receipt.has("action") || receipt.has("delivery")) {
					details.set("performed", !receipt.at("pending", false).asBoolean()).set("effectUnknown", false);
					if (receipt.has("action")) details.set("action", receipt.at("action"));
					else details.set("action", receipt.at("delivery").dup().set("operation", command));
				}
				throw new KemuCliException(error.code, error.getMessage(), command, invocation.json(), details);
			}
			receipt.set("state", snapshot);
			receipt.set("current", snapshot.at("current"));
			return receipt;
		}
		return context(invocation, client, deadline, receipt, command);
	}
}
