"""Observation schema, LCDUI controls, input, resize/rotate, text-box."""


def test_observe_schema_and_atomic_command(kemu, fixtures):
    opened = kemu.open_ready(fixtures["MEGA_CLI_FIXTURE_JAR"])
    assert opened["status"] == "ready"
    assert opened["ready"] is True
    assert opened["displayable"]["kind"] == "list"
    assert "gamePath" not in opened and "gameName" not in opened

    observation = kemu.observe()
    assert observation["active"] is True
    assert observation["schemaVersion"] == 3
    displayable = observation["displayable"]
    assert displayable["title"] == "Mega menu"
    assert isinstance(displayable["commands"], list)
    assert isinstance(observation["revision"], int)
    for legacy_key in ("title", "displayableKind", "commands", "imageBase64"):
        assert legacy_key not in observation

    kemu.run_command("Open editor")
    observation = kemu.observe()
    assert observation["displayable"]["kind"] == "text_box"
    assert observation["displayable"]["title"] == "Mega editor"
    assert observation["displayable"]["text"] == "alpha"

    kemu.ok("key", "press", "SOFT_RIGHT", "--wait-dispatched")
    assert kemu.title() == "Mega menu"


def test_canvas_input_keys_pointer_drag(kemu, fixtures):
    kemu.open_ready(fixtures["MEGA_CLI_FIXTURE_JAR"])
    kemu.run_command("Open canvas")
    assert kemu.title() == "Canvas ready"

    kemu.ok("key", "press", "FIRE", "--wait-dispatched")
    assert kemu.title().startswith("Key ")

    kemu.ok("key", "hold", "5", "--duration", "120", "--wait-release")
    assert kemu.title().startswith("Key ")

    kemu.ok("pointer", "tap", "33", "44", "--wait-dispatched")
    assert kemu.title() == "Tap 33,44"

    kemu.ok("drag", "10", "10", "50", "60", "80", "90", "--delay", "10")
    assert kemu.title() == "Drag 10,10 -> 80,90"

    frame_revision = int(kemu.observe()["frameRevision"])
    kemu.ok("pointer", "tap", "20", "20", "--wait-dispatched")
    waited = kemu.ok("wait", "frame",
                     "--after-revision", str(frame_revision),
                     "--timeout", "5000")
    assert waited["matched"] is True
    kemu.ok("wait", "idle", "--timeout", "5000")


def test_stale_revision_and_unknown_ids(kemu, fixtures):
    kemu.open_ready(fixtures["MEGA_CLI_FIXTURE_JAR"])
    observation = kemu.observe()
    revision = kemu.revision(observation)

    kemu.err("key", "press", "NOT_A_KEY", "--wait-dispatched", code="UNKNOWN_KEY")
    kemu.err("command", "run", "--id", "9999", "--expect-revision", str(revision),
             code="UNKNOWN_COMMAND_ID")

    observation = kemu.observe()
    stale = kemu.revision(observation)
    editor_id = kemu.command_id(observation, "Open editor")
    kemu.ok("command", "run",
            "--id", str(kemu.command_id(observation, "Auto mutate")),
            "--expect-revision", str(stale))
    kemu.wait_title("Auto mutate done")
    kemu.err("command", "run", "--id", str(editor_id),
             "--expect-revision", str(stale), code="STALE_REVISION")

    kemu.run_command("Late command", wait_next=False)
    assert kemu.title() == "Late command selected"


def test_autonomous_fixture_stale_revisions(kemu, fixtures):
    kemu.open_ready(fixtures["AUTO_SNAPSHOT_FIXTURE_JAR"])
    observation = kemu.observe()
    assert observation["displayable"]["title"] == "Auto menu"
    revision = kemu.revision(observation)
    editor_id = kemu.command_id(observation, "Open editor")
    kemu.wait_title("Auto editor")
    kemu.err("command", "run", "--id", str(editor_id),
             "--expect-revision", str(revision), code="STALE_REVISION")
    kemu.close()

    kemu.open_ready(fixtures["MUTABLE_TITLE_FIXTURE_JAR"])
    observation = kemu.observe()
    assert observation["displayable"]["title"] == "Mutable menu"
    revision = kemu.revision(observation)
    editor_id = kemu.command_id(observation, "Open editor")
    kemu.wait_title("Mutable menu updated")
    kemu.err("command", "run", "--id", str(editor_id),
             "--expect-revision", str(revision), code="STALE_REVISION")


