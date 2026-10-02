"""Failed opens, worker death, hung workers, and session recovery.

These tests intentionally destabilize workers, so they run against their own
controller sessions instead of the shared one.
"""

import os
import signal
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import pytest


@pytest.fixture()
def session(kemu_factory):
    return kemu_factory()


def _worker_port(pid):
    inodes = set()
    for descriptor in Path(f"/proc/{pid}/fd").iterdir():
        try:
            target = os.readlink(descriptor)
        except FileNotFoundError:
            continue
        if target.startswith("socket:["):
            inodes.add(target[8:-1])
    for table in ("/proc/net/tcp", "/proc/net/tcp6"):
        for line in Path(table).read_text().splitlines()[1:]:
            fields = line.split()
            if fields[3] == "0A" and fields[9] in inodes:
                return int(fields[1].split(":")[1], 16)
    pytest.fail(f"worker {pid} has no listening socket")


def _worker_connections(port):
    connections = set()
    for table in ("/proc/net/tcp", "/proc/net/tcp6"):
        for line in Path(table).read_text().splitlines()[1:]:
            fields = line.split()
            if fields[3] == "01" and int(fields[1].split(":")[1], 16) == port:
                connections.add((fields[1], fields[2]))
    return connections


def test_parallel_wait_allows_command_to_satisfy_condition(session, fixtures):
    opened = session.open_ready(fixtures["MEGA_CLI_FIXTURE_JAR"])
    assert opened["state"]["displayable"]["title"] == "Mega menu"
    worker_port = _worker_port(opened["worker"]["pid"])
    previous_connections = _worker_connections(worker_port)

    with ThreadPoolExecutor(max_workers=1) as executor:
        waiter = executor.submit(
            session.run, "wait", "display", "--title", "Canvas ready",
            "--timeout", "6000", timeout=15, oneshot=True)
        # A new worker connection proves the wait has entered the protocol
        # path before the command, without relying on a scheduling sleep.
        deadline = time.monotonic() + 5
        while not (_worker_connections(worker_port) - previous_connections):
            if waiter.done():
                pytest.fail(f"wait ended before connecting: {waiter.result().raw}")
            assert time.monotonic() < deadline, "wait never connected to worker"

        started = time.monotonic()
        session.ok("command", "run", "--label", "Open canvas",
                   "--timeout", "1000", timeout=15, oneshot=True)
        command_elapsed = time.monotonic() - started
        waited = waiter.result(timeout=10)

    assert waited.ok, waited.raw
    assert waited["matched"] is True
    assert waited["state"]["displayable"]["title"] == "Canvas ready"
    assert command_elapsed < 3, f"command blocked behind wait for {command_elapsed:.1f}s"


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
    assert opened["status"] == "ready"
    session.close()
    session.err("logs", "read", code="NO_ACTIVE_APP")


def test_open_timeout_is_configurable(session, fixtures):
    outcome = session.err("open", fixtures["COMMAND_FIXTURE_JAR"], "--headless",
                          "--wait-ready", "--open-timeout", "150",
                          code="OPEN_TIMEOUT")
    assert outcome.details["timeoutMs"] == 150
    opened = session.open_ready(fixtures["COMMAND_FIXTURE_JAR"])
    assert opened["status"] == "ready"


def test_killed_worker_reports_failure_and_recovers(session, fixtures, workdir):
    opened = session.open_ready(fixtures["MEGA_CLI_FIXTURE_JAR"])
    worker_pid = int(opened["worker"]["pid"])
    os.kill(worker_pid, signal.SIGKILL)
    exited = session.ok("wait", "worker-exit", "--timeout", "10000")
    assert exited["matched"] is True and "exitCode" in exited

    capture = workdir / "dead-worker.png"
    session.err("screenshot", str(capture), code="WORKER_FAILURE")
    assert not capture.exists()
    assert session.ok("logs", "read")["lines"] is not None

    opened = session.open_ready(fixtures["MEGA_CLI_FIXTURE_JAR"])
    assert opened["state"]["displayable"]["title"] == "Mega menu"


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
    assert opened["state"]["displayable"]["title"] == "Mega menu"


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
    assert opened["status"] == "ready"


def test_worker_self_exit(session, fixtures):
    opened = session.open_ready(fixtures["MEGA_CLI_FIXTURE_JAR"])
    assert opened["state"]["displayable"]["softkeys"]["right"] == "Exit"
    # EXIT-type commands are softkey-only; the worker dies mid-response, so
    # the key press outcome itself is not asserted.
    session.run("key", "press", "RSK", "--wait-dispatched")
    exited = session.ok("wait", "worker-exit", "--timeout", "10000")
    assert exited["matched"] is True and exited["condition"] == "worker-exit"
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
