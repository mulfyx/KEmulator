package emulator.automation.worker;

import emulator.Emulator;
import emulator.ui.IEmulatorFrontend;
import emulator.ui.IScreen;
import emulator.ui.TargetedCommand;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.Vector;
import javax.microedition.lcdui.Alert;
import javax.microedition.lcdui.AutomationStateExtractor;
import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Choice;
import javax.microedition.lcdui.ChoiceGroup;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CustomItem;
import javax.microedition.lcdui.DateField;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Form;
import javax.microedition.lcdui.Gauge;
import javax.microedition.lcdui.Image;
import javax.microedition.lcdui.ImageItem;
import javax.microedition.lcdui.Item;
import javax.microedition.lcdui.List;
import javax.microedition.lcdui.StringItem;
import javax.microedition.lcdui.TextBox;
import javax.microedition.lcdui.TextField;
import javax.microedition.lcdui.Ticker;
import mjson.Json;

/** Native UI facts and live action references; frame capture belongs to the caller. */
final class WorkerUiModel {
	private WorkerUiModel() {
	}

	static synchronized Json build(Displayable current) {
		WorkerTargets.beginUi(current);
		try {
			IEmulatorFrontend frontend = Emulator.getEmulator();
			IScreen screen = frontend == null ? null : frontend.getScreen();
			int contentWidth = current == null ? 0 : current.getWidth();
			int contentHeight = current == null ? 0 : current.getHeight();
			int width = screen == null ? contentWidth : screen.getWidth();
			int height = screen == null ? contentHeight : screen.getHeight();
			Json nodes = Json.array();
			Json result = Json.object()
				.set("kind", AutomationStateExtractor.getStructuredDisplayableKind(current))
				.set("title", current == null ? null : current.getTitle())
				.set("size", size(width, height))
				.set("nodes", nodes);
			if (current != null && (width != contentWidth || height != contentHeight)) {
				result.set("contentSize", size(contentWidth, contentHeight));
			}
			if (current != null) {
				Ticker ticker = current.getTicker();
				if (ticker != null) {
					result.set("ticker", ticker.getString());
				}
			}
			if (current instanceof Form) {
				for (Item item : AutomationStateExtractor.getFormItems((Form) current)) {
					nodes.add(itemNode(current, item));
				}
			} else if (current instanceof List) {
				listNodes((List) current, result, nodes);
			} else if (current instanceof TextBox) {
				TextBox textBox = (TextBox) current;
				Json node = textNode("text-field", current.getTitle(), textBox.getString(),
					textBox.getConstraints(), textBox.getMaxSize(), textBox.getCaretPosition());
				node.set("focused", true);
				if (editable(textBox.getConstraints())) {
					action(node, WorkerTargets.textBoxRef(textBox), "set");
				}
				nodes.add(node);
			} else if (current instanceof Alert) {
				alertNodes((Alert) current, result, nodes);
			} else if (current instanceof Canvas) {
				Canvas canvas = (Canvas) current;
				nodes.add(node("canvas", current.getTitle())
					.set("size", size(contentWidth, contentHeight))
					.set("capabilities", Json.object()
						.set("keyEvents", true)
						.set("pointerEvents", canvas.hasPointerEvents())
						.set("pointerMotionEvents", canvas.hasPointerMotionEvents())
						.set("repeatEvents", canvas.hasRepeatEvents())));
			}
			return result.set("commands", commands(current));
		} finally {
			WorkerTargets.endUi();
		}
	}

	private static Json size(int width, int height) {
		return Json.object().set("width", width).set("height", height);
	}

	private static Json node(String role, String label) {
		return Json.object().set("role", role).set("label", label).set("actions", Json.array());
	}

	private static void action(Json node, String ref, String... actions) {
		if (ref != null) {
			node.set("ref", ref).set("actions", Json.array((Object[]) actions));
		}
	}

