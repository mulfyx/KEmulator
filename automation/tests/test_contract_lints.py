"""Contract-shape lints freezing the uniform response contract.

The envelope lint itself runs on every call inside KemuCli; these tests pin
the wait/mutation shapes, exit-code classes, and the non-JSON output modes.
"""

import json

WAIT_BASE_KEYS = {"condition", "matched", "elapsedMs"}
MUTATION_KEYS = {"oldRevision", "newRevision", "elapsedMs", "state"}


def _assert_wait_shape(result, condition, with_state=True):
    assert WAIT_BASE_KEYS <= set(result), result
    assert result["condition"] == condition
    assert result["matched"] is True
    assert not {"exited", "idle", "changed"} & set(result), result
    if with_state:
        assert isinstance(result["state"], dict)


def test_wait_results_share_one_shape(kemu, fixtures):
    kemu.open_ready(fixtures["MEGA_CLI_FIXTURE_JAR"])
    revision = kemu.revision()

    _assert_wait_shape(
        kemu.ok("wait", "display", "--kind", "list", "--timeout", "5000"),
        "display")
    _assert_wait_shape(
        kemu.ok("wait", "worker-ready", "--timeout", "5000"), "worker-ready")
    _assert_wait_shape(kemu.ok("wait", "idle", "--timeout", "5000"), "idle")
    frame_revision = int(kemu.state_of()["frameRevision"])
    _assert_wait_shape(
        kemu.ok("wait", "frame", "--after-revision", str(max(0, frame_revision - 1)),
                "--timeout", "5000"),
        "frame")

    logged = kemu.ok("wait", "log", "--regex", "Launch MIDlet class",
                     "--timeout", "5000")
    _assert_wait_shape(logged, "log", with_state=False)
    assert "cursor" in logged and "text" in logged
    assert revision == kemu.revision()  # waits mutate nothing


def test_mutation_results_share_one_shape(kemu, fixtures):
    kemu.open_ready(fixtures["FORM_CONTROLS_JAR"])

    for args in (("gauge", "set", "3"),
                 ("choice", "set", "2"),
                 ("text-field", "set", "lint")):
        result = kemu.ok(*args)
        assert MUTATION_KEYS <= set(result), args

    kemu.run_command("To list")
    for args in (("list", "select", "0"), ("list", "move", "down")):
        result = kemu.ok(*args)
        assert MUTATION_KEYS <= set(result), args

    for args in (("resize", "320x240"), ("rotate",)):
        result = kemu.ok(*args)
        assert MUTATION_KEYS <= set(result), args

    kemu.close()
    kemu.open_ready(fixtures["MEGA_CLI_FIXTURE_JAR"])
    kemu.run_command("Open editor")
    result = kemu.ok("text-box", "set", "lint")
    assert MUTATION_KEYS <= set(result)

    run = kemu.run_command("Save")
    assert {"oldRevision", "newRevision", "elapsedMs", "state"} <= set(run)


def test_exit_code_is_function_of_error_code(kemu, fixtures, workdir):
    assert kemu.err("nope", code="UNKNOWN_COMMAND").exit_code == 2
    assert kemu.err("inspect", str(workdir / "gone.jar"),
                    code="PATH_NOT_FOUND").exit_code == 3
    assert kemu.err("logs", "cursor", code="NO_ACTIVE_APP").exit_code == 4

    kemu.open_ready(fixtures["MEGA_CLI_FIXTURE_JAR"])
    outcome = kemu.err("gauge", "set", "1", code="LCDUI_CONTROL_UNAVAILABLE")
    assert outcome.exit_code == 2  # rebuild-the-request class


def test_jsonl_modes_emit_bare_json_lines(kemu, fixtures):
    kemu.open_ready(fixtures["MEGA_CLI_FIXTURE_JAR"])
    kemu.run_command("Open editor")

    for args in (("logs", "read", "--jsonl"), ("events", "read", "--jsonl")):
        proc = kemu.run_raw(*args, json_mode=False)
        assert proc.returncode == 0, proc.stderr
        lines = [line for line in proc.stdout.splitlines() if line.strip()]
        assert lines, args
        for line in lines:
            parsed = json.loads(line)
            assert "ok" not in parsed  # JSONL lines are records, not envelopes


def test_text_mode_is_human_output(kemu):
    proc = kemu.run_raw("help", json_mode=False)
    assert proc.returncode == 0
    assert proc.stdout.startswith("Usage:")

    proc = kemu.run_raw("status", json_mode=False)
    assert proc.returncode == 0
    assert "Running:" in proc.stdout
