package emulator.automation.worker;

import emulator.automation.shared.AutomationErrorCodes;
import emulator.automation.shared.AutomationException;
import emulator.ui.TargetedCommand;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Date;
import javax.microedition.lcdui.AutomationStateExtractor;
import javax.microedition.lcdui.Choice;
import javax.microedition.lcdui.ChoiceGroup;
import javax.microedition.lcdui.DateField;
import javax.microedition.lcdui.Form;
import javax.microedition.lcdui.Gauge;
import javax.microedition.lcdui.Item;
import javax.microedition.lcdui.List;
import javax.microedition.lcdui.TextBox;
import javax.microedition.lcdui.TextField;
import mjson.Json;

/** Ref actions validate the live target on the LCDUI thread, never by an ordinal. */
final class WorkerTargetActions {
	private WorkerTargetActions() { }

	private static String ref(Json request) {
		if (!request.has("ref") || !request.at("ref").isString() || request.at("ref").asString().length() == 0) {
			throw invalid("Action requires a target ref");
		}
		return request.at("ref").asString();
	}

	private static AutomationException invalid(String message) {
		return new AutomationException(AutomationErrorCodes.INVALID_REQUEST, message);
	}

	static Json activate(final Json request) {
		final String ref = ref(request);
		return WorkerCommands.dispatchNative(request, "activate", ref, new WorkerCommands.NativeAction() {
			public Json apply() {
				WorkerTargets.Target target = WorkerTargets.resolve(ref, "activate");
				if ("command".equals(target.kind)) {
					AutomationStateExtractor.invokeCommand(target.command);
					return Json.object();
				}
				List list = (List) target.object;
				TargetedCommand command = new TargetedCommand(list._getSelectCommand(), list);
				Object commandIdentity = AutomationStateExtractor.getCommandMembership(command);
				if (!AutomationStateExtractor.selectChoiceRow(list, target.identity, target.index, true)) throw WorkerTargets.stale(ref);
				// Selection and activation remain in this queue entry. A command
				// replacement while the native frontend refreshes must not redirect it.
				if (WorkerTargets.current() != target.owner
						|| commandIdentity != AutomationStateExtractor.getCommandMembership(command)
						|| !AutomationStateExtractor.hasCommandListener(list)) throw WorkerTargets.stale(ref);
				WorkerTargets.resolve(ref, "activate");
				AutomationStateExtractor.invokeCommand(command);
				return Json.object().set("value", true);
			}
		});
	}

	static Json select(final Json request) {
		final String ref = ref(request);
		final boolean off = request.at("off", false).asBoolean();
		return WorkerCommands.dispatchNative(request, "select", ref, new WorkerCommands.NativeAction() {
			public Json apply() {
				WorkerTargets.Target target = WorkerTargets.resolve(ref, "select");
				int type = target.object instanceof List
					? AutomationStateExtractor.getListType((List) target.object)
					: AutomationStateExtractor.getChoiceType((ChoiceGroup) target.object);
				if (off && type != Choice.MULTIPLE) throw new AutomationException(AutomationErrorCodes.UNSUPPORTED_ACTION,
					"Only multiple-selection options may be deselected", Json.object().set("ref", ref));
				if (!AutomationStateExtractor.selectChoiceRow(target.object, target.identity, target.index, !off)) throw WorkerTargets.stale(ref);
				// Pure selection does not invoke the List select command or item listener.
				return Json.object().set("value", !off);
			}
		});
	}

