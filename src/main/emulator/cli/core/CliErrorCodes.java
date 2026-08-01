package emulator.cli.core;

/**
 * Machine-readable error codes emitted by the CLI layer itself. Codes shared
 * with the controller/worker live in
 * {@link emulator.automation.shared.AutomationErrorCodes}; bootstrap-only
 * codes emitted by kemu.sh are documented in CliAutomation.md.
 */
public final class CliErrorCodes {
	public static final String USAGE_ERROR = "USAGE_ERROR";
	public static final String UNKNOWN_COMMAND = "UNKNOWN_COMMAND";
	public static final String INTERNAL_ERROR = "INTERNAL_ERROR";
	public static final String STORAGE_ERROR = "STORAGE_ERROR";
	public static final String APP_ACTIVE = "APP_ACTIVE";
	public static final String START_FAILED = "START_FAILED";
	public static final String START_TIMEOUT = "START_TIMEOUT";
	public static final String STOP_FAILED = "STOP_FAILED";
	public static final String CONTROLLER_NOT_RUNNING = "CONTROLLER_NOT_RUNNING";
	public static final String CONFLICTING_CONTROLLER_DEFAULTS = "CONFLICTING_CONTROLLER_DEFAULTS";
	public static final String NO_RUNTIME = "NO_RUNTIME";
	public static final String UNKNOWN_RUNTIME = "UNKNOWN_RUNTIME";
	public static final String AMBIGUOUS_RUNTIME = "AMBIGUOUS_RUNTIME";
	public static final String DISPLAY_REQUIRED = "DISPLAY_REQUIRED";
	public static final String HEADLESS_DEPENDENCY_MISSING = "HEADLESS_DEPENDENCY_MISSING";
	public static final String HEADLESS_UNSUPPORTED = "HEADLESS_UNSUPPORTED";
	public static final String MISSING_SWT = "MISSING_SWT";
	public static final String SCREENSHOT_WRITE_FAILED = "SCREENSHOT_WRITE_FAILED";

	private CliErrorCodes() {
	}
}
