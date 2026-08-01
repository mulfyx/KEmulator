package fixtures;

import emulator.Permission;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Form;
import javax.microedition.midlet.MIDlet;

/**
 * Requests an automation permission synchronously inside startApp() so the
 * CLI can prove that a worker blocked before readiness stays observable and
 * answerable.
 */
public final class StartupPermissionFixtureMidlet extends MIDlet {
	private boolean asked;

	protected void startApp() {
		if (asked) {
			return;
		}

		asked = true;
		String title;
		try {
			Permission.checkPermission("fixture.startup");
			title = "startup permission allowed";
		} catch (SecurityException denied) {
			title = "startup permission denied";
		}

		Display.getDisplay(this).setCurrent(new Form(title));
	}

	protected void pauseApp() {
	}

	protected void destroyApp(boolean unconditional) {
	}
}
