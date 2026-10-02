"""Runtime failures, finite budgets and reusable session cleanup."""
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
    for fd in Path(f"/proc/{pid}/fd").iterdir():
        try:
            target = os.readlink(fd)
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


def _pid_alive(pid):
    path = Path(f"/proc/{pid}/stat")
    if not path.exists():
        return False
    try:
        return path.read_text().split(")", 1)[1].strip().split()[0] != "Z"
    except FileNotFoundError:
        return False


def _assert_pids_exit(pids, timeout=5):
    deadline = time.monotonic() + timeout
    while any(_pid_alive(pid) for pid in pids):
        assert time.monotonic() < deadline, f"leaked processes: {[pid for pid in pids if _pid_alive(pid)]}"
        # Poll the OS process state; this is not a UI scheduling barrier.
        time.sleep(0.02)


def test_parallel_wait_allows_command_to_satisfy_condition(session, fixtures):
    before = session.open_ready(fixtures["MEGA_CLI_FIXTURE_JAR"])
    canvas = session.command_ref(before, "Open canvas")
    worker_port = _worker_port(session.worker_pid())
    previous = _worker_connections(worker_port)
    with ThreadPoolExecutor(max_workers=1) as executor:
        waiter = executor.submit(session.run, "wait", "screen", "--title", "Canvas ready", "--timeout", "6000", timeout=15, oneshot=True)
        deadline = time.monotonic() + 5
        while not (_worker_connections(worker_port) - previous):
            if waiter.done():
                pytest.fail(f"wait ended before connecting: {waiter.result().raw}")
            assert time.monotonic() < deadline, "wait never connected to worker"
        started = time.monotonic()
        session.ok("activate", canvas, "--timeout", "2000", timeout=15, oneshot=True)
        elapsed = time.monotonic() - started
        waited = waiter.result(timeout=10)
    assert waited.ok, waited.raw
    assert session.title(waited.result) == "Canvas ready"
    assert elapsed < 4, f"command blocked behind wait: {elapsed}s"


def test_failed_open_reports_cause_and_keeps_logs(session, fixtures):
    started = time.monotonic()
    outcome = session.err("open", fixtures["MISSING_CLASS_JAR"], "--headless", code="WORKER_FAILURE")
    assert time.monotonic() - started < 20
    assert "ClassNotFoundException" in outcome.raw
    assert outcome.details.get("causeHint")
    assert session.ok("logs")["lines"]
    session.ok("logs", "cursor")
    session.ok("wait", "log", "--regex", "ClassNotFoundException", "--timeout", "3000")
    opened = session.open_ready(fixtures["COMMAND_FIXTURE_JAR"])
    assert session.title(opened) == "Fixture Menu"


def test_open_timeout_is_configurable(session, fixtures):
    started = time.monotonic()
    timeout = session.err("open", fixtures["SLOW_STARTUP_JAR"], "--headless", "--timeout", "4000", code="OPEN_TIMEOUT")
    assert time.monotonic() - started < 7
    assert 0 < timeout.details["timeoutMs"] <= 4000
    pid = session.worker_pid()
    # No second open: the first launched worker must reach its first screen.
    session.wait_title("Slow startup ready", timeout_ms=10000)
    assert session.worker_pid() == pid
    session.ok("wait", "ready")
    session.err("open", fixtures["SLOW_STARTUP_JAR"], "--headless", code="APP_ALREADY_OPEN")


def test_killed_worker_reports_failure_and_recovers(session, fixtures, workdir):
    session.open_ready(fixtures["MEGA_CLI_FIXTURE_JAR"])
    pid = session.worker_pid()
    os.kill(pid, signal.SIGKILL)
    session.ok("wait", "exit", "--timeout", "10000")
    capture = workdir / "dead-worker.png"
    session.err("screenshot", str(capture), code="WORKER_FAILURE")
    assert not capture.exists()
    assert session.ok("logs")["lines"] is not None
    opened = session.open_ready(fixtures["MEGA_CLI_FIXTURE_JAR"])
    assert session.title(opened) == "Mega menu"


