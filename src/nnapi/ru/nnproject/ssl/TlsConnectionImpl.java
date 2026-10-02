package ru.nnproject.ssl;

import emulator.Permission;
import emulator.Settings;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;

import javax.microedition.io.SecurityInfo;
import javax.microedition.pki.Certificate;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;

final class TlsConnectionImpl implements TlsConnection {
	private final SSLSocket socket;
	private final byte[] peerCertificate;
	private final String applicationProtocol;
	private final SecurityInfo securityInfo;
	private boolean closed;
	private boolean inputOpened;
	private boolean outputOpened;
	private int openStreams;

	private TlsConnectionImpl(SSLSocket socket, X509Certificate peer,
			String applicationProtocol) throws CertificateException {
		this.socket = socket;
		this.peerCertificate = peer.getEncoded();
		this.applicationProtocol = applicationProtocol;
		this.securityInfo = new TlsSecurityInfo(socket.getSession(), peer);
	}

	static TlsConnection open(String host, int port, byte[] certificateDer,
			byte[] privateKeyPkcs8, byte[] expectedPin, String protocol,
			int timeoutMillis) throws IOException {
		validateArguments(host, port, certificateDer, privateKeyPkcs8,
				expectedPin, protocol, timeoutMillis);
		if (Settings.networkNotAvailable) {
			throw new IOException("Network not available");
		}
		Permission.checkPermission("connector.open.ssl");

		Socket transport = null;
		SSLSocket tls = null;
		boolean connected = false;
		byte[] certificateBytes = certificateDer.clone();
		byte[] keyBytes = privateKeyPkcs8.clone();
		byte[] pin = expectedPin.clone();
		try {
			SSLContext context = createContext(certificateBytes, keyBytes, pin);
			transport = new Socket();
			transport.connect(new InetSocketAddress(host, port), timeoutMillis);
			tls = (SSLSocket) context.getSocketFactory().createSocket(
					transport, host, port, true);
			tls.setUseClientMode(true);
			tls.setEnabledProtocols(new String[] { "TLSv1.3" });
			tls.setSoTimeout(timeoutMillis);
			SSLParameters parameters = tls.getSSLParameters();
			parameters.setApplicationProtocols(protocol == null || protocol.length() == 0
					? new String[0] : new String[] { protocol });
			tls.setSSLParameters(parameters);
			tls.startHandshake();
			SSLSession session = tls.getSession();
			if (!"TLSv1.3".equals(session.getProtocol())) {
				throw new IOException("TLS 1.3 was not negotiated");
			}
			java.security.cert.Certificate[] localCertificates = session.getLocalCertificates();
			if (localCertificates == null || localCertificates.length == 0) {
				throw new IOException("Server did not request a client certificate");
			}
			String negotiatedProtocol = tls.getApplicationProtocol();
			if (protocol != null && protocol.length() != 0 && !protocol.equals(negotiatedProtocol)) {
				throw new IOException("Required application protocol was not negotiated");
			}
			X509Certificate peer = (X509Certificate) session.getPeerCertificates()[0];
			TlsConnectionImpl connection = new TlsConnectionImpl(tls, peer, negotiatedProtocol);
			connected = true;
			return connection;
		} catch (GeneralSecurityException e) {
			throw new IOException("Invalid TLS credentials or configuration", e);
		} catch (IllegalArgumentException e) {
			throw new IOException("Invalid TLS configuration", e);
		} finally {
			Arrays.fill(keyBytes, (byte) 0);
			if (!connected) {
				closeFailedSocket(tls);
				closeFailedSocket(transport);
			}
		}
	}

	private static void validateArguments(String host, int port, byte[] certificate,
			byte[] key, byte[] pin, String protocol, int timeout) throws IOException {
		if (host == null || host.length() == 0 || port < 1 || port > 65535 || timeout <= 0) {
			throw new IOException("Host, port and positive timeout are required");
		}
		if (certificate == null || certificate.length == 0 || key == null || key.length == 0) {
			throw new IOException("Client certificate and private key are required");
		}
		if (pin == null || pin.length != 32) {
			throw new IOException("Peer certificate SHA-256 pin must contain 32 bytes");
		}
		if (protocol != null) {
			if (protocol.length() > 255) {
				throw new IOException("Application protocol must contain at most 255 bytes");
			}
			for (int i = 0; i < protocol.length(); i++) {
				if (protocol.charAt(i) < 0x21 || protocol.charAt(i) > 0x7e) {
					throw new IOException("Application protocol must contain printable ASCII bytes");
				}
			}
		}
	}

