package fixtures;

import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Form;
import javax.microedition.midlet.MIDlet;
import javax.microedition.rms.RecordStore;

/**
 * Increments a counter persisted in RMS on every start and reports it in the
 * displayable title, so rms reset/export/import effects are observable.
 */
public final class RmsCounterFixtureMidlet extends MIDlet {
	private boolean started;

	protected void startApp() {
		if (started) {
			return;
		}

		started = true;
		String title;
		try {
			RecordStore store = RecordStore.openRecordStore("counter", true);
			try {
				int count = 0;
				if (store.getNumRecords() > 0) {
					count = Integer.parseInt(new String(store.getRecord(1), "UTF-8"));
				}
				count++;
				byte[] data = String.valueOf(count).getBytes("UTF-8");
				if (store.getNumRecords() > 0) {
					store.setRecord(1, data, 0, data.length);
				} else {
					store.addRecord(data, 0, data.length);
				}
				title = "RMS count " + count;
			} finally {
				store.closeRecordStore();
			}
		} catch (Exception failure) {
			title = "RMS error " + failure;
		}

		Display.getDisplay(this).setCurrent(new Form(title));
	}

	protected void pauseApp() {
	}

	protected void destroyApp(boolean unconditional) {
	}
}
