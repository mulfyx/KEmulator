# Agent CLI

This branch implements the agent-oriented contract below for Linux.
Old public commands and JSON shapes are replaced. Controller/worker RPC is
internal. Build a release with `./build-release.sh` and run its `kemu.sh`.
Build this checkout's TLS-enabled bundle with JDK 21 and use that JVM for TLS
MIDlets; the TLS extension relies on modern JSSE APIs. The standalone CLI was
also verified on Java 8 before integration. See [TlsSocketApi.md](TlsSocketApi.md).

## Workflow and options

```bash
export KEMU_SESSION=play
./kemu.sh open app.jar
./kemu.sh observe
./kemu.sh set REF 'Alice'
./kemu.sh activate REF
./kemu.sh key press 5
./kemu.sh observe
./kemu.sh close
./kemu.sh stop
```

Global options: `--session NAME`, `--json`, `--verbose`, `--timeout MS`.
The unnamed session is `default`; agents should choose their own session.
`--json` selects representation, `--verbose` adds diagnostics, neither
changes execution. Literal `--` protects argument values.

## Public result

One-shot responses have `command`, `outcome`, and `result` or `error`.
Outcomes are `done`, `pending`, `error`. Optional top-level `diagnostics`
appears only with `--verbose`; bridge additionally echoes `id`.
Done means the command reached its documented threshold. Pending means a
permission suspended it: answer the permission, do not repeat the action.
Errors have stable `code`, readable `message`, relevant `details`.
Exit: 0 done, 5 pending, 2 invalid request/usage, 3 input/path/archive,
4 runtime. JSON is one stdout envelope even on failure; text errors go to
stderr. Both renderers use the same public result.

Observation-bearing results contain `session: {id,status}`,
`app: {name,status}`, and `observation`. Actions may add an `action`
receipt with operation/ref/actual value. Current permission is a sibling
`permission: {ref,id,name,message,actions}` and appears first in text.

An observation has `kind`, `title`, `size: {width,height}`, `nodes`,
`commands`; include `contentSize` if different from screen size, `ticker`
when present, and `image` when captured. Nodes use readable `role`,
`label`, `value`, `selected`, `focused`, `actions` and applicable limits.
Choice groups contain child `nodes`; commands are not duplicated as rows.
Examples: text-field ref + actions [set] + constraints [numeric] +
maxLength; gauge ref + value/min/max + [set]; option ref + selected +
[select]; command ref + label/softkey + [activate]. Read-only text has no
mutation ref. TextBox content is a settable node; Alert body/ticker are
readable text. Values are never silently truncated.

Refs are opaque `@RUN.eN` tokens bound to live targets: Command+owner,
Item+owner, collection+structural identity/generation+row. Changing a value
or selection preserves unrelated refs. Removal/replacement/owner change
or worker restart makes a ref stale. Validate membership, type and action
immediately before LCDUI mutation; global revision is not target identity.
Tokens are never reassigned. No snapshot history/TTL is needed.

## Command surface

- `inspect APP`: metadata/MIDlet choices; suite properties are verbose.
- `open APP [--midlet N] [--visible|--headless] [--size WxH] [storage options]`:
  auto-start session, launch and wait for first display or permission;
  return initial observation. Startup timeout retains the launched worker.
- `status`: session availability, app state, blocker/failure. PID, JVM,
  classpath and internal paths are diagnostic only.
- `close`: close MIDlet, retain reusable session. `stop`: terminate this
  session's controller, worker and private display.
- `observe [--screenshot FILE]`, `screenshot [FILE]`.
- `activate REF`: invoke command; implicit List row selects+invokes its
  select callback as one guarded operation.
- `select REF [--off]`: select List/Choice row without activation;
  off only for multiple selection.
- `set REF VALUE`: TextField/TextBox, integer Gauge, DateField. Dates
  accept ISO date/time appropriate to input mode or epoch milliseconds.
- `key press KEY [--duration MS]`, `key hold KEY [--duration MS]`,
  `key down KEY`, `key up KEY`.
- `pointer tap X Y`, `pointer down X Y`, `pointer up X Y`,
  `drag X1 Y1 X2 Y2 ... [--delay MS]`.
- `pause`, `resume`, `resize WxH`, `rotate`.
- `wait screen [--kind KIND] [--title TITLE] [--title-regex REGEX] [--text TEXT]`;
  `wait ready`, `wait exit`, `wait frame [--after FRAME_ID]`,
  `wait permission [--name NAME]`, `wait log --regex REGEX [--since CURSOR]`.
- `permission allow REF [--remember]`, `permission deny REF`.
- `logs [--since CURSOR] [--jsonl]`, `logs cursor`.
- `storage snapshot FILE`, `storage restore FILE`, `storage rms reset`,
  `storage rms export FILE`, `storage rms import FILE`.
- `bridge`: JSONL `{id,argv}` input, same public envelopes with echoed id;
  fixed session, EOF closes bridge but not session.
- `help [COMMAND...]`: workflow first, precise command options/key names
  on demand. Bare read-only state/start/old native setters are not public.

Native activate/select/set and pause/resume/resize/rotate return a receipt
plus current observation. Physical input returns delivery receipt;
`--observe` additionally requests UI. Dispatch/applied value/completed
callback does not promise game-level transition; wait for a condition.

## Canvas and deadlines

Canvas observation saves a unique PNG and exposes image
`{path,width,height,frameId}`. Use a completed frame of the CURRENT
Displayable/geometry, copied consistently. After display switch wait for
its first paint within the operation budget; never label old pixels as
the new screen. Missing frame is explicit with useful partial observation.
frameId identifies completed frames, not model revision. Native capture
is explicit via screenshot/observe --screenshot. No OCR/cache/history.

One finite operation budget is passed as remaining time to queue, worker
and callbacks. Transport gets sufficient allowance. Ordinary timeout is
not a worker-kill policy. Queued action timeout exposes unknown effect;
observe before retry, no automatic retries. Input callbacks may return
pending permission. Composite press/hold/tap/drag guarantees matching
release is scheduled/enqueued on pending/timeout/interruption. Separate
down/up intentionally holds. Permission/status/observe remain reachable
while callback is blocked; answer continues original action.

Mutable data/captures live outside bundle. Validate storage and JVM
request before reset/import deletion; preserve saves on rejected requests.

## Acceptance

One-pass delivery requires Java 8 build and public one-shot/bridge suite:
two fields set from one observation; async commands/rows never redirect
old ref; old-worker refs rejected; select differs from activate;
permission in keyPressed retains matching release; startup timeout keeps
worker; queued timeout reports unknown effect; current Canvas pixels;
equivalent task facts in text/JSON; storage rejection preserves data;
close/reopen and stop leave no leaked processes. No W4ME compatibility gate.
