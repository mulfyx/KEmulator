"""Public observations and guarded actions against live LCDUI targets."""

import pytest
from kemu import png_pixel, walk_nodes


def test_observe_schema_and_atomic_command(kemu, fixtures):
    opened = kemu.open_ready(fixtures["MEGA_CLI_FIXTURE_JAR"])
    assert opened["session"]["id"] == kemu.session_id
    assert opened["app"]["name"] == "Mega CLI Fixture"
    observation = kemu.observe()
    screen = kemu.state_of(observation)
    assert screen["kind"] == "list" and screen["title"] == "Mega menu"
    assert screen["size"] == {"width": 240, "height": 320}
    assert isinstance(screen["nodes"], list) and isinstance(screen["commands"], list)
    assert "worker" not in observation and "state" not in observation
    assert all("ref" in command and "activate" in command["actions"] for command in screen["commands"])
    editor = kemu.run_command("Open editor")
    assert kemu.title(editor) == "Mega editor"
    assert any(n.get("value") == "alpha" for n in walk_nodes(kemu.state_of(editor)["nodes"]))
    kemu.ok("key", "press", "SOFT_RIGHT")
    kemu.wait_title("Mega menu")


def test_canvas_input_keys_pointer_drag(kemu, fixtures):
    kemu.open_ready(fixtures["MEGA_CLI_FIXTURE_JAR"])
    kemu.run_command("Open canvas")
    kemu.wait_title("Canvas ready")
    receipt = kemu.ok("key", "press", "FIRE")
    assert receipt["app"]["status"] == "ready"
    kemu.ok("wait", "screen", "--title-regex", r"^Key ")
    kemu.ok("key", "hold", "5", "--duration", "120")
    receipt = kemu.ok("pointer", "tap", "33", "44")
    assert receipt["app"]["status"] == "ready"
    kemu.wait_title("Tap 33,44")
    kemu.ok("drag", "10", "10", "50", "60", "80", "90", "--delay", "10")
    kemu.wait_title("Drag 10,10 -> 80,90")
    before = kemu.state_of()["image"]["frameId"]
    kemu.ok("pointer", "tap", "20", "20")
    kemu.ok("wait", "frame", "--after", str(before), "--timeout", "5000")
    kemu.wait_title("Tap 20,20")


def test_stale_revision_and_unknown_ids(kemu, fixtures):
    # Global revisions have been replaced by target identity: new commands
    # must not invalidate an unchanged command from the same observation.
    kemu.open_ready(fixtures["MEGA_CLI_FIXTURE_JAR"])
    observation = kemu.observe()
    editor = kemu.command_ref(observation, "Open editor")
    kemu.err("key", "press", "NOT_A_KEY", code="UNKNOWN_KEY")
    kemu.err("activate", "@unknown.e999999", code="STALE_REF")
    kemu.run_command("Auto mutate")
    kemu.wait_title("Auto mutate done")
    result = kemu.ok("activate", editor)
    assert kemu.title(result) == "Mega editor"


def test_autonomous_fixture_stale_revisions(kemu, fixtures):
    kemu.open_ready(fixtures["AUTO_SNAPSHOT_FIXTURE_JAR"])
    old = kemu.command_ref(kemu.observe(), "Open editor")
    kemu.wait_title("Auto editor")
    kemu.err("activate", old, code="STALE_REF")
    kemu.close()
    kemu.open_ready(fixtures["MUTABLE_TITLE_FIXTURE_JAR"])
    old = kemu.command_ref(kemu.observe(), "Open editor")
    kemu.wait_title("Mutable menu updated")
    result = kemu.ok("activate", old)
    assert kemu.title(result) == "Mutable editor"


def test_list_select_and_move(kemu, fixtures):
    kemu.open_ready(fixtures["AGENT_CONTRACT_JAR"])
    kemu.run_command("List")
    observation = kemu.observe()
    row = kemu.node_ref(observation, label="old second")
    selected = kemu.ok("select", row)
    assert kemu.title(selected) == "Agent list"
    assert kemu.node(selected, label="old second")["selected"] is True
    activated = kemu.ok("activate", row)
    assert kemu.title(activated) == "Activated old second"


def test_form_choice_gauge_text_field(kemu, fixtures):
    kemu.open_ready(fixtures["FORM_CONTROLS_JAR"])
    before = kemu.observe()
    gauge = kemu.node_ref(before, label="Level")
    name = kemu.node_ref(before, label="Name")
    beta = kemu.node_ref(before, label="beta")
    kemu.ok("set", gauge, "7")
    kemu.ok("select", beta)
    result = kemu.ok("set", name, "xyz")
    assert kemu.node(result, label="Name")["value"] == "xyz"
    assert kemu.node(result, label="Level")["value"] == 7
    assert kemu.node(result, label="beta")["selected"] is True
    assert any(n.get("value") == "field=xyz" for n in walk_nodes(kemu.state_of(result)["nodes"]))
    kemu.err("set", gauge, "11", code="INVALID_REQUEST")
    assert kemu.node(kemu.observe(), label="Level")["value"] == 7


