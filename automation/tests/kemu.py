"""Thin wrapper around kemu.sh --json for the CLI test suite.

The wrapper enforces the envelope contract on every call and records the
exercised public commands (from the registry list served by `help --json`)
so the coverage gate can compare exercised vs advertised.
"""

from __future__ import annotations

import json
import os
import queue
import re
import subprocess
import tempfile
import threading
from dataclasses import dataclass
from pathlib import Path

# Shared across every KemuCli instance in one pytest session.
EXERCISED_COMMANDS: set[str] = set()
EXERCISED_OK_COMMANDS: set[str] = set()

_USAGE_LINE = re.compile(r"^\s*kemu\s+(.*)$")
_WORD = re.compile(r"^[a-z][a-z0-9-]*$")
_ALTERNATION = re.compile(r"^<([a-z0-9|-]+)>$")

ENVELOPE_KEYS = {"ok", "command", "result"}
ERROR_ENVELOPE_KEYS = {"ok", "command", "error"}
FORBIDDEN_RESULT_KEYS = {"ok", "error", "command"}


def parse_usage_commands(usage_text: str) -> set[str]:
    """Command tokens scraped from usage text (doc-sync check only)."""
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


def match_known_command(args: tuple[str, ...], known_commands: set[str]) -> str | None:
    """The longest known command prefix of the invocation, if any."""
    for length in range(min(3, len(args)), 0, -1):
        candidate = " ".join(args[:length])
        if candidate in known_commands:
            return candidate
    return None


class KemuError(AssertionError):
    pass


class _Bridge:
    """One long-lived `kemu bridge` process per KemuCli session."""

    def __init__(self, release_dir: Path, session_id: str):
        self.stderr_file = tempfile.NamedTemporaryFile(
            mode="w+", prefix=f"kemu-bridge-{session_id}-", suffix=".log",
            delete=False)
        self.proc = subprocess.Popen(
            ["./kemu.sh", "--session-id", session_id, "--json", "bridge"],
            cwd=release_dir,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=self.stderr_file,
            text=True,
            bufsize=1,
        )
        self.next_id = 0
        self.lines: queue.Queue[str | None] = queue.Queue()
        self.reader = threading.Thread(target=self._pump, daemon=True)
        self.reader.start()

    def _pump(self) -> None:
        for line in self.proc.stdout:
            self.lines.put(line)
        self.lines.put(None)

    def _stderr_tail(self) -> str:
        try:
            self.stderr_file.flush()
            return Path(self.stderr_file.name).read_text()[-400:]
        except OSError:
            return ""

    def request(self, argv: tuple[str, ...], timeout: int) -> dict:
        self.next_id += 1
        request_id = self.next_id
        try:
            self.proc.stdin.write(json.dumps(
                {"id": request_id, "argv": list(argv)}) + "\n")
            self.proc.stdin.flush()
        except (BrokenPipeError, OSError) as failure:
            raise KemuError(
                f"bridge died writing {argv!r}: {self._stderr_tail()!r}"
            ) from failure
        try:
            line = self.lines.get(timeout=timeout)
        except queue.Empty:
            self.shutdown(kill=True)
            raise KemuError(
                f"bridge timed out after {timeout}s on {argv!r}")
        if line is None:
            raise KemuError(
                f"bridge closed unexpectedly on {argv!r}: {self._stderr_tail()!r}")
        envelope = json.loads(line)
        if envelope.pop("id", None) != request_id:
            raise KemuError(f"bridge response id mismatch for {argv!r}: {line!r}")
        EXERCISED_OK_COMMANDS.add("bridge")
        EXERCISED_COMMANDS.add("bridge")
        return envelope

    def alive(self) -> bool:
        return self.proc.poll() is None

    def shutdown(self, kill: bool = False) -> None:
        try:
            if self.proc.stdin and not self.proc.stdin.closed:
                self.proc.stdin.close()
            if kill:
                self.proc.kill()
            self.proc.wait(timeout=10)
        except Exception:
            try:
                self.proc.kill()
            except Exception:
                pass
        try:
            self.stderr_file.close()
            os.unlink(self.stderr_file.name)
        except OSError:
            pass


