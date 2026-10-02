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

ENVELOPE_KEYS = {"outcome", "command", "result"}
ERROR_ENVELOPE_KEYS = {"outcome", "command", "error"}
FORBIDDEN_RESULT_KEYS = {"outcome", "error", "command", "ok"}


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
            ["./kemu.sh", "--session", session_id, "--json", "bridge"],
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
    outcome: str
    diagnostics: dict
    command: str | None
    result: dict
    error: dict
    exit_code: int | None
    raw: str

    @property
    def ok(self) -> bool:
        return self.outcome == "done"

    @property
    def code(self) -> str | None:
        return self.error.get("code")

    @property
    def details(self) -> dict:
        return self.error.get("details") or {}

    def __getitem__(self, key):
        return self.result[key]


class KemuCli:
    """One CLI facade bound to a release bundle and a --session."""

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
        argv = ["./kemu.sh", "--session", self.session_id]
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
        outcome = envelope.get("outcome")
        if outcome not in ("done", "pending", "error"):
            raise KemuError(f"invalid outcome for {args!r}: {envelope!r}")
        expected = ERROR_ENVELOPE_KEYS if outcome == "error" else ENVELOPE_KEYS
        optional = {"diagnostics"} if "--verbose" in args else set()
        if not expected <= set(envelope) or set(envelope) - expected - optional:
            raise KemuError(f"invalid envelope for {args!r}: {envelope!r}")
        if "diagnostics" in envelope and not isinstance(envelope["diagnostics"], dict):
            raise KemuError(f"diagnostics must be an object: {envelope!r}")
        if outcome != "error":
            result = envelope["result"]
            if not isinstance(result, dict) or FORBIDDEN_RESULT_KEYS & set(result):
                raise KemuError(f"invalid public result for {args!r}: {result!r}")
            wanted_exit = 5 if outcome == "pending" else 0
            if exit_code not in (None, wanted_exit):
                raise KemuError(f"{outcome} envelope with exit {exit_code}: {envelope!r}")
            if outcome == "pending":
                permission = result.get("permission")
                if not isinstance(permission, dict) or not permission.get("ref"):
                    raise KemuError(f"pending response needs an actionable permission: {result!r}")
        else:
            error = envelope["error"]
            if not isinstance(error, dict) or not error.get("code") or not error.get("message"):
                raise KemuError(f"malformed error for {args!r}: {error!r}")
            if error.get("details", {}) is None:
                raise KemuError(f"null error.details for {args!r}")
            if exit_code not in (None, 2, 3, 4):
                raise KemuError(f"error envelope with exit {exit_code}: {envelope!r}")

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
        if envelope.get("outcome") in ("done", "pending") and matched:
            EXERCISED_OK_COMMANDS.add(matched)
        return KemuResult(
            outcome=envelope["outcome"],
            diagnostics=envelope.get("diagnostics") or {},
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
        if outcome.outcome != "error":
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

    def done(self, *args: str, **kwargs) -> dict:
        return self.ok(*args, **kwargs)

    def pending(self, *args: str, timeout: int = 240, oneshot: bool = False) -> dict:
        response = self.run(*args, timeout=timeout, oneshot=oneshot)
        if response.outcome != "pending":
            raise KemuError(f"expected pending for {args!r}: {response.raw}")
        return response.result

    def observe(self) -> dict:
        return self.ok("observe")

    def state_of(self, result: dict | None = None) -> dict:
        return (result if result is not None else self.observe())["observation"]

    def title(self, result: dict | None = None) -> str | None:
        return self.state_of(result).get("title")

    def open_ready(self, path: str, *extra: str) -> dict:
        return self.ok("open", path, "--headless", *extra)

    def close(self) -> dict:
        return self.ok("close")

    def close_quietly(self) -> None:
        try:
            self.run("close", timeout=60)
        except Exception:
            pass

    def stop_force_quietly(self) -> None:
        try:
            self.run("stop", timeout=60)
        except Exception:
            pass

    def command_ref(self, result: dict, label: str) -> str:
        for command in self.state_of(result).get("commands", []):
            if command.get("label") == label:
                assert "activate" in command["actions"], command
                return command["ref"]
        raise KemuError(f"command {label!r} not in {result!r}")

    def node(self, result: dict, role: str | None = None, label: str | None = None) -> dict:
        for node in walk_nodes(self.state_of(result).get("nodes", [])):
            if (role is None or node.get("role") == role) and (label is None or node.get("label") == label):
                return node
        raise KemuError(f"node role={role!r} label={label!r} not in {result!r}")

    def node_ref(self, result: dict, role: str | None = None, label: str | None = None) -> str:
        return self.node(result, role, label)["ref"]

    def run_command(self, label: str, *extra: str) -> dict:
        return self.ok("activate", self.command_ref(self.observe(), label), *extra)

    def wait_title(self, title: str, timeout_ms: int = 10000) -> dict:
        return self.ok("wait", "screen", "--title", title, "--timeout", str(timeout_ms))

    def worker_pid(self) -> int:
        response = self.run("--verbose", "status", oneshot=True)
        assert response.ok, response.raw
        return int(response.diagnostics["worker"]["pid"])


def walk_nodes(nodes):
    for node in nodes:
        yield node
        yield from walk_nodes(node.get("nodes", []))


def png_size(path: Path) -> tuple[int, int]:
    import struct
    data = Path(path).read_bytes()[:24]
    assert data[:8] == b"\x89PNG\r\n\x1a\n", f"not a png: {path}"
    return struct.unpack(">II", data[16:24])


def png_pixel(path: Path, x: int, y: int) -> tuple[int, int, int]:
    """Read an actual non-interlaced PNG pixel with only the standard library."""
    import struct
    import zlib
    data = Path(path).read_bytes()
    assert data[:8] == b"\x89PNG\r\n\x1a\n"
    offset, compressed, palette = 8, bytearray(), None
    while offset < len(data):
        length = struct.unpack(">I", data[offset:offset + 4])[0]
        kind = data[offset + 4:offset + 8]
        payload = data[offset + 8:offset + 8 + length]
        if kind == b"IHDR":
            width, height, depth, color, _, _, interlace = struct.unpack(">IIBBBBB", payload)
        elif kind == b"IDAT":
            compressed.extend(payload)
        elif kind == b"PLTE":
            palette = [tuple(payload[i:i + 3]) for i in range(0, len(payload), 3)]
        offset += length + 12
    assert depth == 8 and interlace == 0, (depth, interlace)
    channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[color]
    stride = width * channels
    decoded = zlib.decompress(compressed)
    previous = bytearray(stride)
    for row in range(y + 1):
        start = row * (stride + 1)
        filter_type = decoded[start]
        pixels = bytearray(decoded[start + 1:start + 1 + stride])
        for i in range(stride):
            left = pixels[i - channels] if i >= channels else 0
            above = previous[i]
            upper_left = previous[i - channels] if i >= channels else 0
            if filter_type == 1:
                delta = left
            elif filter_type == 2:
                delta = above
            elif filter_type == 3:
                delta = (left + above) // 2
            elif filter_type == 4:
                p = left + above - upper_left
                candidates = (left, above, upper_left)
                delta = min(candidates, key=lambda n: abs(p - n))
            else:
                assert filter_type == 0, filter_type
                delta = 0
            pixels[i] = (pixels[i] + delta) & 255
        previous = pixels
    pixel = pixels[x * channels:(x + 1) * channels]
    if color == 3:
        return palette[pixel[0]]
    if color in (0, 4):
        return (pixel[0],) * 3
    return tuple(pixel[:3])
