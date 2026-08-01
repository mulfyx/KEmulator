package fixtures;

import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Form;
import javax.microedition.midlet.MIDlet;

/**
 * Reports MIDlet lifecycle callbacks in the displayable title, so
 * `kemu pause` / `kemu resume` have observable evidence:
 * "lifecycle started=N paused=M".
 */
public final class LifecycleFixtureMidlet extends MIDlet {
	private final Form form = new Form("lifecycle started=0 paused=0");
	private int started;
	private int paused;

	protected void startApp() {
		started++;
		updateTitle();
		Display.getDisplay(this).setCurrent(form);
	}

	protected void pauseApp() {
		paused++;
		updateTitle();
	}

	protected void destroyApp(boolean unconditional) {
	}

	private void updateTitle() {
		form.setTitle("lifecycle started=" + started + " paused=" + paused);
	}
}
