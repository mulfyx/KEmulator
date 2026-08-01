package emulator.cli.controller;

import emulator.cli.core.CliErrorCodes;
import emulator.cli.core.KemuCliException;
import emulator.cli.output.CliTextRenderer;
import emulator.cli.parse.CliParsing;
import emulator.cli.support.CliDefaults;
import java.util.List;

final class ControllerOptionParsers {
	private ControllerOptionParsers() {
	}

	static KemuCliException duplicateOption(String option, String commandName, boolean json) {
		return CliParsing.duplicateOption(option, commandName, json);
	}

	static KemuCliException usageError(String commandName, boolean json) {
		return new KemuCliException(
			CliErrorCodes.USAGE_ERROR, CliTextRenderer.usageText(commandName), commandName, json);
	}

	private static void requireSingleAssignment(Object value, String option, String commandName, boolean json) {
		if (value != null) {
			throw duplicateOption(option, commandName, json);
		}
	}

	private static void requireModeOptionAvailable(String currentMode, String token, String commandName, boolean json) {
		if (currentMode == null) {
			return;
		}

		String requestedMode = "--headless".equals(token) ? "headless" : "visible";
		if (currentMode.equals(requestedMode)) {
			throw duplicateOption(token, commandName, json);
		}

		throw new KemuCliException(
			CliErrorCodes.USAGE_ERROR, "Conflicting options: --headless and --visible.", commandName, json);
	}

	static StartOptions parseStartOptions(
		List<String> tokens,
		int startIndex,
		String commandName,
		boolean json,
		boolean requirePathAlreadyConsumed,
		boolean applyDefaults) {
		String mode = null;
		String runtime = null;
		Integer width = null;
		Integer height = null;
		for (int i = startIndex; i < tokens.size(); i++) {
			String token = tokens.get(i);
			if ("--headless".equals(token)) {
				requireModeOptionAvailable(mode, token, commandName, json);
				mode = "headless";
			} else if ("--visible".equals(token)) {
				requireModeOptionAvailable(mode, token, commandName, json);
				mode = "visible";
			} else if ("--runtime".equals(token)) {
				if (i + 1 >= tokens.size()) {
					throw usageError(commandName, json);
				}

				requireSingleAssignment(runtime, "--runtime", commandName, json);
				runtime = tokens.get(++i);
			} else if ("--size".equals(token)) {
				if (i + 1 >= tokens.size()) {
					throw usageError(commandName, json);
				}

				if (width != null || height != null) {
					throw duplicateOption("--size", commandName, json);
				}

				int[] size = CliParsing.parseSize(tokens.get(++i), commandName, json);
				width = Integer.valueOf(size[0]);
				height = Integer.valueOf(size[1]);
			} else {
				throw usageError(commandName, json);
			}
		}

		return new StartOptions(
			mode,
			runtime,
			applyDefaults && width == null ? Integer.valueOf(CliDefaults.DEFAULT_WIDTH) : width,
			applyDefaults && height == null ? Integer.valueOf(CliDefaults.DEFAULT_HEIGHT) : height);
	}
}
