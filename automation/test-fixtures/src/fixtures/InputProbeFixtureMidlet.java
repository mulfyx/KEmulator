package fixtures;

import java.util.Vector;
import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Graphics;
import javax.microedition.midlet.MIDlet;

/**
 * Canvas that reports the set of currently held keys and the pointer state,
 * so half-stroke input (key down/up, pointer down/up) is observable:
 * "keys=[UP,LEFT] pointer=down@10,20".
 */
public final class InputProbeFixtureMidlet extends MIDlet {
	protected void startApp() {
		Display.getDisplay(this).setCurrent(new ProbeCanvas());
	}

	protected void pauseApp() {
	}

	protected void destroyApp(boolean unconditional) {
	}

	private static final class ProbeCanvas extends Canvas {
		private final Vector held = new Vector();
		private String pointer = "none";

		private ProbeCanvas() {
			updateTitle();
		}

		protected void paint(Graphics graphics) {
			graphics.setColor(0xFFFFFF);
			graphics.fillRect(0, 0, getWidth(), getHeight());
			graphics.setColor(0x000000);
			graphics.drawString(getTitle(), 4, 4, Graphics.LEFT | Graphics.TOP);
		}

		protected void keyPressed(int keyCode) {
			String name = name(keyCode);
			if (!held.contains(name)) {
				held.addElement(name);
			}

			updateTitle();
		}

		protected void keyReleased(int keyCode) {
			held.removeElement(name(keyCode));
			updateTitle();
		}

		protected void pointerPressed(int x, int y) {
			pointer = "down@" + x + "," + y;
			updateTitle();
		}

		protected void pointerReleased(int x, int y) {
			pointer = "up@" + x + "," + y;
			updateTitle();
		}

		private String name(int keyCode) {
			int action;
			try {
				action = getGameAction(keyCode);
			} catch (Exception ignored) {
				action = 0;
			}

			if (action == UP) {
				return "UP";
			}
			if (action == DOWN) {
				return "DOWN";
			}
			if (action == LEFT) {
				return "LEFT";
			}
			if (action == RIGHT) {
				return "RIGHT";
			}
			if (action == FIRE) {
				return "FIRE";
			}

			return String.valueOf(keyCode);
		}

		private void updateTitle() {
			StringBuffer keys = new StringBuffer();
			for (int i = 0; i < held.size(); i++) {
				if (i > 0) {
					keys.append(',');
				}

				keys.append((String) held.elementAt(i));
			}

			setTitle("keys=[" + keys.toString() + "] pointer=" + pointer);
			repaint();
		}
	}
}
