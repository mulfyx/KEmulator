"""Half-stroke input (chords, holds), MIDlet lifecycle, date-field, and the
title-regex wait — the roadmap gaps closed after the contract sweep."""

import re


def test_key_down_up_supports_chords(kemu, fixtures):
    kemu.open_ready(fixtures["INPUT_PROBE_JAR"])
    assert kemu.title().startswith("keys=[]")

    kemu.ok("key", "down", "LEFT", "--wait-dispatched")
    kemu.ok("key", "down", "UP", "--wait-dispatched")
    held = re.search(r"keys=\[([^\]]*)\]", kemu.title()).group(1).split(",")
    assert set(held) == {"LEFT", "UP"}  # both keys held at the same time

    kemu.ok("key", "up", "LEFT", "--wait-dispatched")
    assert re.search(r"keys=\[([^\]]*)\]", kemu.title()).group(1) == "UP"

    kemu.ok("key", "up", "UP", "--wait-dispatched")
    assert kemu.title().startswith("keys=[]")


def test_pointer_down_up_supports_holds(kemu, fixtures):
    kemu.open_ready(fixtures["INPUT_PROBE_JAR"])

    kemu.ok("pointer", "down", "30", "40", "--wait-dispatched")
    assert "pointer=down@30,40" in kemu.title()

    kemu.ok("pointer", "up", "35", "45", "--wait-dispatched")
    assert "pointer=up@35,45" in kemu.title()


def test_pause_and_resume_drive_the_midlet_lifecycle(kemu, fixtures):
    kemu.open_ready(fixtures["LIFECYCLE_JAR"])
    assert kemu.title() == "lifecycle started=1 paused=0"
    assert kemu.state_of()["paused"] is False

    paused = kemu.ok("pause")
    assert paused["paused"] is True
    assert paused["newRevision"] > paused["oldRevision"]
    assert paused["state"]["paused"] is True
    assert kemu.title() == "lifecycle started=1 paused=1"

    resumed = kemu.ok("resume")
    assert resumed["paused"] is False
    kemu.wait_title("lifecycle started=2 paused=1", timeout_ms=10000)

    # The app is interactive again after resume.
    kemu.ok("wait", "idle", "--timeout", "5000")


def test_date_field_set(kemu, fixtures):
    kemu.open_ready(fixtures["FORM_CONTROLS_JAR"])
    items = kemu.state_of()["displayable"]["items"]
    assert items[-1]["kind"] == "date-field"
    assert items[-1]["date"] == 0

    epoch_ms = 1785000000000
    result = kemu.ok("date-field", "set", str(epoch_ms))
    assert result["date"] == epoch_ms
    assert result["newRevision"] > result["oldRevision"]

    items = kemu.state_of()["displayable"]["items"]
    assert items[-1]["date"] == epoch_ms
    assert items[0]["text"] == f"when={epoch_ms}"  # itemStateChanged fired

    kemu.err("date-field", "set", "-1", code="USAGE_ERROR")
    kemu.err("date-field", "set", str(epoch_ms), "--expect-revision", "0",
             code="STALE_REVISION")


def test_wait_display_title_regex(kemu, fixtures):
    kemu.open_ready(fixtures["COMMAND_FIXTURE_JAR"])
    kemu.run_command("Open touch")

    revision = kemu.revision()
    kemu.ok("resize", "320x240", "--expect-revision", str(revision),
            "--wait-frame", "--timeout", "10000")
    # The canvas reports its client size, so the exact title is unknown.
    matched = kemu.ok("wait", "display", "--title-regex", r"^Size 320x\d+$",
                      "--timeout", "10000")
    assert matched["matched"] is True
    assert re.match(r"^Size 320x\d+$", kemu.title())

    kemu.err("wait", "display", "--title-regex", "[unclosed",
             "--timeout", "1000", code="INVALID_REQUEST")


def test_softkey_only_commands_are_invokable(kemu, fixtures):
    kemu.open_ready(fixtures["MEGA_CLI_FIXTURE_JAR"])
    observation = kemu.observe()
    displayable = kemu.state_of(observation)["displayable"]

    assert displayable["softkeys"]["right"] == "Exit"
    exit_command = next(
        command for command in displayable["commands"]
        if (command.get("label") or command.get("text")) == "Exit")
    assert exit_command["softkey"] == "right"
    assert exit_command["softkeyOnly"] is True

    # Menu commands stay unmarked.
    editor = next(
        command for command in displayable["commands"]
        if (command.get("label") or command.get("text")) == "Open editor")
    assert "softkeyOnly" not in editor

    # The softkey-only command is reachable by id, no key press needed.
    kemu.run(
        "command", "run",
        "--id", str(exit_command["id"]),
        "--expect-revision", str(kemu.revision(observation)))
    exited = kemu.ok("wait", "worker-exit", "--timeout", "10000")
    assert exited["matched"] is True


def test_observe_with_screenshot_is_atomic(kemu, fixtures, workdir):
    from kemu import png_size

    kemu.open_ready(fixtures["MEGA_CLI_FIXTURE_JAR"])
    capture = workdir / "observe-atomic.png"
    result = kemu.ok("observe", "--screenshot", str(capture))

    assert result["active"] is True
    assert result["state"]["displayable"]["title"] == "Mega menu"
    assert result["screenshot"] == {"saved": True, "path": str(capture)}
    assert "imageBase64" not in result
    assert png_size(capture) == (
        result["state"]["width"], result["state"]["height"])

    kemu.err("observe", "--screenshot", code="USAGE_ERROR")
    kemu.close()
    kemu.err("observe", "--screenshot", str(workdir / "none.png"),
             code="NO_ACTIVE_APP")
