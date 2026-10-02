package ru.nnproject.ssl;

import java.io.IOException;
import javax.microedition.io.SecureConnection;

/** Experimental pinned TLS 1.3 connection for the Symbian extension prototype. */
public interface TlsConnection extends SecureConnection {

	/** Returns a copy of the authenticated peer's leaf X.509 certificate in DER. */
	byte[] getPeerCertificate() throws IOException;

	/** Returns the negotiated ALPN name, or an empty string when ALPN was omitted. */
	String getApplicationProtocol() throws IOException;
}
