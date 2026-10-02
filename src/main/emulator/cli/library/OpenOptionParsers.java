package emulator.cli.library;

import emulator.cli.core.CliErrorCodes;
import emulator.cli.controller.ControllerLifecycle;
import emulator.cli.core.KemuCliException;
import emulator.cli.output.CliTextRenderer;
import emulator.cli.parse.CliParsing;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class OpenOptionParsers {
	private OpenOptionParsers() {
	}

	private static KemuCliException usageError(boolean json) {
		return new KemuCliException(CliErrorCodes.USAGE_ERROR, CliTextRenderer.usageText("open"), "open", json);
	}

	private static KemuCliException duplicateOption(String option, boolean json) {
		return new KemuCliException(
			CliErrorCodes.USAGE_ERROR, "Duplicate option: " + option + '.', "open", json);
	}

	private static void requireSingleAssignment(Object value, String option, boolean json) {
		if (value != null) {
			throw duplicateOption(option, json);
		}
	}

	static OpenOptions parse(List<String> tokens, boolean json) {
		Path inputPath = null;
		Integer midlet = null;
		Path dataDir = null;
		Path rmsDir = null;
		Path fileRoot = null;
		String workerXmx = null;
		boolean resetState = false;
		boolean resetFileRoot = false;
		ArrayList<String> startTokens = new ArrayList<String>();
		for (int i = 1; i < tokens.size(); i++) {
			String token = tokens.get(i);
			if ("--".equals(token)) {
				if (inputPath != null || i + 2 != tokens.size()) throw usageError(json);
				inputPath = CliParsing.resolveUserPath(tokens.get(++i));
			} else if (!token.startsWith("--")) {
				if (inputPath != null) throw usageError(json);
				inputPath = CliParsing.resolveUserPath(token);
			} else if ("--midlet".equals(token)) {
				if (i + 1 >= tokens.size()) {
					throw usageError(json);
				}

				requireSingleAssignment(midlet, "--midlet", json);
				midlet = Integer.valueOf(CliParsing.parseIntegerArgument(tokens.get(++i), "--midlet", "open", json));
			} else if ("--data-dir".equals(token)
				|| "--rms-dir".equals(token)
				|| "--file-root".equals(token)) {
				if (i + 1 >= tokens.size()) {
					throw usageError(json);
				}
				int valueIndex = i + 1;
				if ("--".equals(tokens.get(valueIndex))) {
					valueIndex++;
					if (valueIndex != tokens.size() - 1) throw usageError(json);
				} else if (tokens.get(valueIndex).startsWith("--")) {
					throw usageError(json);
				}
				Path value = CliParsing.resolveUserPath(tokens.get(valueIndex));
				i = valueIndex;
				if ("--data-dir".equals(token)) {
					requireSingleAssignment(dataDir, token, json);
					dataDir = value;
				} else if ("--rms-dir".equals(token)) {
					requireSingleAssignment(rmsDir, token, json);
					rmsDir = value;
				} else {
					requireSingleAssignment(fileRoot, token, json);
					fileRoot = value;
				}
			} else if ("--worker-xmx".equals(token)) {
				if (i + 1 >= tokens.size()) {
					throw usageError(json);
				}
				requireSingleAssignment(workerXmx, token, json);
				workerXmx = tokens.get(++i);
				if (!workerXmx.matches("[1-9][0-9]*[kKmMgG]")) {
					throw new KemuCliException(CliErrorCodes.USAGE_ERROR,
						"Invalid worker heap size: " + workerXmx, "open", json);
				}
			} else if ("--reset-state".equals(token)) {
				if (resetState) {
					throw duplicateOption(token, json);
				}
				resetState = true;
			} else if ("--reset-file-root".equals(token)) {
				if (resetFileRoot) {
					throw duplicateOption(token, json);
				}
				resetFileRoot = true;
			} else if ("--headless".equals(token) || "--visible".equals(token)) {
				startTokens.add(token);
			} else if ("--size".equals(token)) {
				if (i + 1 >= tokens.size()) {
					throw usageError(json);
				}
				startTokens.add(token);
				startTokens.add(tokens.get(++i));
			} else {
				throw usageError(json);
			}
		}

		if (inputPath == null) throw usageError(json);
		if (resetFileRoot && !resetState) {
			throw new KemuCliException(
				CliErrorCodes.USAGE_ERROR,
				"--reset-file-root requires --reset-state.",
				"open",
				json);
		}
		if (resetFileRoot && fileRoot == null) {
			throw new KemuCliException(
				CliErrorCodes.USAGE_ERROR,
				"--reset-file-root requires an explicit --file-root.",
				"open",
				json);
		}

		return new OpenOptions(
			inputPath,
			midlet,
			ControllerLifecycle.parseStartOptions(
				startTokens, 0, "open", json, true, false),
			dataDir,
			rmsDir,
			fileRoot,
			resetState,
			resetFileRoot,
			true,
			null,
			workerXmx);
	}
}
