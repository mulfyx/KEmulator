package emulator.cli.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import emulator.automation.shared.OperationDeadline;
import mjson.Json;

public final class CliInvocation {
	private final List<String> tokens;
	private final boolean json;
	private final boolean verbose;
	private final Long timeoutMs;

	public CliInvocation(List<String> tokens, boolean json) {
		this(tokens, json, false, null);
	}

	public CliInvocation(List<String> tokens, boolean json, boolean verbose, Long timeoutMs) {
		this.tokens = Collections.unmodifiableList(new ArrayList<String>(tokens));
		this.json = json;
		this.verbose = verbose;
		this.timeoutMs = timeoutMs;
	}

	public List<String> tokens() {
		return tokens;
	}

	public boolean json() {
		return json;
	}

	public boolean verbose() { return verbose; }
	public long timeoutMs(long defaultMs) { return timeoutMs == null ? defaultMs : timeoutMs.longValue(); }
	public OperationDeadline deadline(long defaultMs) { return OperationDeadline.afterMillis(timeoutMs(defaultMs)); }
	public Json request(long defaultMs) { return Json.object().set("timeoutMs", timeoutMs(defaultMs)); }
}
