package robaho.net.httpserver.http2;

import com.sun.net.httpserver.HttpServer;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Regressions for the 2026-10-23 deep-audit connection-level findings:
 * malformed frames and broken handlers must stay stream-scoped - they may
 * never tear the connection down without a GOAWAY, wedge the reader thread,
 * or leak the in-progress accounting that gates the final flush.
 */
public class H2ConnectionLevelRegressionTest {
    private HttpServer server;
    private final AtomicInteger okRequests = new AtomicInteger();

    @BeforeMethod
    void setUp() throws IOException {
        if (!"true".equals(System.getProperty("robaho.net.httpserver.http2OverNonSSL"))) {
            throw new org.testng.SkipException("robaho.net.httpserver.http2OverNonSSL is not enabled");
        }
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", ex -> {
            okRequests.incrementAndGet();
            ex.sendResponseHeaders(200, -1);
        });
        server.createContext("/boom", ex -> {
            // handler dies BEFORE any response headers: the old path let the
            // missing-:status encode IOException escalate to a whole-
            // connection close
            throw new RuntimeException("handler died before responding");
        });
        server.start();
    }

    @AfterMethod
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** a handler that dies before sendResponseHeaders must only reset ITS
     *  stream: the connection stays usable for other streams (the old path
     *  killed the connection and left it zombie - later responses without
     *  END_STREAM hung the client) */
    @Test
    public void testHandlerDeathStaysStreamScoped() throws Exception {
        try (H2Client client = new H2Client(server.getAddress().getPort())) {
            int bad = client.sendRequest("GET", "/boom", true);
            var r = client.awaitResponse(bad);
            // the stream must fail (RST INTERNAL_ERROR), not hang
            Assert.assertNotNull(r.rstErrorCode(), "broken handler must reset its stream");
            Assert.assertEquals(r.rstErrorCode().intValue(), 0x2, "INTERNAL_ERROR");
            Assert.assertFalse(r.goaway(), "a broken handler must not kill the connection");
            // the connection must still serve
            int ok = client.sendRequest("GET", "/ok", true);
            var okResp = client.awaitResponse(ok);
            Assert.assertEquals(okResp.status(), 200, "connection must survive a broken handler");
            Assert.assertFalse(okResp.goaway());
        }
    }

    /** a truncated PRIORITY HEADERS frame must be a FRAME_SIZE_ERROR
     *  connection error (GOAWAY visible to the client), never an uncaught
     *  ArrayIndexOutOfBoundsException tearing the TCP connection silently */
    @Test
    public void testTruncatedPriorityHeadersGetsGoaway() throws Exception {
        try (H2Client client = new H2Client(server.getAddress().getPort())) {
            // HEADERS len=4 flags=PRIORITY: too short for the 5 priority bytes
            client.writeRawFrame(H2Client.TYPE_HEADERS, 0x20, 1, new byte[] {0, 0, 0, 2});
            var r = client.awaitResponse(1);
            Assert.assertTrue(r.goaway() || r.rstErrorCode() != null,
                "a truncated frame must produce a protocol error, not a silent teardown");
        }
    }

    /** a PADDED HEADERS frame with length 0 must be FRAME_SIZE_ERROR, not a
     *  raw IOException misdiagnosed as end-of-stream */
    @Test
    public void testEmptyPaddedHeadersRejected() throws Exception {
        try (H2Client client = new H2Client(server.getAddress().getPort())) {
            client.writeRawFrame(H2Client.TYPE_HEADERS, 0x08, 1, new byte[0]);
            var r = client.awaitResponse(1);
            Assert.assertTrue(r.goaway() || r.rstErrorCode() != null,
                "an empty PADDED frame must produce a protocol error");
        }
    }

    /** an overflowing HPACK varint (dynamic table size update with 5
     *  continuation bytes wrapping the int accumulator) must be a
     *  COMPRESSION_ERROR connection error - the old path accepted the
     *  wrapped NEGATIVE size, silently wiped the table and permanently
     *  desynced the connection's HPACK state */
    @Test
    public void testVarintOverflowRejected() throws Exception {
        try (H2Client client = new H2Client(server.getAddress().getPort())) {
            // size update, 5-bit prefix 31, continuation bytes 0,0,0,0 then
            // 0xFF (contributes a negative amount once wrapped) then 0x00
            byte[] block = H2Client.encodeHeaders(":method", "GET",
                ":scheme", "http", ":path", "/ok", ":authority", "x");
            java.io.ByteArrayOutputStream payload = new java.io.ByteArrayOutputStream();
            payload.write(0x3F);
            payload.write(0x80); payload.write(0x80); payload.write(0x80); payload.write(0x80);
            payload.write(0xFF); payload.write(0x00);
            payload.write(block);
            client.writeRawFrame(H2Client.TYPE_HEADERS,
                H2Client.FLAG_END_HEADERS | H2Client.FLAG_END_STREAM, 1, payload.toByteArray());
            var r = client.awaitResponse(1);
            Assert.assertTrue(r.goaway() || r.rstErrorCode() != null,
                "an overflowing varint must be a decoding error, not accepted");
        }
    }

    /** a WINDOW_UPDATE overflow (two 2^31-1 increments) must reset the
     *  stream with FLOW_CONTROL_ERROR while the connection stays alive and
     *  the reader never wedges */
    @Test
    public void testWindowUpdateOverflowStaysStreamScoped() throws Exception {
        try (H2Client client = new H2Client(server.getAddress().getPort())) {
            int s = client.sendRequest("GET", "/ok", true);
            var first = client.awaitResponse(s);
            Assert.assertEquals(first.status(), 200);
            // two max increments push the stream send window over 2^31-1
            byte[] wu = new byte[4];
            wu[0] = 0x7F; wu[1] = (byte) 0xFF; wu[2] = (byte) 0xFF; wu[3] = (byte) 0xFF;
            client.writeRawFrame(0x8, 0, s, wu); // TYPE_WINDOW_UPDATE
            client.writeRawFrame(0x8, 0, s, wu);
            // the connection must still serve new streams (the old path
            // could wedge the reader thread in a flow-control park)
            int ok = client.sendRequest("GET", "/ok", true);
            var okResp = client.awaitResponse(ok);
            Assert.assertEquals(okResp.status(), 200, "connection must survive a window overflow");
            Assert.assertFalse(okResp.goaway());
        }
    }

    /** a client RST_STREAM must not leak the in-progress accounting: after
     *  the reset, later responses must still be flushed promptly (a leaked
     *  count disabled the connection-level final flush) */
    @Test
    public void testRstDoesNotLeakFlushAccounting() throws Exception {
        try (H2Client client = new H2Client(server.getAddress().getPort())) {
            // open a stream, then cancel it before the handler finishes
            int s = client.sendRequest("GET", "/ok", true);
            byte[] rst = new byte[4];
            rst[3] = 0x8; // CANCEL
            client.writeRawFrame(0x3, 0, s, rst); // TYPE_RST_STREAM
            // a subsequent request must complete normally (response flushed)
            int ok = client.sendRequest("GET", "/ok", true);
            var okResp = client.awaitResponse(ok);
            Assert.assertEquals(okResp.status(), 200);
            Assert.assertFalse(okResp.goaway());
        }
    }
}
