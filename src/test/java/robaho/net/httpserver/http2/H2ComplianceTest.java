package robaho.net.httpserver.http2;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * h2c protocol compliance checks that were originally verified by the lost
 * /tmp/hs_smoke suite (todo.md P1-25): trailers on open streams, late frames
 * on closed streams, and RST_STREAM handling - all must stay stream-scoped
 * and never escalate to a connection-level GOAWAY.
 */
public class H2ComplianceTest {
    private HttpServer server;

    @BeforeMethod
    void setUp() throws IOException {
        if (!"true".equals(System.getProperty("robaho.net.httpserver.http2OverNonSSL"))) {
            throw new SkipException("robaho.net.httpserver.http2OverNonSSL is not enabled");
        }
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/echo", H2ComplianceTest::echo);
        server.start();
    }

    @AfterMethod
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** POST with a trailer: the request must complete normally and the
     *  connection must stay usable for further streams */
    @Test
    public void testTrailerThenResponse() throws Exception {
        try (H2Client client = new H2Client(server.getAddress().getPort())) {
            int stream = client.sendRequest("POST", "/echo", false);
            client.sendData(stream, "hello".getBytes(StandardCharsets.UTF_8), false);
            client.sendTrailer(stream, "x-trailer", "value", true);

            var response = client.awaitResponse(stream);
            Assert.assertFalse(response.goaway, "trailer must not trigger GOAWAY");
            Assert.assertNull(response.rstErrorCode, "stream must not be reset");
            Assert.assertEquals(response.status, 200, "trailer request must be served");
            Assert.assertEquals(new String(response.data, StandardCharsets.UTF_8), "hello",
                "handler must see the body sent before the trailer");

            // the connection survives: a new stream is served
            int stream2 = client.sendRequest("GET", "/echo", true);
            var response2 = client.awaitResponse(stream2);
            Assert.assertFalse(response2.goaway);
            Assert.assertEquals(response2.status, 200);
        }
    }

    /** DATA arriving on an already-closed stream must be ignored (RFC 7540
     *  5.1 closed state), not kill the connection */
    @Test
    public void testLateDataAfterStreamClose() throws Exception {
        try (H2Client client = new H2Client(server.getAddress().getPort())) {
            int stream = client.sendRequest("GET", "/echo", true);
            var response = client.awaitResponse(stream);
            Assert.assertEquals(response.status, 200);

            // late DATA on the finished stream
            client.sendData(stream, "late".getBytes(StandardCharsets.UTF_8), true);

            int stream2 = client.sendRequest("GET", "/echo", true);
            var response2 = client.awaitResponse(stream2);
            Assert.assertFalse(response2.goaway, "late DATA on a closed stream must not trigger GOAWAY");
            Assert.assertEquals(response2.status, 200, "connection must stay usable");
        }
    }

    /** RST_STREAM(CANCEL) on an open stream: only that stream is torn down,
     *  the connection and later streams are unaffected */
    @Test
    public void testRstStreamCancelsSingleStream() throws Exception {
        try (H2Client client = new H2Client(server.getAddress().getPort())) {
            int stream = client.sendRequest("POST", "/echo", false);
            // the handler is now parked reading the body; cancel the stream
            client.sendRstStream(stream, H2Client.CANCEL);

            int stream2 = client.sendRequest("GET", "/echo", true);
            var response2 = client.awaitResponse(stream2);
            Assert.assertFalse(response2.goaway, "RST_STREAM must not escalate to GOAWAY");
            Assert.assertEquals(response2.status, 200, "new streams must be served after a reset");
        }
    }

    /** a plain HTTP/1.1 connection must still be served by the same server
     *  (h2c detection must not break the HTTP/1.1 path) */
    @Test
    public void testHttp1StillServedAlongsideH2() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", server.getAddress().getPort())) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write("GET /echo HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n".getBytes());
            socket.getOutputStream().flush();
            String head = readHead(socket);
            Assert.assertTrue(head.startsWith("HTTP/1.1 200"), head);
        }
    }

    private static void echo(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        exchange.getResponseHeaders().set("X-Echo-Len", String.valueOf(body.length));
        if (body.length == 0) {
            exchange.sendResponseHeaders(200, -1);
        } else {
            exchange.sendResponseHeaders(200, body.length);
            try (var os = exchange.getResponseBody()) {
                os.write(body);
            }
        }
    }

    private static String readHead(Socket socket) throws IOException {
        var in = socket.getInputStream();
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            sb.append((char) c);
            int n = sb.length();
            if (n >= 4 && sb.charAt(n - 4) == '\r' && sb.charAt(n - 3) == '\n'
                && sb.charAt(n - 2) == '\r' && sb.charAt(n - 1) == '\n') {
                break;
            }
        }
        return sb.toString();
    }
}
