package emulator.cli.app;

import emulator.cli.controller.*;
import emulator.cli.core.*;
import emulator.cli.output.CliResponses;
import emulator.cli.output.CliTextRenderer;
import emulator.cli.parse.CliParsing;
import java.nio.file.Path;
import mjson.Json;

public final class ObserveCommand implements CliCommand {
	public CommandPath path() {
		return CommandPath.of("observe");
	}

	private KemuCliException usage(boolean json) {
		return new KemuCliException(
			CliErrorCodes.USAGE_ERROR, CliTextRenderer.usageText("observe"), "observe", json);
	}

	public CommandResult run(CliInvocation invocation) throws Exception {
		boolean json = invocation.json();
		Path screenshot = null;
		for (int i = 1; i < invocation.tokens().size(); i++) {
			String token = invocation.tokens().get(i);
			if (!"--screenshot".equals(token)) {
				throw usage(json);
			}
			if (screenshot != null) {
				throw CliParsing.duplicateOption(token, "observe", json);
			}
			if (i + 1 >= invocation.tokens().size()) {
				throw usage(json);
			}
			screenshot = CliParsing.resolveUserPath(invocation.tokens().get(++i));
		}

		ControllerStatus status = ControllerLifecycle.requireRunningController("observe", json);
		ControllerClient client = ControllerStatusService.controllerClient(status);
		Json current = ControllerCalls.currentAppResult(client, "observe", json);
		ControllerCalls.throwIfWorkerFailure(current, "observe", json);
		boolean active = current.at("active", false).asBoolean();
		if (!active) {
			if (screenshot != null) {
				throw new KemuCliException(
					emulator.automation.shared.AutomationErrorCodes.NO_ACTIVE_APP,
					"No active app to capture.",
					"observe",
					json);
			}

			return new CommandResult(
				"observe",
				CliTextRenderer.renderObserve(Json.object().set("active", false)),
				Json.object().set("active", false),
				json);
		}

		Json payload;
		if (screenshot == null) {
			Json session = ControllerCalls.callController(
				client, "app.observe", Json.object().set("includeImage", false), "observe", json);
			payload = CliResponses.buildSnapshotPayload(current, session);
		} else {
			// One worker call returns the snapshot and its image, so the state
			// and the picture cannot drift apart.
			Json capture = CliResponses.normalizePublicJson(ControllerCalls.callController(
				client, "app.screenshot", Json.object(), "observe", json));
			ScreenshotCommand.saveImage(capture, screenshot, "observe", json);
			payload = CliResponses.buildSnapshotPayload(current, capture.at("state", Json.object()));
			payload.set("screenshot", Json.object()
				.set("saved", capture.at("saved", false).asBoolean())
				.set("path", capture.at("path", Json.nil())));
		}

		return new CommandResult("observe", CliTextRenderer.renderObserve(payload), payload, json);
	}
}
