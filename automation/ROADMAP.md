# Automation CLI roadmap

What the current command surface does not cover for agent use, ordered by
practical value. The contract itself (envelopes, wait/mutation shapes, error
codes) is considered settled; see `AutomationReleaseNotes.md`.

## Done

- **JSONL bridge** (`kemu bridge`): one long-lived CLI process, one request
  per stdin line, envelope + echoed `id` per stdout line. Removes the
  per-command JVM startup cost (~300-500 ms per call).
- **`wait display --title-regex`** — regex matching next to the exact-title
  filter.
- **Softkey-only commands** — `state.displayable.commands` marks
  `softkey: "left"|"right"` and `softkeyOnly: true`; BACK/EXIT commands the
  menu omits are invokable by id.
- **`observe --screenshot FILE`** — one worker call returns the snapshot and
  writes the image, so state and picture cannot drift apart.
- **`pause` / `resume`** — MIDP lifecycle control; `pauseApp()` now fires for
  non-Canvas displayables too (`EventQueue.EVENT_PAUSE` no longer returns
  early).
- **Half-stroke input** — `key down`/`key up` (chords) and
  `pointer down`/`pointer up` (holds).
- **`date-field set EPOCH_MS`** — the last interactive LCDUI item without a
  setter; snapshots report `date`/`inputMode`.

## Later: functional gaps

1. **Chord ergonomics** — `key down`/`key up` cover chords, but a single
   `key chord UP+LEFT` shorthand would remove two round trips per step.
2. **Multi-touch** — the pointer primitives use pointer id `0` only.
3. **Text input via keys** — no T9/predictive input path; `text-box set`
   bypasses the keypad entirely, so IME behavior is untested.

## Research-level (emulator changes, not commands)

7. **Deterministic time** — fake/accelerated clock for timer-driven
   gameplay; today agents wait wall-clock time.
8. **Network observation/mocking** — HTTP/socket connections are only
   gated by permissions; agents cannot inspect or stub them.
9. **Deep introspection** — resources, heap state, live method hooks
   (the scope of the former AutomationAgentRoadmap).

Rules for adding a command: it must appear in `kemu help` (the registry is
served as `help --json` `result.commands`), follow the envelope/wait/mutation
shapes, take its limits from `AutomationLimits`, and land with tests — the
coverage gate fails any registered command without a passing invocation.
