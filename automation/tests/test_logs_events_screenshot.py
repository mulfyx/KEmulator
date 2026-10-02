"""Public logs, input evidence and standalone PNG captures."""
from kemu import png_size


def test_logs_cursor_read_wait(kemu, fixtures):
    kemu.open_ready(fixtures["MEGA_CLI_FIXTURE_JAR"])
    cursor = kemu.ok("logs", "cursor")["cursor"]
    assert isinstance(cursor, str) and cursor
    read = kemu.ok("logs")
    assert isinstance(read["lines"], list) and read["lines"]
    assert any("MEGA fixture started" in line["line"] for line in read["lines"])
    assert isinstance(kemu.ok("logs", "--since", cursor)["lines"], list)
    kemu.ok("wait", "log", "--regex", "MEGA fixture started", "--timeout", "5000")
    kemu.err("logs", "--since", "not-a-cursor", code="INVALID_REQUEST")


def test_events_read(kemu, fixtures):
    # The public events stream was removed. Callback evidence is available in
    # the app log; this keeps the old delivery regression covered publicly.
    kemu.open_ready(fixtures["AGENT_CONTRACT_JAR"])
    kemu.run_command("Slow input")
    cursor = kemu.ok("logs", "cursor")["cursor"]
    kemu.ok("key", "press", "5", "--timeout", "5000")
    kemu.ok("wait", "log", "--regex", "AGENT keyReleased", "--since", cursor)
    lines = kemu.ok("logs", "--since", cursor)["lines"]
    assert any("AGENT keyPressed completed" in line["line"] for line in lines)
    assert any("AGENT keyReleased" in line["line"] for line in lines)


def test_screenshot(kemu, fixtures, workdir):
    kemu.open_ready(fixtures["MEGA_CLI_FIXTURE_JAR"])
    for name in ("capture.png", "capture.jpg"):
        capture = workdir / name
        result = kemu.ok("screenshot", str(capture))
        image = kemu.state_of(result)["image"]
        assert image["path"] == str(capture)
        assert png_size(capture) == (240, 320)
    result = kemu.ok("screenshot")
    assert png_size(kemu.state_of(result)["image"]["path"]) == (240, 320)
    blocked = workdir / "as-dir.png"
    blocked.mkdir()
    failure = kemu.err("screenshot", str(blocked), code="SCREENSHOT_WRITE_FAILED", oneshot=True)
    assert failure.exit_code == 3
