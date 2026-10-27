package robaho.net.httpserver.http2;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import jdk.test.lib.net.SimpleSSLContext;
import org.testng.annotations.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/**
 * End-to-end coverage of the ALPN path: the JDK HttpClient offers "h2" in the
 * TLS handshake, the server-side selector (ServerImpl) picks it because
 * {@code robaho.net.httpserver.http2OverSSL=true} is set for the test JVM
 * (surefire systemPropertyVariables), and a full HTTP/2 exchange completes
 * over TLS. this is the only h2 transport test that can run against the JDK
 * client: prior-knowledge h2c is covered by the hand-rolled H2Client test
 * classes instead (the JDK client cannot do prior knowledge over plain
 * sockets).
 */
public class Http2AlpnTest {

    /** no custom SSLParameters: the JDK HttpClient reads the user-supplied
     * parameters' TLS protocols to decide whether to offer h2 at all
     * (HttpConnection.hasRequiredHTTP2TLSVersion), and a bare SSLParameters
     * has null protocols - the client would then offer no ALPN and the
     * exchange silently downgrades to http/1.1. the client defaults (with
     * just the SSLContext set) offer "h2,http/1.1" and pass hostname
     * verification against the testkeys certificate for loopback hosts */
    private static HttpClient newClient(SSLContext ctx, HttpClient.Version version) {
        return HttpClient.newBuilder()
            .version(version)
            .sslContext(ctx)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    private static void handle(HttpExchange ex, AtomicInteger h2Count, AtomicInteger h1Count) throws IOException {
        String protocol = ex.getProtocol();
        if ("HTTP/2".equals(protocol)) {
            h2Count.incrementAndGet();
        } else {
            h1Count.incrementAndGet();
        }
        byte[] body = ("served " + protocol).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ex.sendResponseHeaders(200, body.length);
        try (var os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    @Test
    public void testAlpnNegotiatesH2OverTls() throws Exception {
        SSLContext ctx = new SimpleSSLContext().get();
        HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(ctx));
        AtomicInteger h2Count = new AtomicInteger();
        server.createContext("/alpn", ex -> handle(ex, h2Count, new AtomicInteger()));
        server.start();
        int port = server.getAddress().getPort();
        try (HttpClient client = newClient(ctx, HttpClient.Version.HTTP_2)) {
            HttpResponse<String> resp = client.send(
                HttpRequest.newBuilder(new URI("https://127.0.0.1:" + port + "/alpn")).build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(resp.statusCode(), 200);
            // the client only reports HTTP_2 when ALPN actually negotiated it
            assertEquals(resp.version(), HttpClient.Version.HTTP_2);
            assertEquals(resp.body(), "served HTTP/2");
            // and the server agrees it served an h2 exchange
            assertEquals(h2Count.get(), 1);
            // h1/h2 parity: h2 responses carry a Date header (RFC 9110 6.5.1)
            assertTrue(resp.headers().firstValue("Date").isPresent(), "h2 response is missing the Date header");
        } finally {
            server.stop(0);
        }
    }

    /** a client that only offers http/1.1 must be served http/1.1: the ALPN
     * selector must not force h2 on a peer that did not offer it */
    @Test
    public void testAlpnFallsBackToHttp1OverTls() throws Exception {
        SSLContext ctx = new SimpleSSLContext().get();
        HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(ctx));
        AtomicInteger h1Count = new AtomicInteger();
        server.createContext("/alpn", ex -> handle(ex, new AtomicInteger(), h1Count));
        server.start();
        int port = server.getAddress().getPort();
        try (HttpClient client = newClient(ctx, HttpClient.Version.HTTP_1_1)) {
            HttpResponse<String> resp = client.send(
                HttpRequest.newBuilder(new URI("https://127.0.0.1:" + port + "/alpn")).build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(resp.statusCode(), 200);
            assertEquals(resp.version(), HttpClient.Version.HTTP_1_1);
            assertEquals(resp.body(), "served HTTP/1.1");
            assertEquals(h1Count.get(), 1);
        } finally {
            server.stop(0);
        }
    }

    /** concurrent requests on one ALPN-negotiated connection multiplex as
     * h2 streams: the TLS path must not silently fall back to one-at-a-time */
    @Test
    public void testAlpnH2Multiplexing() throws Exception {
        SSLContext ctx = new SimpleSSLContext().get();
        HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(ctx));
        AtomicInteger h2Count = new AtomicInteger();
        server.createContext("/alpn", ex -> handle(ex, h2Count, new AtomicInteger()));
        server.start();
        int port = server.getAddress().getPort();
        try (HttpClient client = newClient(ctx, HttpClient.Version.HTTP_2)) {
            List<CompletableFuture<HttpResponse<String>>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                futures.add(client.sendAsync(
                    HttpRequest.newBuilder(new URI("https://127.0.0.1:" + port + "/alpn")).build(),
                    HttpResponse.BodyHandlers.ofString()));
            }
            for (var f : futures) {
                HttpResponse<String> resp = f.get(10, java.util.concurrent.TimeUnit.SECONDS);
                assertEquals(resp.statusCode(), 200);
                assertEquals(resp.version(), HttpClient.Version.HTTP_2);
                assertEquals(resp.body(), "served HTTP/2");
            }
            assertEquals(h2Count.get(), 8);
        } finally {
            server.stop(0);
        }
    }
}
