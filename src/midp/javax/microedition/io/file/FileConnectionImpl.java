package javax.microedition.io.file;

import emulator.Emulator;

import java.io.*;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.Enumeration;

public class FileConnectionImpl implements FileConnection {
	private String origUrl;
	private String systemPath;
	private File file;
	private boolean closed;
	private static String aString441;
	private boolean aBoolean440;
	private String aString442;

	public FileConnectionImpl(String url) throws IOException {
		Emulator.getEmulator().getLogStream().println("File opened: " + url);
		if (url.length() > 300) {
			throw new IOException("Path too long");
		}
		this.origUrl = url;
		this.closed = false;
		final File file;
		if (!(file = new File(FileConnectionImpl.aString441)).exists() || file.isFile()) {
			file.mkdirs();
		}
		try {
			// File URLs use percent escapes, not form encoding: '+' is a filename character.
			url = URLDecoder.decode(url.replace("+", "%2B"), "UTF-8");
		} catch (IllegalArgumentException invalidEscape) {
			throw new IOException("Invalid file URL escape", invalidEscape);
		}
		url = method216(method216(method216(url.replaceFirst("^file://localhost/", "file:///").substring("file://".length()), "c"), "d"), "e");
		if (url.startsWith("/"))
			url = url.substring(1);
		if (url.equals("root")) {
			url = "";
		} else if (url.startsWith("root/")) {
			url = url.substring("root/".length());
		}
		this.systemPath = new File(FileConnectionImpl.aString441, url).getPath();
		//method134(this.aString439 = Emulator.getAbsolutePath() + "/file/" + aString314);
		this.file = new File(this.systemPath);
	}

	private static void method134(final String s) {
		final int length = (Emulator.getUserPath() + "/file/").length();
		String s2 = s;
		String s3 = "/";
		int n = length;
		int index;
		while ((index = s2.indexOf(s3, n)) >= 0 && index >= length) {
			final File file;
			if (!(file = new File(s.substring(0, index))).exists() && new File(s).isFile()) {
				file.mkdirs();
			}
			s2 = s;
			s3 = "/";
			n = index + 1;
		}
	}

	private static String method216(final String s, final String s2) {
		String replaceFirst = s;
		final String upper = s2.toUpperCase(java.util.Locale.US);
		// Drive URLs are case-insensitive on handsets: file:///E:/ and
		// file:///e:/ must resolve to the same host directory.
		if (s.contains(s2 + ":") || s.contains(upper + ":")) {
			final File file;
			if (!(file = new File(FileConnectionImpl.aString441, s2)).exists() || file.isFile()) {
				file.mkdirs();
			}
			replaceFirst = s.replaceFirst("[" + s2 + upper + "]:", s2);
		}
		return replaceFirst;
	}

	public long availableSize() {
		if (file == null) {
			return 1000000000L;
		}
		return file.getFreeSpace();
	}

	public boolean canRead() {
		return true;
	}

	public boolean canWrite() {
		return true;
	}

	public void create() throws IOException {
		if (this.closed) {
			throw new ConnectionClosedException();
		}
		if (this.file.exists() || this.file.isDirectory()) {
			throw new IOException("File already exists");
		}
		if (!this.file.getParentFile().exists())
			this.file.getParentFile().mkdirs();
		this.file.createNewFile();
	}

	public void delete() throws IOException {
		if (this.closed) {
			throw new ConnectionClosedException();
		}
		if (!this.file.exists() || this.file.isDirectory()) {
			throw new IOException();
		}
		this.file.delete();
	}

	public long directorySize(final boolean b) throws IOException {
		if (this.file.isFile()) {
			throw new IOException();
		}
		if (this.closed) {
			throw new ConnectionClosedException();
		}
		return this.method217(b);
	}

	private int method217(final boolean b) {
		int n = 0;
		if (this.file.isDirectory()) {
			final File[] listFiles = this.file.listFiles();
			for (int i = 0; i < listFiles.length; ++i) {
				int n2;
				if (listFiles[i].isFile()) {
					n2 = (int) (n + listFiles[i].length());
				} else {
					if (!b) {
						continue;
					}
					n2 = n + this.method217(true);
				}
				n = n2;
			}
		}
		return n;
	}

	public boolean exists() {
		if (this.closed) {
			throw new ConnectionClosedException();
		}
		return this.file.exists();
	}

	public long fileSize() throws IOException {
		if (this.file.isDirectory()) {
			throw new IOException();
		}
		if (this.closed) {
			throw new ConnectionClosedException();
		}
		return this.file.length();
	}

	public String getName() {
		return this.file.getName();
	}

	public String getPath() {
		return this.origUrl.replaceFirst("localhost", "").substring("file://".length());
	}

	public String getURL() {
		return this.origUrl;
	}

	public boolean isDirectory() {
		if (this.closed) {
			throw new ConnectionClosedException();
		}
		return this.file.isDirectory();
	}

	public boolean isHidden() {
		if (this.closed) {
			throw new ConnectionClosedException();
		}
		return this.file.isHidden();
	}

	public boolean isOpen() {
		return !this.closed;
	}

	public long lastModified() {
		if (this.closed) {
			throw new ConnectionClosedException();
		}
		return this.file.lastModified();
	}

	public Enumeration list() throws IOException {
		if (this.closed) {
			throw new ConnectionClosedException();
		}
		if (!this.file.exists() || this.file.isFile()) {
			throw new IOException();
		}
		final File[] listFiles;
		if ((listFiles = this.file.listFiles()) != null) {
			return new FileConnectionListEn2(this, listFiles);
		}
		return null;
	}

