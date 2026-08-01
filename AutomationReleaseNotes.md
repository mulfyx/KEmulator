# Automation contract changelog

Change history of the KEmulator automation CLI contract, newest first. This
fork's automation layer has no separate release process, so sections are
dated. Changes that break existing automation scripts are marked
**BREAKING**.

## 2026-08-01

- **BREAKING** Contract uniformity sweep. Success is expressed only by the
  envelope (`result.ok` duplicates removed everywhere, including
  health/shutdown). Every wait returns `{condition, matched, elapsedMs}` plus
  condition extras (`exited`/`idle`/`changed` are gone; `worker-exit` always
  carries `exitCode`). Every mutation returns `{oldRevision, newRevision,
  elapsedMs, state}`; `text-field set` reports `text`/`caret`/`constraints`/
  `maxSize` and validates maxSize; `gauge set` validates its domain instead
  of clamping. The session snapshot lives in exactly one key: `observe` and
  `state` return `{active, app, state}`, a ready `open` returns `{app,
  worker, status, state}`. The blocked-on-permission status is the single
  string `"pending-permission"`. `--expect-revision` is optional for every
  mutation including `command run`; stale checks report `{expectRevision,
  currentRevision}`. `screenshot` takes a positional FILE and no longer
  enforces a `.png` extension. `open` sends one canonical `timeoutMs`.
  Storage preconditions fail with `APP_ACTIVE` (was `APP_ALREADY_OPEN`);
  a degraded controller fails `stop` with `CONTROLLER_UNREACHABLE`;
  `LCDUI_CONTROL_UNAVAILABLE` now exits `2`; socket timeouts surface as
  `TIMEOUT` instead of `CONTROLLER_UNREACHABLE`. All numeric limits live in
  one place and are enforced at both the CLI (`USAGE_ERROR`) and the worker
  (`INVALID_REQUEST`), including `--size`/`resize` bounds `1..4095` and a
  uniform `--timeout` on every LCDUI setter. `logs read` always returns
  `lines`; the per-line `offset` and the `logs cursor` `offset` field are
  replaced by `toOffset`. Long waits no longer block the controller queue.
  `help --json` returns the machine-readable `commands` list.
- **BREAKING** Removed the `logs wait` alias; `wait log` is the single
  canonical form. Command groups are now uniform: a known group with a
  missing or unknown subcommand (`kemu logs`, `kemu wait nope`, ...) returns
  `USAGE_ERROR` with the group usage text, while an unknown root token stays
  `UNKNOWN_COMMAND`. Documented the previously missing `STORAGE_ERROR` code.
- Fixed lost suite properties: a worker launched with an explicit MIDlet
  class (every CLI `open`) now applies the same JAD/MANIFEST merge as the
  inspector, so `MIDlet.getAppProperty()` sees JAD keys first, MANIFEST
  fallback keys, and MANIFEST-only keys even when the JAD defines
  `MIDlet-1`. `inspect` exposes the merged map as `suiteProperties`.
- **BREAKING** Made `--reset-state` safe: an explicit `--file-root` is now
  preserved unless the new `--reset-file-root` opt-in is passed, and every
  reset root is checked against the launch JAD/JAR before any deletion.
  Dangerous overlaps fail with the new `STORAGE_OVERLAP` code before
  mutation.
- **BREAKING** `open` without `--wait-ready` now returns after the worker
  spawn with `status: "starting"` and the worker identity; `--wait-ready`
  waits for readiness, returns `status: "pending-permission"` when
  `startApp()` blocked on a permission request, and honors the new
  `--open-timeout MS` (default 30000). Worker exit during startup fails
  `open` immediately with the exit code, a `causeHint` log line, `logTail`,
  and the log path instead of a fixed 30-second `OPEN_TIMEOUT`.
- Startup permissions are answerable: a starting worker is registered
  immediately, so `state`, `observe`, `logs`, `wait permission`, and
  `permission allow|deny` work before `startApp()` returns. Commands sent
  before the worker socket accepts connections are retried briefly and then
  fail with the new `WORKER_STARTING` code. `logs read`/`wait log` keep
  addressing the last failed worker until the next `open` or an explicit
  `close`.
- Automation workers no longer block on modal alert dialogs; alerts are
  logged and fatal startup errors exit the worker process.
- Added live `resize WIDTHxHEIGHT` and `rotate` commands: the running worker
  keeps its PID and MIDlet, `Canvas.sizeChanged()` fires, revision and
  frameRevision advance, and `--wait-frame` waits for a repaint at the new
  size.
- Added `text-box set TEXT [--expect-revision REV]` for the current LCDUI
  `TextBox`, executed on the LCDUI event thread with maxSize validation and
  stale-revision protection.
- Made the memory-card mapping explicit: `open`, `state`, and `observe`
  report `memoryCard.guestUrl`/`hostPath`, and drive-letter file URLs are
  case-insensitive (`file:///E:/x` equals `file:///e:/x`). The default
  `fileconn.dir.memorycard=file:///root/e/` is unchanged.
- Window icon decoding failures no longer crash the emulator; hosts without
  an XPM gdk-pixbuf loader log the error and continue without an icon.
- Rebuilt the CLI test layer: `automation/run-cli-tests.sh` builds the
  bundle once, prepares fixtures once, and runs the pytest suite in
  `automation/tests/` with a command-coverage gate derived from `kemu help`.
  It replaces the former bash suites and per-fixture build scripts.

## 2026-07-31

- Added schema 3 observations with monotonic revisions, structured LCDUI trees,
  frame revisions, cursor-addressable events, and one canonical nested
  `displayable` representation.
- Added event-driven waits for displays, worker readiness/exit, LCDUI idle,
  frames, permissions, and worker log regular expressions. Timeouts return
  structured last-state diagnostics and elapsed time.
- Added LCDUI-thread-safe native `List`, `ChoiceGroup`, `Gauge`, and `TextField`
  mutation commands with optimistic revision checks.
- Added atomic command lookup by id or label, `STALE_REVISION` protection,
  callback completion acknowledgement, and optional next-display waiting,
  including applications that reuse one `Displayable` instance while replacing
  its title or contents.
- Command callbacks that suspend on a permission request now return a structured
  `permission-pending` result. The command later emits `command-finished` after
  the request is answered instead of deadlocking the CLI until timeout.
- Added acknowledged key and pointer delivery with delivery-kind metadata.
- Added session IDs, writable data/RMS/file roots, read-only bundle support,
  RMS/state archives, configurable worker JVM options, and actual controller
  and worker PID/status reporting.
- Preserved legacy `file/root` bundle fixtures by copying them once into an
  implicit session-local file root, rejected explicit writable roots that
  overlap the runtime bundle, and made RMS index replacement atomic.
- Kept repaint traffic out of display revision checks, coalesced frame events
  by rendered state revision, delivered native item-state callbacks before
  control mutations return, and initialized an empty writable `midlets.ini`
  when a session starts from a read-only bundle.
- Added cursor-based worker log reads/waits and JSONL event reads.
- Removed fixed `wait <ms>`, legacy key/tap forms, line-tail log commands,
  command snapshots, positional command ids, and schema 1-style observation
  duplicates. Current commands require revision guards and acknowledged input.

---

This automation runtime is a functional MIDP/LCDUI/RMS/JSR-75/MMAPI test
environment. Its wall time, audio timing, and rendered output are not evidence
of physical Java ME device performance or fidelity.
