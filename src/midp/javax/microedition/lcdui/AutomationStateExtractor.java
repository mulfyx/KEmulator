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

	/**
	 * The command list automation exposes: the LCDUI menu list plus softkey
	 * commands that the menu omits (BACK/EXIT-style right softkey), so an
	 * agent can invoke them by id instead of guessing a key press.
	 */
	public static Vector<TargetedCommand> buildAutomationCommands(Displayable displayable) {
		Vector<TargetedCommand> commands = buildCommands(displayable);
		Command rightSoft = getRightSoftCommand(displayable);
		if (rightSoft == null) {
			return commands;
		}

		for (int i = 0; i < commands.size(); i++) {
			TargetedCommand candidate = commands.get(i);
			if (candidate != null && candidate.command == rightSoft) {
				return commands;
			}
		}

		commands.add(new TargetedCommand(rightSoft, displayable));

		return commands;
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
