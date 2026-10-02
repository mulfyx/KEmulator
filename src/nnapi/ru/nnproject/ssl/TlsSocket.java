package ru.nnproject.ssl;

import java.io.IOException;

/** Experimental TLS 1.3 API; its public types are usable from CLDC 1.1 source. */
public final class TlsSocket {

	private TlsSocket() {
	}

	/**
	 * Opens a mutually authenticated TLS 1.3 connection before returning.
	 * Certificate and unencrypted PKCS#8 key are DER; the mandatory peer pin is
	 * the 32-byte SHA-256 of its complete leaf certificate DER. A nonempty ALPN
	 * name must be negotiated exactly. Timeout is positive milliseconds for
	 * connect, handshake and each blocking read; DNS and writes are not bounded.
	 * Invalid input, credentials or handshake failure cause IOException.
	 * Emulator network permission denial causes SecurityException.
	 */
	public static TlsConnection open(String host, int port,
			byte[] clientCertificateDer, byte[] clientPrivateKeyPkcs8,
			byte[] expectedPeerCertificateSha256, String applicationProtocol,
			int timeoutMillis) throws IOException {
		return TlsConnectionImpl.open(host, port, clientCertificateDer,
				clientPrivateKeyPkcs8, expectedPeerCertificateSha256,
				applicationProtocol, timeoutMillis);
	}
}
