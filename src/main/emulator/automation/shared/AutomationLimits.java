package emulator.automation.shared;

/**
 * Single source of truth for every numeric limit and default of the
 * automation contract. The CLI rejects statically known violations as
 * USAGE_ERROR; the worker/controller re-validate the same constants as
 * INVALID_REQUEST.
 */
public final class AutomationLimits {
	public static final int MAX_WAIT_MS = 120000;
	public static final int DEFAULT_TIMEOUT_MS = 5000;

	public static final int MIN_KEY_DURATION_MS = 10;
	public static final int MAX_KEY_DURATION_MS = 5000;
	public static final int DEFAULT_KEY_PRESS_DURATION_MS = 80;
	public static final int DEFAULT_KEY_HOLD_DURATION_MS = 500;

	public static final int MIN_DRAG_DELAY_MS = 5;
	public static final int MAX_DRAG_DELAY_MS = 1000;
	public static final int DEFAULT_DRAG_DELAY_MS = 20;

	// EventQueue.sizeChanged() packs each dimension into 12 bits.
	public static final int MAX_DIMENSION = 4095;

	public static final int MIN_OPEN_TIMEOUT_MS = 1;
	public static final int MAX_OPEN_TIMEOUT_MS = 600000;
	public static final int DEFAULT_OPEN_TIMEOUT_MS = 30000;

	private AutomationLimits() {
	}
}
