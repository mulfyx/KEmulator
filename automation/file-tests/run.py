#!/usr/bin/env python3
"""Compile a CLDC MIDlet and exercise the released CLI with a 16 MB worker."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import uuid

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT.parent / "tests"))
from kemu import KemuCli

SCENARIOS = ("position", "large-position", "large-truncate", "negative", "rename", "escaped")


def main():
    if not Path("/.dockerenv").exists() and not Path("/run/.containerenv").exists():
        raise SystemExit("Run only inside the designated test container.")
    if len(sys.argv) < 2:
        raise SystemExit("Usage: run.py /path/to/release-bundle [scenario ...]")
    release = Path(sys.argv[1]).resolve()
    scenarios = tuple(sys.argv[2:]) or SCENARIOS
    if any(scenario not in SCENARIOS for scenario in scenarios):
        raise SystemExit("Unknown scenario; choose from: " + ", ".join(SCENARIOS))
    failed = []
    with tempfile.TemporaryDirectory(prefix="kemu-file-tests-") as temporary:
        work = Path(temporary)
        os.environ["KEMU_AUTOMATION_DIR"] = str(work / "automation")
        classes = work / "classes"
        classes.mkdir()
        subprocess.run([
            "java", "-jar", os.environ.get("ECJ_JAR", "/opt/tools/ecj.jar"),
            "-source", "1.3", "-target", "1.1", "-encoding", "UTF-8",
            "-cp", str(release / "KEmulator.jar"), "-d", str(classes),
            str(ROOT / "src/filefixture/FileOperationsMidlet.java"),
        ], check=True)
        for scenario in scenarios:
            run = work / scenario
            run.mkdir()
            manifest = run / "manifest.mf"
            manifest.write_text(
                "Manifest-Version: 1.0\nMIDlet-Name: FileOperations\n"
                "MIDlet-Version: 1.0\nMIDlet-Vendor: KEmulator tests\n"
                "MIDlet-1: FileOperations,,filefixture.FileOperationsMidlet\n"
                "MicroEdition-Configuration: CLDC-1.1\nMicroEdition-Profile: MIDP-2.0\n"
                f"File-Test-Scenario: {scenario}\n\n")
            jar = run / "fixture.jar"
            subprocess.run(["jar", "cfm", str(jar), str(manifest), "-C", str(classes), "."], check=True)
            cli = KemuCli(release, "file-tests-" + uuid.uuid4().hex[:12])
            try:
                cli.ok("inspect", str(jar))
                cli.open_ready(str(jar), "--worker-xmx", "16M", "--data-dir", str(run / "data"),
                               "--file-root", str(run / "card"))
                status = cli.run("--verbose", "status")
                assert status.ok, status.raw
                state = status.diagnostics["state"]
                assert "-Xmx16M" in state["jvmOptions"], state
                assert state["memoryCard"]["hostPath"] == str(run / "card/e"), state
                cli.ok("wait", "log", "--regex", "FILE_TEST (PASS|FAIL)", "--timeout", "30000")
                cli.ok("wait", "screen", "--title-regex", "^(PASS|FAIL) ", "--timeout", "5000")
                title = cli.title()
                logs = cli.ok("logs")
                log_lines = [entry["line"] for entry in logs["lines"]]
                relevant = [line for line in log_lines
                            if "FILE_TEST" in line or "OutOfMemoryError" in line or "NegativeArraySizeException" in line]
                print(json.dumps({"scenario": scenario, "title": title, "log": relevant}), flush=True)
                if title != "PASS " + scenario:
                    failed.append(scenario)
                if scenario == "escaped" and title == "PASS escaped":
                    assert (run / "card/e/space файл+name.bin").read_bytes() == b"unicode"
                    assert (run / "card/e/percent%.bin").read_bytes() == b"percent"
                    assert (run / "card/e/localhost.txt").read_bytes() == b"localhost"
                if scenario == "rename" and title == "PASS rename":
                    assert (run / "card/e/renamed + файл%.bin").read_bytes() == b"source!"
            except Exception as failure:
                failed.append(scenario)
                print(json.dumps({"scenario": scenario, "error": str(failure)}), flush=True)
            finally:
                cli.close_quietly()
                cli.stop_force_quietly()
                cli.shutdown_bridge()
    print(f"FileConnection MIDlet regressions: {len(scenarios) - len(failed)}/{len(scenarios)} passed; failed={failed}")
    return bool(failed)


if __name__ == "__main__":
    sys.exit(main())
