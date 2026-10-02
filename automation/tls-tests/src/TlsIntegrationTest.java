import emulator.Emulator;
import emulator.Settings;
import emulator.Permission;
import emulator.custom.CustomClassLoader;
import emulator.ui.IEmulatorFrontend;
import emulator.ui.ILogStream;
import ru.nnproject.ssl.TlsConnection;
import ru.nnproject.ssl.TlsSocket;

import javax.microedition.io.SecurityInfo;
import javax.microedition.io.SocketConnection;
import javax.net.ssl.*;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.security.cert.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** No test framework: every network operation is against a local real JSSE server. */
public final class TlsIntegrationTest {
    private static final String ALPN = "bep/1.0";
    private static final int TIMEOUT = 3000;
    private static Path directory;
    private static SSLContext serverContext;
    private static byte[] serverDer;
    private static byte[] pin;
    private static byte[] certificate;
    private static byte[] key;
    private static int passed;

    public static void main(String[] args) throws Exception {
        directory = Paths.get(args[0]);
        serverDer = bytes("server.cert.der");
        pin = MessageDigest.getInstance("SHA-256").digest(serverDer);
        serverContext = makeServerContext();
        Settings.enableSecurity = false;
        Settings.networkNotAvailable = false;
        test("RSA mutual TLS, pin, ALPN and metadata", () -> positive("rsa", ALPN));
        test("EC mutual TLS", () -> positive("ec", ALPN));
        test("Ed25519 mutual TLS", () -> positive("ed25519", ALPN));
        test("Ed448 mutual TLS", () -> positive("ed448", ALPN));
        credentials("rsa");
        test("optional ALPN accepts no protocol", () -> { positive("rsa", null); positive("rsa", ""); });
        test("wrong pin rejects open", TlsIntegrationTest::wrongPin);
        test("required ALPN rejects mismatch and absent negotiation", TlsIntegrationTest::alpnMismatch);
        test("TLS 1.2 fallback rejected", TlsIntegrationTest::oldTlsRejected);
        test("server must request client authentication", TlsIntegrationTest::missingClientAuth);
        test("invalid/missing/mismatched credentials rejected", TlsIntegrationTest::invalidArguments);
        test("emulator network and SSL permission policy", TlsIntegrationTest::policy);
        test("handshake timeout", TlsIntegrationTest::handshakeTimeout);
        test("read timeout", TlsIntegrationTest::readTimeout);
        test("connection close preserves already opened streams", TlsIntegrationTest::connectionLifecycle);
        test("stream close preserves other references", TlsIntegrationTest::streamLifecycle);
        test("connection with no streams closes socket", TlsIntegrationTest::noStreamLifecycle);
        test("real MIDlet loader visibility and explicit protection", () -> classLoader(args[1]));
        System.out.println("TLS integration checks passed: " + passed);
    }

    private static void test(String name, Checked action) throws Exception {
        action.run();
        passed++;
        System.out.println("PASS " + name);
    }

    private static byte[] bytes(String name) throws IOException {
        return Files.readAllBytes(directory.resolve(name));
    }

    private static void credentials(String algorithm) throws IOException {
        certificate = bytes("client-" + algorithm + ".cert.der");
        key = bytes("client-" + algorithm + ".key.der");
    }

    private static TlsConnection open(int port, String protocol, int timeout) throws IOException {
        return TlsSocket.open("127.0.0.1", port, certificate, key, pin, protocol, timeout);
    }

