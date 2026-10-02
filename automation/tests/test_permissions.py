"""Pending actions continue through permission refs without resubmission."""
import pytest


@pytest.fixture
def permission_session(kemu_factory):
    # A remembered decision is session policy; unrelated tests must not answer
    # this callback before its pending/release behavior has been exercised.
    cli = kemu_factory()
    yield cli
    cli.close_quietly()
    cli.stop_force_quietly()
    cli.shutdown_bridge()


def _permission(kemu):
    result = kemu.observe()
    assert isinstance(result["permission"], dict)
    return result["permission"]


def _ask(kemu, label):
    ref = kemu.command_ref(kemu.observe(), label)
    return kemu.pending("activate", ref)


def test_allow_and_deny(kemu, fixtures):
    kemu.open_ready(fixtures["MEGA_CLI_FIXTURE_JAR"])
    pending = _ask(kemu, "Ask camera")
    request = _permission(kemu)
    assert request["ref"] == pending["permission"]["ref"]
    assert request["name"] == "media.camera"
    kemu.ok("permission", "allow", request["ref"])
    kemu.wait_title("Camera allowed")
    pending = _ask(kemu, "Ask IMEI")
    kemu.ok("permission", "allow", pending["permission"]["ref"])
    kemu.ok("wait", "screen", "--title-regex", r"^IMEI allowed ")


def test_deny_reports_denied(kemu, fixtures):
    kemu.open_ready(fixtures["COMMAND_FIXTURE_JAR"])
    pending = _ask(kemu, "Ask camera")
    kemu.ok("permission", "deny", pending["permission"]["ref"])
    kemu.wait_title("Camera denied")


def test_permission_ordering_race(kemu, fixtures):
    kemu.open_ready(fixtures["MEGA_CLI_FIXTURE_JAR"])
    kemu.run("activate", kemu.command_ref(kemu.observe(), "Ask permission race"))
    first = kemu.ok("wait", "permission", "--timeout", "5000")["permission"]
    kemu.err("permission", "deny", "@unknown.permission", code="STALE_REF")
    kemu.ok("permission", "allow", first["ref"])
    second = kemu.ok("wait", "permission", "--timeout", "5000")["permission"]
    assert first["ref"] != second["ref"]
    kemu.ok("permission", "deny", second["ref"])
    kemu.ok("wait", "screen", "--title-regex", r"^Permission race .*:(allow|deny)", "--timeout", "5000")
    kemu.err("permission", "allow", second["ref"], code="STALE_REF")


def test_allow_always_persists_for_worker(kemu, fixtures):
    kemu.open_ready(fixtures["MEGA_CLI_FIXTURE_JAR"])
    first = _ask(kemu, "Ask camera")
    kemu.ok("permission", "allow", first["permission"]["ref"], "--remember")
    kemu.wait_title("Camera allowed")
    completed = kemu.run_command("Ask camera")
    assert "permission" not in completed
    assert kemu.title(completed) == "Camera allowed"


@pytest.mark.parametrize("oneshot", [False, True], ids=["bridge", "oneshot"])
def test_startup_permission_wait_ready_returns_pending(kemu, fixtures, oneshot):
    opened = kemu.pending("open", fixtures["STARTUP_PERMISSION_JAR"], "--headless", oneshot=oneshot)
    request = opened["permission"]
    assert request["ref"] and request["name"]
    kemu.ok("permission", "deny", request["ref"])
    kemu.wait_title("startup permission denied", timeout_ms=15000)
    kemu.ok("wait", "ready", "--timeout", "15000")


def test_startup_permission_async_open(kemu, fixtures):
    opened = kemu.pending("open", fixtures["STARTUP_PERMISSION_JAR"], "--headless")
    assert kemu.ok("status")["app"]["status"]
    kemu.ok("wait", "permission", "--name", opened["permission"]["name"])
    kemu.ok("permission", "allow", opened["permission"]["ref"])
    kemu.wait_title("startup permission allowed", timeout_ms=15000)


@pytest.mark.parametrize("oneshot", [False, True], ids=["bridge", "oneshot"])
def test_permission_in_key_pressed_retains_paired_release(permission_session, fixtures, oneshot):
    kemu = permission_session
    kemu.open_ready(fixtures["AGENT_CONTRACT_JAR"])
    kemu.run_command("Input permission")
    cursor = kemu.ok("logs", "cursor")["cursor"]
    pending = kemu.pending("key", "press", "5", oneshot=oneshot)
    request = pending["permission"]
    assert request["name"] == "media.camera"
    # Introspection must remain reachable while the event callback is blocked.
    assert _permission(kemu)["ref"] == request["ref"]
    assert kemu.ok("status")["app"]["status"]
    kemu.ok("permission", "allow", request["ref"])
    kemu.ok("wait", "log", "--regex", "AGENT keyReleased", "--since", cursor, "--timeout", "10000")
    kemu.wait_title("Input released")
    lines = kemu.ok("logs", "--since", cursor)["lines"]
    text = "\n".join(line["line"] for line in lines)
    assert text.count("AGENT keyPressed entered") == 1
    assert text.count("AGENT keyReleased") == 1
