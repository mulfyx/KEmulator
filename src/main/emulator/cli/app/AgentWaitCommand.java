package emulator.cli.app;

import emulator.automation.shared.AutomationLimits;
import emulator.automation.shared.OperationDeadline;
import emulator.cli.controller.ControllerClient;
import emulator.cli.controller.ControllerLifecycle;
import emulator.cli.controller.ControllerStatusService;
import emulator.cli.core.*;
import emulator.cli.output.CliTextRenderer;
import emulator.cli.parse.CliParsing;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import mjson.Json;

/** Public conditions use the same operation budget as their resulting observation. */
public final class AgentWaitCommand implements CliCommand {
	private final String type;
	public AgentWaitCommand(String type) { this.type = type; }
	public CommandPath path() { return CommandPath.of("wait", type); }
	private String commandName() { return "wait " + type; }

	public CommandResult run(CliInvocation invocation) throws Exception {
		Json request = parse(invocation);
		OperationDeadline deadline = invocation.deadline(AutomationLimits.DEFAULT_TIMEOUT_MS);
		ControllerClient client = ControllerStatusService.controllerClient(
			ControllerLifecycle.requireRunningController(commandName(), invocation.json(), deadline));
		String operation = "exit".equals(type) ? "app.wait.worker-exit"
			: "frame".equals(type) ? "app.agent.wait.frame"
			: "log".equals(type) ? "logs.wait" : "app.wait.condition";
		Json payload = AgentCalls.call(invocation, client, deadline, operation, request, commandName());
		payload.set("condition", type);
		if ("frame".equals(type)) {
			if (payload.has("state") && payload.at("state").isObject())
				AgentCalls.saveImage(payload.at("state"), null, invocation, commandName());
			payload = AgentCalls.context(invocation, client, deadline, payload, commandName());
		} else if ("screen".equals(type) || "ready".equals(type) || "permission".equals(type)) {
			payload = AgentCalls.afterAction(invocation, client, deadline, commandName(), payload, true);
		} else {
			payload = AgentCalls.context(invocation, client, deadline, payload, commandName());
		}
		return new CommandResult(commandName(), payload, invocation.json());
	}

	private Json parse(CliInvocation invocation) {
		Json request = Json.object();
		if (!"exit".equals(type) && !"frame".equals(type) && !"log".equals(type))
			request.set("type", "screen".equals(type) ? "display" : "ready".equals(type) ? "worker-ready" : type);
		List<String> tokens = invocation.tokens();
		for (int i = 2; i < tokens.size(); i++) {
			String option = tokens.get(i);
			String key = optionKey(option);
			if (key == null || i + 1 >= tokens.size()) throw usage(invocation);
			if (request.has(key)) throw CliParsing.duplicateOption(option, commandName(), invocation.json());
			int valueIndex = i + 1;
			if ("--".equals(tokens.get(valueIndex))) {
				valueIndex++;
				if (valueIndex != tokens.size() - 1) throw usage(invocation);
			}
			String value = tokens.get(valueIndex);
			if (("regex".equals(key) || "titleRegex".equals(key))) {
				try { Pattern.compile(value); }
				catch (PatternSyntaxException invalid) {
					throw new KemuCliException(CliErrorCodes.USAGE_ERROR,
						"Invalid " + option + ": " + invalid.getDescription(), commandName(), invocation.json());
				}
			}
			if (value.length() == 0 && ("regex".equals(key) || "kind".equals(key)
				|| "afterFrame".equals(key) || "since".equals(key))) throw usage(invocation);
			request.set(key, value);
			i = valueIndex;
		}
		if ("log".equals(type) && !request.has("regex")) throw usage(invocation);
		return request;
	}

	private String optionKey(String option) {
		if ("screen".equals(type)) {
			if ("--kind".equals(option)) return "kind";
			if ("--title".equals(option)) return "title";
			if ("--title-regex".equals(option)) return "titleRegex";
			if ("--text".equals(option)) return "text";
		} else if ("frame".equals(type) && "--after".equals(option)) return "afterFrame";
		else if ("permission".equals(type) && "--name".equals(option)) return "name";
		else if ("log".equals(type)) {
			if ("--regex".equals(option)) return "regex";
			if ("--since".equals(option)) return "since";
		}
		return null;
	}

	private KemuCliException usage(CliInvocation invocation) {
		return new KemuCliException(CliErrorCodes.USAGE_ERROR, CliTextRenderer.usageText(commandName()),
			commandName(), invocation.json());
	}
}
