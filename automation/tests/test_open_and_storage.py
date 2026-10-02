"""open contract, session storage safety, RMS/state archives, memory card."""

import hashlib
from pathlib import Path

import pytest


def _tree_digest(root: Path) -> dict[str, str]:
    return {
        str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest()
        for path in sorted(root.rglob("*"))
        if path.is_file()
    }


def _write_card_root(fixtures, root: Path) -> Path:
    (root / "e").mkdir(parents=True)
    jar_bytes = Path(fixtures["PROPS_PLAIN_JAR"]).read_bytes()
    (root / "e" / "app.jar").write_bytes(jar_bytes)
    (root / "e" / "app.jad").write_text(
        "MIDlet-1: Command Fixture,,fixtures.CommandFixtureMidlet\n"
        "MIDlet-Name: Command Fixture\n"
        "MIDlet-Jar-URL: app.jar\n")
    (root / "payload.txt").write_text("sentinel-payload\n")
    return root / "e" / "app.jad"


def test_open_variants(kemu, fixtures):
    opened = kemu.open_ready(fixtures["DASH_PREFIXED_JAR"])
    assert opened["app"]["status"] == "ready"
    assert opened["app"]["name"] == "Command Fixture"
    kemu.close()

    opened = kemu.ok("open", "--headless", "--", fixtures["DASH_PREFIXED_MEGA_JAR"])
    assert opened["app"]["name"] == "Mega CLI Fixture"
    kemu.close()

    opened = kemu.open_ready(fixtures["MULTI_MIDLET_JAR"], "--midlet", "2")
    assert opened["app"]["name"]
    assert opened["observation"]["title"] == "Mutable menu"
    kemu.close()

    opened = kemu.open_ready(fixtures["BOM_MANIFEST_JAR"])
    assert opened["app"]["name"] == "Command Fixture"
    kemu.close()

    opened = kemu.open_ready(fixtures["PARENT_RELATIVE_JAD"])
    assert opened["observation"]["title"] == "Parent Relative Target Menu"
    kemu.err("open", fixtures["COMMAND_FIXTURE_JAR"], "--headless",
             code="APP_ALREADY_OPEN")


def test_open_midlet_selection(kemu, fixtures):
    kemu.err("open", fixtures["MEGA_MULTI_MIDLET_JAR"], "--headless",
             code="MIDLET_SELECTION_REQUIRED")
    kemu.err("open", fixtures["MEGA_MULTI_MIDLET_JAR"], "--midlet", "3",
             "--headless", code="UNKNOWN_MIDLET")


def test_worker_sees_merged_suite_properties(kemu, fixtures):
    cases = [
        ("PROPS_MANIFEST_JAR", (), "MANIFEST ONLY TITLE"),
        ("PROPS_JAD_ONLY_JAD", (), "JAD ONLY TITLE"),
        ("PROPS_MANIFEST_FALLBACK_JAD", (), "MANIFEST ONLY TITLE"),
        ("PROPS_OVERRIDE_JAD", (), "JAD OVERRIDE TITLE"),
        ("PROPS_MULTI_MIDLET_JAD", ("--midlet", "2"), "MULTI JAD TITLE"),
    ]
    for env_key, extra, title in cases:
        opened = kemu.open_ready(fixtures[env_key], *extra)
        assert opened["observation"]["title"] == title, env_key
        kemu.close()


def test_reset_state_preserves_explicit_file_root(kemu, fixtures, workdir):
    card_root = workdir / "card-root"
    jad = _write_card_root(fixtures, card_root)
    before = _tree_digest(card_root)

    opened = kemu.ok("open", str(jad), "--headless",
                     "--data-dir", str(workdir / "card-data"),
                     "--file-root", str(card_root),
                     "--reset-state")
    assert opened["observation"]["title"]
    kemu.close()

    outcome = kemu.err("open", str(jad), "--headless",
                       "--data-dir", str(workdir / "card-data"),
                       "--file-root", str(card_root),
                       "--reset-state", "--reset-file-root",
                       code="STORAGE_OVERLAP")

    kemu.err("open", str(jad), "--headless",
             "--data-dir", str(card_root), "--reset-state",
             code="STORAGE_OVERLAP")

    assert _tree_digest(card_root) == before


def test_implicit_session_local_reset_still_works(kemu, fixtures, workdir):
    data_dir = workdir / "implicit-data"
    kemu.ok("open", fixtures["PROPS_PLAIN_JAR"], "--headless",
            "--data-dir", str(data_dir), "--reset-state")
    kemu.close()

    marker = data_dir / "files" / "marker.txt"
    marker.parent.mkdir(parents=True, exist_ok=True)
    marker.write_text("stale\n")

    kemu.ok("open", fixtures["PROPS_PLAIN_JAR"], "--headless",
            "--data-dir", str(data_dir), "--reset-state")
    kemu.close()
    assert not marker.exists()


