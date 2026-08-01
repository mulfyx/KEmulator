package emulator.cli.app;

import emulator.automation.shared.AutomationErrorCodes;
import emulator.cli.core.CliErrorCodes;
import emulator.cli.controller.*;
import emulator.cli.core.*;
import emulator.cli.output.CliResponses;
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
		if (invocation.tokens().size() != 2) {
			throw new KemuCliException(
				CliErrorCodes.USAGE_ERROR,
				CliTextRenderer.usageText("screenshot"),
				"screenshot",
				invocation.json());
		}

		Path out = CliParsing.resolveUserPath(invocation.tokens().get(1));

		ControllerStatus status = ControllerLifecycle.requireRunningController("screenshot", invocation.json());
		Json payload = CliResponses.normalizePublicJson(ControllerCalls.callController(
			ControllerStatusService.controllerClient(status),
			"app.screenshot",
			Json.object(),
			"screenshot",
			invocation.json()));
		String imageBase64 = payload.at("imageBase64", Json.nil()).isNull()
			? null
			: payload.at("imageBase64").asString();
		if (imageBase64 == null || imageBase64.length() == 0) {
			throw new KemuCliException(
				AutomationErrorCodes.SCREENSHOT_FAILED,
				"Controller did not return image data.",
				"screenshot",
				invocation.json());
		}

		byte[] imageBytes;
		try {
			imageBytes = Base64.getDecoder().decode(imageBase64);
		} catch (IllegalArgumentException e) {
			throw new KemuCliException(
				AutomationErrorCodes.SCREENSHOT_FAILED,
				"Controller returned invalid image data.",
				"screenshot",
				invocation.json());
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
				"screenshot",
				invocation.json(),
				Json.object().set("path", out.toString()));
		}

		payload.delAt("imageBase64");
		payload.set("saved", true);
		payload.set("path", out.toString());

		return new CommandResult("screenshot", out.toString(), payload, invocation.json());
	}
}
