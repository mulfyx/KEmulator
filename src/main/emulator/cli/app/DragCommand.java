package emulator.cli.app;

import emulator.cli.core.CliErrorCodes;
import emulator.automation.shared.AutomationLimits;
import emulator.automation.shared.OperationDeadline;
import emulator.cli.controller.ControllerClient;
import emulator.cli.core.*;
import emulator.cli.output.CliTextRenderer;
import emulator.cli.parse.CliParsing;
import java.util.ArrayList;
import java.util.List;
import mjson.Json;

public final class DragCommand implements CliCommand {
	public CommandPath path() {
		return CommandPath.of("drag");
	}

	public CommandResult run(CliInvocation invocation) throws Exception {
		if (invocation.tokens().size() < 5) {
			throw new KemuCliException(
				CliErrorCodes.USAGE_ERROR, CliTextRenderer.usageText("drag"), "drag", invocation.json());
		}

		Integer delay = null;
		boolean sawDelay = false;
		boolean observe = false;
		boolean literal = false;
		List<Integer> coords = new ArrayList<Integer>();
		for (int i = 1; i < invocation.tokens().size(); i++) {
			String token = invocation.tokens().get(i);
			if (!literal && "--".equals(token)) {
				literal = true;
			} else if (!literal && "--observe".equals(token)) {
				if (observe) throw CliParsing.duplicateOption(token, "drag", invocation.json());
				observe = true;
			} else if (!literal && "--delay".equals(token)) {
				if (sawDelay) {
					throw new KemuCliException(
						CliErrorCodes.USAGE_ERROR, "Duplicate option: --delay.", "drag", invocation.json());
				}

				if (i + 1 >= invocation.tokens().size()) {
					throw new KemuCliException(
						CliErrorCodes.USAGE_ERROR,
						CliTextRenderer.usageText("drag"),
						"drag",
						invocation.json());
				}

				sawDelay = true;
				delay = Integer.valueOf(CliParsing.parseIntegerArgument(
					invocation.tokens().get(++i), "--delay", "drag", invocation.json()));
				delay = Integer.valueOf(CliParsing.requireInclusiveRange(
					delay.intValue(), AutomationLimits.MIN_DRAG_DELAY_MS,
					AutomationLimits.MAX_DRAG_DELAY_MS, "--delay", "drag", invocation.json()));
			} else {
				int coordinate = CliParsing.parseIntegerArgument(token, "coordinate", "drag", invocation.json());
				coords.add(Integer.valueOf(CliParsing.requireInclusiveRange(
					coordinate, 0, Integer.MAX_VALUE, "coordinate", "drag", invocation.json())));
			}
		}

		if (coords.size() < 4 || coords.size() % 2 != 0) {
			throw new KemuCliException(
				CliErrorCodes.USAGE_ERROR, CliTextRenderer.usageText("drag"), "drag", invocation.json());
		}

		Json points = Json.array();
		for (int i = 0; i < coords.size(); i += 2) {
			points.add(Json.object()
				.set("x", coords.get(i).intValue())
				.set("y", coords.get(i + 1).intValue()));
		}

		Json args = Json.object().set("points", points).set("waitDispatched", true);
		if (delay != null) {
			args.set("delayMs", delay.intValue());
		}

		OperationDeadline deadline = invocation.deadline(AutomationLimits.DEFAULT_TIMEOUT_MS);
		ControllerClient client = AgentCalls.client(invocation, "drag", deadline);
		Json payload = AgentCalls.call(invocation, client, deadline, "app.drag", args, "drag");
		payload = AgentCalls.afterAction(invocation, client, deadline, "drag", payload, observe);

		return new CommandResult(
			"drag", payload, invocation.json());
	}
}