	private static Json itemNode(Displayable owner, Item item) {
		Json result;
		if (item instanceof TextField) {
			TextField field = (TextField) item;
			result = textNode("text-field", field.getLabel(), field.getString(),
				field.getConstraints(), field.getMaxSize(), field.getCaretPosition());
			if (editable(field.getConstraints())) {
				action(result, WorkerTargets.itemRef(owner, item), "set");
			}
		} else if (item instanceof DateField) {
			DateField field = (DateField) item;
			Date date = field.getDate();
			result = node("date-field", field.getLabel())
				.set("value", date == null ? null : Long.valueOf(date.getTime()))
				.set("inputMode", dateMode(field.getInputMode()));
			action(result, WorkerTargets.itemRef(owner, item), "set");
		} else if (item instanceof Gauge) {
			Gauge gauge = (Gauge) item;
			result = gaugeNode(gauge);
			if (gauge.isInteractive()) {
				action(result, WorkerTargets.itemRef(owner, item), "set");
			}
		} else if (item instanceof ChoiceGroup) {
			result = choiceNode(owner, (ChoiceGroup) item);
		} else if (item instanceof StringItem) {
			result = node("text", item.getLabel()).set("value", ((StringItem) item).getText());
		} else if (item instanceof ImageItem) {
			ImageItem imageItem = (ImageItem) item;
			result = node("image", item.getLabel())
				.set("value", imageItem.getAltText())
				.set("image", imageMetadata(imageItem.getImage()));
		} else {
			result = node(item instanceof CustomItem ? "custom-item" : "item", item.getLabel());
		}
		return result.set("focused", AutomationStateExtractor.isFocused(item));
	}

	private static Json textNode(String role, String label, String value,
		int constraints, int maxLength, int caret) {
		return node(role, label).set("value", value)
			.set("constraints", textConstraints(constraints))
			.set("maxLength", maxLength).set("caret", caret);
	}

	private static boolean editable(int constraints) {
		return (constraints & TextField.UNEDITABLE) == 0;
	}

	private static Json textConstraints(int constraints) {
		Json result = Json.array();
		switch (constraints & TextField.CONSTRAINT_MASK) {
			case TextField.EMAILADDR: result.add("email"); break;
			case TextField.NUMERIC: result.add("numeric"); break;
			case TextField.PHONENUMBER: result.add("phone-number"); break;
			case TextField.URL: result.add("url"); break;
			case TextField.DECIMAL: result.add("decimal"); break;
			default: result.add("any"); break;
		}
		if ((constraints & TextField.PASSWORD) != 0) result.add("password");
		if ((constraints & TextField.UNEDITABLE) != 0) result.add("uneditable");
		if ((constraints & TextField.SENSITIVE) != 0) result.add("sensitive");
		if ((constraints & TextField.NON_PREDICTIVE) != 0) result.add("non-predictive");
		if ((constraints & TextField.INITIAL_CAPS_WORD) != 0) result.add("initial-caps-word");
		if ((constraints & TextField.INITIAL_CAPS_SENTENCE) != 0) result.add("initial-caps-sentence");
		return result;
	}

	private static String dateMode(int mode) {
		switch (mode) {
			case DateField.DATE: return "date";
			case DateField.TIME: return "time";
			default: return "date-time";
		}
	}

	private static Json gaugeNode(Gauge gauge) {
		Json result = node("gauge", gauge.getLabel()).set("value", gauge.getValue());
		if (gauge.getMaxValue() != Gauge.INDEFINITE) {
			return result.set("min", 0).set("max", gauge.getMaxValue());
		}
		String state;
		switch (gauge.getValue()) {
			case Gauge.CONTINUOUS_IDLE: state = "continuous-idle"; break;
			case Gauge.INCREMENTAL_IDLE: state = "incremental-idle"; break;
			case Gauge.CONTINUOUS_RUNNING: state = "continuous-running"; break;
			default: state = "incremental-updating"; break;
		}
		return result.set("mode", "indefinite").set("state", state);
	}

	private static Json choiceNode(Displayable owner, ChoiceGroup choice) {
		synchronized (AutomationStateExtractor.getChoiceLock(choice)) {
			int type = AutomationStateExtractor.getChoiceType(choice);
			boolean focused = AutomationStateExtractor.isFocused(choice);
			int focusedIndex = AutomationStateExtractor.getCurrentChoiceIndex(choice);
			Json options = Json.array();
			for (int i = 0; i < choice.size(); i++) {
				Json option = optionNode(choice.getString(i), choice.isSelected(i), type)
					.set("focused", focused && i == focusedIndex);
				Image image = choice.getImage(i);
				if (image != null) option.set("image", imageMetadata(image));
				action(option, WorkerTargets.rowRef(owner, choice, i), "select");
				options.add(option);
			}
			return node("choice-group", choice.getLabel())
				.set("selection", choiceType(type)).set("nodes", options);
		}
	}

