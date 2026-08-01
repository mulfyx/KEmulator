package emulator.cli.library;

import emulator.cli.controller.StartOptions;
import java.nio.file.Path;

public final class OpenOptions {
	public final Path inputPath;
	public final Integer midletIndex;
	public final StartOptions startOptions;
	public final Path dataDir;
	public final Path rmsDir;
	public final Path fileRoot;
	public final boolean resetState;
	public final boolean resetFileRoot;
	public final boolean waitReady;
	public final Integer openTimeoutMs;
	public final String workerXmx;

	public OpenOptions(
		Path inputPath,
		Integer midletIndex,
		StartOptions startOptions,
		Path dataDir,
		Path rmsDir,
		Path fileRoot,
		boolean resetState,
		boolean resetFileRoot,
		boolean waitReady,
		Integer openTimeoutMs,
		String workerXmx) {
		this.inputPath = inputPath;
		this.midletIndex = midletIndex;
		this.startOptions = startOptions;
		this.dataDir = dataDir;
		this.rmsDir = rmsDir;
		this.fileRoot = fileRoot;
		this.resetState = resetState;
		this.resetFileRoot = resetFileRoot;
		this.waitReady = waitReady;
		this.openTimeoutMs = openTimeoutMs;
		this.workerXmx = workerXmx;
	}
}
