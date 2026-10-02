package emulator.cli.app;

import emulator.automation.shared.OperationDeadline;
import emulator.cli.core.CliErrorCodes;
import emulator.cli.controller.*;
import emulator.cli.core.*;
import emulator.cli.output.CliTextRenderer;
import emulator.cli.parse.CliParsing;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import mjson.Json;

public final class ScreenshotCommand implements CliCommand {
	public CommandPath path() {
		return CommandPath.of("screenshot");
	}

	public CommandResult run(CliInvocation invocation) throws Exception {
		int pathIndex = invocation.tokens().size() > 1 && "--".equals(invocation.tokens().get(1)) ? 2 : 1;
		if (invocation.tokens().size() != 1 && (invocation.tokens().size() != pathIndex + 1
			|| pathIndex == 1 && invocation.tokens().get(pathIndex).startsWith("--"))) {
			throw new KemuCliException(
				CliErrorCodes.USAGE_ERROR,
				CliTextRenderer.usageText("screenshot"),
				"screenshot",
				invocation.json());
		}

		Path out = invocation.tokens().size() == 1 ? null : CliParsing.resolveUserPath(invocation.tokens().get(pathIndex));
		OperationDeadline deadline = invocation.deadline(10000L);
		ControllerClient client = ControllerStatusService.controllerClient(
			ControllerLifecycle.requireRunningController("screenshot", invocation.json(), deadline));
		Json payload = AgentCalls.observe(invocation, client, deadline, "screenshot", out, true);

		return new CommandResult("screenshot", payload, invocation.json());
	}

	/**
	 * Writes the base64 image out of a screenshot payload and replaces it with
	 * {@code saved}/{@code path} metadata. Shared with `observe --screenshot`.
	 */
	static void saveImage(Json payload, Path out, String commandName, boolean json) {
		String imageBase64 = payload.at("imageBase64", Json.nil()).isNull()
			? null
			: payload.at("imageBase64").asString();
		if (imageBase64 == null || imageBase64.length() == 0) {
			throw new KemuCliException(
				emulator.automation.shared.AutomationErrorCodes.SCREENSHOT_FAILED,
				"Controller did not return image data.",
				commandName,
				json);
		}

		byte[] imageBytes;
		try {
			imageBytes = Base64.getDecoder().decode(imageBase64);
		} catch (IllegalArgumentException e) {
			throw new KemuCliException(
				emulator.automation.shared.AutomationErrorCodes.SCREENSHOT_FAILED,
				"Controller returned invalid image data.",
				commandName,
				json);
		}

		Path parent = out.getParent();
		try {
			if (parent != null) {
				Files.createDirectories(parent);
			}

			Files.write(out, imageBytes);
		} catch (IOException e) {
			throw new KemuCliException(
				CliErrorCodes.SCREENSHOT_WRITE_FAILED,
				"Could not write screenshot to " + out + ": " + e.getMessage(),
				commandName,
				json,
				Json.object().set("path", out.toString()));
		}

		payload.delAt("imageBase64");
		payload.set("saved", true);
		payload.set("path", out.toString());
	}
}
