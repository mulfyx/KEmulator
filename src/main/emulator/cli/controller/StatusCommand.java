package emulator.cli.controller;

import emulator.cli.core.*;
import emulator.automation.shared.AutomationErrorCodes;
import emulator.automation.shared.OperationDeadline;
import emulator.cli.app.AgentCalls;
import mjson.Json;

public final class StatusCommand implements CliCommand {
	public CommandPath path() {
		return CommandPath.of("status");
	}

	public CommandResult run(CliInvocation invocation) throws Exception {
		ControllerLifecycle.requireTokenCount(invocation.tokens(), 1, "status", invocation.json());
		OperationDeadline deadline = invocation.deadline(10000L);
		ControllerStatus status = ControllerStatusService.readControllerStatus(deadline);
		Json payload = status.toJson();
		payload.set("active", false).set("appStatus", "none");
		if (!status.running) {
			payload.set("reason", status.degraded ? "controller-unresponsive"
				: status.loadError != null ? "controller-state-invalid"
				: ControllerStatusService.isForeignPidState(status) ? "controller-identity-mismatch"
				: status.exists ? "controller-exited" : "session-not-started");
		} else {
			ControllerClient client = ControllerStatusService.controllerClient(status);
			try {
				Json current = AgentCalls.call(invocation, client, deadline, "app.current", Json.object(), "status");
				payload.set("current", current).set("active", current.at("active", false));
				if (current.has("app")) payload.set("app", current.at("app"));
				if (current.has("worker")) payload.set("worker", current.at("worker"));
				if (current.has("failure")) {
					payload.set("workerFailure", current.at("failure")).set("appStatus", "dead")
						.set("reason", "worker-exited");
				} else if (current.at("active", false).asBoolean()) {
					Json snapshot = AgentCalls.call(invocation, client, deadline, "app.agent.observe",
						Json.object().set("captureCanvas", false).set("includeImage", false), "status");
					boolean ready = snapshot.at("ready", false).asBoolean()
						|| snapshot.at("displayable", Json.nil()).isObject();
					payload.set("state", snapshot).set("appStatus", snapshot.at("paused", false).asBoolean()
						? "paused" : ready ? "ready" : "starting");
					if (snapshot.at("permissionRequest", Json.nil()).isObject()) payload.set("reason", "permission-required");
				}
			} catch (KemuCliException failure) {
				boolean dead = AutomationErrorCodes.WORKER_FAILURE.equals(failure.code);
				payload.set("appStatus", dead ? "dead" : "unresponsive").set("reason", dead
					? "worker-exited" : "state-unavailable");
				Json details = Json.object().set("code", failure.code).set("message", failure.getMessage());
				if (failure.payload != null) details.set("details", failure.payload);
				payload.set("statusFailure", details);
			}
		}

		return new CommandResult(
			"status",
			payload,
			invocation.json());
	}
}
