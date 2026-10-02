"""JSONL bridge: one CLI process, many commands."""

import json
import os


def _raw_bridge(kemu, lines, env=None):
    import subprocess

    proc = subprocess.run(
        ["./kemu.sh", "--session", kemu.session_id, "--json", "bridge"],
        cwd=kemu.release_dir,
        input="\n".join(lines) + "\n",
        capture_output=True,
        text=True,
        timeout=120,
        env=env,
    )
    assert proc.returncode == 0, proc.stderr
    return [json.loads(line) for line in proc.stdout.splitlines() if line.strip()]


def test_bridge_serves_many_requests(kemu):
    responses = _raw_bridge(kemu, [
        json.dumps({"id": 1, "argv": ["help", "open"]}),
        json.dumps({"id": "two", "argv": ["status"]}),
        json.dumps({"id": 3, "argv": ["nope"]}),
    ])
    assert [r["id"] for r in responses] == [1, "two", 3]
    assert responses[0]["outcome"] == "done" and responses[0]["command"] == "help"
    assert responses[1]["outcome"] == "done"
    assert responses[2]["outcome"] == "error"
    assert responses[2]["error"]["code"] == "UNKNOWN_COMMAND"


def test_bridge_rejects_malformed_and_nested_requests(kemu):
    responses = _raw_bridge(kemu, [
        "not json",
        json.dumps({"id": 1, "argv": "open"}),
        json.dumps({"id": 2, "argv": ["bridge"]}),
        json.dumps({"id": 3, "argv": ["--session", "x", "status"]}),
    ])
    assert responses[0]["id"] is None
    for response in responses:
        assert response["outcome"] == "error"
        assert response["error"]["code"] == "USAGE_ERROR"


def test_wrapper_uses_one_bridge_process(kemu):
    if not kemu.use_bridge:
        return
    kemu.ok("status")
    first = kemu._bridge_session().proc.pid
    kemu.ok("status")
    assert kemu._bridge_session().proc.pid == first


def test_bridge_eof_keeps_session_alive(kemu, fixtures):
    kemu.open_ready(fixtures["COMMAND_FIXTURE_JAR"])
    pid = kemu.worker_pid()
    responses = _raw_bridge(kemu, [json.dumps({"id": "observe", "argv": ["observe"]})])
    assert responses[0]["result"]["observation"]["title"] == "Fixture Menu"
    assert kemu.worker_pid() == pid
    assert kemu.title() == "Fixture Menu"


def test_explicit_bridge_session_overrides_environment_and_survives_eof(kemu, fixtures):
    kemu.open_ready(fixtures["COMMAND_FIXTURE_JAR"])
    pid = kemu.worker_pid()
    env = os.environ.copy()
    env["KEMU_SESSION"] = f"env-other-{kemu.session_id}"
    responses = _raw_bridge(kemu, [
        json.dumps({"id": "read", "argv": ["observe"]}),
        json.dumps({"id": "status", "argv": ["status"]}),
    ], env=env)
    assert [r["id"] for r in responses] == ["read", "status"]
    assert all(r["outcome"] == "done" for r in responses)
    assert all(r["result"]["session"]["id"] == kemu.session_id for r in responses)
    assert responses[0]["result"]["observation"]["title"] == "Fixture Menu"
    assert kemu.worker_pid() == pid
