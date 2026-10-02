# TLS extension integration checks

Run only inside the designated build/test container, against an already built
release bundle:

```sh
bash /work/KEmulator/automation/tls-tests/run.sh /path/to/release-bundle
```

Requires JDK 17 or newer, OpenSSL and Python 3. The script refuses host execution,
does not rebuild production classes, and removes its own temporary certificates
and harness classes on exit. All servers bind loopback on dynamically chosen ports;
no internet, UI, production certificates, or external dependencies are involved.

The harness exercises TLS 1.3 mutual authentication with RSA, EC, Ed25519 and Ed448
credentials, leaf-certificate SHA-256 pinning, required and optional ALPN, rejection
of TLS 1.2 and malformed credentials, emulator network/permission policy,
handshake/read timeouts, defensive certificate copies, metadata, and reference
counted connection/stream closure. The local server independently checks the exact
client certificate, TLS protocol and ALPN and echoes application data.

The class-loader check loads a minimal fixture through `CustomClassLoader` with
`hideEmulation=true`, using the actual method rewriting and protected-package
rules. It verifies both public TLS types remain reachable, while the connection
implementation and explicit protected packages remain inaccessible. The fixture
contains only Java 1.4 compatible instructions; the runner lowers its class-file
version because current javac releases no longer offer `-target 1.4`.

A deterministic connect-timeout check is intentionally omitted: portable loopback
networking cannot reliably produce a TCP connect timeout. Handshake and read
timeout checks use local sockets that deliberately withhold data.
