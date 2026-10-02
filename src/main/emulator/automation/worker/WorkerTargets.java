package emulator.automation.worker;

import emulator.Emulator;
import emulator.automation.shared.AutomationErrorCodes;
import emulator.automation.shared.AutomationException;
import emulator.ui.TargetedCommand;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.microedition.lcdui.Alert;
import javax.microedition.lcdui.AutomationStateExtractor;
import javax.microedition.lcdui.Choice;
import javax.microedition.lcdui.ChoiceGroup;
import javax.microedition.lcdui.DateField;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Gauge;
import javax.microedition.lcdui.Item;
import javax.microedition.lcdui.TextBox;
import javax.microedition.lcdui.TextField;
import mjson.Json;

/** Only live targets are retained. Tokens belong to one worker run and are never reused. */
final class WorkerTargets {
	private static final Object LOCK = new Object();
	private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
	private static final Map<String, Target> targets = new LinkedHashMap<String, Target>();
	private static final ThreadLocal<Set<String>> observed = new ThreadLocal<Set<String>>();
	private static final ThreadLocal<Long> observationEpoch = new ThreadLocal<Long>();
	private static long nextId = 1L;
	private static long uiEpoch;
	private static WorkerPermissions.PendingPermission permission;
	private static String permissionToken;

	static final class Target {
		final String ref;
		final String kind;
		final Displayable owner;
		final Object object;
		final Object identity;
		final long ownerGeneration;
		final int index;
		final TargetedCommand command;

		Target(String ref, String kind, Displayable owner, Object object, Object identity,
				long ownerGeneration, int index, TargetedCommand command) {
			this.ref = ref;
			this.kind = kind;
			this.owner = owner;
			this.object = object;
			this.identity = identity;
			this.ownerGeneration = ownerGeneration;
			this.index = index;
			this.command = command;
		}
	}

	private WorkerTargets() { }
	static long epoch() { synchronized (LOCK) { return uiEpoch; } }

	static String frameRef(long sequence) { return "@" + RUN + ".f" + sequence; }

	static long frameSequence(String ref) {
		String prefix = "@" + RUN + ".f";
		if (ref == null || !ref.startsWith(prefix))
			throw new AutomationException(AutomationErrorCodes.STALE_REF, "Frame belongs to another worker");
		try { return Long.parseLong(ref.substring(prefix.length())); }
		catch (NumberFormatException invalid) {
			throw new AutomationException(AutomationErrorCodes.INVALID_REQUEST, "Invalid frame reference: " + ref);
		}
	}

	private static String nextRef() {
		return "@" + RUN + ".e" + nextId++;
	}

	static Displayable current() {
		return Emulator.getCurrentDisplay() == null ? null : Emulator.getCurrentDisplay().getCurrent();
	}

	static void clear() {
		synchronized (LOCK) {
			targets.clear();
			uiEpoch++;
			permission = null;
			permissionToken = null;
		}
	}

	static void clearUi() {
		synchronized (LOCK) { targets.clear(); uiEpoch++; }
	}

	static void beginUi(Displayable current) {
		observed.set(new HashSet<String>());
		ArrayList<Target> existing;
		synchronized (LOCK) {
			observationEpoch.set(Long.valueOf(uiEpoch));
			existing = new ArrayList<Target>(targets.values());
		}
		for (Target target : existing) {
			if (!isLive(target, current)) remove(target);
		}
	}

	static void endUi() {
		Set<String> seen = observed.get();
		Long epoch = observationEpoch.get();
		observed.remove();
		observationEpoch.remove();
		if (seen == null) return;
		synchronized (LOCK) {
			if (epoch == null || epoch.longValue() != uiEpoch) return;
			java.util.Iterator<Map.Entry<String, Target>> entries = targets.entrySet().iterator();
			while (entries.hasNext()) {
				if (!seen.contains(entries.next().getKey())) entries.remove();
			}
		}
	}

	private static String register(String kind, Displayable owner, Object object, Object identity,
			long ownerGeneration, int index, TargetedCommand command) {
		String ref = null;
		synchronized (LOCK) {
			Long epoch = observationEpoch.get();
			if (owner != current() || epoch != null && epoch.longValue() != uiEpoch) return null;
			for (Target target : targets.values()) {
				if (kind.equals(target.kind) && target.owner == owner && target.object == object
						&& target.identity == identity && target.ownerGeneration == ownerGeneration
						&& target.index == index) {
					ref = target.ref;
					break;
				}
			}
			if (ref == null) {
				ref = nextRef();
				targets.put(ref, new Target(ref, kind, owner, object, identity, ownerGeneration, index, command));
			}
		}
		Set<String> seen = observed.get();
		if (seen != null) seen.add(ref);
		return ref;
	}

	static String itemRef(Displayable owner, Item item) {
		if (!AutomationStateExtractor.containsItem(owner, item) || !settable(item)) return null;
		return register("item", owner, item, item,
			AutomationStateExtractor.getItemOwnerGeneration(item), -1, null);
	}

	static String textBoxRef(TextBox textBox) {
		if (textBox == null || (textBox.getConstraints() & TextField.UNEDITABLE) != 0) return null;
		return register("text-box", textBox, textBox, textBox, 0L, -1, null);
	}