    private static SSLContext makeServerContext() throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(directory.resolve("server.p12"))) {
            store.load(input, "test-only".toCharArray());
        }
        KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        factory.init(store, "test-only".toCharArray());
        final List<byte[]> clients = Arrays.asList(bytes("client-rsa.cert.der"),
                bytes("client-ec.cert.der"), bytes("client-ed25519.cert.der"), bytes("client-ed448.cert.der"));
        X509TrustManager trust = new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                if (chain == null || chain.length == 0) throw new CertificateException("Client certificate required");
                for (byte[] accepted : clients) {
                    if (Arrays.equals(accepted, chain[0].getEncoded())) return;
                }
                throw new CertificateException("Unexpected client certificate");
            }
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                throw new CertificateException("Server-only trust manager");
            }
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(factory.getKeyManagers(), new TrustManager[]{trust}, null);
        return context;
    }

    private static void positive(String algorithm, String protocol) throws Exception {
        credentials(algorithm);
        SSLContext defaultContext = SSLContext.getDefault();
        SSLSocketFactory defaultFactory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        byte[] originalCertificate = certificate.clone();
        byte[] originalKey = key.clone();
        try (Server server = new Server("TLSv1.3", protocol, false, false);
             CloseConnection cleanup = new CloseConnection(open(server.port(), protocol, TIMEOUT))) {
            TlsConnection connection = cleanup.connection;
            check(Arrays.equals(originalCertificate, certificate), "open modified caller certificate");
            check(Arrays.equals(originalKey, key), "open modified caller private key");
            check(connection instanceof SocketConnection, "TLS connection must implement SocketConnection");
            check(Arrays.equals(serverDer, connection.getPeerCertificate()), "peer DER differs from leaf certificate");
            byte[] copy = connection.getPeerCertificate();
            copy[0] ^= 1;
            check(Arrays.equals(serverDer, connection.getPeerCertificate()), "peer certificate bytes are not defensive copies");
            equal(protocol == null ? "" : protocol, connection.getApplicationProtocol(), "client ALPN");
            SecurityInfo security = connection.getSecurityInfo();
            equal("TLS", security.getProtocolName(), "protocol name");
            equal("1.3", security.getProtocolVersion(), "protocol version");
            check(security.getCipherSuite() != null && security.getCipherSuite().contains("TLS_"), "cipher suite missing");
            check(security.getServerCertificate() != null, "MIDP server certificate missing");
            X509Certificate expectedCert = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(serverDer));
            javax.microedition.pki.Certificate midpCert = security.getServerCertificate();
            equal(expectedCert.getSubjectX500Principal().getName(), midpCert.getSubject(), "certificate subject");
            equal(expectedCert.getIssuerX500Principal().getName(), midpCert.getIssuer(), "certificate issuer");
            equal(expectedCert.getSerialNumber().toString(16), midpCert.getSerialNumber(), "certificate serial");
            equal(expectedCert.getSigAlgName(), midpCert.getSigAlgName(), "certificate signature algorithm");
            equal("X.509", midpCert.getType(), "certificate type");
            equal(Integer.toString(expectedCert.getVersion()), midpCert.getVersion(), "certificate version");
            check(midpCert.getNotBefore() == expectedCert.getNotBefore().getTime(), "certificate not-before");
            check(midpCert.getNotAfter() == expectedCert.getNotAfter().getTime(), "certificate not-after");
            equal("127.0.0.1", connection.getAddress(), "remote address");
            check(connection.getPort() == server.port(), "remote port");
            check(connection.getLocalPort() > 0, "local port");
            check(connection.getLocalAddress() != null, "local address");
            connection.setSocketOption(SocketConnection.KEEPALIVE, 1);
            check(connection.getSocketOption(SocketConnection.KEEPALIVE) == 1, "socket option roundtrip");
            // MIDP DELAY=0 disables Nagle; observe the real JSSE socket as the oracle.
            Field socketField = connection.getClass().getDeclaredField("socket");
            socketField.setAccessible(true);
            SSLSocket nativeSocket = (SSLSocket) socketField.get(connection);
            connection.setSocketOption(SocketConnection.DELAY, 0);
            check(nativeSocket.getTcpNoDelay(), "DELAY=0 must set TCP_NODELAY");
            connection.setSocketOption(SocketConnection.DELAY, 1);
            check(!nativeSocket.getTcpNoDelay(), "DELAY=1 must enable Nagle");
            nativeSocket.setTcpNoDelay(true);
            check(connection.getSocketOption(SocketConnection.DELAY) == 0, "TCP_NODELAY must report DELAY=0");
            nativeSocket.setTcpNoDelay(false);
            check(connection.getSocketOption(SocketConnection.DELAY) == 1, "Nagle must report DELAY=1");
            try (InputStream input = connection.openInputStream(); OutputStream output = connection.openOutputStream()) {
                output.write(new byte[]{7, 11, 19});
                output.flush();
                for (int expected : new int[]{7, 11, 19}) check(input.read() == expected, "TLS application data differs");
            }
            server.assertHandshake(certificate, protocol);
        }
        check(defaultContext == SSLContext.getDefault(), "TLS extension replaced global default context");
        check(defaultFactory.getClass() == SSLSocketFactory.getDefault().getClass(), "global socket factory changed");
    }

    private static void wrongPin() throws Exception {
        byte[] wrong = pin.clone();
        wrong[0] ^= 1;
        try (Server server = new Server("TLSv1.3", ALPN, true, false)) {
            expectIOException(() -> TlsSocket.open("127.0.0.1", server.port(), certificate, key, wrong, ALPN, TIMEOUT));
            check(server.handshake.await(TIMEOUT, TimeUnit.MILLISECONDS), "wrong-pin handshake did not end");
            check(server.received.get() == 0, "application data reached wrong-pin peer");
        }
    }

    private static void alpnMismatch() throws Exception {
        for (String advertised : new String[]{"other/1.0", null}) {
            try (Server server = new Server("TLSv1.3", advertised, true, false)) {
                expectIOException(() -> open(server.port(), ALPN, TIMEOUT));
                check(server.received.get() == 0, "application data reached ALPN-mismatched peer");
            }
        }
    }

    private static void oldTlsRejected() throws Exception {
        try (Server server = new Server("TLSv1.2", ALPN, true, false)) {
            expectIOException(() -> open(server.port(), ALPN, TIMEOUT));
        }
    }

    private static void missingClientAuth() throws Exception {
        try (Server server = new Server("TLSv1.3", ALPN, true, false, false)) {
            expectIOException(() -> open(server.port(), ALPN, TIMEOUT));
        }
    }

    private static void invalidArguments() throws Exception {
        // Use a listening real server for mismatched credentials so a connection
        // refusal can never masquerade as successful credential validation.
        try (Server server = new Server("TLSv1.3", ALPN, true, false)) {
            expectIOException(() -> TlsSocket.open("127.0.0.1", server.port(), certificate,
                    bytes("server.key.der"), pin, ALPN, TIMEOUT));
        }
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            int port = listener.getLocalPort();
            long start = System.nanoTime();
            expectIOException(() -> TlsSocket.open("127.0.0.1", port, null, key, pin, ALPN, 250));
            expectIOException(() -> TlsSocket.open("127.0.0.1", port, certificate, null, pin, ALPN, 250));
            expectIOException(() -> TlsSocket.open("127.0.0.1", port, new byte[0], key, pin, ALPN, 250));
            expectIOException(() -> TlsSocket.open("127.0.0.1", port, certificate, new byte[0], pin, ALPN, 250));
            expectIOException(() -> TlsSocket.open("127.0.0.1", port, new byte[]{1,2,3}, key, pin, ALPN, 250));
            expectIOException(() -> TlsSocket.open("127.0.0.1", port, certificate, new byte[]{1,2,3}, pin, ALPN, 250));
            expectIOException(() -> TlsSocket.open("127.0.0.1", port, certificate, key, null, ALPN, 250));
            expectIOException(() -> TlsSocket.open("127.0.0.1", port, certificate, key, new byte[31], ALPN, 250));
            expectIOException(() -> TlsSocket.open("127.0.0.1", port, certificate, key, new byte[33], ALPN, 250));
            expectIOException(() -> open(port, ALPN, 0));
            expectIOException(() -> open(port, ALPN, -1));
            expectIOException(() -> TlsSocket.open(null, port, certificate, key, pin, ALPN, 250));
            expectIOException(() -> TlsSocket.open("", port, certificate, key, pin, ALPN, 250));
            expectIOException(() -> TlsSocket.open("127.0.0.1", 0, certificate, key, pin, ALPN, 250));
            expectIOException(() -> TlsSocket.open("127.0.0.1", 65536, certificate, key, pin, ALPN, 250));
            check(elapsedMillis(start) < 2000, "argument validation tried to handshake");
            listener.setSoTimeout(100);
            try (Socket unexpected = listener.accept()) {
                throw new AssertionError("Invalid arguments opened a network socket");
            } catch (SocketTimeoutException expected) { }
        }
    }

    private static void policy() throws Exception {
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            try {
                Settings.networkNotAvailable = true;
                expectIOException(() -> open(listener.getLocalPort(), ALPN, 250));
            } finally { Settings.networkNotAvailable = false; }
            try {
                Settings.enableSecurity = true;
                Permission.permissions.put("connector.open.ssl", Permission.never);
                try {
                    open(listener.getLocalPort(), ALPN, 250);
                    throw new AssertionError("Denied SSL permission accepted");
                } catch (SecurityException expected) { }
            } finally {
                Settings.enableSecurity = false;
                Permission.permissions.remove("connector.open.ssl");
            }
            listener.setSoTimeout(100);
            try (Socket unexpected = listener.accept()) {
                throw new AssertionError("Denied network policy opened a socket");
            } catch (SocketTimeoutException expected) { }
        }
    }

    private static void handshakeTimeout() throws Exception {
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            long start = System.nanoTime();
            IOException failure = expectIOException(() -> open(listener.getLocalPort(), ALPN, 250));
            check(hasTimeout(failure), "handshake rejection was not a timeout: " + failure);
            check(elapsedMillis(start) >= 100 && elapsedMillis(start) < 2000, "handshake timeout outside bounds");
            listener.setSoTimeout(1000);
            try (Socket accepted = listener.accept()) {
                accepted.setSoTimeout(1000);
                InputStream raw = accepted.getInputStream();
                while (raw.read() != -1) { }
            }
        }
    }

    private static void readTimeout() throws Exception {
        try (Server server = new Server("TLSv1.3", ALPN, false, true);
             CloseConnection cleanup = new CloseConnection(open(server.port(), ALPN, 250));
             InputStream input = cleanup.connection.openInputStream()) {
            server.assertHandshake(certificate, ALPN);
            long start = System.nanoTime();
            IOException failure = expectIOException(() -> input.read());
            check(hasTimeout(failure), "read rejection was not a timeout: " + failure);
            check(elapsedMillis(start) >= 100 && elapsedMillis(start) < 2000, "read timeout outside bounds");
        }
    }

    private static void connectionLifecycle() throws Exception {
        try (Server server = new Server("TLSv1.3", ALPN, false, false)) {
            TlsConnection connection = open(server.port(), ALPN, TIMEOUT);
            InputStream input = connection.openInputStream();
            OutputStream output = connection.openOutputStream();
            expectIOException(() -> connection.openDataInputStream());
            expectIOException(() -> connection.openDataOutputStream());
            connection.close();
            connection.close();
            expectIOException(() -> connection.openInputStream());
            expectIOException(() -> connection.openOutputStream());
            expectIOException(() -> connection.getPeerCertificate());
            expectIOException(() -> connection.getApplicationProtocol());
            expectIOException(() -> connection.getSecurityInfo());
            expectIOException(() -> connection.getAddress());
            expectIOException(() -> connection.getPort());
            expectIOException(() -> connection.getLocalAddress());
            expectIOException(() -> connection.getLocalPort());
            expectIOException(() -> connection.getSocketOption(SocketConnection.KEEPALIVE));
            expectIOException(() -> connection.setSocketOption(SocketConnection.KEEPALIVE, 1));
            output.write(41); output.flush();
            check(input.read() == 41, "connection close invalidated already open streams");
            input.close(); input.close();
            output.write(43); output.flush();
            server.awaitReceived(2);
            check(!server.eof.await(100, TimeUnit.MILLISECONDS), "input close released live output reference");
            output.close(); output.close();
            server.awaitEof();
            expectIOException(() -> input.read());
            expectIOException(() -> output.write(1));
        }
    }

    private static void streamLifecycle() throws Exception {
        try (Server server = new Server("TLSv1.3", ALPN, false, false)) {
            TlsConnection connection = open(server.port(), ALPN, TIMEOUT);
            InputStream input = connection.openDataInputStream();
            OutputStream output = connection.openDataOutputStream();
            output.write(59); output.flush();
            server.awaitReceived(1);
            output.close(); output.close();
            check(input.read() == 59, "output close invalidated live input reference");
            expectIOException(() -> connection.openOutputStream());
            check(!server.eof.await(100, TimeUnit.MILLISECONDS), "stream close released connection reference");
            connection.getSecurityInfo();
            input.close(); input.close();
            expectIOException(() -> connection.openInputStream());
            check(!server.eof.await(100, TimeUnit.MILLISECONDS), "closing streams closed live connection");
            connection.close(); connection.close();
            server.awaitEof();
        }
    }

    private static void noStreamLifecycle() throws Exception {
        try (Server server = new Server("TLSv1.3", ALPN, false, false)) {
            TlsConnection connection = open(server.port(), ALPN, TIMEOUT);
            server.assertHandshake(certificate, ALPN);
            connection.close(); connection.close();
            server.awaitEof();
        }
    }

    private static void classLoader(String fixturePath) throws Exception {
        // Headless log/frontend doubles only support denied-class diagnostics;
        // all security decisions and bytecode rewriting remain production code.
        ILogStream log = (ILogStream) java.lang.reflect.Proxy.newProxyInstance(TlsIntegrationTest.class.getClassLoader(),
                new Class[]{ILogStream.class}, (proxy, method, args) -> null);
        IEmulatorFrontend frontend = (IEmulatorFrontend) java.lang.reflect.Proxy.newProxyInstance(TlsIntegrationTest.class.getClassLoader(),
                new Class[]{IEmulatorFrontend.class}, (proxy, method, args) ->
                        method.getName().equals("getLogStream") ? log : null);
        Field field = Emulator.class.getDeclaredField("emulatorimpl");
        field.setAccessible(true);
        Object oldFrontend = field.get(null);
        String oldClasspath = Emulator.classPath;
        String oldMidletJar = Emulator.midletJarPath;
        CustomClassLoader oldLoader = Emulator.customClassLoader;
        Set<String> oldProtection = new HashSet<>(Settings.protectedPackages);
        boolean oldHide = Settings.hideEmulation;
        String fixtureName = "tlsfixture.ApiProbe";
        try {
            field.set(null, frontend);
            Settings.hideEmulation = true;
            Settings.protectedPackages.clear();
            Emulator.jarClasses.add(fixtureName);
            Emulator.classPath = fixturePath;
            Emulator.midletJarPath = null;
            CustomClassLoader loader = new CustomClassLoader(TlsIntegrationTest.class.getClassLoader());
            Emulator.customClassLoader = loader;
            Class<?> fixture = loader.loadClass(fixtureName);
            check(fixture.getClassLoader() == loader, "fixture was loaded by host rather than MIDlet loader");
            Method load = fixture.getMethod("load", String.class);
            equal("ru.nnproject.ssl.TlsSocket", load.invoke(null, "ru.nnproject.ssl.TlsSocket"), "public socket visibility");
            equal("ru.nnproject.ssl.TlsConnection", load.invoke(null, "ru.nnproject.ssl.TlsConnection"), "public connection visibility");
            String implementation;
            try (Server server = new Server("TLSv1.3", ALPN, false, false);
                 CloseConnection cleanup = new CloseConnection(open(server.port(), ALPN, TIMEOUT))) {
                implementation = cleanup.connection.getClass().getName();
            }
            denied(load, implementation);
            denied(load, "ru.nnproject.ssl.NotPublic");
            Settings.protectedPackages.add("ru.nnproject.ssl.TlsSocket");
            denied(load, "ru.nnproject.ssl.TlsSocket");
            Settings.protectedPackages.clear();
            Settings.protectedPackages.add("ru.nnproject.ssl");
            denied(load, "ru.nnproject.ssl.TlsSocket");
            denied(load, "ru.nnproject.ssl.TlsConnection");
        } finally {
            field.set(null, oldFrontend);
            Emulator.jarClasses.remove(fixtureName);
            Emulator.classPath = oldClasspath;
            Emulator.midletJarPath = oldMidletJar;
            Emulator.customClassLoader = oldLoader;
            Settings.hideEmulation = oldHide;
            Settings.protectedPackages.clear();
            Settings.protectedPackages.addAll(oldProtection);
        }
    }

    private static void denied(Method method, String name) throws Exception {
        try {
            method.invoke(null, name);
            throw new AssertionError("MIDlet loader exposed protected class: " + name);
        } catch (InvocationTargetException failure) {
            check(failure.getCause() instanceof ClassNotFoundException,
                    "Class denial raised unexpected error: " + failure.getCause());
        }
    }

    private static IOException expectIOException(Checked action) throws Exception {
        try { action.run(); } catch (IOException expected) { return expected; }
        throw new AssertionError("Expected IOException");
    }
    private static boolean hasTimeout(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException) return true;
        }
        return false;
    }
    private static long elapsedMillis(long start) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void equal(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + ": expected " + expected + ", got " + actual);
    }
    private interface Checked { void run() throws Exception; }

    private static final class CloseConnection implements AutoCloseable {
        final TlsConnection connection;
        CloseConnection(TlsConnection connection) { this.connection = connection; }
        public void close() throws IOException { connection.close(); }
    }

    private static final class Server implements AutoCloseable {
        final SSLServerSocket listener;
        final Thread thread;
        final CountDownLatch handshake = new CountDownLatch(1);
        final CountDownLatch eof = new CountDownLatch(1);
        final AtomicInteger received = new AtomicInteger();
        final boolean expectedFailure;
        volatile SSLSocket socket;
        volatile Throwable failure;
        volatile boolean stopping;
        volatile byte[] peer;
        volatile String protocol;
        volatile String alpn;

        Server(String version, String applicationProtocol, boolean expectedFailure, boolean silent) throws IOException {
            this(version, applicationProtocol, expectedFailure, silent, true);
        }
        Server(String version, String applicationProtocol, boolean expectedFailure, boolean silent, boolean clientAuth) throws IOException {
            this.expectedFailure = expectedFailure;
            listener = (SSLServerSocket) serverContext.getServerSocketFactory().createServerSocket(
                    0, 1, InetAddress.getLoopbackAddress());
            listener.setEnabledProtocols(new String[]{version});
            listener.setNeedClientAuth(clientAuth);
            SSLParameters parameters = listener.getSSLParameters();
            parameters.setApplicationProtocols(applicationProtocol == null || applicationProtocol.isEmpty()
                    ? new String[0] : new String[]{applicationProtocol});
            listener.setSSLParameters(parameters);
            listener.setSoTimeout(TIMEOUT);
            thread = new Thread(() -> {
                try (SSLSocket accepted = (SSLSocket) listener.accept()) {
                    socket = accepted;
                    accepted.setSoTimeout(TIMEOUT);
                    accepted.startHandshake();
                    if (clientAuth) peer = accepted.getSession().getPeerCertificates()[0].getEncoded();
                    protocol = accepted.getSession().getProtocol();
                    alpn = accepted.getApplicationProtocol();
                    handshake.countDown();
                    InputStream input = accepted.getInputStream();
                    OutputStream output = accepted.getOutputStream();
                    for (int value; (value = input.read()) != -1;) {
                        received.incrementAndGet();
                        if (!silent) { output.write(value); output.flush(); }
                    }
                    eof.countDown();
                } catch (Throwable error) {
                    if (!stopping) failure = error;
                } finally { handshake.countDown(); }
            }, "tls-test-server");
            thread.setDaemon(true);
            thread.start();
        }
        int port() { return listener.getLocalPort(); }
        void assertHandshake(byte[] expectedClient, String expectedAlpn) throws Exception {
            check(handshake.await(TIMEOUT, TimeUnit.MILLISECONDS), "server handshake did not finish");
            check(failure == null, "server handshake failed: " + failure);
            check(Arrays.equals(expectedClient, peer), "server did not authenticate expected client DER");
            equal("TLSv1.3", protocol, "server TLS protocol");
            equal(expectedAlpn == null ? "" : expectedAlpn, alpn, "server ALPN");
        }
        void awaitReceived(int count) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT);
            while (received.get() < count && failure == null && System.nanoTime() < deadline) Thread.sleep(5);
            check(received.get() >= count, "server received too few bytes; failure=" + failure);
        }
        void awaitEof() throws Exception {
            check(eof.await(TIMEOUT, TimeUnit.MILLISECONDS), "physical TLS socket did not close; failure=" + failure);
        }
        public void close() throws Exception {
            stopping = true;
            listener.close();
            if (socket != null) socket.close();
            thread.join(TIMEOUT + 1000);
            check(!thread.isAlive(), "TLS server thread did not exit");
            if (!expectedFailure && failure != null) throw new AssertionError("TLS server failed", failure);
        }
    }
}
