"""Task facts and outcomes agree across one-shot, bridge, text and JSON."""
import json
import re
import pytest
from kemu import walk_nodes


def test_wait_results_share_one_shape(kemu, fixtures):
    before = kemu.open_ready(fixtures["MEGA_CLI_FIXTURE_JAR"])
    refs = [c["ref"] for c in kemu.state_of(before)["commands"]]
    for args in (("screen", "--kind", "list"), ("ready",)):
        result = kemu.ok("wait", *args, "--timeout", "5000")
        assert result["session"]["id"] == kemu.session_id
        assert kemu.title(result) == "Mega menu"
        assert [c["ref"] for c in kemu.state_of(result)["commands"]] == refs
    kemu.run_command("Open canvas")
    image = kemu.state_of()["image"]
    assert image["frameId"]
    kemu.ok("pointer", "tap", "10", "20")
    kemu.ok("wait", "frame", "--after", image["frameId"], "--timeout", "5000")
    log = kemu.ok("wait", "log", "--regex", "MEGA fixture started", "--timeout", "5000")
    assert "cursor" in log


def test_mutation_results_share_one_shape(kemu, fixtures):
    before = kemu.open_ready(fixtures["FORM_CONTROLS_JAR"])
    actions = [("set", kemu.node_ref(before, label="Level"), "3"),
               ("select", kemu.node_ref(before, label="gamma")),
               ("set", kemu.node_ref(before, label="Name"), "lint")]
    for args in actions:
        result = kemu.ok(*args)
        assert {"session", "app", "observation", "action"} <= set(result)
        assert result["action"]["operation"] == args[0]
        assert result["action"]["ref"] == args[1]
        assert isinstance(kemu.state_of(result)["nodes"], list)
    kemu.run_command("To list")
    for args in (("resize", "320x240"), ("rotate",)):
        result = kemu.ok(*args)
        assert {"session", "app", "observation", "action"} <= set(result)
    kemu.close()
    kemu.open_ready(fixtures["MEGA_CLI_FIXTURE_JAR"])
    editor = kemu.run_command("Open editor")
    result = kemu.ok("set", kemu.node_ref(editor, role="text-field", label="Mega editor"), "lint")
    assert any(n.get("value") == "lint" for n in walk_nodes(kemu.state_of(result)["nodes"]))
    saved = kemu.run_command("Save")
    assert kemu.title(saved) == "Saved lint"


def test_exit_code_is_function_of_error_code(kemu, fixtures, workdir):
    assert kemu.err("nope", code="UNKNOWN_COMMAND", oneshot=True).exit_code == 2
    assert kemu.err("inspect", str(workdir / "gone.jar"), code="PATH_NOT_FOUND", oneshot=True).exit_code == 3
    kemu.open_ready(fixtures["COMMAND_FIXTURE_JAR"])
    kemu.close()
    assert kemu.err("logs", "cursor", code="NO_ACTIVE_APP", oneshot=True).exit_code == 4


def test_jsonl_modes_emit_bare_json_lines(kemu, fixtures):
    kemu.open_ready(fixtures["MEGA_CLI_FIXTURE_JAR"])
    proc = kemu.run_raw("logs", "--jsonl", json_mode=False)
    assert proc.returncode == 0, proc.stderr
    lines = [line for line in proc.stdout.splitlines() if line.strip()]
    assert lines
    for line in lines:
        record = json.loads(line)
        assert "outcome" not in record and "line" in record


def test_text_mode_is_human_output(kemu, fixtures):
    before = kemu.open_ready(fixtures["AGENT_CONTRACT_JAR"])
    proc = kemu.run_raw("observe", json_mode=False)
    assert proc.returncode == 0, proc.stderr
    for fact in ("Agent form", "A", "B", "first", "second", kemu.node_ref(before, label="A"), kemu.node_ref(before, label="B")):
        assert fact in proc.stdout
    proc = kemu.run_raw("set", kemu.node_ref(before, label="B"), "Bob", json_mode=False)
    assert proc.returncode == 0, proc.stderr
    assert "Bob" in proc.stdout and "first" in proc.stdout
    assert kemu.node(kemu.observe(), label="B")["value"] == "Bob"