	private static SSLContext createContext(byte[] certificateBytes,
			byte[] keyBytes, byte[] pin) throws GeneralSecurityException, IOException {
		if (certificateBytes[0] != 0x30 || keyBytes[0] != 0x30) {
			throw new CertificateException("Certificate and key must use DER encoding");
		}
		X509Certificate certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
				.generateCertificate(new ByteArrayInputStream(certificateBytes));
		if (!MessageDigest.isEqual(certificateBytes, certificate.getEncoded())) {
			throw new CertificateException("Exactly one DER certificate is required");
		}
		String keyAlgorithm = certificate.getPublicKey().getAlgorithm();
		String signatureAlgorithm;
		if ("RSA".equals(keyAlgorithm)) {
			signatureAlgorithm = "SHA256withRSA";
		} else if ("EC".equals(keyAlgorithm)) {
			signatureAlgorithm = "SHA256withECDSA";
		} else if ("EdDSA".equals(keyAlgorithm) || "Ed25519".equals(keyAlgorithm)
				|| "Ed448".equals(keyAlgorithm)) {
			signatureAlgorithm = keyAlgorithm;
		} else {
			throw new CertificateException("Unsupported client key algorithm: " + keyAlgorithm);
		}
		PrivateKey privateKey = KeyFactory.getInstance(keyAlgorithm)
				.generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
		byte[] challenge = new byte[32];
		new SecureRandom().nextBytes(challenge);
		Signature signature = Signature.getInstance(signatureAlgorithm);
		signature.initSign(privateKey);
		signature.update(challenge);
		byte[] signed = signature.sign();
		signature.initVerify(certificate.getPublicKey());
		signature.update(challenge);
		if (!signature.verify(signed)) {
			throw new CertificateException("Client certificate and private key do not match");
		}
		KeyStore credentials = KeyStore.getInstance("PKCS12");
		credentials.load(null, null);
		char[] password = new char[0];
		credentials.setKeyEntry("client", privateKey, password,
				new java.security.cert.Certificate[] { certificate });
		KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
		keyManagers.init(credentials, password);
		SSLContext context = SSLContext.getInstance("TLSv1.3");
		context.init(keyManagers.getKeyManagers(), new TrustManager[] { new PinnedTrustManager(pin) }, null);
		return context;
	}

	private static void closeFailedSocket(Socket socket) {
		if (socket != null) {
			try {
				socket.close();
			} catch (IOException ignored) {
			}
		}
	}

	private void ensureOpen() throws IOException {
		if (closed) {
			throw new IOException("TLS connection is closed");
		}
	}

	public synchronized byte[] getPeerCertificate() throws IOException {
		ensureOpen();
		return peerCertificate.clone();
	}

	public synchronized String getApplicationProtocol() throws IOException {
		ensureOpen();
		return applicationProtocol;
	}

	public synchronized SecurityInfo getSecurityInfo() throws IOException {
		ensureOpen();
		return securityInfo;
	}

	public synchronized InputStream openInputStream() throws IOException {
		ensureOpen();
		if (inputOpened) {
			throw new IOException("TLS input stream has already been opened");
		}
		InputStream stream = new TlsInputStream(socket.getInputStream());
		inputOpened = true;
		openStreams++;
		return stream;
	}

	public DataInputStream openDataInputStream() throws IOException {
		return new DataInputStream(openInputStream());
	}

	public synchronized OutputStream openOutputStream() throws IOException {
		ensureOpen();
		if (outputOpened) {
			throw new IOException("TLS output stream has already been opened");
		}
		OutputStream stream = new TlsOutputStream(socket.getOutputStream());
		outputOpened = true;
		openStreams++;
		return stream;
	}

	public DataOutputStream openDataOutputStream() throws IOException {
		return new DataOutputStream(openOutputStream());
	}

	public synchronized void close() throws IOException {
		if (closed) return;
		closed = true;
		if (openStreams == 0) socket.close();
	}

	private synchronized void releaseStream() throws IOException {
		openStreams--;
		if (closed && openStreams == 0) socket.close();
	}

	public synchronized String getLocalAddress() throws IOException {
		ensureOpen();
		return socket.getLocalAddress().getHostAddress();
	}

	public synchronized int getLocalPort() throws IOException {
		ensureOpen();
		return socket.getLocalPort();
	}

	public synchronized String getAddress() throws IOException {
		ensureOpen();
		return socket.getInetAddress().getHostAddress();
	}

	public synchronized int getPort() throws IOException {
		ensureOpen();
		return socket.getPort();
	}

	public synchronized int getSocketOption(byte option) throws IOException {
		ensureOpen();
		switch (option) {
			case DELAY: return socket.getTcpNoDelay() ? 0 : 1;
			case LINGER: return Math.max(0, socket.getSoLinger());
			case KEEPALIVE: return socket.getKeepAlive() ? 1 : 0;
			case RCVBUF: return socket.getReceiveBufferSize();
			case SNDBUF: return socket.getSendBufferSize();
			default: throw new IllegalArgumentException("Unknown socket option");
		}
	}