def test_list_select_and_move(kemu, fixtures):
    kemu.open_ready(fixtures["FORM_CONTROLS_JAR"])
    kemu.run_command("To list")
    observation = kemu.observe()
    assert observation["displayable"]["kind"] == "list"
    assert [item["text"] for item in observation["displayable"]["items"]] == [
        "one", "two", "three"]

    result = kemu.ok("list", "select", "2",
                     "--expect-revision", str(kemu.revision(observation)))
    assert result["selectedIndex"] == 2
    result = kemu.ok("list", "move", "up")
    assert result["selectedIndex"] == 1
    result = kemu.ok("list", "move", "down", "--count", "2")
    assert result["selectedIndex"] == 2
    kemu.err("list", "select", "9", code="INVALID_REQUEST")


def test_form_choice_gauge_text_field(kemu, fixtures):
    kemu.open_ready(fixtures["FORM_CONTROLS_JAR"])
    observation = kemu.observe()
    displayable = observation["displayable"]
    assert displayable["kind"] == "form"
    kinds = [item["kind"] for item in displayable["items"]]
    assert kinds == ["string-item", "gauge", "choice-group", "text-field"]

    result = kemu.ok("gauge", "set", "7",
                     "--expect-revision", str(kemu.revision(observation)))
    assert result["value"] == 7

    result = kemu.ok("choice", "set", "1")
    assert result["selectedIndex"] == 1

    result = kemu.ok("text-field", "set", "xyz")
    assert result["value"] == "xyz"

    items = kemu.observe()["displayable"]["items"]
    assert items[0]["text"] == "field=xyz"  # item-state callbacks were delivered
    assert items[1]["value"] == 7
    assert items[2]["selectedIndex"] == 1
    assert items[3]["text"] == "xyz"

    kemu.err("gauge", "set", "5", "--expect-revision", "0", code="STALE_REVISION")
    kemu.err("choice", "set", "9", code="INVALID_REQUEST")


def test_text_box_set(kemu, fixtures):
    kemu.open_ready(fixtures["COMMAND_FIXTURE_JAR"])
    observation = kemu.observe()
    kemu.err("text-box", "set", "nope",
             "--expect-revision", str(kemu.revision(observation)),
             code="LCDUI_CONTROL_UNAVAILABLE")

    kemu.run_command("Open editor")
    observation = kemu.observe()
    assert observation["displayable"]["kind"] == "text_box"
    editor_revision = kemu.revision(observation)

    result = kemu.ok("text-box", "set", "pwd",
                     "--expect-revision", str(editor_revision))
    assert result["text"] == "pwd"
    assert result["caret"] == 3
    assert result["newRevision"] > result["oldRevision"]
    assert kemu.observe()["displayable"]["text"] == "pwd"

    kemu.err("text-box", "set", "nope",
             "--expect-revision", str(editor_revision), code="STALE_REVISION")
    assert kemu.observe()["displayable"]["text"] == "pwd"

    kemu.ok("command", "run", "--label", "Save",
            "--expect-revision", str(kemu.revision()),
            "--wait-next-display")
    assert kemu.title() == "Saved"


def test_resize_and_rotate(kemu, fixtures):
    kemu.open_ready(fixtures["COMMAND_FIXTURE_JAR"])
    worker_pid = kemu.ok("status")["worker"]["pid"]
    kemu.run_command("Open touch")

    observation = kemu.observe()
    resized = kemu.ok("resize", "320x240",
                      "--expect-revision", str(kemu.revision(observation)),
                      "--wait-frame", "--timeout", "10000")
    assert (resized["oldWidth"], resized["oldHeight"]) == (240, 320)
    assert (resized["width"], resized["height"]) == (320, 240)
    assert resized["newRevision"] > resized["oldRevision"]

    # Canvas.sizeChanged reports the canvas client size (softkey bar excluded).
    kemu.ok("wait", "display",
            "--after-revision", str(resized["newRevision"]),
            "--timeout", "10000")
    observation = kemu.observe()
    assert observation["displayable"]["title"].startswith("Size 320x")
    assert (observation["width"], observation["height"]) == (320, 240)

    rotated = kemu.ok("rotate", "--wait-frame", "--timeout", "10000")
    assert (rotated["width"], rotated["height"]) == (240, 320)
    kemu.ok("wait", "display",
            "--after-revision", str(rotated["newRevision"]),
            "--timeout", "10000")
    assert kemu.title().startswith("Size 240x")

    assert kemu.ok("status")["worker"]["pid"] == worker_pid

    current = kemu.revision()
    kemu.ok("resize", "240x320", "--expect-revision", str(current))
    kemu.err("resize", "100x100", "--expect-revision", str(current),
             code="STALE_REVISION")
    assert (kemu.observe()["width"], kemu.observe()["height"]) == (240, 320)
