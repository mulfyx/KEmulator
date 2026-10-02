"""Separate down/up strokes, lifecycle, dates and observation captures."""
import re
from kemu import png_size, walk_nodes


def test_key_down_up_supports_chords(kemu, fixtures):
    kemu.open_ready(fixtures["INPUT_PROBE_JAR"])
    kemu.ok("key", "down", "LEFT")
    kemu.ok("key", "down", "UP")
    held = re.search(r"keys=\[([^\]]*)\]", kemu.title()).group(1).split(",")
    assert set(held) == {"LEFT", "UP"}
    kemu.ok("key", "up", "LEFT")
    assert re.search(r"keys=\[([^\]]*)\]", kemu.title()).group(1) == "UP"
    kemu.ok("key", "up", "UP")
    assert kemu.title().startswith("keys=[]")


def test_pointer_down_up_supports_holds(kemu, fixtures):
    kemu.open_ready(fixtures["INPUT_PROBE_JAR"])
    kemu.ok("pointer", "down", "30", "40")
    assert "pointer=down@30,40" in kemu.title()
    kemu.ok("pointer", "up", "35", "45")
    assert "pointer=up@35,45" in kemu.title()


def test_pause_and_resume_drive_the_midlet_lifecycle(kemu, fixtures):
    opened = kemu.open_ready(fixtures["LIFECYCLE_JAR"])
    assert kemu.title(opened) == "lifecycle started=1 paused=0"
    paused = kemu.ok("pause")
    assert kemu.title(paused) == "lifecycle started=1 paused=1"
    resumed = kemu.ok("resume")
    assert kemu.title(resumed) == "lifecycle started=2 paused=1"
    kemu.ok("wait", "ready", "--timeout", "5000")


def test_repeated_pause_and_resume_do_not_leave_pending_lifecycle_events(kemu, fixtures):
    kemu.open_ready(fixtures["LIFECYCLE_JAR"])
    for started, paused in ((1, 1), (2, 2)):
        kemu.ok("pause")
        expected = f"lifecycle started={started} paused={paused}"
        assert kemu.title(kemu.ok("pause")) == expected
        expected = f"lifecycle started={started + 1} paused={paused}"
        assert kemu.title(kemu.ok("resume")) == expected
        assert kemu.title(kemu.ok("resume")) == expected
        assert kemu.title(kemu.ok("wait", "ready")) == expected


def test_date_field_set(kemu, fixtures):
    before = kemu.open_ready(fixtures["FORM_CONTROLS_JAR"])
    field = kemu.node(before, role="date-field", label="When")
    assert field["value"] == 0
    epoch = 1785000000000
    after = kemu.ok("set", field["ref"], str(epoch))
    assert kemu.node(after, label="When")["value"] == epoch
    assert any(n.get("value") == f"when={epoch}" for n in walk_nodes(kemu.state_of(after)["nodes"]))
    kemu.ok("set", field["ref"], "2026-07-25T12:00:00Z")
    kemu.err("set", field["ref"], "not-a-date", code="INVALID_REQUEST")


def test_wait_display_title_regex(kemu, fixtures):
    kemu.open_ready(fixtures["COMMAND_FIXTURE_JAR"])
    kemu.run_command("Open touch")
    kemu.ok("resize", "320x240")
    result = kemu.ok("wait", "screen", "--title-regex", r"^Size 320x\d+$")
    assert re.match(r"^Size 320x\d+$", kemu.title(result))
    kemu.err("wait", "screen", "--title-regex", "[unclosed", code="USAGE_ERROR")


def test_softkey_only_commands_are_invokable(kemu, fixtures):
    before = kemu.open_ready(fixtures["MEGA_CLI_FIXTURE_JAR"])
    exit_command = next(c for c in kemu.state_of(before)["commands"] if c["label"] == "Exit")
    assert exit_command["softkey"] == "right"
    kemu.run("activate", exit_command["ref"])
    kemu.ok("wait", "exit", "--timeout", "10000")


def test_observe_with_screenshot_is_atomic(kemu, fixtures, workdir):
    kemu.open_ready(fixtures["MEGA_CLI_FIXTURE_JAR"])
    capture = workdir / "observe-atomic.png"
    result = kemu.ok("observe", "--screenshot", str(capture))
    screen = kemu.state_of(result)
    assert screen["title"] == "Mega menu"
    assert screen["image"]["path"] == str(capture)
    assert png_size(capture) == (screen["size"]["width"], screen["size"]["height"])
    assert "imageBase64" not in result
    kemu.err("observe", "--screenshot", code="USAGE_ERROR")
    kemu.close()
    kemu.err("observe", "--screenshot", str(workdir / "none.png"), code="NO_ACTIVE_APP")
