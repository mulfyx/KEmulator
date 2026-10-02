package javax.microedition.lcdui;

import emulator.ui.TargetedCommand;
import java.util.Vector;

public final class AutomationStateExtractor {
	private AutomationStateExtractor() {
	}

	public static String getDisplayableKind(Displayable displayable) {
		if (displayable == null) {
			return "none";
		}

		if (displayable instanceof TextBox) {
			return "text_box";
		}

		if (displayable instanceof List) {
			return "list";
		}

		if (displayable instanceof Canvas) {
			return "canvas";
		}

		if (displayable instanceof Alert) {
			return "alert";
		}

		if (displayable instanceof Screen) {
			return "screen";
		}

		return displayable.getClass().getName();
	}

	public static String getStructuredDisplayableKind(Displayable displayable) {
		if (displayable instanceof Form) {
			return "form";
		}
		return getDisplayableKind(displayable);
	}

	public static String getLeftSoftLabel(Displayable displayable) {
		if (displayable == null) {
			return "";
		}

		Command command = displayable.getLeftSoftCommand();

		return command == null ? "" : command.getLabel();
	}

	public static String getRightSoftLabel(Displayable displayable) {
		if (displayable == null) {
			return "";
		}

		Command command = displayable.getRightSoftCommand();

		return command == null ? "" : command.getLabel();
	}

	public static Vector<TargetedCommand> buildCommands(Displayable displayable) {
		return displayable == null ? new Vector<TargetedCommand>() : displayable.buildAllCommands();
	}

	public static Command getLeftSoftCommand(Displayable displayable) {
		return displayable == null ? null : displayable.getLeftSoftCommand();
	}

	public static Command getRightSoftCommand(Displayable displayable) {
		return displayable == null ? null : displayable.getRightSoftCommand();
	}

	/** Actual screen and item commands, including unfocused Form items. */
	public static Vector<TargetedCommand> buildAutomationCommands(Displayable displayable) {
		Vector<TargetedCommand> commands = new Vector<TargetedCommand>();
		if (displayable == null) return commands;
		if (displayable instanceof Form) {
			for (Item item : getFormItems((Form) displayable)) {
				Command[] itemCommands;
				synchronized (item.commands) {
					itemCommands = item.commands.toArray(new Command[0]);
				}
				for (Command command : itemCommands) commands.add(new TargetedCommand(command, item));
			}
		}
		Command[] screenCommands;
		synchronized (displayable.commands) {
			screenCommands = (Command[]) displayable.commands.toArray(new Command[0]);
		}
		for (Command command : screenCommands) commands.add(new TargetedCommand(command, displayable));
		if (displayable instanceof List && getListType((List) displayable) == Choice.IMPLICIT) {
			Command select = ((List) displayable)._getSelectCommand();
			if (select != null && !displayable.commands.contains(select)) {
				commands.add(new TargetedCommand(select, displayable));
			}
		}
		return commands;
	}

	public static Item[] getFormItems(Form form) {
		if (form == null) return new Item[0];
		synchronized (form.items) {
			return (Item[]) form.items.toArray(new Item[0]);
		}
	}

	public static Object getFormLock(Form form) {
		return form.items;
	}

	public static boolean containsItem(Displayable owner, Item item) {
		if (!(owner instanceof Form) || item == null) return false;
		Form form = (Form) owner;
		synchronized (form.items) {
			return item.screen == owner && form.items.contains(item);
		}
	}

	public static long getItemOwnerGeneration(Item item) {
		return item.automationOwnerGeneration;
	}

	public static Object getCommandMembership(TargetedCommand target) {
		if (target == null || target.command == null) return null;
		if (target.item != null) {
			synchronized (target.item.automationCommands) {
				return target.item.commands.contains(target.command)
					? target.item.automationCommands.get(target.command) : null;
			}
		}
		Displayable owner = target.screen;
		if (owner == null) return null;
		synchronized (owner.automationCommands) {
			if (owner.commands.contains(target.command)) return owner.automationCommands.get(target.command);
			if (owner instanceof List && getListType((List) owner) == Choice.IMPLICIT
					&& ((List) owner)._getSelectCommand() == target.command) {
				return ((List) owner).automationSelectMembership;
			}
			return null;
		}
	}

	public static boolean hasCommandListener(Displayable displayable) {
		return displayable != null && displayable.cmdListener != null;
	}

	public static boolean hasItemCommandListener(Item item) {
		return item != null && item.itemCommandListener != null;
	}

	public static boolean isFocused(Item item) {
		return item != null && item.focused;
	}

	public static int getListType(List list) {
		return list == null ? -1 : list.automationChoiceType();
	}

	public static int getCurrentChoiceIndex(ChoiceGroup choice) {
		return choice == null ? -1 : choice.automationCurrentIndex();
	}

	public static Object getChoiceLock(Object collection) {
		return collection instanceof List ? ((List) collection).automationLock() : collection;
	}

	public static Object getChoiceRowIdentity(Object collection, int index) {
		if (collection instanceof List) return ((List) collection).automationRowIdentity(index);
		return ((ChoiceGroup) collection).items.elementAt(index);
	}

	public static boolean selectChoiceRow(Object collection, Object identity, int index, boolean selected) {
		if (collection instanceof List) return ((List) collection).automationSelect(identity, index, selected);
		ChoiceGroup choice = (ChoiceGroup) collection;
		synchronized (choice) {
			if (index < 0 || index >= choice.size() || choice.items.elementAt(index) != identity) return false;
			choice.setSelectedIndex(index, selected);
			return true;
		}
	}

	public static void invokeCommand(TargetedCommand target) {
		if (target.item != null) {
			target.item._callCommandAction(target.command);
		} else if (target.screen instanceof Alert && target.command == Alert.DISMISS_COMMAND
				&& target.screen.cmdListener == null) {
			((Alert) target.screen).close();
		} else {
			target.screen._callCommandAction(target.command);
		}
	}

	public static int getChoiceType(ChoiceGroup choiceGroup) {
		return choiceGroup == null ? -1 : choiceGroup.choiceType;
	}

	public static int getFocusedItemIndex(Displayable displayable) {
		if (!(displayable instanceof Form) || displayable.focusedItem == null) {
			return -1;
		}
		Form form = (Form) displayable;
		for (int i = 0; i < form.size(); i++) {
			if (form.get(i) == displayable.focusedItem) {
				return i;
			}
		}
		return -1;
	}
}