	public Enumeration list(final String s, final boolean b) throws IOException {
		if (this.closed) {
			throw new ConnectionClosedException();
		}
		if (!this.file.exists() || this.file.isFile()) {
			throw new IOException();
		}
		final File[] listFiles = this.file.listFiles(new FileFilterr(s, b));
		if (listFiles != null) {
			return new FileConnectionListEn(this, listFiles);
		}
		return null;
	}

	public void mkdir() throws IOException {
		if (this.closed) {
			throw new ConnectionClosedException();
		}
		if (this.file.exists() || this.file.isFile()) {
			throw new IOException();
		}
		this.file.mkdir();
	}

	public DataInputStream openDataInputStream() throws IOException {
		if (!this.file.exists() || this.file.isDirectory()) {
			throw new IOException();
		}
		return new DataInputStream(new FileInputStream(this.file));
	}

	public DataOutputStream openDataOutputStream() throws IOException {
		if (!this.file.exists() || this.file.isDirectory()) {
			throw new IOException(!this.file.exists() ? "File doesn't exist" : "No access to file");
		}
		return new DataOutputStream(new FileOutputStream(this.file));
	}

	public InputStream openInputStream() throws IOException {
		if (!this.file.exists() || this.file.isDirectory()) {
			throw new IOException();
		}
		return new FileInputStream(this.file);
	}

	public OutputStream openOutputStream() throws IOException {
		if (!this.file.exists() || this.file.isDirectory()) {
			throw new IOException();
		}
		return new FileOutputStream(this.file);
	}

	public OutputStream openOutputStream(final long n) throws IOException {
		if (n < 0) {
			throw new IllegalArgumentException("Negative byte offset");
		}
		if (!this.file.exists() || this.file.isDirectory()) {
			throw new IOException();
		}
		final RandomAccessFile output = new RandomAccessFile(this.file, "rw");
		try {
			output.seek(Math.min(n, output.length()));
		} catch (IOException failure) {
			output.close();
			throw failure;
		}
		// Seek without copying or truncating the existing prefix and tail.
		return new OutputStream() {
			public void write(int value) throws IOException {
				output.write(value);
			}

			public void write(byte[] bytes, int offset, int length) throws IOException {
				output.write(bytes, offset, length);
			}

			public void close() throws IOException {
				output.close();
			}
		};
	}

	public void rename(final String s) throws IOException {
		if (s == null) {
			throw new NullPointerException();
		}
		if (this.closed) {
			throw new ConnectionClosedException();
		}
		String name;
		try {
			// JSR-75 accepts escaped and unescaped URI parts, including rename names.
			name = URLDecoder.decode(s.replace("+", "%2B"), "UTF-8");
		} catch (IllegalArgumentException invalidEscape) {
			throw new IOException("Invalid filename escape", invalidEscape);
		}
		if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.equals(".") || name.equals("..")) {
			throw new IllegalArgumentException("Rename must stay in the same directory");
		}
		if (name.length() == 0 || name.indexOf('\0') >= 0) {
			throw new IOException("Invalid filename");
		}
		boolean directory = this.file.isDirectory();
		File renamed = new File(this.file.getParentFile(), name);
		if (Files.exists(renamed.toPath(), LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Rename target already exists");
		}
		// No REPLACE_EXISTING: an occupied destination must leave both files intact.
		Files.move(this.file.toPath(), renamed.toPath());
		String previousUrl = this.origUrl;
		if (previousUrl.endsWith("/")) {
			previousUrl = previousUrl.substring(0, previousUrl.length() - 1);
		}
		this.origUrl = previousUrl.substring(0, previousUrl.lastIndexOf('/') + 1)
				+ URLEncoder.encode(name, "UTF-8").replace("+", "%20") + (directory ? "/" : "");
		this.file = renamed;
		this.systemPath = renamed.getPath();
	}

	public void setFileConnection(final String s) throws IOException {
		if (s == null) {
			throw new NullPointerException();
		}
		if (this.closed) {
			throw new ConnectionClosedException();
		}
		final File file;
		if (!(file = new File(this.file + "//" + s)).exists()) {
			throw new IllegalArgumentException();
		}
		this.origUrl = "file://" + file.getAbsolutePath().substring(FileConnectionImpl.aString441.length());
		this.closed = false;
		this.systemPath = file.getAbsolutePath();
		this.file = new File(this.systemPath);
	}

	public void setHidden(final boolean b) throws IOException {
		if (this.closed) {
			throw new ConnectionClosedException();
		}
		if (!this.file.exists()) {
			throw new IOException();
		}
	}

	public void setReadable(final boolean b) throws IOException {
	}

	public void setWritable(final boolean b) throws IOException {
	}

	public long totalSize() {
		return 100000000L;
	}

	public void truncate(final long n) throws IOException {
		if (this.closed) {
			throw new ConnectionClosedException();
		}
		if (n < 0) {
			throw new IllegalArgumentException("Negative byte offset");
		}
		if (!this.file.exists() || this.file.isDirectory()) {
			throw new IOException();
		}
		RandomAccessFile output = new RandomAccessFile(this.file, "rw");
		try {
			if (n < output.length()) {
				output.setLength(n);
			}
		} finally {
			output.close();
		}
	}

	public long usedSize() {
		return 1000000L;
	}

	public void close() throws IOException {
		this.closed = true;
	}

	static {
		String configuredRoot = System.getProperty("kemu.file.root");
		FileConnectionImpl.aString441 =
			configuredRoot != null && configuredRoot.trim().length() > 0
				? new File(configuredRoot).getAbsolutePath()
				: Emulator.getUserPath() + "/file/" + "root/";
	}

	public String getRealPath() {
		return file.toString();
	}

	public File getFile() {
		return file;
	}
}