	static String rowRef(Displayable owner, Object collection, int index) {
		if (!(collection instanceof Choice)) return null;
		if (collection instanceof Item && !AutomationStateExtractor.containsItem(owner, (Item) collection)) return null;
		if (collection instanceof Displayable && collection != owner) return null;
		synchronized (AutomationStateExtractor.getChoiceLock(collection)) {
			if (index < 0 || index >= ((Choice) collection).size()) return null;
			return register("row", owner, collection,
				AutomationStateExtractor.getChoiceRowIdentity(collection, index),
				collection instanceof Item ? AutomationStateExtractor.getItemOwnerGeneration((Item) collection) : 0L,
				index, null);
		}
	}

	static String commandRef(Displayable owner, TargetedCommand command) {
		if (command == null || command.command == null) return null;
		Object identity = AutomationStateExtractor.getCommandMembership(command);
		if (identity == null || !commandAvailable(command, owner)) return null;
		return register("command", owner, command.command, identity,
			command.item == null ? 0L : AutomationStateExtractor.getItemOwnerGeneration(command.item),
			-1, command);
	}

	private static boolean settable(Object object) {
		if (object instanceof TextField) return (((TextField) object).getConstraints() & TextField.UNEDITABLE) == 0;
		if (object instanceof TextBox) return (((TextBox) object).getConstraints() & TextField.UNEDITABLE) == 0;
		if (object instanceof Gauge) return ((Gauge) object).isInteractive();
		return object instanceof DateField;
	}

	private static boolean commandAvailable(TargetedCommand command, Displayable owner) {
		if (command.item != null) {
			return AutomationStateExtractor.containsItem(owner, command.item)
				&& AutomationStateExtractor.hasItemCommandListener(command.item);
		}
		return command.screen == owner && (AutomationStateExtractor.hasCommandListener(owner)
			|| owner instanceof Alert && command.command == Alert.DISMISS_COMMAND);
	}

	private static boolean isLive(Target target, Displayable current) {
		if (target.owner != current) return false;
		if ("text-box".equals(target.kind)) return settable(target.object);
		if ("command".equals(target.kind)) {
			return commandAvailable(target.command, current)
				&& AutomationStateExtractor.getCommandMembership(target.command) == target.identity
				&& (target.command.item == null || AutomationStateExtractor.getItemOwnerGeneration(target.command.item) == target.ownerGeneration);
		}
		if (target.object instanceof Item && (!AutomationStateExtractor.containsItem(current, (Item) target.object)
				|| AutomationStateExtractor.getItemOwnerGeneration((Item) target.object) != target.ownerGeneration)) return false;
		if ("item".equals(target.kind)) return settable(target.object);
		if ("row".equals(target.kind)) {
			synchronized (AutomationStateExtractor.getChoiceLock(target.object)) {
				return target.index >= 0 && target.index < ((Choice) target.object).size()
					&& AutomationStateExtractor.getChoiceRowIdentity(target.object, target.index) == target.identity;
			}
		}
		return false;
	}

	private static void remove(Target target) {
		synchronized (LOCK) { if (targets.get(target.ref) == target) targets.remove(target.ref); }
	}

	static AutomationException stale(String ref) {
		return new AutomationException(AutomationErrorCodes.STALE_REF,
			"Target is no longer available; observe the current screen", Json.object().set("ref", ref));
	}

	/** Call on the LCDUI thread immediately before applying the action. */
	static Target resolve(String ref, String action) {
		Target target;
		synchronized (LOCK) { target = targets.get(ref); }
		if (target == null || !isLive(target, current())) {
			if (target != null) remove(target);
			throw stale(ref);
		}
		boolean supported = "set".equals(action) && ("item".equals(target.kind) || "text-box".equals(target.kind));
		if ("select".equals(action)) supported = "row".equals(target.kind);
		if ("activate".equals(action)) {
			supported = "command".equals(target.kind)
				|| "row".equals(target.kind) && target.object instanceof javax.microedition.lcdui.List
				&& AutomationStateExtractor.getListType((javax.microedition.lcdui.List) target.object) == Choice.IMPLICIT
				&& ((javax.microedition.lcdui.List) target.object)._getSelectCommand() != null
				&& AutomationStateExtractor.hasCommandListener(target.owner);
		}
		if (!supported) throw new AutomationException(AutomationErrorCodes.UNSUPPORTED_ACTION,
			"Target does not support " + action, Json.object().set("ref", ref).set("operation", action));
		return target;
	}

	static String permissionRef(WorkerPermissions.PendingPermission current) {
		WorkerPermissions.PendingPermission head = WorkerPermissions.snapshot();
		if (current != head) return null;
		synchronized (LOCK) {
			if (current == null) {
				permission = null;
				permissionToken = null;
				return null;
			}
			if (permission != current) {
				permission = current;
				permissionToken = nextRef();
			}
			return permissionToken;
		}
	}

	static int permissionId(String ref) {
		WorkerPermissions.PendingPermission head = WorkerPermissions.snapshot();
		String current = permissionRef(head);
		if (head == null || ref == null || !ref.equals(current)) throw stale(ref);
		return head.id;
	}
}