def test_invalid_worker_heap_preserves_state_before_reset(kemu, fixtures, workdir):
    data_dir = workdir / "invalid-heap-data"
    jar = fixtures["RMS_COUNTER_JAR"]
    kemu.ok("open", jar, "--headless", "--data-dir", str(data_dir),
            "--reset-state")
    assert kemu.title() == "RMS count 1"
    kemu.close()

    assert _tree_digest(data_dir / "rms"), "fixture did not write actual RMS data"
    before = _tree_digest(data_dir)
    kemu.err("open", jar, "--headless", "--data-dir", str(data_dir),
             "--reset-state", "--worker-xmx", "512MB",
             code="USAGE_ERROR")
    assert _tree_digest(data_dir) == before

    kemu.ok("open", jar, "--headless", "--data-dir", str(data_dir))
    assert kemu.title() == "RMS count 2"
    kemu.close()


@pytest.mark.parametrize("scope,export_action,import_action,archive_root", [
    ("rms", "export", "import", "rms"),
    ("state", "snapshot", "restore", "data"),
    ("state", "snapshot", "restore", "rms"),
    ("state", "snapshot", "restore", "files"),
])
def test_restore_rejects_archive_in_storage_root(
        kemu, fixtures, workdir, scope, export_action, import_action, archive_root):
    case_dir = workdir / f"nested-archive-{scope}-{archive_root}"
    roots = {name: case_dir / name for name in ("data", "rms", "files")}
    jar = fixtures["RMS_COUNTER_JAR"]
    storage_options = ("--data-dir", str(roots["data"]),
                       "--rms-dir", str(roots["rms"]),
                       "--file-root", str(roots["files"]))
    kemu.ok("open", jar, "--headless", *storage_options)
    assert kemu.title() == "RMS count 1"
    kemu.close()
    (roots["files"] / "sentinel.txt").write_text("memory card\n")

    archive = case_dir / "backup.zip"
    kemu.ok("storage", *(() if scope == "state" else ("rms",)), export_action, str(archive))
    nested_archive = roots[archive_root] / "backup.zip"
    nested_archive.write_bytes(archive.read_bytes())
    before = {name: _tree_digest(root) for name, root in roots.items()}

    kemu.err("storage", *(() if scope == "state" else ("rms",)), import_action, str(nested_archive), code="STORAGE_ERROR")
    assert {name: _tree_digest(root) for name, root in roots.items()} == before

    kemu.ok("storage", *(() if scope == "state" else ("rms",)), import_action, str(archive))
    assert (roots["files"] / "sentinel.txt").read_text() == "memory card\n"
    kemu.ok("open", jar, "--headless", *storage_options)
    assert kemu.title() == "RMS count 2"
    kemu.close()


def test_memory_card_mapping(kemu, fixtures, workdir):
    memcard_root = workdir / "memcard-root"
    opened = kemu.ok("open", fixtures["PROBE_MEMORYCARD_JAD"], "--headless",
                     "--data-dir", str(workdir / "memcard-data"),
                     "--file-root", str(memcard_root),
                     "--reset-state")
    kemu.wait_title("WROTE file:///root/e/probe.txt", timeout_ms=15000)
    kemu.close()
    assert (memcard_root / "e" / "probe.txt").read_bytes() == b"probe"

    kemu.ok("open", fixtures["PROBE_DRIVE_E_JAD"], "--headless",
            "--data-dir", str(workdir / "memcard-data"),
            "--file-root", str(memcard_root))
    kemu.wait_title("WROTE file:///E:/probe-e.txt", timeout_ms=15000)
    kemu.close()
    assert (memcard_root / "e" / "probe-e.txt").read_bytes() == b"probe"


def test_rms_archives_and_state_snapshot(kemu, fixtures, workdir):
    data_dir = workdir / "rms-data"
    jar = fixtures["RMS_COUNTER_JAR"]

    def open_and_read_count() -> int:
        opened = kemu.ok("open", jar, "--headless",
                         "--data-dir", str(data_dir))
        title = opened["observation"]["title"]
        assert title.startswith("RMS count "), title
        return int(title.rsplit(" ", 1)[1])

    kemu.ok("open", jar, "--headless", "--data-dir", str(data_dir),
            "--reset-state")
    assert kemu.title() == "RMS count 1"
    kemu.err("storage", "rms", "reset", code="APP_ACTIVE")
    kemu.close()

    assert open_and_read_count() == 2
    kemu.close()

    rms_archive = workdir / "rms-export.zip"
    kemu.ok("storage", "rms", "export", str(rms_archive))
    state_archive = workdir / "state-snapshot.zip"
    kemu.ok("storage", "snapshot", str(state_archive))

    kemu.ok("storage", "rms", "reset")
    assert open_and_read_count() == 1
    kemu.close()

    kemu.ok("storage", "rms", "import", str(rms_archive))
    assert open_and_read_count() == 3
    kemu.close()

    kemu.ok("storage", "restore", str(state_archive))
    assert open_and_read_count() == 3
    kemu.close()


def test_open_size_applies_to_each_app_without_restarting_controller(kemu, fixtures):
    first = kemu.open_ready(fixtures["AGENT_CONTRACT_JAR"], "--size", "176x208")
    assert kemu.state_of(first)["size"] == {"width": 176, "height": 208}
    controller = int(kemu.run("--verbose", "status").diagnostics["pid"])
    kemu.close()
    second = kemu.open_ready(fixtures["AGENT_CONTRACT_JAR"], "--size", "320x240")
    assert kemu.state_of(second)["size"] == {"width": 320, "height": 240}
    assert int(kemu.run("--verbose", "status").diagnostics["pid"]) == controller
