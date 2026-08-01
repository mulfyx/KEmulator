package fixtures;

import java.io.OutputStream;
import javax.microedition.io.Connector;
import javax.microedition.io.file.FileConnection;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Form;
import javax.microedition.midlet.MIDlet;

/**
 * Writes a probe file to the JSR-75 URL supplied via the Fixture-File-Url
 * app property ("memorycard" resolves fileconn.dir.memorycard) and reports
 * the outcome in the displayable title.
 */
public final class FileProbeFixtureMidlet extends MIDlet {
	private boolean started;

	protected void startApp() {
		if (started) {
			return;
		}

		started = true;
		Display.getDisplay(this).setCurrent(new Form("Probing"));
		new Thread(new Runnable() {
			public void run() {
				String title;
				try {
					String url = getAppProperty("Fixture-File-Url");
					if (url == null || url.trim().length() == 0) {
						url = "memorycard";
					}
					url = url.trim();
					if (url.equals("memorycard")) {
						url = System.getProperty("fileconn.dir.memorycard") + "probe.txt";
					}
					FileConnection connection = (FileConnection) Connector.open(url, Connector.READ_WRITE);
					try {
						if (!connection.exists()) {
							connection.create();
						}
						OutputStream out = connection.openOutputStream();
						out.write("probe".getBytes("UTF-8"));
						out.close();
					} finally {
						connection.close();
					}
					title = "WROTE " + url;
				} catch (SecurityException denied) {
					title = "WRITE DENIED";
				} catch (Exception failure) {
					title = "WRITE FAILED " + failure;
				}

				Display.getDisplay(FileProbeFixtureMidlet.this).setCurrent(new Form(title));
			}
		}).start();
	}

	protected void pauseApp() {
	}

	protected void destroyApp(boolean unconditional) {
	}
}
