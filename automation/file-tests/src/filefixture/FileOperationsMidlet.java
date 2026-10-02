package filefixture;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import javax.microedition.io.Connector;
import javax.microedition.io.file.FileConnection;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Form;
import javax.microedition.midlet.MIDlet;

/** Public JSR-75 operations, including files larger than the worker heap. */
public final class FileOperationsMidlet extends MIDlet {
	private static final int LARGE_SIZE = 20 * 1024 * 1024;
	private String root;
	private boolean started;

	protected void startApp() {
		if (started) return;
		started = true;
		root = System.getProperty("fileconn.dir.memorycard");
		Display.getDisplay(this).setCurrent(new Form("RUNNING"));
		new Thread(new Runnable() {
			public void run() {
				String scenario = getAppProperty("File-Test-Scenario");
				String result;
				try {
					if ("position".equals(scenario)) position();
					else if ("large-position".equals(scenario)) largePosition();
					else if ("large-truncate".equals(scenario)) largeTruncate();
					else if ("negative".equals(scenario)) negative();
					else if ("rename".equals(scenario)) rename();
					else if ("escaped".equals(scenario)) escaped();
					else throw new IOException("Unknown scenario " + scenario);
					result = "PASS " + scenario;
				} catch (Throwable failure) {
					failure.printStackTrace();
					result = "FAIL " + scenario + " " + failure;
				}
				System.out.println("FILE_TEST " + result);
				Display.getDisplay(FileOperationsMidlet.this).setCurrent(new Form(result));
			}
		}).start();
	}

	private FileConnection open(String name) throws IOException {
		return (FileConnection) Connector.open(root + name, Connector.READ_WRITE);
	}

	private FileConnection create(String name) throws IOException {
		FileConnection connection = open(name);
		connection.create();
		return connection;
	}

	private void require(boolean value, String message) throws IOException {
		if (!value) throw new IOException(message);
	}

	private void write(FileConnection connection, String value) throws IOException {
		OutputStream output = connection.openOutputStream();
		try { output.write(value.getBytes("UTF-8")); }
		finally { output.close(); }
	}

	private void expect(FileConnection connection, String value) throws IOException {
		byte[] expected = value.getBytes("UTF-8");
		InputStream input = connection.openInputStream();
		try {
			for (int i = 0; i < expected.length; i++)
				require(input.read() == (expected[i] & 255), "Unexpected byte at " + i);
			require(input.read() == -1, "Unexpected file tail");
		} finally { input.close(); }
	}

	private void position() throws IOException {
		FileConnection connection = create("position.bin");
		try {
			write(connection, "abcdef");
			OutputStream output = connection.openOutputStream(2);
			output.write(new byte[] {'X', 'Y'});
			output.close();
			expect(connection, "abXYef");
			output = connection.openOutputStream(Long.MAX_VALUE);
			output.write('!');
			output.close();
			expect(connection, "abXYef!");
			output = connection.openOutputStream(0);
			output.write('A');
			output.close();
			expect(connection, "AbXYef!");
		} finally { connection.close(); }
	}

	private void fill(FileConnection connection) throws IOException {
		byte[] block = new byte[8192];
		OutputStream output = connection.openOutputStream();
		try {
			for (int start = 0; start < LARGE_SIZE; start += block.length) {
				for (int i = 0; i < block.length; i++) block[i] = (byte) ((start + i) % 251);
				output.write(block);
			}
		} finally { output.close(); }
	}

	private void verifyPattern(FileConnection connection, int length, int changedAt) throws IOException {
		require(connection.fileSize() == length, "Unexpected file size " + connection.fileSize());
		InputStream input = connection.openInputStream();
		byte[] block = new byte[8192];
		int position = 0;
		try {
			int count;
			while ((count = input.read(block)) != -1) {
				for (int i = 0; i < count; i++) {
					int at = position + i;
					int expected = at == changedAt ? 255 : at % 251;
					if ((block[i] & 255) != expected) throw new IOException("Wrong pattern at " + at);
				}
				position += count;
			}
			require(position == length, "Unexpected input length " + position);
		} finally { input.close(); }
	}

	private void largePosition() throws IOException {
		FileConnection connection = create("large-position.bin");
		try {
			fill(connection);
			int offset = 18 * 1024 * 1024;
			OutputStream output = connection.openOutputStream(offset);
			output.write(255);
			output.flush();
			require(connection.fileSize() == LARGE_SIZE, "Seek write lost file tail");
			output.close();
			verifyPattern(connection, LARGE_SIZE, offset);
		} finally { connection.close(); }
	}

	private void largeTruncate() throws IOException {
		FileConnection connection = create("large-truncate.bin");
		try {
			fill(connection);
			connection.truncate(Long.MAX_VALUE);
			require(connection.fileSize() == LARGE_SIZE, "Truncate extended file");
			connection.truncate(18 * 1024 * 1024);
			verifyPattern(connection, 18 * 1024 * 1024, -1);
			connection.truncate(0);
			require(connection.fileSize() == 0, "Truncate to zero failed");
		} finally { connection.close(); }
	}

