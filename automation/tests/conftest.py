"""Session wiring for the KEmulator CLI test suite.

Environment (normally exported by automation/run-cli-tests.sh):
  KEMU_RELEASE_DIR    built release bundle (contains kemu.sh, KEmulator.jar).
                      Built automatically into a temp dir when unset.
  KEMU_FIXTURES_ENV   fixtures.env produced by prepare-cli-fixtures.sh.
                      Prepared automatically when unset.
  KEMU_COVERAGE_CHECK "1" enables the command-coverage gate (full runs only).

Rules for new tests (see README.md):
  - never build the product inside a test;
  - never write into the release bundle: use the `workdir` fixture;
  - go through KemuCli so the coverage gate sees every command.
"""

from __future__ import annotations

import os
import shlex
import shutil
import subprocess
import sys
import tempfile
import uuid
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parent))

from kemu import KemuCli, parse_usage_commands  # noqa: E402

REPO_ROOT = Path(__file__).resolve().parents[2]


def _build_release(target: Path) -> None:
    subprocess.run(
        [str(REPO_ROOT / "build-release.sh"), str(target)],
        check=True,
        capture_output=True,
        text=True,
    )


def _prepare_fixtures(release_dir: Path, target: Path) -> Path:
    subprocess.run(
        [
            str(REPO_ROOT / "automation/test-fixtures/prepare-cli-fixtures.sh"),
            str(release_dir / "KEmulator.jar"),
            str(target),
        ],
        check=True,
        capture_output=True,
        text=True,
    )
    return target / "fixtures.env"


@pytest.fixture(scope="session")
def release_dir(tmp_path_factory) -> Path:
    configured = os.environ.get("KEMU_RELEASE_DIR")
    if configured:
        release = Path(configured)
        if not (release / "KEmulator.jar").is_file():
            raise RuntimeError(f"KEMU_RELEASE_DIR has no KEmulator.jar: {release}")
        return release
    release = tmp_path_factory.mktemp("kemu-release") / "release"
    _build_release(release)
    return release


@pytest.fixture(scope="session")
def fixtures(release_dir, tmp_path_factory) -> dict:
    configured = os.environ.get("KEMU_FIXTURES_ENV")
    if configured:
        env_file = Path(configured)
    else:
        env_file = _prepare_fixtures(
            release_dir, tmp_path_factory.mktemp("kemu-fixtures"))
    values: dict[str, str] = {}
    for line in env_file.read_text().splitlines():
        if "=" not in line or line.lstrip().startswith("#"):
            continue
        key, raw = line.split("=", 1)
        parts = shlex.split(raw)
        values[key.strip()] = parts[0] if parts else ""
    return values


@pytest.fixture(scope="session")
def known_commands(release_dir) -> set[str]:
    probe = KemuCli(release_dir, session_id=f"pt-probe-{uuid.uuid4().hex[:8]}")
    usage = probe.ok("help")["usage"]
    commands = parse_usage_commands(usage)
    assert "open" in commands and "observe" in commands, usage
    return commands


@pytest.fixture(scope="session")
def kemu_factory(release_dir, known_commands):
    """Creates isolated KemuCli sessions; controllers are stopped at exit."""
    created: list[KemuCli] = []

    def make(start: bool = True) -> KemuCli:
        cli = KemuCli(
            release_dir,
            session_id=f"pt-{uuid.uuid4().hex[:10]}",
            known_commands=known_commands,
        )
        created.append(cli)
        if start:
            cli.ok("start", "--headless", "--runtime", "release",
                   "--size", "240x320")
        return cli

    yield make
    for cli in created:
        cli.close_quietly()
        cli.stop_force_quietly()


@pytest.fixture(scope="session")
def kemu(kemu_factory) -> KemuCli:
    """The shared long-lived session used by most tests."""
    return kemu_factory()


@pytest.fixture(autouse=True)
def _close_leftover_app(request):
    """Keep tests independent: no app may leak into the next test."""
    yield
    if "kemu" in request.fixturenames:
        request.getfixturevalue("kemu").close_quietly()


@pytest.fixture(scope="session")
def workdir():
    """Writable storage roots for workers: must live outside the bundle."""
    path = Path(tempfile.mkdtemp(prefix="kemu-pytest-"))
    yield path
    shutil.rmtree(path, ignore_errors=True)
