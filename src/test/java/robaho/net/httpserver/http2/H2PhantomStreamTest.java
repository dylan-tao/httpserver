package robaho.net.httpserver.http2;

import com.sun.net.httpserver.HttpServer;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * regression guards for the phantom-stream dispatch (audit E1, 2026-11-20):
 * a stream-level WINDOW_UPDATE or DATA frame carrying a stream id the
 * connection never opened falls into the common new-stream path, which
 * unconditionally HPACK-decodes the (empty) header-block assembly and fails
 * the ":path" validation with a connection-killing GOAWAY.
 * <p>
 * lock-in cases (green before AND after the fix): a WU/DATA on an IDLE
 * stream id is a connection error of type PROTOCOL_ERROR (RFC 7540 5.1) -
 * the outcome is right today, only the code path is accidental; and frames
 * for a GOAWAY-refused stream are re-refused, keeping the connection alive.
 * <p>
 * EXPECT-RED until the E1 fix: the max-concurrent refusal branch does not
 * advance lastSeenStreamId, so once the registry dips back below the limit
 * the refused stream's IN-FLIGHT DATA/WU falls through both refusal checks
 * into the phantom path and GOAWAYs every concurrent stream.
 * the class is wired into testng.xml together with that fix.
 */
public class H2PhantomStreamTest {

  private static final int REFUSED_STREAM = 0x7;

  private static final int PROTOCOL_ERROR = 0x1;

  private HttpServer server;

