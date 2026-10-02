"""Discoverability, argument values, parser errors and removed public API."""
import json
import os
import subprocess
import pytest


def test_root_help_contract(kemu):
    result = kemu.ok("help", command="help")
    usage = result["usage"]
    for word in ("open", "observe", "activate", "select", "set", "close", "stop"):
        assert word in usage
    assert result["commands"]


def test_command_run_help_topic(kemu):
    result = kemu.ok("help", "activate", command="help")
    assert "activate" in result["usage"] and "REF" in result["usage"]


def test_help_topic_for_every_public_command(kemu, known_commands):
    for command in sorted(known_commands):
        result = kemu.ok("help", *command.split(), command="help")
        assert command.split()[0] in result["usage"], command


@pytest.mark.parametrize("oneshot", [False, True], ids=["bridge", "oneshot"])
def test_help_suffixes(kemu, oneshot):
    for topic in ("status", "open", "set", "activate"):
        for flag in ("--help", "-h"):
            result = kemu.ok(*topic.split(), flag, command="help", oneshot=oneshot)
            assert topic in result["usage"]
    for group in ("key", "wait"):
        for suffix in ("help", "--help", "-h"):
            result = kemu.ok(group, suffix, command="help", oneshot=oneshot)
            assert group in result["usage"]


@pytest.mark.parametrize("oneshot", [False, True], ids=["bridge", "oneshot"])
@pytest.mark.parametrize("control", ["text-field", "text-box"])
def test_help_is_a_text_value(kemu, fixtures, control, oneshot):
    if control == "text-field":
        before = kemu.open_ready(fixtures["FORM_CONTROLS_JAR"])
        ref = kemu.node_ref(before, label="Name")
    else:
        kemu.open_ready(fixtures["COMMAND_FIXTURE_JAR"])
        before = kemu.run_command("Open editor")
        ref = kemu.node_ref(before, role="text-field", label="Editor")
    result = kemu.ok("set", ref, "help", command="set", oneshot=oneshot)
    assert kemu.node(result, role="text-field", label="Name" if control == "text-field" else "Editor")["value"] == "help"


@pytest.mark.parametrize("oneshot", [False, True], ids=["bridge", "oneshot"])
@pytest.mark.parametrize(("condition", "option"), [("screen", "--title"), ("log", "--regex")])
def test_help_is_a_filter_value(kemu, fixtures, condition, option, oneshot):
    kemu.open_ready(fixtures["FORM_CONTROLS_JAR"])
    args = ["wait", condition, "--timeout", "100"]
    if condition == "log":
        args.extend(["--since", kemu.ok("logs", "cursor")["cursor"]])
    kemu.err(*args, option, "help", code="TIMEOUT", oneshot=oneshot)


@pytest.mark.parametrize("oneshot", [False, True], ids=["bridge", "oneshot"])
def test_help_is_a_path_value(kemu, oneshot):
    kemu.err("inspect", "help", code="PATH_NOT_FOUND", oneshot=oneshot)


@pytest.mark.parametrize("oneshot", [False, True], ids=["bridge", "oneshot"])
def test_help_flags_after_literal_marker(kemu, oneshot):
    kemu.err("open", "--", "--help", code="PATH_NOT_FOUND", oneshot=oneshot)
    kemu.err("open", "--", "-h", code="PATH_NOT_FOUND", oneshot=oneshot)


def test_unknown_and_bare_commands(kemu):
    kemu.err("nope", code="UNKNOWN_COMMAND")
    kemu.err("activate", code="USAGE_ERROR")
    kemu.err("set", "@unknown", code="USAGE_ERROR")


def test_start_parser_errors(kemu, fixtures):
    jar = fixtures["COMMAND_FIXTURE_JAR"]
    for options in (("--headless", "--headless"), ("--headless", "--visible"),
                    ("--size", "240x320", "--size", "176x208"), ("--size", "widex320")):
        kemu.err("open", jar, *options, code="USAGE_ERROR")


def test_removed_legacy_surface(kemu):
    for args in (("start",), ("state",), ("command", "run", "--id", "1"),
                 ("list", "select", "1"), ("choice", "set", "1"),
                 ("gauge", "set", "1"), ("text-field", "set", "x"),
                 ("text-box", "set", "x"), ("date-field", "set", "1"),
                 ("rms", "reset"), ("events", "read"), ("tap", "10", "20")):
        kemu.err(*args, code="UNKNOWN_COMMAND")
    for args in (("wait", "idle"), ("wait", "display"), ("logs", "read"), ("key", "FIRE")):
        kemu.err(*args, code="USAGE_ERROR")


def test_bare_groups_are_usage_errors(kemu):
    for group in ("wait", "key", "pointer", "permission", "storage"):
        outcome = kemu.err(group, code="USAGE_ERROR")
        assert outcome.error["message"]


def test_open_parser_errors(kemu, fixtures):
    jar = fixtures["MEGA_CLI_FIXTURE_JAR"]
    for args in (("open", "--headless"), ("open", jar, "--midlet"),
                 ("open", jar, "--midlet", "1", "--midlet", "1"),
                 ("open", jar, "--headless", "--visible"),
                 ("open", jar, "--reset-file-root"),
                 ("open", jar, "--reset-state", "--reset-file-root")):
        kemu.err(*args, code="USAGE_ERROR")


@pytest.mark.parametrize("args", [
    ("key", "hold", "FIRE", "--duration", "0"),
    ("drag", "20", "20", "120"),
    ("activate", "@ref", "--expect-revision", "1"),
    ("resize", "0x100"), ("resize", "100"), ("resize", "5000x100"),
    ("open", "app.jar", "--size", "0x0"),
    ("screenshot", "--out", "shot.png"),
    ("set", "@ref", "value", "--unknown"),
    ("permission", "allow"),
])
def test_representative_parser_failures(kemu, args):
    kemu.err(*args, code="USAGE_ERROR")


def test_session_environment_option(kemu, fixtures):
    kemu.open_ready(fixtures["COMMAND_FIXTURE_JAR"])
    env = os.environ.copy()
    env["KEMU_SESSION"] = kemu.session_id
    proc = subprocess.run(["./kemu.sh", "--json", "observe"], cwd=kemu.release_dir, env=env, capture_output=True, text=True, timeout=30)
    assert proc.returncode == 0, proc.stderr
    response = json.loads(proc.stdout)
    assert response["outcome"] == "done"
    assert response["result"]["session"]["id"] == kemu.session_id
    assert response["result"]["observation"]["title"] == "Fixture Menu"
