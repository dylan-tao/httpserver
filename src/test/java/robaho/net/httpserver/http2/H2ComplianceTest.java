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
        // consumes the request body and answers without a response body, so
        // upload probes never depend on the (window-limited) response path
        server.createContext("/sink", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("X-Len", String.valueOf(body.length));
            exchange.sendResponseHeaders(200, -1);
        });
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
            Assert.assertFalse(response.goaway(), "trailer must not trigger GOAWAY");
            Assert.assertNull(response.rstErrorCode(), "stream must not be reset");
            Assert.assertEquals(response.status(), 200, "trailer request must be served");
            Assert.assertEquals(new String(response.data(), StandardCharsets.UTF_8), "hello",
                "handler must see the body sent before the trailer");

            // the connection survives: a new stream is served
            int stream2 = client.sendRequest("GET", "/echo", true);
            var response2 = client.awaitResponse(stream2);
            Assert.assertFalse(response2.goaway());
            Assert.assertEquals(response2.status(), 200);
        }
    }

    /** DATA arriving on an already-closed stream must be ignored (RFC 7540
     *  5.1 closed state), not kill the connection */
    @Test
    public void testLateDataAfterStreamClose() throws Exception {
        try (H2Client client = new H2Client(server.getAddress().getPort())) {
            int stream = client.sendRequest("GET", "/echo", true);
            var response = client.awaitResponse(stream);
            Assert.assertEquals(response.status(), 200);

            // late DATA on the finished stream
            client.sendData(stream, "late".getBytes(StandardCharsets.UTF_8), true);

            int stream2 = client.sendRequest("GET", "/echo", true);
            var response2 = client.awaitResponse(stream2);
            Assert.assertFalse(response2.goaway(), "late DATA on a closed stream must not trigger GOAWAY");
            Assert.assertEquals(response2.status(), 200, "connection must stay usable");
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
            Assert.assertFalse(response2.goaway(), "RST_STREAM must not escalate to GOAWAY");
            Assert.assertEquals(response2.status(), 200, "new streams must be served after a reset");
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

    /** DATA frames with the PADDED flag must deliver only the unpadded body
     *  (RFC 7540 6.1: the pad length octet is framing, not data) */
    @Test
    public void testPaddedDataEcho() throws Exception {
        try (H2Client client = new H2Client(server.getAddress().getPort())) {
            int stream = client.sendRequest("POST", "/echo", false);
            // [padLen=3]["hello"][3 pad octets]
            byte[] payload = new byte[9];
            payload[0] = 3;
            System.arraycopy("hello".getBytes(StandardCharsets.UTF_8), 0, payload, 1, 5);
            client.writeRawFrame(H2Client.TYPE_DATA, H2Client.FLAG_END_STREAM | H2Client.FLAG_PADDED, stream, payload);
            var response = client.awaitResponse(stream);
            Assert.assertFalse(response.goaway());
            Assert.assertEquals(response.status(), 200);
            Assert.assertEquals(new String(response.data(), StandardCharsets.UTF_8), "hello",
                "padded DATA must deliver only the unpadded body");
        }
    }

    /** a bodyless request whose header block spans HEADERS + CONTINUATION must
     *  still see END_STREAM (the flag rides on the HEADERS frame and cannot
     *  ride on CONTINUATION): a body-reading handler must get EOF, not park
     *  forever */
    @Test
    public void testLargeHeaderBlockRequest() throws Exception {
        try (H2Client client = new H2Client(server.getAddress().getPort())) {
            byte[] block = H2Client.encodeHeaders(
                ":method", "GET", ":scheme", "http", ":path", "/echo", ":authority", "127.0.0.1",
                "x-big", "v".repeat(20_000));
            client.writeRawFrame(H2Client.TYPE_HEADERS, H2Client.FLAG_END_STREAM, 1, java.util.Arrays.copyOfRange(block, 0, 7_000));
            client.writeRawFrame(H2Client.TYPE_CONTINUATION, 0, 1, java.util.Arrays.copyOfRange(block, 7_000, 14_000));
            client.writeRawFrame(H2Client.TYPE_CONTINUATION, H2Client.FLAG_END_HEADERS, 1, java.util.Arrays.copyOfRange(block, 14_000, block.length));
            var response = client.awaitResponse(1);
            Assert.assertFalse(response.goaway());
            Assert.assertNull(response.rstErrorCode());
            Assert.assertEquals(response.status(), 200, "request must complete after a multi-frame header block");
        }
    }

    /** single-stream upload throughput: stream-level WINDOW_UPDATE frames must
     *  be flushed, otherwise an uploading client blocked on its stream window
     *  only advances one window per idle ping (~64KB/s) */
    @Test
    public void testSingleStreamUploadThroughput() throws Exception {
        try (H2Client client = new H2Client(server.getAddress().getPort())) {
            int stream = client.sendRequest("POST", "/sink", false);
            long start = System.nanoTime();
            int total = 256 * 1024;
            int sent = 0;
            long streamWindow = 65535, connWindow = 65535;
            byte[] chunk = new byte[16384];
            while (sent < total) {
                if (streamWindow <= 0 || connWindow <= 0) {
                    int[] update = client.readWindowUpdate();
                    if (update[0] == stream) { streamWindow += update[1]; }
                    else if (update[0] == 0) { connWindow += update[1]; }
                    continue;
                }
                int n = (int) Math.min(Math.min(chunk.length, total - sent), Math.min(streamWindow, connWindow));
                boolean end = sent + n == total;
                client.writeRawFrame(H2Client.TYPE_DATA, end ? H2Client.FLAG_END_STREAM : 0, stream, java.util.Arrays.copyOf(chunk, n));
                sent += n;
                streamWindow -= n;
                connWindow -= n;
            }
            var response = client.awaitResponse(stream);
            Assert.assertEquals(response.status(), 200);
            long ms = (System.nanoTime() - start) / 1_000_000;
            // healthy is sub-second on loopback; the unfixed server throttled
            // to ~64KB/s (256KB would take ~4s). 1.5s leaves CI margin.
            Assert.assertTrue(ms < 1500, "256KB single-stream upload took " + ms + "ms (stream WINDOW_UPDATE not flushed?)");
        }
    }

    /** padded uploads must not leak the stream window: the padding octets are
     *  discarded on receipt, so their window credit must be returned - without
     *  it the client's window shrinks by the padding of every frame until the
     *  upload deadlocks */
    @Test
    public void testPaddedUploadThroughput() throws Exception {
        try (H2Client client = new H2Client(server.getAddress().getPort())) {
            int stream = client.sendRequest("POST", "/sink", false);
            long start = System.nanoTime();
            int total = 160 * 1024;
            int sent = 0;
            int pad = 256;
            int overhead = 1 + pad; // pad length octet + padding
            long streamWindow = 65535, connWindow = 65535;
            byte[] chunk = new byte[16000];
            while (sent < total) {
                // the window debit is the FULL frame length: the data size must
                // leave room for the padding overhead or the frame overdraws
                if (streamWindow <= overhead || connWindow <= overhead) {
                    int[] update = client.readWindowUpdate();
                    if (update[0] == stream) { streamWindow += update[1]; }
                    else if (update[0] == 0) { connWindow += update[1]; }
                    continue;
                }
                int n = (int) Math.min(Math.min(chunk.length, total - sent),
                    Math.min(streamWindow - overhead, connWindow - overhead));
                boolean end = sent + n == total;
                // [Pad Length][data][pad octets], total frame length <= 16384
                byte[] frame = new byte[overhead + n];
                frame[0] = (byte) pad;
                System.arraycopy(chunk, 0, frame, 1, n);
                client.writeRawFrame(H2Client.TYPE_DATA,
                    (end ? H2Client.FLAG_END_STREAM : 0) | H2Client.FLAG_PADDED, stream, frame);
                sent += n;
                streamWindow -= frame.length;
                connWindow -= frame.length;
            }
            var response = client.awaitResponse(stream);
            Assert.assertEquals(response.status(), 200);
            long ms = (System.nanoTime() - start) / 1_000_000;
            Assert.assertTrue(ms < 1500, "160KB padded upload took " + ms + "ms (padding window credit lost?)");
        }
    }

    /** only CONTINUATION may follow an incomplete header block (RFC 7540 6.2):
     *  a stream-level WINDOW_UPDATE falling into the stream dispatch would
     *  create a request from the PARTIAL header block */
    @Test
    public void testWindowUpdateDuringHeaderBlockRejected() throws Exception {
        try (H2Client client = new H2Client(server.getAddress().getPort())) {
            // HEADERS without END_HEADERS: header block now incomplete
            client.writeRawFrame(H2Client.TYPE_HEADERS, 0, 1, H2Client.encodeHeaders(
                ":method", "GET", ":scheme", "http", ":path", "/echo", ":authority", "127.0.0.1"));
            // a stream-level WINDOW_UPDATE mid-block must be a connection error
            client.writeRawFrame(H2Client.TYPE_WINDOW_UPDATE, 0, 1, new byte[] { 0, 0, 0x0F, 0x00 });
            try {
                var response = client.awaitResponse(1);
                Assert.assertTrue(response.goaway(),
                    "WINDOW_UPDATE during an incomplete header block must terminate the connection");
            } catch (IOException expected) {
                // connection closed without a GOAWAY is also an acceptable
                // rejection; serving the partial request is not
            }
        }
    }

    /** trailer header blocks must run through the HPACK decoder: a trailer
     *  field sent with incremental indexing adds a dynamic table entry on the
     *  client side, and a discarded block desyncs the next request on the
     *  connection (COMPRESSION_ERROR or wrong headers) */
    @Test
    public void testTrailerHpackStateSync() throws Exception {
        try (H2Client client = new H2Client(server.getAddress().getPort())) {
            int stream = client.sendRequest("POST", "/echo", false);
            client.sendData(stream, "hello".getBytes(StandardCharsets.UTF_8), false);

            // trailer field encoded as "literal with incremental indexing, new
            // name" (0x40): the client's dynamic table gains entry 62
            java.io.ByteArrayOutputStream trailer = new java.io.ByteArrayOutputStream();
            trailer.write(0x40);
            trailer.write("x-checksum".length());
            trailer.writeBytes("x-checksum".getBytes(StandardCharsets.ISO_8859_1));
            trailer.write("abc".length());
            trailer.writeBytes("abc".getBytes(StandardCharsets.ISO_8859_1));
            client.writeRawFrame(H2Client.TYPE_HEADERS,
                H2Client.FLAG_END_STREAM | H2Client.FLAG_END_HEADERS, stream, trailer.toByteArray());

            var response = client.awaitResponse(stream);
            Assert.assertFalse(response.goaway());
            Assert.assertEquals(response.status(), 200);

            // the next request references the dynamic table entry 62 (0xBE):
            // it must decode to the trailer field, not fail with COMPRESSION_ERROR.
            // pseudo-headers must come first (a regular header before them is
            // rejected by HeaderFields validation)
            java.io.ByteArrayOutputStream block = new java.io.ByteArrayOutputStream();
            for (String[] kv : new String[][] {
                    {":method", "GET"}, {":scheme", "http"}, {":path", "/echo"}, {":authority", "127.0.0.1"}}) {
                block.write(0x00);
                block.write(kv[0].length());
                block.writeBytes(kv[0].getBytes(StandardCharsets.ISO_8859_1));
                block.write(kv[1].length());
                block.writeBytes(kv[1].getBytes(StandardCharsets.ISO_8859_1));
            }
            block.write(0xBE); // indexed header field, dynamic index 62
            client.writeRawFrame(H2Client.TYPE_HEADERS,
                H2Client.FLAG_END_STREAM | H2Client.FLAG_END_HEADERS, 3, block.toByteArray());

            var response2 = client.awaitResponse(3);
            Assert.assertFalse(response2.goaway(), "dynamic table must stay in sync after a trailer");
            Assert.assertEquals(response2.status(), 200, "request referencing the trailer's dynamic entry must be served");
        }
    }

    /** a client that only sends whole DATA frames must not deadlock when the
     *  connection window remainder falls below its frame size: the server must
     *  replenish the window before then. the old 10% threshold left a dead
     *  zone [threshold, frame size) where the client waited for credit and the
     *  server waited for data, forever */
    @Test
    public void testFlowControlUploadBeyondInitialWindow() throws Exception {
        try (H2Client client = new H2Client(server.getAddress().getPort())) {
            int stream = client.sendRequest("POST", "/sink", false);
            long streamWindow = 65535, connWindow = 65535;
            int total = 256 * 1024;
            byte[] body = new byte[total];
            for (int i = 0; i < total; i++) {
                body[i] = (byte) (i * 31 + 7);
            }
            int sent = 0;
            while (sent < total) {
                int n = Math.min(8192, total - sent);
                if (streamWindow < n || connWindow < n) {
                    int[] wu = client.readWindowUpdate();
                    if (wu[0] == 0) {
                        connWindow += wu[1];
                    } else if (wu[0] == stream) {
                        streamWindow += wu[1];
                    }
                    continue;
                }
                boolean end = sent + n == total;
                client.sendData(stream, java.util.Arrays.copyOfRange(body, sent, sent + n), end);
                streamWindow -= n;
                connWindow -= n;
                sent += n;
            }
            // a flow control deadlock surfaces as awaitResponse timing out
            var response = client.awaitResponse(stream);
            Assert.assertFalse(response.goaway(), "upload must not trigger GOAWAY");
            Assert.assertNull(response.rstErrorCode(), "upload must not be reset");
            Assert.assertEquals(response.status(), 200, "upload must complete");
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
