package emulator.automation.worker;

import emulator.automation.shared.AutomationErrorCodes;
import emulator.automation.shared.AutomationException;
import mjson.Json;

/**
 * The single optimistic-revision guard. {@code expectRevision} is optional
 * for every mutation; when present and stale the mutation fails with
 * STALE_REVISION and details {expectRevision, currentRevision}.
 */
final class RevisionGuard {
	private RevisionGuard() {
	}

	static AutomationException stale(long expected, long current) {
		return new AutomationException(
			AutomationErrorCodes.STALE_REVISION,
			"Stale revision: " + expected + ", current: " + current,
			Json.object().set("expectRevision", expected).set("currentRevision", current));
	}

	static Long expected(Json request) {
		if (!request.has("expectRevision") || request.at("expectRevision").isNull()) {
			return null;
		}

		return Long.valueOf(request.at("expectRevision").asLong());
	}

	static void check(Json request) {
		Long expected = expected(request);
		if (expected == null) {
			return;
		}

		long current = WorkerEventModel.revision();
		if (expected.longValue() != current) {
			throw stale(expected.longValue(), current);
		}
	}
}
