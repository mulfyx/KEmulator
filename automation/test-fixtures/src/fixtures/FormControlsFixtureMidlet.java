package fixtures;

import javax.microedition.lcdui.Choice;
import javax.microedition.lcdui.ChoiceGroup;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.DateField;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Form;
import javax.microedition.lcdui.Gauge;
import javax.microedition.lcdui.Item;
import javax.microedition.lcdui.ItemStateListener;
import javax.microedition.lcdui.List;
import javax.microedition.lcdui.StringItem;
import javax.microedition.lcdui.TextField;
import javax.microedition.midlet.MIDlet;

/**
 * A Form with every automation-controllable item kind plus a switchable List,
 * used to exercise choice/gauge/text-field/list CLI commands end to end.
 */
public final class FormControlsFixtureMidlet extends MIDlet
	implements CommandListener, ItemStateListener {
	private final Command toListCommand = new Command("To list", Command.SCREEN, 1);
	private final Command backCommand = new Command("Back", Command.BACK, 2);

	private Form form;
	private List list;
	private StringItem status;
	private Gauge gauge;
	private ChoiceGroup choice;
	private TextField field;
	private DateField when;

	protected void startApp() {
		if (form == null) {
			form = new Form("Controls form");
			status = new StringItem("Status", "idle");
			gauge = new Gauge("Level", true, 10, 2);
			choice = new ChoiceGroup("Mode", Choice.EXCLUSIVE);
			choice.append("alpha", null);
			choice.append("beta", null);
			choice.append("gamma", null);
			field = new TextField("Name", "abc", 32, 0);
			when = new DateField("When", DateField.DATE_TIME);
			when.setDate(new java.util.Date(0L));
			form.append(status);
			form.append(gauge);
			form.append(choice);
			form.append(field);
			form.append(when);
			form.addCommand(toListCommand);
			form.setCommandListener(this);
			form.setItemStateListener(this);
		}

		Display.getDisplay(this).setCurrent(form);
	}

	protected void pauseApp() {
	}

	protected void destroyApp(boolean unconditional) {
	}

	public void itemStateChanged(Item item) {
		if (item == gauge) {
			status.setText("gauge=" + gauge.getValue());
		} else if (item == choice) {
			status.setText("choice=" + choice.getString(choice.getSelectedIndex()));
		} else if (item == field) {
			status.setText("field=" + field.getString());
		} else if (item == when) {
			status.setText("when=" + (when.getDate() == null ? "null" : String.valueOf(when.getDate().getTime())));
		}
	}

	public void commandAction(Command command, Displayable displayable) {
		if (command == toListCommand) {
			if (list == null) {
				list = new List("Pick list", List.IMPLICIT);
				list.append("one", null);
				list.append("two", null);
				list.append("three", null);
				list.addCommand(backCommand);
				list.setCommandListener(this);
			}

			Display.getDisplay(this).setCurrent(list);

			return;
		}

		if (command == backCommand) {
			Display.getDisplay(this).setCurrent(form);
		}
	}
}
