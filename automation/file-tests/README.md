# FileConnection regression MIDlet

Run inside the designated test container against a complete release bundle:

```sh
python3 /work/KEmulator/automation/file-tests/run.py /work/project/.cache/kemu-release
```

Append scenario names (for example `rename escaped`) to run focused checks.

Requires JDK 21, ECJ 3.36 at `/opt/tools/ecj.jar` (or `ECJ_JAR`), Python 3,
and the release CLI's Xvfb dependencies. The guest fixture is compiled with
`-source 1.3 -target 1.1` and calls the public JSR-75 API from a real MIDlet.
Each scenario uses an isolated CLI session with `--worker-xmx 16M` and the
normal `file:///root/e/` memory card mapping. All fixture and session files
are created in a unique container temporary directory and removed afterward.

The scenarios cover in-place overwrite with an intact tail, clamping an
offset beyond EOF, 20 MiB files with seek and truncation above the worker
heap size, negative offsets, occupied and invalid rename targets, reuse and
reopening after rename, escaped UTF-8 and spaces, and literal plus signs.
Rename tests accept both raw and escaped names, decode `%25` as a literal
percent, and reject escaped path separators. `localhost.txt` remains a
filename while `file://localhost/` is accepted as the URL host.
The large-file checks compare every byte using an 8 KiB buffer.
