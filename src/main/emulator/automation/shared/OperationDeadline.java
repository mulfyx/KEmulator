package emulator.automation.shared;

import java.util.concurrent.TimeUnit;
import mjson.Json;

/** A monotonic budget for one local part of an automation operation. */
public final class OperationDeadline {
	private final long startedNanos;
	private final long deadlineNanos;
	private final long timeoutMs;

	private OperationDeadline(long timeoutMs) {
		if (timeoutMs < 0L || timeoutMs > AutomationLimits.MAX_OPEN_TIMEOUT_MS) {
			throw new AutomationException(
				AutomationErrorCodes.INVALID_REQUEST,
				"timeoutMs must be between 0 and " + AutomationLimits.MAX_OPEN_TIMEOUT_MS);
		}
		this.timeoutMs = timeoutMs;
		startedNanos = System.nanoTime();
		deadlineNanos = startedNanos + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
	}

	public static OperationDeadline afterMillis(long timeoutMs) {
		return new OperationDeadline(timeoutMs);
	}

	public static OperationDeadline fromRequest(Json request, long defaultMs) {
		return afterMillis(request == null ? defaultMs : request.at("timeoutMs", defaultMs).asLong());
	}

	public long remainingMillis() {
		long remaining = deadlineNanos - System.nanoTime();
		// Round up: a positive sub-millisecond remainder must not become an
		// infinite socket timeout or an accidentally exhausted queue budget.
		return remaining <= 0L ? 0L : 1L + TimeUnit.NANOSECONDS.toMillis(remaining - 1L);
	}

	public boolean timedOut() {
		return System.nanoTime() >= deadlineNanos;
	}

	public long elapsedMillis() {
		return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
	}

	public long timeoutMillis() {
		return timeoutMs;
	}

	public Json withRemaining(Json request) {
		return (request == null ? Json.object() : request.dup()).set("timeoutMs", remainingMillis());
	}
}
