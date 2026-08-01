"""Thin wrapper around kemu.sh --json for the CLI test suite.

Every invocation records the exercised public command so the coverage gate
(test_zz_coverage.py) can compare the exercised set against the command
surface advertised by `kemu help --json`.
"""

from __future__ import annotations

import json
import re
import subprocess
from dataclasses import dataclass
from pathlib import Path

# Shared across every KemuCli instance in one pytest session.
EXERCISED_COMMANDS: set[str] = set()

_USAGE_LINE = re.compile(r"^\s*kemu\s+(.*)$")
_WORD = re.compile(r"^[a-z][a-z0-9-]*$")
_ALTERNATION = re.compile(r"^<([a-z0-9|-]+)>$")


def parse_usage_commands(usage_text: str) -> set[str]:
    """Extract the public command surface from `help --json` usage text.

    `kemu wait <worker-ready|worker-exit|idle> ...` expands into one command
    per alternative. Parsing stops at the first placeholder/option token.
    """
    commands: set[str] = set()
    for line in usage_text.splitlines():
        match = _USAGE_LINE.match(line)
        if not match:
            continue
        words: list[str] = []
        alternatives: list[str] | None = None
        for token in match.group(1).split():
            alternation = _ALTERNATION.match(token)
            if alternation and "|" in alternation.group(1):
                alternatives = alternation.group(1).split("|")
                break
            if not _WORD.match(token):
                break
            words.append(token)
        if alternatives is not None:
            for alternative in alternatives:
                commands.add(" ".join(words + [alternative]))
        elif words:
            commands.add(" ".join(words))
    return commands


def record_exercised(args: tuple[str, ...], known_commands: set[str]) -> None:
    """Record the longest known command prefix of the invocation."""
    lowered = [a for a in args]
    for length in range(min(3, len(lowered)), 0, -1):
        candidate = " ".join(lowered[:length])
        if candidate in known_commands:
            EXERCISED_COMMANDS.add(candidate)
            return


class KemuError(AssertionError):
    pass


@dataclass
class KemuResult:
    ok: bool
    command: str | None
    result: dict
    error: dict
    exit_code: int
    raw: str

    @property
    def code(self) -> str | None:
        return self.error.get("code")

    @property
    def details(self) -> dict:
        return self.error.get("details") or {}

    def __getitem__(self, key):
        return self.result[key]


class KemuCli:
    """One CLI facade bound to a release bundle and a --session-id."""

    def __init__(self, release_dir: Path, session_id: str,
                 known_commands: set[str] | None = None):
        self.release_dir = Path(release_dir)
        self.session_id = session_id
        self.known_commands = known_commands or set()

    def run(self, *args: str, timeout: int = 240) -> KemuResult:
        argv = ["./kemu.sh", "--session-id", self.session_id, *args, "--json"]
        proc = subprocess.run(
            argv,
            cwd=self.release_dir,
            capture_output=True,
            text=True,
            timeout=timeout,
        )
        record_exercised(tuple(args), self.known_commands)
        raw = proc.stdout.strip()
        try:
            envelope = json.loads(raw)
        except json.JSONDecodeError as failure:
            raise KemuError(
                f"kemu {' '.join(args)}: invalid JSON on stdout "
                f"(exit {proc.returncode}): {raw[:400]!r} stderr={proc.stderr[:400]!r}"
            ) from failure
        return KemuResult(
            ok=bool(envelope.get("ok")),
            command=envelope.get("command"),
            result=envelope.get("result") or {},
            error=envelope.get("error") or {},
            exit_code=proc.returncode,
            raw=raw,
        )

    def ok(self, *args: str, command: str | None = None, timeout: int = 240) -> dict:
        outcome = self.run(*args, timeout=timeout)
        if not outcome.ok:
            raise KemuError(
                f"kemu {' '.join(args)}: expected ok, got "
                f"{outcome.code}: {outcome.error.get('message')}"
            )
        if outcome.exit_code != 0:
            raise KemuError(
                f"kemu {' '.join(args)}: ok envelope but exit {outcome.exit_code}"
            )
        if command is not None and outcome.command != command:
            raise KemuError(
                f"kemu {' '.join(args)}: expected command {command!r}, "
                f"got {outcome.command!r}"
            )
        return outcome.result

    def err(self, *args: str, code: str, timeout: int = 240) -> KemuResult:
        outcome = self.run(*args, timeout=timeout)
        if outcome.ok:
            raise KemuError(
                f"kemu {' '.join(args)}: expected {code}, got success: "
                f"{outcome.raw[:400]}"
            )
        if outcome.exit_code == 0:
            raise KemuError(
                f"kemu {' '.join(args)}: error envelope but exit 0"
            )
        if outcome.code != code:
            raise KemuError(
                f"kemu {' '.join(args)}: expected {code}, got {outcome.code}: "
                f"{outcome.error.get('message')}"
            )
        # Error envelope shape contract.
        if not outcome.command or not outcome.error.get("message"):
            raise KemuError(
                f"kemu {' '.join(args)}: malformed error envelope: {outcome.raw[:400]}"
            )
        return outcome

    # -- convenience helpers -------------------------------------------------

    def observe(self) -> dict:
        return self.ok("observe")

    def revision(self, observation: dict | None = None) -> int:
        return int((observation or self.observe())["revision"])

    def title(self, observation: dict | None = None) -> str | None:
        displayable = (observation or self.observe()).get("displayable") or {}
        return displayable.get("title")

    def open_ready(self, path: str, *extra: str) -> dict:
        return self.ok("open", path, "--headless", "--wait-ready", *extra)

    def close(self) -> dict:
        return self.ok("close")

    def close_quietly(self) -> None:
        try:
            self.run("close", timeout=60)
        except Exception:
            pass

    def stop_force_quietly(self) -> None:
        try:
            self.run("stop", "--force", timeout=60)
        except Exception:
            pass

    def command_id(self, observation: dict, wanted_text: str) -> int:
        commands = (observation.get("displayable") or {}).get("commands") or []
        for command in commands:
            text = command.get("text") or command.get("label") or ""
            if text == wanted_text:
                return int(command["id"])
        raise KemuError(f"command not found: {wanted_text!r} in {commands!r}")

    def run_command(self, label_text: str, *extra: str, wait_next: bool = True) -> dict:
        observation = self.observe()
        args = [
            "command", "run",
            "--id", str(self.command_id(observation, label_text)),
            "--expect-revision", str(self.revision(observation)),
        ]
        if wait_next:
            args.append("--wait-next-display")
        args.extend(extra)
        return self.ok(*args)

    def wait_title(self, title: str, timeout_ms: int = 10000) -> dict:
        return self.ok(
            "wait", "display", "--title", title, "--timeout", str(timeout_ms))


def png_size(path: Path) -> tuple[int, int]:
    import struct

    data = Path(path).read_bytes()[:24]
    assert data[:8] == b"\x89PNG\r\n\x1a\n", f"not a png: {path}"
    width, height = struct.unpack(">II", data[16:24])
    return width, height
