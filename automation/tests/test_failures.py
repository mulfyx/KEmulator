"""Failed opens, worker death, hung workers, and session recovery.

These tests intentionally destabilize workers, so they run against their own
controller sessions instead of the shared one.
"""

import os
import signal
import time

import pytest


@pytest.fixture()
def session(kemu_factory):
    return kemu_factory()


def test_failed_open_reports_cause_and_keeps_logs(session, fixtures):
    started = time.monotonic()
    outcome = session.err("open", fixtures["MISSING_CLASS_JAR"], "--headless",
                          "--wait-ready", code="WORKER_FAILURE")
    elapsed = time.monotonic() - started
    details = outcome.details
    assert details["reason"] == "worker-exited"
    assert isinstance(details["exitCode"], int)
    assert "ClassNotFoundException" in details["causeHint"]
    assert details["logTail"]
    assert details["worker"]["logPath"]
    assert elapsed < 20, f"failed open took {elapsed:.1f}s (fixed-timeout regression)"

    lines = session.ok("logs", "read")["lines"]
    assert lines
    session.ok("logs", "cursor")
    waited = session.ok("wait", "log", "--regex", "ClassNotFoundException",
                        "--timeout", "3000")
    assert waited["matched"] is True

    opened = session.open_ready(fixtures["COMMAND_FIXTURE_JAR"])
    assert opened["ready"] is True
    session.close()
    session.err("logs", "read", code="NO_ACTIVE_APP")


def test_open_timeout_is_configurable(session, fixtures):
    outcome = session.err("open", fixtures["COMMAND_FIXTURE_JAR"], "--headless",
                          "--wait-ready", "--open-timeout", "150",
                          code="OPEN_TIMEOUT")
    assert outcome.details["timeoutMs"] == 150
    opened = session.open_ready(fixtures["COMMAND_FIXTURE_JAR"])
    assert opened["ready"] is True


def test_killed_worker_reports_failure_and_recovers(session, fixtures, workdir):
    opened = session.open_ready(fixtures["MEGA_CLI_FIXTURE_JAR"])
    worker_pid = int(opened["worker"]["pid"])
    os.kill(worker_pid, signal.SIGKILL)
    time.sleep(0.5)

    capture = workdir / "dead-worker.png"
    session.err("screenshot", "--out", str(capture), code="WORKER_FAILURE")
    assert not capture.exists()
    assert session.ok("logs", "read")["lines"] is not None

    opened = session.open_ready(fixtures["MEGA_CLI_FIXTURE_JAR"])
    assert opened["displayable"]["title"] == "Mega menu"


def test_stopped_worker_reports_failure(session, fixtures):
    opened = session.open_ready(fixtures["MEGA_CLI_FIXTURE_JAR"])
    worker_pid = int(opened["worker"]["pid"])
    os.kill(worker_pid, signal.SIGSTOP)
    try:
        session.err("observe", code="WORKER_FAILURE")
    finally:
        try:
            os.kill(worker_pid, signal.SIGCONT)
        except ProcessLookupError:
            pass
    opened = session.open_ready(fixtures["MEGA_CLI_FIXTURE_JAR"])
    assert opened["displayable"]["title"] == "Mega menu"


def test_crashing_command_callback(session, fixtures):
    session.open_ready(fixtures["MEGA_CLI_FIXTURE_JAR"])
    observation = session.observe()
    outcome = session.err(
        "command", "run",
        "--id", str(session.command_id(observation, "Crash command")),
        "--expect-revision", str(session.revision(observation)),
        code="WORKER_FAILURE")
    assert "RuntimeException" in outcome.details.get("errorType", "")
    assert session.observe()["active"] is True  # worker survived the callback


def test_hanging_command_times_out(session, fixtures):
    session.open_ready(fixtures["MEGA_CLI_FIXTURE_JAR"])
    observation = session.observe()
    session.err(
        "command", "run",
        "--id", str(session.command_id(observation, "Hang command")),
        "--expect-revision", str(session.revision(observation)),
        "--timeout", "1500",
        code="TIMEOUT")
    # The LCDUI thread is stuck forever; close must still tear the worker down.
    session.close()
    opened = session.open_ready(fixtures["MEGA_CLI_FIXTURE_JAR"])
    assert opened["ready"] is True


def test_worker_self_exit(session, fixtures):
    opened = session.open_ready(fixtures["MEGA_CLI_FIXTURE_JAR"])
    assert opened["displayable"]["softkeys"]["right"] == "Exit"
    # EXIT-type commands are softkey-only; the worker dies mid-response, so
    # the key press outcome itself is not asserted.
    session.run("key", "press", "RSK", "--wait-dispatched")
    exited = session.ok("wait", "worker-exit", "--timeout", "10000")
    assert exited["exited"] is True
    closed = session.ok("close")
    assert closed["closed"] is False
    assert closed["reason"] == "not_running"
    assert session.ok("state")["active"] is False


def test_stop_and_status_lifecycle(kemu_factory):
    session = kemu_factory()
    status = session.ok("status")
    assert status["running"] is True
    assert session.ok("state")["active"] is False
    assert session.ok("observe")["active"] is False
    session.err("logs", "cursor", code="NO_ACTIVE_APP")

    stopped = session.ok("stop", "--force")
    assert stopped["stopped"] is True
    assert session.ok("status")["running"] is False