def test_stopped_worker_reports_failure(session, fixtures):
    session.open_ready(fixtures["MEGA_CLI_FIXTURE_JAR"])
    pid = session.worker_pid()
    os.kill(pid, signal.SIGSTOP)
    try:
        outcome = session.run("observe", "--timeout", "1000", timeout=10)
        assert outcome.outcome == "error" and outcome.code in ("TIMEOUT", "WORKER_FAILURE"), outcome.raw
        assert _pid_alive(pid), "ordinary observation timeout killed a live worker"
    finally:
        try:
            os.kill(pid, signal.SIGCONT)
        except ProcessLookupError:
            pass
    assert session.title() == "Mega menu"


def test_crashing_command_callback(session, fixtures):
    before = session.open_ready(fixtures["MEGA_CLI_FIXTURE_JAR"])
    outcome = session.err("activate", session.command_ref(before, "Crash command"), code="WORKER_FAILURE")
    assert "RuntimeException" in outcome.raw
    assert session.title() == "Mega menu"


def test_hanging_command_times_out(session, fixtures):
    before = session.open_ready(fixtures["MEGA_CLI_FIXTURE_JAR"])
    outcome = session.err("activate", session.command_ref(before, "Hang command"), "--timeout", "1500", code="TIMEOUT")
    assert outcome.details["admitted"] is True
    assert outcome.details["effectUnknown"] is True
    session.close()
    opened = session.open_ready(fixtures["MEGA_CLI_FIXTURE_JAR"])
    assert session.title(opened) == "Mega menu"


def test_input_timeout_reports_unknown_effect_without_duplicate_retry(session, fixtures):
    session.open_ready(fixtures["AGENT_CONTRACT_JAR"])
    session.run_command("Slow input")
    cursor = session.ok("logs", "cursor")["cursor"]
    pid = session.worker_pid()
    outcome = session.err("key", "press", "5", "--timeout", "250", code="TIMEOUT")
    assert outcome.details["admitted"] is True
    assert outcome.details["effectUnknown"] is True
    assert outcome.details["releaseScheduled"] is True
    session.ok("wait", "log", "--regex", "AGENT keyReleased", "--since", cursor, "--timeout", "10000")
    session.wait_title("Input released")
    assert session.worker_pid() == pid
    lines = session.ok("logs", "--since", cursor)["lines"]
    text = "\n".join(line["line"] for line in lines)
    assert text.count("AGENT keyPressed entered") == 1
    assert text.count("AGENT keyReleased") == 1


def test_worker_self_exit(session, fixtures):
    session.open_ready(fixtures["MEGA_CLI_FIXTURE_JAR"])
    pid = session.worker_pid()
    session.run("key", "press", "RSK")
    session.ok("wait", "exit", "--timeout", "10000")
    session.close()
    _assert_pids_exit([pid])
    assert session.ok("status")["app"]["status"] != "ready"


def test_stop_and_status_lifecycle(kemu_factory, fixtures):
    session = kemu_factory()
    assert session.ok("status")["session"]["status"]
    session.open_ready(fixtures["COMMAND_FIXTURE_JAR"])
    worker = session.worker_pid()
    diagnostics = session.run("--verbose", "status").diagnostics
    controller = int(diagnostics["pid"])
    # xvfb-run owns both the controller JVM and its private Xvfb sibling.
    # Follow only this controller's verified wrapper, never unrelated displays.
    parent = int(Path(f"/proc/{controller}/stat").read_text().split(")", 1)[1].split()[1])
    private_display = []
    if b"xvfb-run" in Path(f"/proc/{parent}/cmdline").read_bytes():
        children_file = Path(f"/proc/{parent}/task/{parent}/children")
        private_display = [parent, *(int(n) for n in children_file.read_text().split())]
    session.ok("stop")
    _assert_pids_exit([worker, controller, *private_display])
    assert session.ok("status")["session"]["status"] != "running"


def test_close_reopen_reuses_session_without_worker_leaks(session, fixtures):
    session.open_ready(fixtures["COMMAND_FIXTURE_JAR"])
    old_worker = session.worker_pid()
    controller = int(session.run("--verbose", "status").diagnostics["pid"])
    session.close()
    _assert_pids_exit([old_worker])
    after = session.open_ready(fixtures["COMMAND_FIXTURE_JAR"])
    assert session.title(after) == "Fixture Menu"
    assert session.worker_pid() != old_worker
    assert int(session.run("--verbose", "status").diagnostics["pid"]) == controller
