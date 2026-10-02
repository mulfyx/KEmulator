# KEmulator agent CLI tests

Run the packaged CLI through the single entrypoint:

```bash
./automation/run-cli-tests.sh                         # full suite and coverage gate
./automation/run-cli-tests.sh -k two_fields -x         # focused run, gate disabled
./automation/run-cli-tests.sh --release-dir /tmp/rel   # reuse a built release
```

The runner builds the release once, prepares the fixture pack outside the
bundle, and runs pytest. It requires Linux, JDK 8, `xvfb-run`, and Python 3
with `pytest`. PNG pixel checks use the Python standard library.

The public command and result contract is [CliAutomation.md](../../CliAutomation.md).
Tests invoke its actual commands through `KemuCli`; the wrapper does not
translate removed commands or recreate obsolete response shapes.

## Test setup

`KEMU_RELEASE_DIR` selects a built release, and `KEMU_FIXTURES_ENV` selects
`fixtures.env` from `prepare-cli-fixtures.sh`. The runner supplies both.
`KEMU_COVERAGE_CHECK=1` enables the full-run gate against the advertised
`help` commands. `KEMU_NO_BRIDGE=1` runs the wrapper through one-shot calls.
Individual scenarios also exercise both transports explicitly.

`kemu` is a shared session with a fresh app per test. `kemu_factory()` creates
isolated sessions for worker failures and permission policy tests. A session
starts automatically on `open`; teardown closes apps, stops sessions and ends
bridge processes. Tests that require a fresh permission prompt use an isolated
session because a remembered decision can survive reopening an app.

`kemu.ok(...)` or `kemu.done(...)` requires `outcome: done` and returns the
public result. `kemu.pending(...)` requires an actionable permission and
`outcome: pending`; `kemu.err(..., code=...)` requires the specified error.
Every wrapped response checks the envelope and meaningful process exit code.
A pending action is answered through its permission ref, then observed or
waited for; it is never submitted a second time.

Find targets by role and label through `node`, `node_ref`, and `command_ref`.
Refs are opaque; tests check live identity without knowing their token format.
Keep writable roots and output files under `workdir`, outside the release.

## Fixtures and evidence

Fixture sources live in `../test-fixtures/src/fixtures/`. The build script
compiles them once and packages each readable `*.mf` manifest. The prepare
script adds archive/JAD/property variants and writes `fixtures.env`.

`AGENT_CONTRACT_JAR` supplies two editable fields, command replacement,
List/Choice structural changes, permission and slow input callbacks, two
colored Canvases, GameCanvas repaint/partial flush, and an Alert.
`SLOW_STARTUP_JAR` deliberately delays `startApp()` beyond a short open budget.
The existing fixtures cover controls, lifecycle, files, RMS, descriptors and
worker failures.

Use public `wait screen/frame/ready/exit/permission/log` conditions and app log
markers as barriers. Bounded delays inside the slow fixtures are test inputs;
OS process polling verifies cleanup. Avoid sleeps to synchronize UI actions.
Pixel assertions decode actual PNG bytes. Storage rejection compares actual
saved bytes, and cleanup checks the worker, controller and its private display.

The full-run gate requires a successful invocation of every command advertised
by `help`; partial selections leave it disabled. Add a consumer-visible test
when adding a public command, and preserve independent expected facts from the
fixture or public contract instead of copying production constants or source.
