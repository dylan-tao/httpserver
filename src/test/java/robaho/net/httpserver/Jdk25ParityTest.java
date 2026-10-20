package robaho.net.httpserver;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import jdk.test.lib.net.SimpleSSLContext;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;

/**
 * End-to-end regressions for the JDK 25 parity hardening: request validation
 * (tchar header names, header block budget, "//" URIs), atomic response
 * header writes (a handler retry must not emit a second status line),
 * early-returning stop(), and legacy HttpsParameters configurators.
 */
public class Jdk25ParityTest {
    private HttpServer server;

    @BeforeMethod
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    }

    @AfterMethod
    void tearDown() {
        server.stop(0);
    }

    private int port() {
        return server.getAddress().getPort();
    }

    /** send a raw request, return the full raw response bytes. tolerates a
     * write failure: an early server rejection may reset the connection
     * while the request is still being sent */
    private byte[] rawExchange(byte[] request) throws IOException {
        try (Socket s = new Socket("127.0.0.1", port())) {
            s.setSoTimeout(8000);
            try {
                s.getOutputStream().write(request);
                s.getOutputStream().flush();
            } catch (IOException e) {
                // fall through: read whatever the server sent before closing
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            try {
                while (true) {
                    int n = s.getInputStream().read(buf);
                    if (n < 0) break;
                    out.write(buf, 0, n);
                }
            } catch (IOException e) {
                // partial response is still evidence
            }
            return out.toByteArray();
        }
    }

    private static String statusLine(byte[] r) {
        return r.length == 0 ? "<empty>" : new String(r).split("\r\n")[0];
    }

    @Test
    public void doubleSlashUriRejected() throws Exception {
        server.createContext("/", ex -> respond(ex, "ok"));
        server.start();
        byte[] r = rawExchange("GET //foo HTTP/1.1\r\nHost: b\r\nConnection: close\r\n\r\n".getBytes());
        Assert.assertTrue(statusLine(r).startsWith("HTTP/1.1 400"), "expected 400, got: " + statusLine(r));
    }

    /** only HTTP/1.0 and HTTP/1.1 are supported: anything else (the h2c PRI
     *  line is handled earlier) is a 505 */
    @Test
    public void unsupportedHttpVersionRejected() throws Exception {
        server.createContext("/", ex -> respond(ex, "ok"));
        server.start();
        byte[] r = rawExchange("GET / HTTP/9.9\r\nHost: b\r\nConnection: close\r\n\r\n".getBytes());
        Assert.assertTrue(statusLine(r).startsWith("HTTP/1.1 505"), "expected 505, got: " + statusLine(r));
    }

    /** leading empty lines are tolerated (RFC 9112 3) but bounded: an
     *  unbounded skip lets a peer hold the connection with blank lines */
    @Test
    public void excessiveLeadingBlankLinesRejected() throws Exception {
        server.createContext("/", ex -> respond(ex, "ok"));
        server.start();
        byte[] r = rawExchange("\r\n\r\n\r\n\r\n\r\n\r\nGET / HTTP/1.1\r\nHost: b\r\nConnection: close\r\n\r\n".getBytes());
        Assert.assertTrue(r.length == 0 || statusLine(r).startsWith("HTTP/1.1 400"),
                "expected 400, got: " + statusLine(r));
    }

    /** a CONNECT target port out of the valid range is a clean 400, not an
     *  IllegalArgumentException that kills the connection */
    @Test
    public void connectPortOutOfRangeRejected() throws Exception {
        server.createContext("/", new robaho.net.httpserver.extras.ProxyHandler() {
            @Override
            protected boolean authorizeConnect(com.sun.net.httpserver.HttpExchange exchange) {
                return true; // the port validation is under test, not auth
            }
        });
        server.start();
        byte[] r = rawExchange("CONNECT example.com:-1 HTTP/1.1\r\nHost: x\r\n\r\n".getBytes());
        Assert.assertTrue(statusLine(r).startsWith("HTTP/1.1 400"), "expected 400, got: " + statusLine(r));
    }

    @Test
    public void illegalHeaderNameRejected() throws Exception {
        server.createContext("/", ex -> respond(ex, "ok"));
        server.start();
        byte[] r = rawExchange("GET / HTTP/1.1\r\nHost: b\r\nFoo Bar: 1\r\nConnection: close\r\n\r\n".getBytes());
        Assert.assertTrue(statusLine(r).startsWith("HTTP/1.1 400"), "expected 400, got: " + statusLine(r));
    }

    @Test
    public void headerBlockBudgetRejected() throws Exception {
        server.createContext("/", ex -> respond(ex, "ok"));
        server.start();
        byte[] big = new byte[60 * 1024];
        Arrays.fill(big, (byte) 'v');
        StringBuilder sb = new StringBuilder("GET / HTTP/1.1\r\nHost: b\r\n");
        for (int i = 0; i < 8; i++) { // 8 x ~60KB = ~480KB > 380KB default
            sb.append("X-Big-").append(i).append(':').append(new String(big)).append("\r\n");
        }
        sb.append("Connection: close\r\n\r\n");
        byte[] r = rawExchange(sb.toString().getBytes());
        Assert.assertTrue(r.length == 0 || statusLine(r).startsWith("HTTP/1.1 400"),
                "expected rejection, got: " + statusLine(r));
    }

    @Test
    public void atomicResponseHeadersOnRetry() throws Exception {
        server.createContext("/badlist", ex -> {
            ex.getResponseHeaders().set("X-Foo", "clean");
            // bypasses Headers.set validation via the mutable value list
            // (the "external modification" the JDK Headers comment names)
            ex.getResponseHeaders().get("X-Foo").add("v\r\ninjected");
            try {
                ex.sendResponseHeaders(200, 3);
            } catch (Exception first) {
                ex.getResponseHeaders().get("X-Foo").remove("v\r\ninjected");
                ex.sendResponseHeaders(200, 3);
            }
            try (var os = ex.getResponseBody()) { os.write("ok!".getBytes()); }
        });
        server.start();
        byte[] r = rawExchange("GET /badlist HTTP/1.1\r\nHost: b\r\nConnection: close\r\n\r\n".getBytes());
        int statusLines = 0;
        for (String line : new String(r).split("\r\n")) {
            if (line.startsWith("HTTP/1.1 200")) statusLines++;
        }
        Assert.assertEquals(statusLines, 1, "retry must not emit a second status line: " + new String(r));
    }

    @Test
    public void stopReturnsEarlyWhenIdle() throws Exception {
        server.createContext("/", ex -> respond(ex, "ok"));
        server.start();
        long t0 = System.nanoTime();
        server.stop(3);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        Assert.assertTrue(ms < 2500, "stop(3) should return early when idle, took " + ms + "ms");
    }

    @Test
    public void sslLegacyConfiguratorApplied() throws Exception {
        SSLContext ctx = new SimpleSSLContext().get();
        HttpsServer https = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            https.setHttpsConfigurator(new HttpsConfigurator(ctx) {
                @Override
                public void configure(HttpsParameters params) {
                    // legacy style: per-item setters, never setSSLParameters
                    params.setCipherSuites(new String[]{"TLS_AES_256_GCM_SHA384"});
                }
            });
            https.createContext("/", ex -> respond(ex, "ok"));
            https.start();
            int port = https.getAddress().getPort();

            // a client restricted to a cipher the legacy config did NOT
            // enable must fail: the config was applied, not ignored
            Assert.assertThrows(IOException.class, () -> handshake(ctx, port, "TLS_AES_128_GCM_SHA256"));
            // a client offering the legacy-configured cipher succeeds
            handshake(ctx, port, "TLS_AES_256_GCM_SHA384");
        } finally {
            https.stop(0);
        }
    }

    private static void handshake(SSLContext ctx, int port, String cipher) throws IOException {
        try (SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket("127.0.0.1", port)) {
            s.setEnabledCipherSuites(new String[]{cipher});
            s.setSoTimeout(5000);
            s.startHandshake();
        }
    }

    static void respond(HttpExchange ex, String body) throws IOException {
        ex.sendResponseHeaders(200, body.getBytes().length);
        try (var os = ex.getResponseBody()) { os.write(body.getBytes()); }
    }
}