@dataclass
class KemuResult:
    ok: bool
    command: str | None
    result: dict
    error: dict
    exit_code: int | None
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
        self.use_bridge = os.environ.get("KEMU_NO_BRIDGE") != "1"
        self._bridge: _Bridge | None = None

    def _bridge_session(self) -> _Bridge:
        if self._bridge is None or not self._bridge.alive():
            self._bridge = _Bridge(self.release_dir, self.session_id)
        return self._bridge

    def shutdown_bridge(self) -> None:
        if self._bridge is not None:
            self._bridge.shutdown()
            self._bridge = None

    def run_raw(self, *args: str, json_mode: bool = True,
                timeout: int = 240) -> subprocess.CompletedProcess:
        # Global flags go before the command tokens so a literal `--` in the
        # command arguments cannot swallow them.
        argv = ["./kemu.sh", "--session-id", self.session_id]
        if json_mode:
            argv.append("--json")
        argv.extend(args)
        return subprocess.run(
            argv,
            cwd=self.release_dir,
            capture_output=True,
            text=True,
            timeout=timeout,
        )

    def _assert_envelope(self, args: tuple[str, ...], envelope: dict,
                         exit_code: int | None) -> None:
        if envelope.get("ok"):
            if set(envelope) != ENVELOPE_KEYS:
                raise KemuError(
                    f"kemu {' '.join(args)}: success envelope keys "
                    f"{sorted(envelope)}, expected {sorted(ENVELOPE_KEYS)}")
            result = envelope.get("result")
            if not isinstance(result, dict):
                raise KemuError(
                    f"kemu {' '.join(args)}: result is not an object: {result!r}")
            leaked = FORBIDDEN_RESULT_KEYS & set(result)
            if leaked:
                raise KemuError(
                    f"kemu {' '.join(args)}: envelope keys leaked into result: "
                    f"{sorted(leaked)}")
            if exit_code not in (None, 0):
                raise KemuError(
                    f"kemu {' '.join(args)}: ok envelope but exit {exit_code}")
        else:
            if set(envelope) != ERROR_ENVELOPE_KEYS:
                raise KemuError(
                    f"kemu {' '.join(args)}: error envelope keys "
                    f"{sorted(envelope)}, expected {sorted(ERROR_ENVELOPE_KEYS)}")
            error = envelope.get("error")
            if not isinstance(error, dict) or not error.get("code") \
                    or not error.get("message"):
                raise KemuError(
                    f"kemu {' '.join(args)}: malformed error object: {error!r}")
            if "details" in error and error["details"] is None:
                raise KemuError(
                    f"kemu {' '.join(args)}: error.details must be omitted or non-null")
            if exit_code == 0:
                raise KemuError(
                    f"kemu {' '.join(args)}: error envelope but exit 0")
            # exit_code is None over the bridge: nothing more to check.

    def run(self, *args: str, timeout: int = 240,
            oneshot: bool = False) -> KemuResult:
        matched = match_known_command(tuple(args), self.known_commands)
        if matched:
            EXERCISED_COMMANDS.add(matched)
        if self.use_bridge and not oneshot:
            envelope = self._bridge_session().request(tuple(args), timeout)
            exit_code = None
            raw = json.dumps(envelope)
        else:
            proc = self.run_raw(*args, timeout=timeout)
            raw = proc.stdout.strip()
            try:
                envelope = json.loads(raw)
            except json.JSONDecodeError as failure:
                raise KemuError(
                    f"kemu {' '.join(args)}: invalid JSON on stdout "
                    f"(exit {proc.returncode}): {raw[:400]!r} "
                    f"stderr={proc.stderr[:400]!r}"
                ) from failure
            exit_code = proc.returncode
        self._assert_envelope(tuple(args), envelope, exit_code)
        if envelope.get("ok") and matched:
            EXERCISED_OK_COMMANDS.add(matched)
        return KemuResult(
            ok=bool(envelope.get("ok")),
            command=envelope.get("command"),
            result=envelope.get("result") or {},
            error=envelope.get("error") or {},
            exit_code=exit_code,
            raw=raw,
        )

    def ok(self, *args: str, command: str | None = None, timeout: int = 240,
           oneshot: bool = False) -> dict:
        outcome = self.run(*args, timeout=timeout, oneshot=oneshot)
        if not outcome.ok:
            raise KemuError(
                f"kemu {' '.join(args)}: expected ok, got "
                f"{outcome.code}: {outcome.error.get('message')}"
            )
        if command is not None and outcome.command != command:
            raise KemuError(
                f"kemu {' '.join(args)}: expected command {command!r}, "
                f"got {outcome.command!r}"
            )
        return outcome.result

    def err(self, *args: str, code: str, timeout: int = 240,
            oneshot: bool = False) -> KemuResult:
        outcome = self.run(*args, timeout=timeout, oneshot=oneshot)
        if outcome.ok:
            raise KemuError(
                f"kemu {' '.join(args)}: expected {code}, got success: "
                f"{outcome.raw[:400]}"
            )
        if outcome.code != code:
            raise KemuError(
                f"kemu {' '.join(args)}: expected {code}, got {outcome.code}: "
                f"{outcome.error.get('message')}"
            )
        return outcome

    # -- convenience helpers -------------------------------------------------

    def observe(self) -> dict:
        return self.ok("observe")

    def state_of(self, observation: dict | None = None) -> dict:
        return (observation or self.observe())["state"]

    def revision(self, observation: dict | None = None) -> int:
        return int(self.state_of(observation)["revision"])

    def title(self, observation: dict | None = None) -> str | None:
        displayable = self.state_of(observation).get("displayable") or {}
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
        commands = (self.state_of(observation).get("displayable") or {}) \
            .get("commands") or []
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
