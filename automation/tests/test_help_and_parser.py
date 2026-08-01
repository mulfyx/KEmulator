"""Help surface, parser errors, and removed legacy command forms."""

import pytest


def test_root_help_contract(kemu):
    result = kemu.ok("help", command="help")
    usage = result["usage"]
    assert "kemu help [command...] [--json]" in usage
    assert "CLI automation contract is currently Linux-only." in usage
    assert "Path-first workflow is canonical: inspect/open <path>." in usage


def test_command_run_help_topic(kemu):
    result = kemu.ok("help", "command", "run", command="help")
    assert result["topic"] == "command run"
    assert result["usage"].startswith(
        "Usage: kemu command run <--id ID|--label LABEL> --expect-revision REV")


def test_help_topic_for_every_public_command(kemu, known_commands):
    for command in sorted(known_commands):
        result = kemu.ok("help", *command.split(), command="help")
        assert f"kemu {command.split()[0]}" in result["usage"], command


def test_unknown_and_bare_commands(kemu):
    kemu.err("nope", code="UNKNOWN_COMMAND")
    kemu.err("command", code="USAGE_ERROR")
    kemu.err("command", "nope", code="USAGE_ERROR")


def test_start_parser_errors(kemu, kemu_factory):
    outcome = kemu.err("start", "--headless", "--headless", code="USAGE_ERROR")
    assert outcome.error["message"] == "Duplicate option: --headless."
    outcome = kemu.err("start", "--headless", "--visible", code="USAGE_ERROR")
    assert outcome.error["message"] == "Conflicting options: --headless and --visible."
    outcome = kemu.err("start", "--size", "240x320", "--size", "176x208",
                       code="USAGE_ERROR")
    assert outcome.error["message"] == "Duplicate option: --size."
    kemu.err("start", "--size", "widex320", code="USAGE_ERROR")
    # Runtime resolution happens before controller startup, so this must run
    # against a session with no controller yet.
    kemu_factory(start=False).err("start", "--runtime", "nope",
                                  code="UNKNOWN_RUNTIME")


def test_removed_legacy_surface(kemu):
    # Known group + removed/unknown subcommand: usage error with group usage.
    kemu.err("wait", "1", code="USAGE_ERROR")
    kemu.err("key", "FIRE", code="USAGE_ERROR")
    kemu.err("logs", "worker", code="USAGE_ERROR")
    kemu.err("logs", "wait", "--regex", "x", code="USAGE_ERROR")  # removed alias
    kemu.err("command", "run", "1", "--snapshot", "1", code="USAGE_ERROR")
    # Unknown root token: unknown command.
    kemu.err("tap", "10", "20", code="UNKNOWN_COMMAND")


def test_bare_groups_are_usage_errors(kemu):
    for group in ("logs", "wait", "key", "pointer", "list", "choice",
                  "gauge", "text-field", "text-box", "rms", "events",
                  "command"):
        outcome = kemu.err(group, code="USAGE_ERROR")
        assert f"kemu {group}" in outcome.error["message"], group


def test_open_parser_errors(kemu, fixtures):
    jar = fixtures["MEGA_CLI_FIXTURE_JAR"]
    kemu.err("open", "--headless", code="USAGE_ERROR")
    kemu.err("open", jar, "--midlet", code="USAGE_ERROR")
    outcome = kemu.err("open", jar, "--midlet", "1", "--midlet", "1",
                       code="USAGE_ERROR")
    assert outcome.error["message"] == "Duplicate option: --midlet."
    outcome = kemu.err("open", jar, "--headless", "--visible", code="USAGE_ERROR")
    assert outcome.error["message"] == "Conflicting options: --headless and --visible."
    kemu.err("open", jar, "--headless", "--open-timeout", "0", code="USAGE_ERROR")
    kemu.err("open", jar, "--headless", "--reset-file-root", code="USAGE_ERROR")
    kemu.err("open", jar, "--headless", "--reset-state", "--reset-file-root",
             code="USAGE_ERROR")


@pytest.mark.parametrize(
    ("args", "code"),
    [
        (("wait", "idle", "--timeout", "120001"), "USAGE_ERROR"),
        (("key", "hold", "FIRE", "--duration", "0", "--wait-release"),
         "USAGE_ERROR"),
        (("drag", "20", "20", "120", "120", "--delay", "4"), "USAGE_ERROR"),
        (("command", "run", "--id", "1", "--expect-revision"), "USAGE_ERROR"),
        (("resize", "0x100"), "USAGE_ERROR"),
        (("resize", "100"), "USAGE_ERROR"),
    ],
)
def test_representative_parser_failures(kemu, args, code):
    kemu.err(*args, code=code)
