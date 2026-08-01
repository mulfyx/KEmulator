"""Command-coverage gate: every registered command must be exercised.

Runs last (file name sorts last). Enabled only for full runs via
KEMU_COVERAGE_CHECK=1 so `pytest -k`/partial selections stay usable.
Adding a command to the registry without touching the tests fails here.
"""

import os

import pytest

from kemu import EXERCISED_OK_COMMANDS, parse_usage_commands


def _gate_enabled():
    return os.environ.get("KEMU_COVERAGE_CHECK") == "1"


def test_every_registered_command_has_a_passing_test(known_commands):
    if not _gate_enabled():
        pytest.skip("coverage gate disabled (set KEMU_COVERAGE_CHECK=1)")
    missing = sorted(known_commands - EXERCISED_OK_COMMANDS)
    assert not missing, (
        "Registered CLI commands without a successful test invocation: "
        + ", ".join(missing)
        + ". Add tests (or remove the command from the registry).")


def test_usage_text_covers_the_registry(kemu, known_commands):
    """Every registered command must be reachable from the root usage text
    (usage granularity may be coarser, e.g. `list <select|move ...>`)."""
    usage_commands = parse_usage_commands(kemu.ok("help")["usage"])
    uncovered = sorted(
        command for command in known_commands
        if not any(command == u or command.startswith(u + " ")
                   or u.startswith(command + " ")
                   for u in usage_commands))
    assert not uncovered, f"commands absent from root usage: {uncovered}"