def test_async_form_string_item_advances_revision(kemu, fixtures):
    kemu.open_ready(fixtures["FORM_CONTROLS_JAR"])
    before = kemu.observe()
    to_list = kemu.command_ref(before, "To list")
    kemu.run_command("Load")
    loaded = kemu.ok("wait", "screen", "--text", "Loaded", "--timeout", "5000")
    assert kemu.title(loaded) == "Controls form"
    kemu.ok("activate", to_list)
    kemu.wait_title("Pick list")


def test_text_box_set(kemu, fixtures):
    kemu.open_ready(fixtures["COMMAND_FIXTURE_JAR"])
    kemu.run_command("Open editor")
    observation = kemu.observe()
    editable = next(n for n in walk_nodes(kemu.state_of(observation)["nodes"]) if "set" in n.get("actions", []))
    result = kemu.ok("set", editable["ref"], "pwd")
    assert any(n.get("value") == "pwd" for n in walk_nodes(kemu.state_of(result)["nodes"]))
    kemu.ok("set", editable["ref"], "still live")
    kemu.run_command("Save")
    kemu.wait_title("Saved")
    kemu.err("set", editable["ref"], "wrong owner", code="STALE_REF")


def test_resize_and_rotate(kemu, fixtures):
    kemu.open_ready(fixtures["COMMAND_FIXTURE_JAR"])
    pid = kemu.worker_pid()
    kemu.run_command("Open touch")
    resized = kemu.ok("resize", "320x240")
    assert kemu.state_of(resized)["size"] == {"width": 320, "height": 240}
    kemu.ok("wait", "screen", "--title-regex", r"^Size 320x\d+$")
    rotated = kemu.ok("rotate")
    assert kemu.state_of(rotated)["size"] == {"width": 240, "height": 320}
    kemu.ok("wait", "screen", "--title-regex", r"^Size 240x\d+$")
    assert kemu.worker_pid() == pid


@pytest.mark.parametrize("oneshot", [False, True], ids=["bridge", "oneshot"])
def test_two_fields_from_one_observation(kemu, fixtures, oneshot):
    before = kemu.open_ready(fixtures["AGENT_CONTRACT_JAR"])
    a, b = (kemu.node_ref(before, label=label) for label in ("A", "B"))
    kemu.ok("set", a, "Alice", oneshot=oneshot)
    changed = kemu.ok("set", b, "Bob", oneshot=oneshot)
    assert kemu.node(changed, label="A")["value"] == "Alice"
    assert kemu.node(changed, label="B")["value"] == "Bob"
    assert kemu.node_ref(changed, label="A") == a
    assert kemu.node_ref(changed, label="B") == b


def test_command_removal_never_redirects_ref(kemu, fixtures):
    before = kemu.open_ready(fixtures["AGENT_CONTRACT_JAR"])
    kept, removed = (kemu.command_ref(before, label) for label in ("Kept", "Removed"))
    kemu.run_command("Mutate commands")
    kemu.wait_title("Commands changed")
    kemu.err("activate", removed, code="STALE_REF")
    assert kemu.title() == "Commands changed"
    changed = kemu.ok("activate", kept)
    assert kemu.title(changed) == "Kept 1"
    assert kemu.command_ref(changed, "Replacement") != removed


@pytest.mark.parametrize("collection", ["list", "choice"])
def test_collection_structure_invalidates_old_row(kemu, fixtures, collection):
    before = kemu.open_ready(fixtures["AGENT_CONTRACT_JAR"])
    if collection == "list":
        before = kemu.run_command("List")
    old = kemu.node_ref(before, label="old first")
    kemu.run_command("Change rows" if collection == "list" else "Change choices")
    kemu.wait_title("Rows changed" if collection == "list" else "Choices changed")
    kemu.err("select", old, code="STALE_REF")
    changed = kemu.observe()
    new = kemu.node_ref(changed, label="new first")
    assert new != old
    kemu.ok("select", new)


def test_choice_multiple_can_deselect(kemu, fixtures):
    before = kemu.open_ready(fixtures["AGENT_CONTRACT_JAR"])
    row = kemu.node_ref(before, label="old second")
    kemu.ok("select", row)
    changed = kemu.ok("select", row, "--off")
    assert kemu.node(changed, label="old second")["selected"] is False


