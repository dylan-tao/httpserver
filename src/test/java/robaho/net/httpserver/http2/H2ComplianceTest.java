package robaho.net.httpserver.http2;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * h2c protocol compliance checks that were originally verified by the lost
 * /tmp/hs_smoke suite trailers on open streams, late frames
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

    /** regression: a client RST_STREAM arriving while response bytes sit
     *  unflushed in the 16KB stream buffer must not flush them into the reset
     *  stream - the flush's rstReceived IOException escalated into a
     *  whole-connection close, whose isClosed() flag kills every writer
     *  parked on flow control (their responses truncate with no
     *  END_STREAM) */
    @Test
    public void testRstWithBufferedResponseKeepsConnection() throws Exception {
        var written = new java.util.concurrent.CountDownLatch(1);
        var seq = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/buffered", ex -> {
            try {
                if (seq.incrementAndGet() == 1) {
                    // stream 1: buffer 100 unflushed bytes, then block - the
                    // stream close interrupts this sleep
                    byte[] small = new byte[100]; // < 16KB: stays in the stream buffer
                    java.util.Arrays.fill(small, (byte) 'x');
                    ex.sendResponseHeaders(200, small.length);
                    try (var os = ex.getResponseBody()) {
                        os.write(small);
                        written.countDown();
                        Thread.sleep(60_000);
                    }
                } else {
                    // stream 3: a response far larger than the flow-control
                    // windows - its writer parks mid-response and must be
                    // able to finish once the client returns credit
                    byte[] big = new byte[200 * 1024];
                    java.util.Arrays.fill(big, (byte) 'y');
                    ex.sendResponseHeaders(200, big.length);
                    try (var os = ex.getResponseBody()) {
                        os.write(big);
                    }
                }
            } catch (InterruptedException e) {
                // interrupted by the stream close: expected
            } catch (Exception e) {
                // write-after-reset etc: expected
            }
        });
        try (H2Client client = new H2Client(server.getAddress().getPort())) {
            int stream1 = client.sendRequest("GET", "/buffered", true);
            Assert.assertTrue(written.await(5, java.util.concurrent.TimeUnit.SECONDS),
                    "handler must buffer its bytes before the RST");
            // stream 3's writer parks once the 64KB windows are exhausted;
            // nothing reads the socket yet, so no credit returns
            int stream3 = client.sendRequest("GET", "/buffered", true);
            Thread.sleep(300);
            client.sendRstStream(stream1, H2Client.CANCEL);
            // reading returns flow-control credit: the parked writer must
            // still be alive and complete the response
            var response3 = client.awaitResponse(stream3);
            Assert.assertFalse(response3.goaway(), "RST on a buffered stream must not kill the connection");
            Assert.assertEquals(response3.status(), 200,
                    "the concurrent writer parked on flow control must survive the other stream's reset");
            Assert.assertEquals(response3.data().length, 200 * 1024, "the response must complete");
        }
    }

    /** regression: a header block arriving for an already-closed stream must
     *  still be HPACK-decoded (contents discarded): the dynamic table is
     *  connection-level state, and an undecoded incrementally-indexed field
     *  desyncs every later request on the connection */
    @Test
    public void testHeaderBlockOnClosedStreamKeepsHpackSync() throws Exception {
        // registered at "/": the indexed request below carries the static
        // pseudo-header ":path /" (index 4)
        server.createContext("/", ex -> {
            String v = ex.getRequestHeaders().getFirst("X-Dyn");
            byte[] b = ("v=" + (v == null ? "null" : v)).getBytes();
            ex.sendResponseHeaders(200, b.length);
            try (var os = ex.getResponseBody()) { os.write(b); }
        });
        try (H2Client client = new H2Client(server.getAddress().getPort())) {
            int s1 = client.sendRequest("GET", "/hdr", true);
            var r1 = client.awaitResponse(s1);
            Assert.assertEquals(r1.status(), 200, "first request must succeed");

            // a stale HEADERS block on the (now closed) stream carrying an
            // INCREMENTALLY-INDEXED field: the client adds it to its dynamic
            // table (first dynamic entry, index 62); the server must decode
            // and discard the block to stay in sync
            java.io.ByteArrayOutputStream stale = new java.io.ByteArrayOutputStream();
            stale.write(0x40); // literal header field, incremental indexing
            stale.write(5); stale.writeBytes("x-dyn".getBytes());
            stale.write(6); stale.writeBytes("secret".getBytes());
            client.writeRawFrame(H2Client.TYPE_HEADERS, H2Client.FLAG_END_HEADERS, s1, stale.toByteArray());

            // a new request referencing the dynamic-table entry by index
            // (0xBE = indexed field, index 62) alongside static-indexed
            // pseudo-headers: :method GET(2) :scheme http(6) :path /(4),
            // then :authority(1) as a literal-with-indexed-name
            client.writeRawFrame(H2Client.TYPE_HEADERS,
                    H2Client.FLAG_END_HEADERS | H2Client.FLAG_END_STREAM,
                    s1 + 2, new byte[]{(byte) 0x82, (byte) 0x86, (byte) 0x84,
                            (byte) 0x01, (byte) 0x01, (byte) 'x', (byte) 0xBE});
            var r2 = client.awaitResponse(s1 + 2);
            Assert.assertFalse(r2.goaway(), "the stale block must not desync the connection");
            Assert.assertEquals(r2.status(), 200, "the indexed request must decode");
            Assert.assertEquals(new String(r2.data()), "v=secret",
                    "the dynamic-table entry established by the stale block must resolve");
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
            } catch (IOException e) {
                // only a CLOSED connection is an acceptable rejection: a
                // wall-clock timeout means the server WEDGED its reader on
                // the violation (the exact bug class this test guards) and
                // must fail, not pass
                if (!e.getMessage().contains("connection closed")) {
                    throw e;
                }
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

    /** full-size DATA frames exercise the frame body pool
     *  (DataFrameBodyPool): bodies of exactly the advertised max frame size
     *  are borrowed on receipt and returned once fully consumed. two
     *  sequential uploads on one connection force a released buffer to be
     *  re-borrowed - any pool bug (a returned-then-reused array still
     *  referenced somewhere) surfaces as an echo mismatch, and the
     *  position-dependent byte pattern means a stale buffer cannot match */
    @Test
    public void testFullSizeDataFrameBodiesRoundTrip() throws Exception {
        int frame = 16384; // the server's advertised max frame size (default)
        try (H2Client client = new H2Client(server.getAddress().getPort())) {
            for (int round = 0; round < 2; round++) {
                int total = 3 * frame + 100; // 3 full frames + a short tail
                byte[] body = new byte[total];
                for (int i = 0; i < total; i++) {
                    body[i] = (byte) (i * 7 + round * 3);
                }
                int stream = client.sendRequest("POST", "/echo", false);
                int sent = 0;
                while (sent < total) {
                    int n = Math.min(frame, total - sent);
                    boolean end = sent + n == total;
                    client.sendData(stream, java.util.Arrays.copyOfRange(body, sent, sent + n), end);
                    sent += n;
                }
                var response = client.awaitResponse(stream);
                Assert.assertFalse(response.goaway(), "upload must not trigger GOAWAY");
                Assert.assertNull(response.rstErrorCode(), "upload must not be reset");
                Assert.assertEquals(response.status(), 200, "upload must complete");
                Assert.assertEquals(response.data(), body,
                    "echoed body must match byte-for-byte (round " + round + ")");
            }
        }
    }

    /** SETTINGS_MAX_FRAME_SIZE outside the RFC 7540 6.5.2 range
     *  (16384..16777215) must be a connection error: a 0 value would
     *  otherwise compute _len=0 for every DATA write and wedge the writer */
    @Test
    public void testInvalidMaxFrameSizeSetting() throws Exception {
        try (H2Client client = new H2Client(server.getAddress().getPort())) {
            byte[] setting = new byte[6];
            setting[1] = 0x05; // SETTINGS_MAX_FRAME_SIZE, value 0 (out of range)
            client.writeRawFrame(H2Client.TYPE_SETTINGS, 0, 0, setting);
            int stream = client.sendRequest("GET", "/echo", true);
            var response = client.awaitResponse(stream);
            Assert.assertTrue(response.goaway(), "out-of-range SETTINGS_MAX_FRAME_SIZE must be a connection error");
        }
    }

    /** after a client GOAWAY(NO_ERROR), in-flight streams finish and any NEW
     *  stream is refused with REFUSED_STREAM while the connection stays up
     *  (RFC 7540 6.8 graceful shutdown) */
    @Test
    public void testClientGoawayRefusesNewStreams() throws Exception {
        try (H2Client client = new H2Client(server.getAddress().getPort())) {
            int stream = client.sendRequest("GET", "/echo", true);
            var r = client.awaitResponse(stream);
            Assert.assertFalse(r.goaway());
            Assert.assertEquals(r.status(), 200, "baseline request must be served");

            // GOAWAY(NO_ERROR) with last-stream-id = the completed stream
            byte[] goaway = new byte[8];
            goaway[3] = (byte) stream;
            client.writeRawFrame(H2Client.TYPE_GOAWAY, 0, 0, goaway);

            int stream2 = client.sendRequest("GET", "/echo", true);
            var r2 = client.awaitResponse(stream2);
            Assert.assertFalse(r2.goaway(), "GOAWAY must not kill the connection");
            Assert.assertNotNull(r2.rstErrorCode(), "new stream after GOAWAY must be refused");
            Assert.assertEquals(r2.rstErrorCode().intValue(), H2Client.REFUSED_STREAM,
                "refusal code must be REFUSED_STREAM");
        }
    }

    /** an h2 upload whose content-length header lies about the body size:
     *  the END_STREAM mismatch must reset the stream with PROTOCOL_ERROR
     *  (stream-scoped), never wedge the connection */
    @Test
    public void testContentLengthMismatchResetsStream() throws Exception {        try (H2Client client = new H2Client(server.getAddress().getPort())) {
            // content-length: 100 but only 50 bytes arrive. END_STREAM rides on
            // the DATA frame (data after an END_STREAM HEADERS would correctly
            // get STREAM_CLOSED instead)
            byte[] block = H2Client.encodeHeaders(
                ":method", "POST",
                ":scheme", "http",
                ":path", "/echo",
                ":authority", "127.0.0.1",
                "content-length", "100");
            int stream = 1;
            client.writeRawFrame(H2Client.TYPE_HEADERS, H2Client.FLAG_END_HEADERS, stream, block);
            client.sendData(stream, new byte[50], true);

            var r = client.awaitResponse(stream);
            Assert.assertFalse(r.goaway(), "mismatch must not GOAWAY");
            Assert.assertNotNull(r.rstErrorCode(), "mismatch must reset the stream");
            Assert.assertEquals(r.rstErrorCode().intValue(), 1,
                "reset code must be PROTOCOL_ERROR, got " + r.rstErrorCode());

            // the connection survives
            int stream2 = client.sendRequest(3, "GET", "/echo", true);
            var r2 = client.awaitResponse(stream2);
            Assert.assertFalse(r2.goaway());
            Assert.assertEquals(r2.status(), 200);
        }
    }

    /** regression: the content-length mismatch check must also fire when
     *  END_STREAM arrives on a trailer HEADERS block (RFC 7540 8.1.2.6),
     *  not only on the DATA frame - a short body completed by a trailer was
     *  silently treated as the complete request */
    @Test
    public void testTrailerContentLengthMismatchResetsStream() throws Exception {
        try (H2Client client = new H2Client(server.getAddress().getPort())) {
            byte[] block = H2Client.encodeHeaders(
                ":method", "POST",
                ":scheme", "http",
                ":path", "/echo",
                ":authority", "127.0.0.1",
                "content-length", "100");
            int stream = 1;
            client.writeRawFrame(H2Client.TYPE_HEADERS, H2Client.FLAG_END_HEADERS, stream, block);
            client.sendData(stream, new byte[50], false);
            client.sendTrailer(stream, "x-trailer", "v", true);

            var r = client.awaitResponse(stream);
            Assert.assertFalse(r.goaway(), "trailer mismatch must not GOAWAY");
            Assert.assertNotNull(r.rstErrorCode(), "trailer mismatch must reset the stream");
            Assert.assertEquals(r.rstErrorCode().intValue(), 1,
                "reset code must be PROTOCOL_ERROR, got " + r.rstErrorCode());
        }
    }

    /** regression: an unknown/unsupported frame type carrying a stream id
     *  must be ignored (RFC 7540 4.1) - it used to fall into the stream
     *  dispatch, fail header validation on an empty block, and kill the
     *  whole connection */
    @Test
    public void testUnknownFrameTypeIgnored() throws Exception {
        try (H2Client client = new H2Client(server.getAddress().getPort())) {
            int s1 = client.sendRequest("GET", "/echo", true);
            var r1 = client.awaitResponse(s1);
            Assert.assertEquals(r1.status(), 200);

            client.writeRawFrame(0x42, 0, s1 + 2, new byte[8]);
            int s2 = client.sendRequest("GET", "/echo", true);
            var r2 = client.awaitResponse(s2);
            Assert.assertFalse(r2.goaway(), "unknown frame type must not kill the connection");
            Assert.assertEquals(r2.status(), 200, "the connection must stay usable");
        }
    }

    /** a multi-frame HEADERS+CONTINUATION block re-using the id of the most
     *  recently closed stream must be ignored WITHOUT leaving the header
     *  block state machine wedged: the completing CONTINUATION breaks out of
     *  the switch with inHeaders still set, and the closed-stream ignore
     *  path must reset it or the next stream-level frame GOAWAYs every
     *  concurrent stream */
    @Test
    public void testReusedStreamIdMultiFrameBlockDoesNotWedge() throws Exception {
        try (H2Client client = new H2Client(server.getAddress().getPort())) {
            int stream = client.sendRequest("GET", "/echo", true);
            var r = client.awaitResponse(stream);
            Assert.assertFalse(r.goaway());
            Assert.assertEquals(r.status(), 200);

            // re-use the closed stream's id with a multi-frame block
            byte[] block = H2Client.encodeHeaders(":method", "GET", ":scheme", "http",
                ":path", "/echo", ":authority", "127.0.0.1");
            client.writeRawFrame(H2Client.TYPE_HEADERS, 0, stream,
                java.util.Arrays.copyOfRange(block, 0, 4));
            client.writeRawFrame(H2Client.TYPE_CONTINUATION, H2Client.FLAG_END_HEADERS, stream,
                java.util.Arrays.copyOfRange(block, 4, block.length));

            // the connection must NOT be wedged: a fresh stream is served
            int stream2 = client.sendRequest("GET", "/echo", true);
            var r2 = client.awaitResponse(stream2);
            Assert.assertFalse(r2.goaway(), "reused-id multi-frame block must not wedge the connection");
            Assert.assertEquals(r2.status(), 200);
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
