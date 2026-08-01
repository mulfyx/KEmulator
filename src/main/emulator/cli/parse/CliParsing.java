package emulator.cli.parse;

import emulator.automation.shared.AutomationLimits;
import emulator.cli.core.CliErrorCodes;
import emulator.cli.core.KemuCliException;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class CliParsing {
	private CliParsing() {
	}

	public static int parseIntegerArgument(String value, String label, String commandName, boolean json) {
		try {
			return Integer.parseInt(value);
		} catch (NumberFormatException e) {
			throw new KemuCliException(
				CliErrorCodes.USAGE_ERROR,
				"Expected integer for " + label + ": " + value,
				commandName,
				json);
		}
	}

	public static long parseLongArgument(String value, String label, String commandName, boolean json) {
		try {
			long parsed = Long.parseLong(value);
			if (parsed < 0L) {
				throw new NumberFormatException();
			}

			return parsed;
		} catch (NumberFormatException e) {
			throw new KemuCliException(
				CliErrorCodes.USAGE_ERROR,
				"Expected non-negative integer for " + label + ": " + value,
				commandName,
				json);
		}
	}

	public static long parseRevision(String value, String commandName, boolean json) {
		return parseLongArgument(value, "--expect-revision", commandName, json);
	}

	public static int requireInclusiveRange(
		int value, int min, int max, String label, String commandName, boolean json) {
		if (value < min || value > max) {
			throw new KemuCliException(
				CliErrorCodes.USAGE_ERROR,
				"Expected " + label + " between " + min + " and " + max + ": " + value,
				commandName,
				json);
		}

		return value;
	}

	public static KemuCliException duplicateOption(String option, String commandName, boolean json) {
		return new KemuCliException(
			CliErrorCodes.USAGE_ERROR, "Duplicate option: " + option + '.', commandName, json);
	}

	/** Parses WIDTHxHEIGHT with the shared 1..MAX_DIMENSION bounds. */
	public static int[] parseSize(String value, String commandName, boolean json) {
		String[] parts = value.toLowerCase(java.util.Locale.US).split("x");
		if (parts.length != 2) {
			throw new KemuCliException(
				CliErrorCodes.USAGE_ERROR,
				"Expected WIDTHxHEIGHT: " + value,
				commandName,
				json);
		}

		int width = parseIntegerArgument(parts[0], "width", commandName, json);
		int height = parseIntegerArgument(parts[1], "height", commandName, json);
		requireInclusiveRange(width, 1, AutomationLimits.MAX_DIMENSION, "width", commandName, json);
		requireInclusiveRange(height, 1, AutomationLimits.MAX_DIMENSION, "height", commandName, json);

		return new int[]{width, height};
	}

	public static Path resolveUserPath(String value) {
		Path path = Paths.get(value);

		return path.toAbsolutePath().normalize();
	}
}
