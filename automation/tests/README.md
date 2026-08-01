# KEmulator CLI test suite

Single entrypoint:

```bash
./automation/run-cli-tests.sh                 # full run + coverage gate
./automation/run-cli-tests.sh -k resize -x    # partial run (gate off)
./automation/run-cli-tests.sh --release-dir /tmp/rel   # reuse a built bundle
```

The runner builds the release bundle **once**, prepares the fixture pack
**once** (outside the bundle), then runs pytest over this directory.
Requires Linux, `xvfb-run`, JDK 8+, and `python3` with `pytest`.

## Layout

- `kemu.py` — `KemuCli` wrapper around `kemu.sh --json`; records every
  exercised public command.
- `conftest.py` — session fixtures: release bundle, fixture pack, controller
  lifecycle (`kemu` shared session, `kemu_factory` for isolated sessions),
  `workdir` for writable storage roots.
- `test_*.py` — scenarios grouped by domain.
- `test_zz_coverage.py` — fails the full run when a command advertised by
  `kemu help` was never exercised.

Fixture MIDlets live in `../test-fixtures/src/fixtures/`;
`../test-fixtures/build-fixtures.sh` compiles them once and packages one JAR
per `*.mf` manifest; `../test-fixtures/prepare-cli-fixtures.sh` derives the
descriptor/JAR variants and writes `fixtures.env`.

## Rules (how this layer stays sane)

1. **Never build the product inside a test.** The runner owns the build;
   tests get `KEMU_RELEASE_DIR`.
2. **Never write into the release bundle.** Worker `--data-dir`/`--file-root`
   and any output files go under the `workdir` fixture (explicit roots that
   overlap the bundle are rejected by the CLI anyway).
3. **Go through `KemuCli`** (`kemu.ok(...)` / `kemu.err(..., code=...)`), so
   the JSON envelope contract is asserted uniformly and the coverage gate
   sees the command.
4. **New CLI command ⇒ new test.** The coverage gate fails a full run for
   any command present in `kemu help` but absent from the tests.
5. **Don't leak state.** Tests using the shared `kemu` session must tolerate
   a fresh app (an autouse fixture force-closes leftovers); tests that kill
   or wedge workers use `kemu_factory()` for an isolated controller.
6. **No sleeps for synchronization** — use `wait display/frame/idle/log/...`
   CLI primitives, mirroring the automation contract itself.
