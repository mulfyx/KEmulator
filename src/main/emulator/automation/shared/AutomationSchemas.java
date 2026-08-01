package emulator.automation.shared;

/** Schema versions of the automation payloads, declared exactly once. */
public final class AutomationSchemas {
	/** Session snapshot / observe schema ({@code schemaVersion}). */
	public static final int SNAPSHOT_VERSION = 3;

	/** Structured event stream schema ({@code events read}, JSONL lines). */
	public static final int EVENTS_VERSION = 2;

	private AutomationSchemas() {
	}
}
