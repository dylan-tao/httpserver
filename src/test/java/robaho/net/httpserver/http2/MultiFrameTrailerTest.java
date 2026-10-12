package robaho.net.httpserver.http2;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A trailer header block larger than one frame (HEADERS without END_HEADERS
 * followed by CONTINUATION frames) on an open stream must be treated as a
 * multi-frame trailer (RFC 7540 8.1.2.3), not as a new request - the original
 * bug fed the CONTINUATION into request processing and killed the whole
 * connection with GOAWAY (todo.md 复查-2).
 */
public class MultiFrameTrailerTest {
    private HttpServer server;

    @BeforeMethod
    void setUp() throws IOException {
        if (!"true".equals(System.getProperty("robaho.net.httpserver.http2OverNonSSL"))) {
            throw new SkipException("robaho.net.httpserver.http2OverNonSSL is not enabled");
        }
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/echo", MultiFrameTrailerTest::echo);
        server.createContext("/quickclose", MultiFrameTrailerTest::quickClose);
        server.start();
    }

    @AfterMethod
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    public void testMultiFrameTrailer() throws Exception {
        try (H2Client client = new H2Client(server.getAddress().getPort())) {
            int stream = client.sendRequest("POST", "/echo", false);
            client.sendData(stream, "hello".getBytes(StandardCharsets.UTF_8), false);

            // a ~20KB trailer block, split as HEADERS + 2x CONTINUATION
            byte[] block = H2Client.encodeHeaders("x-trailer", "v".repeat(20_000));
            byte[] first = java.util.Arrays.copyOfRange(block, 0, 7_000);
            byte[] second = java.util.Arrays.copyOfRange(block, 7_000, 14_000);
            byte[] third = java.util.Arrays.copyOfRange(block, 14_000, block.length);
            // END_STREAM rides on the HEADERS frame; END_HEADERS on the last CONTINUATION
            client.writeRawFrame(H2Client.TYPE_HEADERS, H2Client.FLAG_END_STREAM, stream, first);
            client.writeRawFrame(H2Client.TYPE_CONTINUATION, 0, stream, second);
            client.writeRawFrame(H2Client.TYPE_CONTINUATION, H2Client.FLAG_END_HEADERS, stream, third);

            var response = client.awaitResponse(stream);
            Assert.assertFalse(response.goaway(), "multi-frame trailer must not trigger GOAWAY");
            Assert.assertNull(response.rstErrorCode(), "stream must not be reset");
            Assert.assertEquals(response.status(), 200, "request must complete normally");
            Assert.assertEquals(new String(response.data(), StandardCharsets.UTF_8), "hello");

            // the connection survives: a new stream is served
            int stream2 = client.sendRequest("GET", "/echo", true);
            var response2 = client.awaitResponse(stream2);
            Assert.assertFalse(response2.goaway());
            Assert.assertEquals(response2.status(), 200);
        }
    }

    /**
     * A multi frame trailer block completing AFTER its stream was closed and
     * unregistered by the handler must not wedge the connection state: the
     * original code broke out of the CONTINUATION case without resetting
     * inHeaders, so the next request's HEADERS frame hit "HEADERS frame
     * received on open stream" and killed the whole connection with GOAWAY.
     */
    @Test
    public void testTrailerCompletingAfterStreamClose() throws Exception {
        try (H2Client client = new H2Client(server.getAddress().getPort())) {
            int stream = client.sendRequest("POST", "/quickclose", false);
            client.sendData(stream, "hello".getBytes(StandardCharsets.UTF_8), false);

            // open the trailer block while the stream is still open (the handler
            // sleeps 50ms before closing)...
            byte[] block = H2Client.encodeHeaders("x-trailer", "v".repeat(4_000));
            byte[] first = java.util.Arrays.copyOfRange(block, 0, 2_000);
            byte[] second = java.util.Arrays.copyOfRange(block, 2_000, block.length);
            client.writeRawFrame(H2Client.TYPE_HEADERS, H2Client.FLAG_END_STREAM, stream, first);
            // ...let the handler close and unregister the stream mid-block...
            Thread.sleep(150);
            // ...then complete the block on the unregistered stream
            client.writeRawFrame(H2Client.TYPE_CONTINUATION, H2Client.FLAG_END_HEADERS, stream, second);

            var response = client.awaitResponse(stream);
            Assert.assertFalse(response.goaway(), "trailer completing after stream close must not GOAWAY");
            Assert.assertEquals(response.status(), 200, "request must complete normally");

            // the connection survives: a new stream is served (the wedged
            // inHeaders flag used to make this request kill the connection)
            int stream2 = client.sendRequest("GET", "/echo", true);
            var response2 = client.awaitResponse(stream2);
            Assert.assertFalse(response2.goaway());
            Assert.assertEquals(response2.status(), 200);
        }
    }

    private static void echo(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        if (body.length == 0) {
            exchange.sendResponseHeaders(200, -1);
        } else {
            exchange.sendResponseHeaders(200, body.length);
            try (var os = exchange.getResponseBody()) {
                os.write(body);
            }
        }
    }

    /** responds and closes WITHOUT reading the request body, so the stream is
     * unregistered while the client's trailer block is still in flight */
    private static void quickClose(HttpExchange exchange) throws IOException {
        try {
            Thread.sleep(50);
        } catch (InterruptedException ignored) {
        }
        exchange.sendResponseHeaders(200, -1);
        exchange.close();
    }
}
