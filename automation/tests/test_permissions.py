"""Permission prompts: runtime, ordering, and startApp()-blocking requests."""


def _pending_permission_id(kemu):
    request = kemu.observe()["permissionRequest"]
    assert request is not None
    return int(request["id"])


def test_allow_and_deny(kemu, fixtures):
    kemu.open_ready(fixtures["MEGA_CLI_FIXTURE_JAR"])

    pending = kemu.run_command("Ask camera", wait_next=False)
    assert pending["pending"] is True
    assert pending["status"] == "permission-pending"
    assert isinstance(pending["permissionRequest"], dict)
    camera_id = _pending_permission_id(kemu)
    assert camera_id == int(pending["permissionRequest"]["id"])
    kemu.ok("permission", "allow", str(camera_id))
    assert kemu.title() == "Camera allowed"

    kemu.run_command("Ask IMEI", wait_next=False)
    kemu.ok("permission", "allow", str(_pending_permission_id(kemu)))
    assert kemu.title().startswith("IMEI allowed ")


def test_deny_reports_denied(kemu, fixtures):
    kemu.open_ready(fixtures["COMMAND_FIXTURE_JAR"])
    kemu.run_command("Ask camera", wait_next=False)
    kemu.ok("permission", "deny", str(_pending_permission_id(kemu)))
    assert kemu.title() == "Camera denied"


def test_permission_ordering_race(kemu, fixtures):
    kemu.open_ready(fixtures["MEGA_CLI_FIXTURE_JAR"])
    kemu.run_command("Ask permission race", wait_next=False)
    kemu.ok("wait", "permission", "--timeout", "5000")

    head_id = _pending_permission_id(kemu)
    second_id = head_id + 1
    kemu.err("permission", "deny", str(second_id),
             code="PERMISSION_ORDER_VIOLATION")
    kemu.ok("permission", "allow", str(head_id))
    kemu.ok("wait", "permission", "--timeout", "5000")
    assert _pending_permission_id(kemu) == second_id

    before = kemu.revision()
    kemu.ok("permission", "deny", str(second_id))
    kemu.ok("wait", "display", "--after-revision", str(before),
            "--timeout", "5000")
    assert kemu.title().startswith("Permission race ")
    kemu.err("permission", "allow", str(second_id), code="UNKNOWN_PERMISSION_ID")


def test_allow_always_persists_for_worker(kemu, fixtures):
    kemu.open_ready(fixtures["MEGA_CLI_FIXTURE_JAR"])
    kemu.run_command("Ask camera", wait_next=False)
    kemu.ok("permission", "allow", "--always")
    assert kemu.title() == "Camera allowed"

    completed = kemu.run_command("Ask camera", wait_next=False)
    assert "pending" not in completed  # no prompt: policy persists for worker
    assert kemu.title() == "Camera allowed"
    assert kemu.observe()["permissionRequest"] is None


def test_startup_permission_async_open(kemu, fixtures):
    opened = kemu.ok("open", fixtures["STARTUP_PERMISSION_JAR"], "--headless")
    assert opened["status"] == "starting"
    assert opened["ready"] is False
    assert isinstance(opened["worker"]["pid"], str)

    kemu.ok("wait", "permission", "--timeout", "30000")
    kemu.ok("permission", "allow", str(_pending_permission_id(kemu)))
    kemu.wait_title("startup permission allowed", timeout_ms=15000)
    ready = kemu.ok("wait", "worker-ready", "--timeout", "15000")
    assert ready["matched"] is True


def test_startup_permission_wait_ready_returns_pending(kemu, fixtures):
    opened = kemu.ok("open", fixtures["STARTUP_PERMISSION_JAR"], "--headless",
                     "--wait-ready", "--open-timeout", "60000")
    assert opened["status"] == "pending-permission"
    assert isinstance(opened["permissionRequest"], dict)

    kemu.ok("permission", "deny")
    kemu.wait_title("startup permission denied", timeout_ms=15000)