@pytest.mark.parametrize("value", ["12345", "oops"])
def test_text_constraints_reject_without_truncating(kemu, fixtures, value):
    before = kemu.open_ready(fixtures["AGENT_CONTRACT_JAR"])
    node = kemu.node(before, label="Digits")
    assert node["maxLength"] == 4
    assert "numeric" in node["constraints"]
    kemu.err("set", node["ref"], value, code="INVALID_REQUEST")
    assert kemu.node(kemu.observe(), label="Digits")["value"] == "12"


def test_refs_from_old_worker_are_stale(kemu, fixtures):
    before = kemu.open_ready(fixtures["AGENT_CONTRACT_JAR"])
    old = kemu.node_ref(before, label="A")
    kemu.close()
    after = kemu.open_ready(fixtures["AGENT_CONTRACT_JAR"])
    kemu.err("set", old, "wrong worker", code="STALE_REF")
    assert kemu.node(after, label="A")["ref"] != old
    assert kemu.node(kemu.observe(), label="A")["value"] == "first"


def test_canvas_switch_captures_current_pixels(kemu, fixtures):
    kemu.open_ready(fixtures["AGENT_CONTRACT_JAR"])
    red = kemu.run_command("Colors")
    image_a = kemu.state_of(red)["image"]
    assert png_pixel(image_a["path"], 40, 40) == (255, 0, 0)
    kemu.ok("key", "press", "5")
    blue = kemu.wait_title("Canvas B")
    image_b = kemu.state_of(blue)["image"]
    assert png_pixel(image_b["path"], 40, 40) == (0, 0, 255)
    assert image_b["frameId"] != image_a["frameId"]
    assert image_b["path"] != image_a["path"]
    # Prior capture is a stable artifact; taking a new image never overwrites it.
    assert png_pixel(image_a["path"], 40, 40) == (255, 0, 0)


def test_ref_action_is_validated_before_mutation(kemu, fixtures):
    before = kemu.open_ready(fixtures["AGENT_CONTRACT_JAR"])
    command = kemu.command_ref(before, "Kept")
    field = kemu.node_ref(before, label="A")
    kemu.err("set", command, "bad", code="UNSUPPORTED_ACTION")
    kemu.err("activate", field, code="UNSUPPORTED_ACTION")
    after = kemu.observe()
    assert kemu.title(after) == "Agent form"
    assert kemu.node(after, label="A")["value"] == "first"


def test_canvas_first_paint_timeout_does_not_relabel_old_pixels(kemu, fixtures):
    kemu.open_ready(fixtures["AGENT_CONTRACT_JAR"])
    red = kemu.run_command("Colors")
    image_a = kemu.state_of(red)["image"]
    assert png_pixel(image_a["path"], 40, 40) == (255, 0, 0)
    kemu.ok("key", "press", "5")
    # First paint deliberately takes 350ms. A short capture either has the
    # actual completed blue frame or explicitly reports frame unavailability.
    response = kemu.run("observe", "--timeout", "30")
    if response.outcome == "error":
        assert response.code == "FRAME_NOT_READY", response.raw
        partial = response.details["observation"]
        assert partial["title"] == "Canvas B" and partial["kind"] == "canvas"
        assert "image" not in partial
        assert image_a["path"] not in response.raw
    else:
        assert response.ok, response.raw
        screen = kemu.state_of(response.result)
        assert screen["title"] == "Canvas B"
        assert png_pixel(screen["image"]["path"], 40, 40) == (0, 0, 255)
    current = kemu.wait_title("Canvas B")
    assert png_pixel(kemu.state_of(current)["image"]["path"], 40, 40) == (0, 0, 255)


@pytest.mark.parametrize("key,title", [("6", "Game partial flush"), ("7", "Game repaint")])
def test_game_canvas_owns_initial_pixels_and_repaints(kemu, fixtures, key, title):
    kemu.open_ready(fixtures["AGENT_CONTRACT_JAR"])
    red = kemu.run_command("Colors")
    assert png_pixel(kemu.state_of(red)["image"]["path"], 100, 100) == (255, 0, 0)
    kemu.ok("key", "press", key)
    game = kemu.wait_title(title)
    image = kemu.state_of(game)["image"]
    assert png_pixel(image["path"], 40, 40) == (0, 0, 255)
    assert png_pixel(image["path"], 100, 100) == (255, 255, 255)
    kemu.ok("key", "press", "5")
    changed = kemu.wait_title("Game repaint changed")
    image = kemu.state_of(changed)["image"]
    assert png_pixel(image["path"], 40, 40) == (0, 255, 0)
    assert png_pixel(image["path"], 100, 100) == (0, 255, 0)
