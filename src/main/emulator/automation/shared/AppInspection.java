package emulator.automation.shared;

import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

public final class AppInspection {
	public final Path inputPath;
	public final Path launchPath;
	public final Path jarPath;
	public final Path jadPath;
	public final String sourceKind;
	public final String displayName;
	public final String vendor;
	public final String version;
	public final List<MidletDescriptor> midlets;
	public final Properties suiteProperties;

	public AppInspection(
		Path inputPath,
		Path launchPath,
		Path jarPath,
		Path jadPath,
		String sourceKind,
		String displayName,
		String vendor,
		String version,
		List<MidletDescriptor> midlets,
		Properties suiteProperties) {
		this.inputPath = inputPath;
		this.launchPath = launchPath;
		this.jarPath = jarPath;
		this.jadPath = jadPath;
		this.sourceKind = sourceKind;
		this.displayName = displayName;
		this.vendor = vendor;
		this.version = version;
		this.midlets = midlets;
		this.suiteProperties = suiteProperties;
	}
}