  @BeforeMethod
  void setUp() throws IOException {
    if (!"true".equals(System.getProperty("robaho.net.httpserver.http2OverNonSSL"))) {
      throw new SkipException("robaho.net.httpserver.http2OverNonSSL is not enabled");
    }
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/echo", exchange -> {
      byte[] body = exchange.getRequestBody().readAllBytes();
      exchange.sendResponseHeaders(200, body.length);
      try (var os = exchange.getResponseBody()) {
        os.write(body);
      }
    });
    server.start();
  }

  @AfterMethod
  void tearDown() {
    if (server != null) {
      server.stop(0);
    }
  }

  /**
   * lock-in: a stream-level WINDOW_UPDATE on an idle (never-opened) stream
   * id is a connection error of type PROTOCOL_ERROR (RFC 7540 5.1). today
   * it arrives via the phantom decode ("missing required header field
   * :path"), after the frame-type guard it arrives via a dedicated check -
   * both must GOAWAY the connection
   */
  @Test
  public void testWindowUpdateOnIdleStreamIsConnectionError() throws Exception {
    try (H2Client client = new H2Client(server.getAddress().getPort())) {
      client.writeWindowUpdate(1, 1000);
      var response = client.awaitResponse(1);
      Assert.assertTrue(response.goaway(), "WU on an idle stream must be a connection error");
      Assert.assertEquals(client.goawayErrorCode, PROTOCOL_ERROR, "the GOAWAY must carry PROTOCOL_ERROR");
    }
  }

  /** lock-in: the same rule for a bare DATA frame on an idle stream id */
  @Test
  public void testDataOnIdleStreamIsConnectionError() throws Exception {
    try (H2Client client = new H2Client(server.getAddress().getPort())) {
      client.writeRawFrame(H2Client.TYPE_DATA, 0, 101, new byte[] {'x'});
      var response = client.awaitResponse(101);
      Assert.assertTrue(response.goaway(), "DATA on an idle stream must be a connection error");
      Assert.assertEquals(client.goawayErrorCode, PROTOCOL_ERROR, "the GOAWAY must carry PROTOCOL_ERROR");
    }
  }

  /**
   * lock-in: an in-flight WINDOW_UPDATE for a GOAWAY-refused stream is
   * re-refused (the id stays beyond the boundary), and the connection
   * keeps serving its in-flight streams
   */
  @Test
  public void testWindowUpdateOnGoawayRefusedStreamKeepsConnection() throws Exception {
    try (H2Client client = new H2Client(server.getAddress().getPort())) {
      // stream 1 in flight: the handler is parked reading its body
      client.sendRequest("POST", "/echo", false);

      // the peer's GOAWAY(NO_ERROR): no new streams beyond 100
      client.writeRawFrame(H2Client.TYPE_GOAWAY, 0, 0, new byte[] {0, 0, 0, 100, 0, 0, 0, 0});

      client.sendRequest(101, "GET", "/echo", true);
      var refused = client.awaitResponse(101);
      Assert.assertEquals(refused.rstErrorCode(), Integer.valueOf(REFUSED_STREAM), "stream 101 must be refused");

      // a WINDOW_UPDATE the client may legally still have in flight for
      // the refused stream (sent before the RST was seen)
      client.writeWindowUpdate(101, 1000);

      // the connection survives: the in-flight stream completes
      client.sendData(1, "done".getBytes(StandardCharsets.UTF_8), true);
      var response = client.awaitResponse(1);
      Assert.assertFalse(response.goaway(), "in-flight WU on a refused stream must not GOAWAY the connection");
      Assert.assertEquals(response.status(), 200, "the in-flight stream must complete");
      Assert.assertEquals(new String(response.data(), StandardCharsets.UTF_8), "done");
    }
  }

  /** lock-in: the same scenario with an in-flight DATA frame */
  @Test
  public void testDataOnGoawayRefusedStreamKeepsConnection() throws Exception {
    try (H2Client client = new H2Client(server.getAddress().getPort())) {
      client.sendRequest("POST", "/echo", false);
      client.writeRawFrame(H2Client.TYPE_GOAWAY, 0, 0, new byte[] {0, 0, 0, 100, 0, 0, 0, 0});

      client.sendRequest(101, "GET", "/echo", true);
      var refused = client.awaitResponse(101);
      Assert.assertEquals(refused.rstErrorCode(), Integer.valueOf(REFUSED_STREAM), "stream 101 must be refused");

      client.writeRawFrame(H2Client.TYPE_DATA, 0, 101, new byte[] {'x'});

      client.sendData(1, "done".getBytes(StandardCharsets.UTF_8), true);
      var response = client.awaitResponse(1);
      Assert.assertFalse(response.goaway(), "in-flight DATA on a refused stream must not GOAWAY the connection");
      Assert.assertEquals(response.status(), 200, "the in-flight stream must complete");
      Assert.assertEquals(new String(response.data(), StandardCharsets.UTF_8), "done");
    }
  }

  /**
   * EXPECT-RED: the max-concurrent refusal does not advance
   * lastSeenStreamId, so once the registry dips back below the limit the
   * refused stream's in-flight DATA falls through both refusal checks into
   * the phantom path and kills every concurrent stream. the fix advances
   * lastSeenStreamId on refusal, routing the frame into the existing
   * closed-stream ignore branch instead
   */
  @Test
  public void testDataOnConcurrentRefusedStreamKeepsConnection() throws Exception {
    try (H2Client client = new H2Client(server.getAddress().getPort())) {
      // fill SETTINGS_MAX_CONCURRENT_STREAMS (default 100) with parked POSTs
      for (int i = 0; i < 100; i++) {
        client.sendRequest("POST", "/echo", false);
      }
      int refused = client.sendRequest("POST", "/echo", false); // stream id 201
      var refusal = client.awaitResponse(refused);
      Assert.assertEquals(refusal.rstErrorCode(), Integer.valueOf(REFUSED_STREAM), "the 101st stream must be refused");

      // free one slot: the RST is processed synchronously by the reader,
      // so the registry has already dipped before the next frame arrives
      client.sendRstStream(3, H2Client.CANCEL);

      // in-flight DATA for the refused stream, with the registry below the
      // limit: must be ignored as a closed-stream frame, not phantom-created
      client.writeRawFrame(H2Client.TYPE_DATA, 0, refused, new byte[] {'x'});

      // the connection survives: all accepted streams complete (their
      // responses batch-flush at the last close)
      for (int i = 0; i < 100; i++) {
        int id = 2 * i + 1;
        if (id != 3) { // cancelled above
          client.sendData(id, "done".getBytes(StandardCharsets.UTF_8), true);
        }
      }
      var first = client.awaitResponse(1, 15_000);
      Assert.assertFalse(first.goaway(), "in-flight DATA on a refused stream must not GOAWAY the connection");
      Assert.assertEquals(first.status(), 200, "the connection must keep serving accepted streams");
      var last = client.awaitResponse(199, 15_000);
      Assert.assertEquals(last.status(), 200, "the last accepted stream must complete too");
    }
  }

  /** EXPECT-RED: the same phantom path via an in-flight WINDOW_UPDATE */
  @Test
  public void testWindowUpdateOnConcurrentRefusedStreamKeepsConnection() throws Exception {
    try (H2Client client = new H2Client(server.getAddress().getPort())) {
      for (int i = 0; i < 100; i++) {
        client.sendRequest("POST", "/echo", false);
      }
      int refused = client.sendRequest("POST", "/echo", false); // stream id 201
      var refusal = client.awaitResponse(refused);
      Assert.assertEquals(refusal.rstErrorCode(), Integer.valueOf(REFUSED_STREAM), "the 101st stream must be refused");

      client.sendRstStream(3, H2Client.CANCEL);
      client.writeWindowUpdate(refused, 1000);

      for (int i = 0; i < 100; i++) {
        int id = 2 * i + 1;
        if (id != 3) { // cancelled above
          client.sendData(id, "done".getBytes(StandardCharsets.UTF_8), true);
        }
      }
      var first = client.awaitResponse(1, 15_000);
      Assert.assertFalse(first.goaway(), "in-flight WU on a refused stream must not GOAWAY the connection");
      Assert.assertEquals(first.status(), 200, "the connection must keep serving accepted streams");
      var last = client.awaitResponse(199, 15_000);
      Assert.assertEquals(last.status(), 200, "the last accepted stream must complete too");
    }
  }

}
