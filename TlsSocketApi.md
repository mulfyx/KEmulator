# Experimental TLS 1.3 socket extension

This is a working KEmulator prototype for discussing a native extension with
Arman. It is a proposed contract, not an API agreed with or implemented by the
phone patch maintainer. The native Nokia E5 / Symbian 9.3 implementation is a
separate task. KEmulator performs the cryptography in its host JVM; this does not
establish that it fits a CLDC 1.1 / MIDP 2 phone with a 16 MB heap.

## Public API

The application-visible signatures contain CLDC / MIDP types only. They can be
called from Java 1.3 source without `javax.net.ssl`, `java.net`, reflection,
generics or Java SE certificate classes:

```java
package ru.nnproject.ssl;

public final class TlsSocket {
    public static TlsConnection open(
        String host, int port,
        byte[] clientCertificateDer,
        byte[] clientPrivateKeyPkcs8,
        byte[] expectedPeerCertificateSha256,
        String applicationProtocol,
        int timeoutMillis) throws java.io.IOException;
}

public interface TlsConnection extends javax.microedition.io.SecureConnection {
    byte[] getPeerCertificate() throws java.io.IOException;
    String getApplicationProtocol() throws java.io.IOException;
}
```

For MIDlet compilation, put these signatures in the compile-time API library.
Do not package API stubs in the application JAR: KEmulator loads classes shipped
by the MIDlet before its host extension, so a packaged stub would replace the
working implementation.

`open` negotiates TLS 1.3, authenticates the server's pinned certificate and
presents the supplied client certificate before returning a connection. It
rejects TLS downgrade, a missing server request for client authentication and
failure to negotiate the requested application protocol. A server may still
send a later TLS alert if it rejects the client: TLS 1.3 does not acknowledge the
client's final handshake flight. Applications must handle I/O failures on the
returned streams.

## Inputs and identity

* `host` is a nonempty DNS name or IP address; `port` is 1 through 65535.
* `clientCertificateDer` contains exactly one complete X.509 certificate in
  binary DER form. PEM text and certificate chains are not accepted.
* `clientPrivateKeyPkcs8` is the matching, unencrypted private key in binary
  PKCS#8 DER form. PEM, encrypted PKCS#8 and traditional PKCS#1/SEC1 key files
  require conversion before calling the API. The prototype accepts RSA, EC and
  EdDSA keys supported by the host JSSE provider. It checks that the certificate
  and key match before connecting.
* `expectedPeerCertificateSha256` is mandatory and contains exactly 32 raw bytes:
  SHA-256 of the peer's **complete leaf certificate DER**, not its public key,
  hex text, a Syncthing Device ID string or a CA certificate. The application
  obtains and verifies this identity through its pairing/configuration flow.
* `applicationProtocol` is `"bep/1.0"` for Syncthing. A nonempty value is one
  printable ASCII ALPN name, at most 255 bytes, and must be negotiated exactly.
  `null` or `""` omits ALPN and `getApplicationProtocol()` returns `""`.
* `timeoutMillis` is positive milliseconds for TCP connect and each blocking
  read, including handshake reads. DNS resolution and blocking writes have no
  deadline in this JSSE prototype; it is not a total operation deadline.

The pin is checked by a connection-local trust manager during the handshake,
before application streams are available. A wrong pin closes the socket and
throws `IOException`. Trust uses this exact certificate identity, including
self-signed certificates; it does not apply CA-chain, hostname or certificate
validity-date checks. TLS proof of possession authenticates the pinned key.
There is no system-trust fallback and no change to JVM-wide default trust.
Changing a peer certificate requires a newly verified pin.

Credential and pin arrays are copied for the handshake. The caller owns its
input arrays and should erase its private key buffer when it no longer needs it.
`getPeerCertificate()` returns a fresh DER copy on every call.

## GCF behavior

`TlsConnection` exposes the usual `SecureConnection` streams, addresses, ports
and socket options. `getSecurityInfo()` reports protocol name `"TLS"`, version
`"1.3"`, the negotiated cipher suite and a MIDP `Certificate` for the peer.
It uses the established session, rather than `getHandshakeSession()` after
handshaking. The certificate type is `"X.509"`, version is a decimal string and
serial number is a hexadecimal string.

One input stream and one output stream can be opened per connection. Opening
one again, including after its closure, throws `IOException`. Data streams
share this limit with their corresponding plain streams. Each stream's close
is idempotent and makes that stream unusable; it leaves the other stream usable.
The prototype delays TLS shutdown rather than half-closing its JSSE streams.

`connection.close()` is idempotent. It forbids further connection operations and
new streams, but existing streams remain usable. The underlying TLS socket is
closed when both the connection and all streams opened from it have been
closed. To stop a session completely, close the streams and the connection in a
`finally` block.

Invalid arguments, invalid credentials, transport errors, TLS authentication,
protocol negotiation and timeouts fail with `IOException`. Socket option values
retain the GCF `IllegalArgumentException` behavior. The emulator applies
`Settings.networkNotAvailable` and the existing `connector.open.ssl` permission;
a denied permission raises `SecurityException`. The two public classes remain
available with `hideEmulation=true`; explicit `protectedPackages` restrictions
still apply, and the host implementation stays hidden from MIDlets.

## Example and verification

Provision `certificateDer`, `privateKeyPkcs8` and the verified `peerPin` through
the application's storage/pairing flow; the extension does not generate or
persist credentials:

```java
TlsConnection connection = TlsSocket.open(host, 22000,
    certificateDer, privateKeyPkcs8, peerPin, "bep/1.0", 10000);
java.io.InputStream input = null;
java.io.OutputStream output = null;
try {
    input = connection.openInputStream();
    output = connection.openOutputStream();
    // Exchange BEP bytes using input/output.
} finally {
    try {
        if (output != null) output.close();
    } finally {
        try {
            if (input != null) input.close();
        } finally {
            connection.close();
        }
    }
}
```

The host prototype requires a TLS 1.3 / ALPN capable JVM; its test target is
JDK 21. Build the release once, then run the real TLS server/client tests as
described in [automation/tls-tests/README.md](automation/tls-tests/README.md).
Tests exercise the emulator implementation and classloader. They do not qualify
the native Symbian patch, phone memory use or phone-network behavior.

Reference semantics: [GCF Connection lifetime](https://docs.oracle.com/javame/config/cdc/ref-impl/pp1.1.2/jsr216/javax/microedition/io/Connection.html),
[JSSE ALPN parameters](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/javax/net/ssl/SSLParameters.html).
