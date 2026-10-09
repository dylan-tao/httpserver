package robaho.net.httpserver.http2;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Regression tests for the 2026-09-21 review batch (todo.md 复查-4 and 复查-6):
 * a trailer on an interleaved (older, still open) stream must not be rejected
 * by the out-of-order HEADERS guard, and a bare-LF HTTP/1.1 request line must
 * be accepted as a line terminator.
 */
public class Review3Test {
    private HttpServer server;

    @BeforeMethod
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/echo", Review3Test::echo);
        server.start();
    }

    @AfterMethod
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** stream 1 POST (no END_STREAM) + interleaved new stream 3 + stream 1
     *  trailer: both streams must complete, no GOAWAY (复查-4) */
    @Test
    public void testInterleavedStreamTrailer() throws Exception {
        if (!"true".equals(System.getProperty("robaho.net.httpserver.http2OverNonSSL"))) {
            throw new SkipException("robaho.net.httpserver.http2OverNonSSL is not enabled");
        }
        try (H2Client client = new H2Client(server.getAddress().getPort())) {
            int stream1 = client.sendRequest("POST", "/echo", false);
            int stream3 = client.sendRequest("GET", "/echo", true);
            // trailer on the OLDER stream while stream 3 is the most recent one:
            // the out-of-order guard must not fire before the trailer check
            client.sendTrailer(stream1, "x-trailer", "value", true);

            var response3 = client.awaitResponse(stream3);
            Assert.assertFalse(response3.goaway(), "interleaved trailer must not trigger GOAWAY");
            Assert.assertEquals(response3.status(), 200, "stream 3 must complete");

            var response1 = client.awaitResponse(stream1);
            Assert.assertFalse(response1.goaway());
            Assert.assertNull(response1.rstErrorCode());
            Assert.assertEquals(response1.status(), 200, "stream 1 must complete after its trailer");
        }
    }

    /** a bare-LF request line (LF-only client) must be accepted (复查-6) */
    @Test
    public void testBareLfRequestLine() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", server.getAddress().getPort())) {
            socket.setSoTimeout(5000);
            // LF-only request: no CR anywhere
            socket.getOutputStream().write("GET /echo HTTP/1.1\nHost: h\n\n".getBytes());
            socket.getOutputStream().flush();
            String head = readHead(socket);
            Assert.assertTrue(head.startsWith("HTTP/1.1 200"),
                "bare-LF request line must be served, got: " + head);
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
