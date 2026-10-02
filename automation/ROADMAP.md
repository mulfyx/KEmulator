# Automation CLI roadmap

The implemented agent workflow and response contract live in
[CliAutomation.md](../CliAutomation.md). The public workflow tests and their
runner are documented in [tests/README.md](tests/README.md).

## Implemented

- `open` starts a named session and returns its first UI or pending permission.
- `activate`, `select`, and `set` operate on live refs. Values and selections
  preserve unrelated refs; removed targets and old workers reject stale refs.
- Native actions return their receipt and current observation. Physical input
  acknowledges delivery and supports separate down/up strokes for holds/chords.
- Permission replies continue the original callback, including a pending key
  press whose matching release has already been scheduled.
- Screen, ready, exit, frame, permission and log waits use finite budgets.
  Startup timeout retains the launched worker; admitted action timeout reports
  an unknown effect.
- Canvas observations capture the current owner and geometry. Explicit native
  screenshot captures and Canvas/GameCanvas pixel tests cover actual PNG bytes.
- One-shot and JSONL bridge commands share the public outcomes and facts. Text
  shows the same task facts, with runtime diagnostics available through verbose.
- Public storage commands validate resets and restores before changing saves.

## Optional functional gaps

1. A chord shorthand could reduce the round trips needed for separate down/up.
2. Pointer primitives currently use one pointer; multi-touch remains unexposed.
3. Native text setters cover editable fields; keypad/T9 input behavior remains
   a separate emulator capability to investigate.

## Emulator research

- Deterministic or accelerated time for timer-driven applications.
- Observing or mocking HTTP/socket traffic beyond permission requests.
- Resource, heap and live method introspection.

Changes to the public surface must be discoverable through `help` and have a
consumer-visible test. The full suite checks advertised command coverage;
proposed research items are not requirements for the current workflow.