def test_verbose_only_adds_diagnostics(kemu, fixtures):
    kemu.open_ready(fixtures["AGENT_CONTRACT_JAR"])
    plain = kemu.run("status", oneshot=True)
    verbose = kemu.run("--verbose", "status", oneshot=True)
    assert plain.result == verbose.result
    assert plain.result["app"]["status"] == "ready"
    assert not plain.diagnostics and verbose.diagnostics.get("worker")
    assert "worker" not in plain.result and "pid" not in plain.result


@pytest.mark.parametrize("oneshot", [False, True], ids=["bridge", "oneshot"])
def test_pending_and_error_text_share_actionable_facts(kemu, fixtures, oneshot):
    kemu.open_ready(fixtures["MEGA_CLI_FIXTURE_JAR"])
    ref = kemu.command_ref(kemu.observe(), "Ask camera")
    pending = kemu.pending("activate", ref, oneshot=oneshot)
    permission = pending["permission"]
    text = kemu.run_raw("observe", json_mode=False)
    assert text.returncode == 0, text.stderr
    assert permission["name"] in text.stdout and permission["ref"] in text.stdout
    assert "allow" in text.stdout and "deny" in text.stdout
    # Answer the existing request; do not execute activate twice for comparison.
    kemu.ok("permission", "deny", permission["ref"])
    kemu.wait_title("Camera denied")
    json_error = kemu.err("activate", "@gone", code="STALE_REF", oneshot=oneshot)
    text_error = kemu.run_raw("activate", "@gone", json_mode=False)
    assert text_error.returncode == 2
    assert json_error.code in text_error.stderr


def test_text_and_json_preserve_control_modes_and_canvas_capabilities(kemu, fixtures):
    form = kemu.open_ready(fixtures["FORM_CONTROLS_JAR"])
    assert kemu.node(form, label="When")["inputMode"] == "date-time"
    assert kemu.node(form, label="Mode")["selection"] == "exclusive"
    text = kemu.run_raw("observe", json_mode=False)
    assert text.returncode == 0, text.stderr
    assert "When" in text.stdout and "date-time" in text.stdout
    assert "Mode" in text.stdout and "exclusive" in text.stdout
    kemu.close()
    form = kemu.open_ready(fixtures["AGENT_CONTRACT_JAR"])
    assert kemu.node(form, label="Options")["selection"] == "multiple"
    text = kemu.run_raw("observe", json_mode=False)
    assert "Options" in text.stdout and "multiple" in text.stdout
    canvas = kemu.run_command("Colors")
    capabilities = kemu.node(canvas, role="canvas")["capabilities"]
    assert capabilities["keyEvents"] is True
    text = kemu.run_raw("observe", json_mode=False)
    assert text.returncode == 0, text.stderr
    for name, value in capabilities.items():
        match = re.search(re.escape(name) + r'"?\s*[:=]\s*(true|false)', text.stdout, re.IGNORECASE)
        assert match and match.group(1).lower() == str(value).lower(), text.stdout


def test_text_and_json_preserve_alert_body_timeout_and_indicator(kemu, fixtures):
    kemu.open_ready(fixtures["AGENT_CONTRACT_JAR"])
    alert = kemu.run_command("Alert")
    screen = kemu.state_of(alert)
    assert screen["kind"] == "alert" and screen["timeout"] == 60000
    assert screen["ticker"] == "Agent alert ticker"
    assert screen["indicator"]["value"] == 3 and screen["indicator"]["max"] == 10
    body = next(n for n in screen["nodes"] if n.get("value") == "Agent alert body")
    assert "ref" not in body
    text = kemu.run_raw("observe", json_mode=False)
    assert text.returncode == 0, text.stderr
    for fact in ("Agent alert", "Agent alert body", "Agent alert ticker", "60000", "Indicator", "gauge"):
        assert fact in text.stdout
    indicator = text.stdout.split("Indicator", 1)[1]
    assert "3" in indicator and "10" in indicator