	private static void listNodes(List list, Json result, Json nodes) {
		synchronized (AutomationStateExtractor.getChoiceLock(list)) {
			int type = AutomationStateExtractor.getListType(list);
			int selectedIndex = list.getSelectedIndex();
			boolean activates = type == Choice.IMPLICIT && list._getSelectCommand() != null
				&& AutomationStateExtractor.hasCommandListener(list);
			result.set("selection", choiceType(type));
			for (int i = 0; i < list.size(); i++) {
				Json option = optionNode(list.getString(i), list.isSelected(i), type);
				if (type != Choice.MULTIPLE) option.set("focused", i == selectedIndex);
				Image image = list.getImage(i);
				if (image != null) option.set("image", imageMetadata(image));
				String ref = WorkerTargets.rowRef(list, list, i);
				if (activates) action(option, ref, "select", "activate");
				else action(option, ref, "select");
				nodes.add(option);
			}
		}
	}

	private static Json optionNode(String label, boolean selected, int type) {
		Json result = node("option", label).set("selected", selected);
		if (type == Choice.MULTIPLE) result.set("canDeselect", true);
		return result;
	}

	private static String choiceType(int type) {
		switch (type) {
			case Choice.MULTIPLE: return "multiple";
			case Choice.EXCLUSIVE: return "exclusive";
			case Choice.IMPLICIT: return "implicit";
			case Choice.POPUP: return "popup";
			default: return "unknown";
		}
	}

	private static void alertNodes(Alert alert, Json result, Json nodes) {
		result.set("timeout", alert.getTimeout());
		nodes.add(node("text", null).set("value", alert.getString()));
		Gauge indicator = alert.getIndicator();
		if (indicator != null) result.set("indicator", gaugeNode(indicator));
		Image image = alert.getImage();
		if (image != null) nodes.add(node("image", null).set("image", imageMetadata(image)));
	}

	private static Json imageMetadata(Image image) {
		return image == null ? Json.nil() : size(image.getWidth(), image.getHeight())
			.set("mutable", image.isMutable());
	}

	private static Json commands(Displayable current) {
		Json result = Json.array();
		Set<String> seen = new HashSet<String>();
		Command left = AutomationStateExtractor.getLeftSoftCommand(current);
		Command right = AutomationStateExtractor.getRightSoftCommand(current);
		Vector<TargetedCommand> commands = AutomationStateExtractor.buildAutomationCommands(current);
		for (TargetedCommand target : commands) {
			if (target == null || target.command == null || target.isChoice()) continue;
			String ref = WorkerTargets.commandRef(current, target);
			if (ref != null && !seen.add(ref)) continue;
			Command command = target.command;
			String label = command.getLongLabel();
			Json node = node("command", label == null ? command.getLabel() : label)
				.set("type", commandType(command.getCommandType()));
			action(node, ref, "activate");
			if (command == left && isSoftkeyOwner(target, commands)) node.set("softkey", "left");
			else if (command == right && isSoftkeyOwner(target, commands)) node.set("softkey", "right");
			if (target.item != null) {
				node.set("owner", Json.object().set("label", target.item.getLabel()));
			}
			result.add(node);
		}
		return result;
	}

	private static boolean isSoftkeyOwner(TargetedCommand target, Vector<TargetedCommand> commands) {
		if (target.item != null) return AutomationStateExtractor.isFocused(target.item);
		for (TargetedCommand candidate : commands) {
			if (candidate != null && candidate.command == target.command && candidate.item != null
					&& AutomationStateExtractor.isFocused(candidate.item)) return false;
		}
		return true;
	}

	private static String commandType(int type) {
		switch (type) {
			case Command.SCREEN: return "screen";
			case Command.BACK: return "back";
			case Command.CANCEL: return "cancel";
			case Command.OK: return "ok";
			case Command.HELP: return "help";
			case Command.STOP: return "stop";
			case Command.EXIT: return "exit";
			case Command.ITEM: return "item";
			default: return "unknown";
		}
	}
}