	public synchronized void setSocketOption(byte option, int value) throws IOException {
		ensureOpen();
		if (value < 0 || ((option == RCVBUF || option == SNDBUF) && value == 0)) {
			throw new IllegalArgumentException("Invalid socket option value");
		}
		switch (option) {
			case DELAY: socket.setTcpNoDelay(value == 0); break;
			case LINGER: socket.setSoLinger(value != 0, value); break;
			case KEEPALIVE: socket.setKeepAlive(value != 0); break;
			case RCVBUF: socket.setReceiveBufferSize(value); break;
			case SNDBUF: socket.setSendBufferSize(value); break;
			default: throw new IllegalArgumentException("Unknown socket option");
		}
	}

	private final class TlsInputStream extends InputStream {
		private final InputStream delegate;
		private volatile boolean streamClosed;

		TlsInputStream(InputStream delegate) {
			this.delegate = delegate;
		}

		private void ensureStreamOpen() throws IOException {
			if (streamClosed) throw new IOException("TLS input stream is closed");
		}

		public int read() throws IOException {
			ensureStreamOpen();
			return delegate.read();
		}

		public int read(byte[] bytes, int offset, int length) throws IOException {
			ensureStreamOpen();
			return delegate.read(bytes, offset, length);
		}

		public int available() throws IOException {
			ensureStreamOpen();
			return delegate.available();
		}

		public synchronized void close() throws IOException {
			if (streamClosed) return;
			streamClosed = true;
			// Closing a JSSE stream closes the whole socket; GCF owns its lifetime.
			releaseStream();
		}
	}

	private final class TlsOutputStream extends OutputStream {
		private final OutputStream delegate;
		private volatile boolean streamClosed;

		TlsOutputStream(OutputStream delegate) {
			this.delegate = delegate;
		}

		private void ensureStreamOpen() throws IOException {
			if (streamClosed) throw new IOException("TLS output stream is closed");
		}

		public void write(int value) throws IOException {
			ensureStreamOpen();
			delegate.write(value);
		}

		public void write(byte[] bytes, int offset, int length) throws IOException {
			ensureStreamOpen();
			delegate.write(bytes, offset, length);
		}

		public void flush() throws IOException {
			ensureStreamOpen();
			delegate.flush();
		}

		public synchronized void close() throws IOException {
			if (streamClosed) return;
			streamClosed = true;
			releaseStream();
		}
	}

	private static final class PinnedTrustManager extends X509ExtendedTrustManager {
		private final byte[] pin;

		PinnedTrustManager(byte[] pin) {
			this.pin = pin;
		}

		private void checkPeer(X509Certificate[] chain) throws CertificateException {
			if (chain == null || chain.length == 0) {
				throw new CertificateException("Server certificate is required");
			}
			try {
				byte[] actual = MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded());
				if (!MessageDigest.isEqual(pin, actual)) {
					throw new CertificateException("Peer certificate SHA-256 pin mismatch");
				}
			} catch (GeneralSecurityException e) {
				throw new CertificateException("Peer certificate authentication failed", e);
			}
		}

		public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
			checkPeer(chain);
		}

		public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
			checkPeer(chain);
		}

		public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
			checkPeer(chain);
		}

		public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
			throw new CertificateException("This context only authenticates servers");
		}

		public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
			checkClientTrusted(chain, authType);
		}

		public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
			checkClientTrusted(chain, authType);
		}

		public X509Certificate[] getAcceptedIssuers() {
			return new X509Certificate[0];
		}
	}

	private static final class TlsSecurityInfo implements SecurityInfo {
		private final String cipherSuite;
		private final Certificate certificate;

		TlsSecurityInfo(SSLSession session, X509Certificate peer) {
			cipherSuite = session.getCipherSuite();
			certificate = new TlsCertificate(peer);
		}

		public String getCipherSuite() { return cipherSuite; }
		public String getProtocolName() { return "TLS"; }
		public String getProtocolVersion() { return "1.3"; }
		public Certificate getServerCertificate() { return certificate; }
	}

	private static final class TlsCertificate implements Certificate {
		private final X509Certificate certificate;

		TlsCertificate(X509Certificate certificate) {
			this.certificate = certificate;
		}

		public String getIssuer() { return certificate.getIssuerX500Principal().getName(); }
		public long getNotAfter() { return certificate.getNotAfter().getTime(); }
		public long getNotBefore() { return certificate.getNotBefore().getTime(); }
		public String getSerialNumber() { return certificate.getSerialNumber().toString(16); }
		public String getSigAlgName() { return certificate.getSigAlgName(); }
		public String getSubject() { return certificate.getSubjectX500Principal().getName(); }
		public String getType() { return "X.509"; }
		public String getVersion() { return Integer.toString(certificate.getVersion()); }
	}
}
