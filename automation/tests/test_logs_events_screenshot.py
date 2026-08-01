"""Worker logs, structured events, and screenshots."""

from kemu import png_size


def test_logs_cursor_read_wait(kemu, fixtures):
    kemu.open_ready(fixtures["MEGA_CLI_FIXTURE_JAR"])

    cursor = kemu.ok("logs", "cursor")["cursor"]
    assert isinstance(cursor, str) and ":" in cursor

    read = kemu.ok("logs", "read")
    assert isinstance(read["lines"], list)
    assert any("Mega" in line["line"] or "Get class" in line["line"]
               for line in read["lines"])

    read_since = kemu.ok("logs", "read", "--since", cursor)
    assert read_since["fromOffset"] >= 0

    waited = kemu.ok("logs", "wait", "--regex", "Launch MIDlet class",
                     "--timeout", "5000")
    assert waited["matched"] is True

    waited = kemu.ok("wait", "log", "--regex", "Launch MIDlet class",
                     "--timeout", "5000")
    assert waited["matched"] is True

    kemu.err("logs", "read", "--since", "not-a-cursor", code="INVALID_REQUEST")


def test_events_read(kemu, fixtures):
    kemu.open_ready(fixtures["MEGA_CLI_FIXTURE_JAR"])
    kemu.run_command("Open editor")

    events = kemu.ok("events", "read")
    assert events["cursor"] > 0
    names = {event["event"] for event in events["events"]}
    assert "display-changed" in names

    since = events["cursor"]
    kemu.ok("key", "press", "SOFT_RIGHT", "--wait-dispatched")
    fresh = kemu.ok("events", "read", "--since", str(since))
    assert all(event["cursor"] > since for event in fresh["events"])
    assert any(event["event"] == "input-dispatched" for event in fresh["events"])


def test_screenshot(kemu, fixtures, workdir):
    kemu.open_ready(fixtures["MEGA_CLI_FIXTURE_JAR"])

    capture = workdir / "capture.png"
    result = kemu.ok("screenshot", "--out", str(capture))
    assert result["saved"] is True
    assert result["path"] == str(capture)
    assert "imageBase64" not in result
    assert png_size(capture) == (240, 320)

    kemu.err("screenshot", "--out", str(workdir / "not-png.jpg"),
             code="USAGE_ERROR")
    blocked = workdir / "as-dir.png"
    blocked.mkdir()
    kemu.err("screenshot", "--out", str(blocked), code="SCREENSHOT_WRITE_FAILED")
