"""Command-coverage gate: every advertised command must have been exercised.

Runs last (file name sorts last). Enabled only for full runs via
KEMU_COVERAGE_CHECK=1 so `pytest -k`/partial selections stay usable.
Adding a CLI command to the help usage without touching the tests fails here.
"""

import os

import pytest

from kemu import EXERCISED_COMMANDS


def test_every_public_command_is_exercised(known_commands):
    if os.environ.get("KEMU_COVERAGE_CHECK") != "1":
        pytest.skip("coverage gate disabled (set KEMU_COVERAGE_CHECK=1)")
    missing = sorted(known_commands - EXERCISED_COMMANDS)
    assert not missing, (
        "Public CLI commands without test coverage: "
        + ", ".join(missing)
        + ". Add tests (or fix the help usage text if the command is gone).")
