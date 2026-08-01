"""JSONL bridge: one CLI process, many commands."""

import json


def _raw_bridge(kemu, lines):
    import subprocess

    proc = subprocess.run(
        ["./kemu.sh", "--session-id", kemu.session_id, "--json", "bridge"],
        cwd=kemu.release_dir,
        input="\n".join(lines) + "\n",
        capture_output=True,
        text=True,
        timeout=120,
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
    assert responses[0]["ok"] is True and responses[0]["command"] == "help"
    assert responses[1]["ok"] is True
    assert responses[2]["ok"] is False
    assert responses[2]["error"]["code"] == "UNKNOWN_COMMAND"


def test_bridge_rejects_malformed_and_nested_requests(kemu):
    responses = _raw_bridge(kemu, [
        "not json",
        json.dumps({"id": 1, "argv": "open"}),
        json.dumps({"id": 2, "argv": ["bridge"]}),
        json.dumps({"id": 3, "argv": ["--session-id", "x", "status"]}),
    ])
    assert responses[0]["id"] is None
    for response in responses:
        assert response["ok"] is False
        assert response["error"]["code"] == "USAGE_ERROR"


def test_wrapper_uses_one_bridge_process(kemu):
    if not kemu.use_bridge:
        return
    kemu.ok("status")
    first = kemu._bridge_session().proc.pid
    kemu.ok("status")
    assert kemu._bridge_session().proc.pid == first