	private void negative() throws IOException {
		FileConnection connection = create("negative.bin");
		try {
			write(connection, "unchanged");
			try { connection.openOutputStream(-1); throw new IOException("Negative offset accepted"); }
			catch (IllegalArgumentException expected) { }
			try { connection.truncate(-1); throw new IOException("Negative truncate accepted"); }
			catch (IllegalArgumentException expected) { }
			expect(connection, "unchanged");
		} finally { connection.close(); }
	}

	private void rename() throws IOException {
		FileConnection source = create("source.bin");
		FileConnection occupied = create("occupied.bin");
		try {
			write(source, "source");
			write(occupied, "target");
			boolean rejected = false;
			try { source.rename("occupied.bin"); }
			catch (IOException expected) { rejected = true; }
			require(rejected, "Occupied rename accepted");
			expect(source, "source");
			expect(occupied, "target");
			rejected = false;
			try { source.rename("source.bin"); }
			catch (IOException expected) { rejected = true; }
			require(rejected, "Rename to same existing name accepted");
			String[] invalid = {"../outside.bin", "sub/file.bin", "sub\\file.bin", ".", ".."};
			for (int i = 0; i < invalid.length; i++) {
				rejected = false;
				try { source.rename(invalid[i]); }
				catch (IllegalArgumentException expected) { rejected = true; }
				require(rejected, "Path rename accepted " + invalid[i]);
			}
			String[] invalidNames = {"", "null\0name"};
			for (int i = 0; i < invalidNames.length; i++) {
				rejected = false;
				try { source.rename(invalidNames[i]); }
				catch (IOException expected) { rejected = true; }
				require(rejected, "Invalid filename accepted");
			}
			source.rename("renamed + \u0444\u0430\u0439\u043b.bin");
			require(source.exists(), "Renamed connection lost target");
			require(source.getName().equals("renamed + \u0444\u0430\u0439\u043b.bin"), "Raw rename name changed");
			source.rename("renamed%20%2B%20%D1%84%D0%B0%D0%B9%D0%BB%25.bin");
			require(source.getName().equals("renamed + \u0444\u0430\u0439\u043b%.bin"), "Escaped rename kept URL escapes: " + source.getName());
			String[] escapedPaths = {"sub%2Ffile.bin", "sub%5Cfile.bin", "%2E", "%2E%2E", "%2E%2E%2Foutside.bin"};
			for (int i = 0; i < escapedPaths.length; i++) {
				rejected = false;
				try { source.rename(escapedPaths[i]); }
				catch (IllegalArgumentException expected) { rejected = true; }
				require(rejected, "Escaped path rename accepted " + escapedPaths[i]);
			}
			FileConnection reopened = (FileConnection) Connector.open(source.getURL());
			try { expect(reopened, "source"); }
			finally { reopened.close(); }
			FileConnection old = open("source.bin");
			try { require(!old.exists(), "Old rename target still exists"); }
			finally { old.close(); }
			expect(source, "source");
			OutputStream output = source.openOutputStream(6);
			output.write('!');
			output.close();
			expect(source, "source!");
		} finally { source.close(); occupied.close(); }
		FileConnection missing = open("missing.bin");
		try {
			boolean rejected = false;
			try { missing.rename("missing-renamed.bin"); }
			catch (IOException expected) { rejected = true; }
			require(rejected, "Missing source rename accepted");
		} finally { missing.close(); }
		FileConnection directory = open("before/");
		try {
			directory.mkdir();
			directory.rename("after");
			require(directory.exists() && directory.isDirectory(), "Renamed directory lost target");
			require(directory.getURL().equals(root + "after/"), "Stale directory URL");
			FileConnection reopened = (FileConnection) Connector.open(directory.getURL());
			try { require(reopened.exists() && reopened.isDirectory(), "Renamed directory URL not reusable"); }
			finally { reopened.close(); }
		} finally { directory.close(); }
	}

	private void escaped() throws IOException {
		FileConnection raw = create("space \u0444\u0430\u0439\u043b+name.bin");
		try {
			write(raw, "unicode");
			FileConnection escaped = open("space%20%D1%84%D0%B0%D0%B9%D0%BB+name.bin");
			try {
				require(escaped.exists(), "Escaped URL opened a different file");
				require(escaped.getName().equals("space \u0444\u0430\u0439\u043b+name.bin"), "URL plus or UTF-8 decoded incorrectly");
				expect(escaped, "unicode");
			} finally { escaped.close(); }
		} finally { raw.close(); }
		FileConnection percent = create("percent%25.bin");
		try {
			write(percent, "percent");
			require(percent.getName().equals("percent%.bin"), "Percent escape decoded incorrectly");
		} finally { percent.close(); }
		FileConnection localhost = create("localhost.txt");
		try {
			require(localhost.getName().equals("localhost.txt"), "localhost basename changed");
			write(localhost, "localhost");
			FileConnection withHost = (FileConnection) Connector.open("file://localhost/root/e/localhost.txt");
			try { expect(withHost, "localhost"); }
			finally { withHost.close(); }
		} finally { localhost.close(); }
	}

	protected void pauseApp() { }
	protected void destroyApp(boolean unconditional) { }
}
