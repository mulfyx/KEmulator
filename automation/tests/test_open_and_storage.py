"""open contract, session storage safety, RMS/state archives, memory card."""

import hashlib
from pathlib import Path


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
    assert opened["app"]["displayName"] == "Command Fixture"
    kemu.close()

    opened = kemu.ok("open", "--", fixtures["DASH_PREFIXED_MEGA_JAR"],
                     "--headless", "--wait-ready")
    assert opened["app"]["displayName"] == "Mega CLI Fixture"
    kemu.close()

    opened = kemu.open_ready(fixtures["MULTI_MIDLET_JAR"], "--midlet", "2")
    assert opened["app"]["midletName"] == "Mutable Title Fixture"
    assert opened["displayable"]["title"] == "Mutable menu"
    kemu.close()

    opened = kemu.open_ready(fixtures["BOM_MANIFEST_JAR"])
    assert opened["app"]["displayName"] == "Command Fixture"
    kemu.close()

    opened = kemu.open_ready(fixtures["PARENT_RELATIVE_JAD"])
    assert opened["displayable"]["title"] == "Parent Relative Target Menu"
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
        assert opened["displayable"]["title"] == title, env_key
        kemu.close()


def test_reset_state_preserves_explicit_file_root(kemu, fixtures, workdir):
    card_root = workdir / "card-root"
    jad = _write_card_root(fixtures, card_root)
    before = _tree_digest(card_root)

    opened = kemu.ok("open", str(jad), "--headless",
                     "--data-dir", str(workdir / "card-data"),
                     "--file-root", str(card_root),
                     "--reset-state", "--wait-ready")
    assert opened["ready"] is True
    kemu.close()

    outcome = kemu.err("open", str(jad), "--headless",
                       "--data-dir", str(workdir / "card-data"),
                       "--file-root", str(card_root),
                       "--reset-state", "--reset-file-root", "--wait-ready",
                       code="STORAGE_OVERLAP")
    assert "Nothing was deleted" in outcome.error["message"]

    kemu.err("open", str(jad), "--headless",
             "--data-dir", str(card_root), "--reset-state", "--wait-ready",
             code="STORAGE_OVERLAP")

    assert _tree_digest(card_root) == before


def test_implicit_session_local_reset_still_works(kemu, fixtures, workdir):
    data_dir = workdir / "implicit-data"
    kemu.ok("open", fixtures["PROPS_PLAIN_JAR"], "--headless",
            "--data-dir", str(data_dir), "--reset-state", "--wait-ready")
    kemu.close()

    marker = data_dir / "files" / "marker.txt"
    marker.parent.mkdir(parents=True, exist_ok=True)
    marker.write_text("stale\n")

    kemu.ok("open", fixtures["PROPS_PLAIN_JAR"], "--headless",
            "--data-dir", str(data_dir), "--reset-state", "--wait-ready")
    kemu.close()
    assert not marker.exists()


def test_memory_card_mapping(kemu, fixtures, workdir):
    memcard_root = workdir / "memcard-root"
    opened = kemu.ok("open", fixtures["PROBE_MEMORYCARD_JAD"], "--headless",
                     "--data-dir", str(workdir / "memcard-data"),
                     "--file-root", str(memcard_root),
                     "--reset-state", "--wait-ready")
    assert opened["memoryCard"]["guestUrl"] == "file:///root/e/"
    assert opened["memoryCard"]["hostPath"] == str(memcard_root / "e")
    kemu.wait_title("WROTE file:///root/e/probe.txt", timeout_ms=15000)
    state = kemu.ok("state")
    assert state["memoryCard"]["hostPath"] == str(memcard_root / "e")
    kemu.close()
    assert (memcard_root / "e" / "probe.txt").read_bytes() == b"probe"

    kemu.ok("open", fixtures["PROBE_DRIVE_E_JAD"], "--headless",
            "--data-dir", str(workdir / "memcard-data"),
            "--file-root", str(memcard_root), "--wait-ready")
    kemu.wait_title("WROTE file:///E:/probe-e.txt", timeout_ms=15000)
    kemu.close()
    assert (memcard_root / "e" / "probe-e.txt").read_bytes() == b"probe"


def test_rms_archives_and_state_snapshot(kemu, fixtures, workdir):
    data_dir = workdir / "rms-data"
    jar = fixtures["RMS_COUNTER_JAR"]

    def open_and_read_count() -> int:
        opened = kemu.ok("open", jar, "--headless",
                         "--data-dir", str(data_dir), "--wait-ready")
        title = opened["displayable"]["title"]
        assert title.startswith("RMS count "), title
        return int(title.rsplit(" ", 1)[1])

    kemu.ok("open", jar, "--headless", "--data-dir", str(data_dir),
            "--reset-state", "--wait-ready")
    assert kemu.title() == "RMS count 1"
    kemu.err("rms", "reset", code="APP_ALREADY_OPEN")
    kemu.close()

    assert open_and_read_count() == 2
    kemu.close()

    rms_archive = workdir / "rms-export.zip"
    kemu.ok("rms", "export", str(rms_archive))
    state_archive = workdir / "state-snapshot.zip"
    kemu.ok("state", "snapshot", str(state_archive))

    kemu.ok("rms", "reset")
    assert open_and_read_count() == 1
    kemu.close()

    kemu.ok("rms", "import", str(rms_archive))
    assert open_and_read_count() == 3
    kemu.close()

    kemu.ok("state", "restore", str(state_archive))
    assert open_and_read_count() == 3
    kemu.close()