	static Json set(final Json request) {
		final String ref = ref(request);
		if (!request.has("value") || request.at("value").isNull()) throw invalid("set requires a value");
		return WorkerCommands.dispatchNative(request, "set", ref, new WorkerCommands.NativeAction() {
			public Json apply() {
				WorkerTargets.Target target = WorkerTargets.resolve(ref, "set");
				Json result;
				if (target.object instanceof TextBox) {
					TextBox text = (TextBox) target.object;
					String value = textValue(request.at("value"));
					validateText(value, text.getConstraints(), text.getMaxSize());
					WorkerTargets.resolve(ref, "set");
					try { text.setString(value); }
					catch (IllegalArgumentException rejected) { throw invalid("TextBox rejected the value: " + rejected.getMessage()); }
					return Json.object().set("value", text.getString());
				}
				Item item = (Item) target.object;
				Form form = (Form) target.owner;
				{
					// Setters may refresh SWT synchronously. Never hold the Form
					// collection lock while waiting for its frontend thread.
					target = WorkerTargets.resolve(ref, "set");
					if (item instanceof TextField) {
						TextField field = (TextField) item;
						String value = textValue(request.at("value"));
						validateText(value, field.getConstraints(), field.getMaxSize());
						WorkerTargets.resolve(ref, "set");
						field.setString(value);
						result = Json.object().set("value", field.getString());
					} else if (item instanceof Gauge) {
						Gauge gauge = (Gauge) item;
						int value = integerValue(request.at("value"));
						if (value < 0 || value > gauge.getMaxValue()) throw invalid("Gauge value must be between 0 and " + gauge.getMaxValue());
						WorkerTargets.resolve(ref, "set");
						gauge.setValue(value);
						result = Json.object().set("value", gauge.getValue());
					} else if (item instanceof DateField) {
						DateField field = (DateField) item;
						Date value = parseDate(request.at("value"), field.getInputMode());
						WorkerTargets.resolve(ref, "set");
						field.setDate(value);
						Date date = field.getDate();
						result = Json.object().set("value", date == null ? null : Long.valueOf(date.getTime()));
					} else throw invalid("Unsupported set target");
				}
				// A user edit notifies the owning Form after applying the value.
				if (AutomationStateExtractor.containsItem(form, item)) form._itemStateChanged(item);
				return result;
			}
		});
	}

	private static String textValue(Json value) {
		if (!value.isString()) throw invalid("Text value must be a string");
		return value.asString();
	}

	private static void validateText(String value, int constraints, int maxLength) {
		if (value.length() > maxLength) throw invalid("Text exceeds maximum length " + maxLength);
		int type = constraints & TextField.CONSTRAINT_MASK;
		if (value.length() == 0) return;
		if (type == TextField.NUMERIC && !value.matches("-?[0-9]+")) throw invalid("Text must contain a signed integer");
		if (type == TextField.DECIMAL && !value.matches("-?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)")) throw invalid("Text must contain a decimal number");
		if (type == TextField.PHONENUMBER && !value.matches("[0-9+*#(). /-]+")) throw invalid("Text must contain a phone number");
	}

	private static int integerValue(Json value) {
		try {
			String text = value.isString() ? value.asString() : value.isNumber() ? value.toString() : "";
			return Integer.parseInt(text);
		} catch (NumberFormatException rejected) { throw invalid("Gauge value must be an integer"); }
	}

	private static Date parseDate(Json value, int mode) {
		String text = value.isString() ? value.asString() : value.isNumber() ? value.toString() : "";
		try {
			if (text.matches("-?[0-9]+")) return new Date(Long.parseLong(text));
			ZoneId zone = ZoneId.systemDefault();
			if (mode == DateField.DATE) return Date.from(LocalDate.parse(text).atStartOfDay(zone).toInstant());
			if (mode == DateField.TIME) return Date.from(LocalTime.parse(text).atDate(LocalDate.of(1970, 1, 1)).atZone(zone).toInstant());
			try { return Date.from(Instant.parse(text)); }
			catch (DateTimeParseException ignored) { }
			try { return Date.from(OffsetDateTime.parse(text).toInstant()); }
			catch (DateTimeParseException ignored) { }
			return Date.from(LocalDateTime.parse(text).atZone(zone).toInstant());
		} catch (RuntimeException rejected) {
			throw invalid("Date value must be epoch milliseconds or ISO "
				+ (mode == DateField.DATE ? "date (YYYY-MM-DD)" : mode == DateField.TIME ? "time (HH:mm[:ss])" : "date/time"));
		}
	}
}
