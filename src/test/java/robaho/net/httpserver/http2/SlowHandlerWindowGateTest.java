package robaho.net.httpserver.http2;

import com.sun.net.httpserver.HttpServer;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * M1 memory-bound regression: with parse-site (arrival-side) stream credit
 * the client's flow-control window never runs out, so a handler slower than
 * the wire could accumulate an unbounded arrived-but-unconsumed DataIn
 * queue - the only OOM vector in the default deployment (maxRequestBodySize
 * defaults to unlimited). the watermark gate withholds replenishment while
 * the backlog is above 2x the window target; the consumption-side recovery
 * re-opens the window once the handler drains.
 * <p>
 * the tests speak the flow-control accounting by hand so the gate is
 * observable from the wire. the client discipline mirrors a compliant
 * sender (RFC 7540 6.9): a frame may only be sent when BOTH the stream and
 * the connection window can fit it, stream-id WINDOW_UPDATEs credit only
 * the stream window, connection-id updates only the connection window.
 * mis-crediting either (a first-draft bug here) lets the client oversend
 * and the server correctly GOAWAYs with FLOW_CONTROL_ERROR.
 */
public class SlowHandlerWindowGateTest {

  private HttpServer server;

  private final AtomicInteger okRequests = new AtomicInteger();

  /** the server's default auto-tune window cap (ServerConfig default) */
  private static final int AUTO_TUNE_CAP = 512 * 1024;

  /** the server's default initial stream window */
  private static final int INITIAL_WINDOW = 65535;

  private static final int FRAME = 16384;

  /** wall-clock budget for a gate probe: the server withholds for the whole test */
  private static final long GATE_PROBE_MS = 4_000;

  /**
   * the flood budget for the gate tests. the watermark gate only becomes
   * observable after roughly 2x the auto-tune target PLUS the granted
   * credit (~1.5MB with the 512K auto-tune cap), but the suite's surefire
   * config sets maxRequestBodySize=1MB (for RequestBodyCapTest) - a capped
   * stream is reset with ENHANCE_YOUR_CALM before the gate can trip, and
   * the probe cannot tell "credit withheld" from "stream reset". so the
   * gate observation needs BOTH a flood larger than the gate bound AND no
   * body cap: only then is the test discriminative. the surefire property
   * is read per test-JVM: under `mvn test` standalone it is unset
   * (flood=4MB, full discriminative power); inside the suite JVM it is
   * 1MB and the gate tests SKIP via the assumption below (their coverage
   * comes from the standalone runs - same contract as the skip pattern
   * every other h2-over-non-SSL test uses)
   */
  private static final long BODY_CAP = Long.getLong("robaho.net.httpserver.maxRequestBodySize", -1);

  /** the gate is only observable when no request-body cap interferes */
  private static boolean gateObservable() {
    long cap = BODY_CAP;
    return cap <= 0 || cap > 4 * 1024 * 1024;
  }

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
    server.start();
  }

  @AfterMethod
  void tearDown() {
    if (server != null) {
      server.stop(0);
    }
  }

  /**
   * wait for flow-control credit until BOTH windows can fit a frame. returns
   * true when credit arrived (either kind - connection credit folds into the
   * client's tracking inside readWindowUpdate), false when nothing arrived
   * within the budget. stream-id increments are applied to the stream window
   * here; connection-id increments must NOT be (that was the first-draft
   * bug: it inflated the stream window and the client oversent)
   */
  private boolean topUpWindows(H2Client client, int streamId, long budgetMillis) throws IOException {
    while (client.streamSendWindow < FRAME || client.connSendWindow < FRAME) {
      int[] wu = client.readWindowUpdate(budgetMillis);
      if (wu == null) {
        return false;
      }
      if (wu[0] == streamId) {
        client.streamSendWindow += wu[1];
      }
    }
    return true;
  }

  /**
   * the slow handler's queue must be BOUNDED: with the handler parked and
   * reading nothing, the client can only push the gate (2x the auto-tuned
   * target, where replenishment stops) plus whatever credit the wire
   * already granted before the gate tripped (at most one more target's
   * worth) - never the whole unbounded flood. observed from the wire as:
   * both windows dry and no WINDOW_UPDATE of either kind within the probe
   * budget. the un-gated server (the bug) keeps topping the stream window
   * up forever - the client pushes the full 4MB flood and the assertions
   * fail
   */
  @Test
  public void testSlowHandlerBacklogIsBounded() throws Exception {
    if (!gateObservable()) {
      throw new org.testng.SkipException(
          "suite JVM runs with maxRequestBodySize=1MB: the cap resets the stream before the gate bound (~1.5MB) - run standalone for the gate coverage");
    }
    CountDownLatch release = new CountDownLatch(1);
    server.createContext("/slow", ex -> {
      try (InputStream in = ex.getRequestBody()) {
        // park without reading: the backlog grows on the reader thread
        release.await();
        in.readAllBytes();
      } catch (InterruptedException e) {
        // stream closed under us: fine
      }
      try {
        ex.sendResponseHeaders(200, -1);
      } catch (IOException e) {
        // stream reset while parked: fine
      }
    });

    try (H2Client client = new H2Client(server.getAddress().getPort())) {
      client.streamSendWindow = INITIAL_WINDOW;
      int streamId = client.sendRequest("POST", "/slow", false);
      long sent = 0;
      byte[] frame = new byte[FRAME];
      boolean gateConfirmed = false;
      // the flood must stay under the suite's body cap when one is set
      // (1MB): past it the cap resets the stream, which the probe would
      // misread as the gate
      long flood = 4 * 1024 * 1024;
      while (sent < flood) {
        if (!topUpWindows(client, streamId, GATE_PROBE_MS)) {
          // no credit of either kind within the budget: the gate is holding
          gateConfirmed = true;
          break;
        }
        client.sendData(streamId, frame, false);
        client.streamSendWindow -= FRAME; // (connSendWindow is decremented inside sendData)

        sent += FRAME;
      }
      release.countDown();
      Assert.assertTrue(gateConfirmed, "the gate must trip: the client pushed " + sent
          + " bytes into a parked handler with no withheld credit (unbounded backlog)");
      // the bound: the gate (2x target) + the credit already granted before
      // the gate tripped (at most one more target's worth, the topped-up
      // window) + a frame of reader-transient margin. 3x the auto-tune cap
      // + slack; the un-gated flood would be the full budget
      Assert.assertTrue(sent <= 3L * AUTO_TUNE_CAP + 4L * FRAME,
          "slow-handler backlog must be bounded at the gate, but the client pushed " + sent + " bytes");
      // and the server must still be alive and serving (the client never
      // oversent, so no FLOW_CONTROL_ERROR GOAWAY ever fired)
      int ok = client.sendRequest("GET", "/ok", true);
      var okResp = client.awaitResponse(ok);
      Assert.assertEquals(okResp.status(), 200, "a gated connection must stay perfectly usable");
      Assert.assertFalse(okResp.goaway());
    }
  }

  /**
   * the recovery path: once the handler drains below the low watermark, the
   * withheld window is returned and the same client completes a full upload
   * that crossed the gate on the way. if the recovery never fires, the
   * client's topUpWindows probe runs out its budget and the test fails
   * instead of hanging
   */
  @Test
  public void testGatedStreamRecoversAndCompletes() throws Exception {
    if (!gateObservable()) {
      throw new org.testng.SkipException(
          "suite JVM runs with maxRequestBodySize=1MB: the cap resets the stream before the gate bound (~1.5MB) - run standalone for the gate coverage");
    }
    CountDownLatch release = new CountDownLatch(1);
    server.createContext("/slowdrain", ex -> {
      try (InputStream in = ex.getRequestBody()) {
        // park until the client confirms the gate, then drain everything
        release.await();
        in.readAllBytes();
      } catch (InterruptedException e) {
        // closed under us
      }
      ex.sendResponseHeaders(200, -1);
    });

    try (H2Client client = new H2Client(server.getAddress().getPort())) {
      client.streamSendWindow = INITIAL_WINDOW;
      int streamId = client.sendRequest("POST", "/slowdrain", false);
      // the flood must stay under the suite's body cap (1MB when set): a
      // capped stream is reset with ENHANCE_YOUR_CALM, which the probe
      // would misread as the gate. 768KB crosses the 64K-target gate
      // (~256K bound) but stays below the 1MB cap
      long total = 2 * 1024 * 1024;
      Assert.assertTrue(total > 4 * INITIAL_WINDOW,
          "test needs headroom above the initial window (total=" + total + ", cap=" + BODY_CAP + ")");
      long sent = 0;
      byte[] frame = new byte[FRAME];
      boolean gateConfirmed = false;
      while (sent < total) {
        if (!topUpWindows(client, streamId, GATE_PROBE_MS)) {
          gateConfirmed = true;
          break;
        }
        client.sendData(streamId, frame, false);
        client.streamSendWindow -= FRAME; // (connSendWindow is decremented inside sendData)

        sent += FRAME;
      }
      Assert.assertTrue(gateConfirmed, "phase 1 must confirm the gate before the drain (pushed " + sent + ")");
      // phase 2: release the handler. the drain must re-open the window
      // via the consumption-side recovery - a missing recovery leaves both
      // windows dry forever and the probe times out
      release.countDown();
      while (sent < total) {
        Assert.assertTrue(topUpWindows(client, streamId, 15_000),
            "the recovery path must return the withheld stream credit (sent=" + sent + ")");
        client.sendData(streamId, frame, false);
        client.streamSendWindow -= FRAME; // (connSendWindow is decremented inside sendData)

        sent += FRAME;
      }
      client.sendData(streamId, new byte[0], true);
      var r = client.awaitResponse(streamId, 30_000);
      Assert.assertEquals(r.status(), 200, "the gated stream must complete after the handler drains");
      Assert.assertFalse(r.goaway());
    }
  }

  /**
   * a FAST handler's upload must be unaffected by the gate: a 1MB upload
   * consumed as it arrives completes without the client ever observing a
   * dry window beyond normal per-frame waits
   */
  @Test
  public void testFastHandlerUploadUnaffected() throws Exception {
    server.createContext("/fast", ex -> {
      try (InputStream in = ex.getRequestBody()) {
        in.readAllBytes();
      }
      ex.sendResponseHeaders(200, -1);
    });

    try (H2Client client = new H2Client(server.getAddress().getPort())) {
      client.streamSendWindow = INITIAL_WINDOW;
      int streamId = client.sendRequest("POST", "/fast", false);
      long sent = 0;
      // stay under the suite's body cap when one is set (cap-FRAME leaves
      // the incremental check one frame of margin)
      long total = 1024 * 1024;
      byte[] frame = new byte[FRAME];
      while (sent < total) {
        Assert.assertTrue(topUpWindows(client, streamId, 15_000),
            "a fast handler must never be starved of credit (sent=" + sent + ")");
        client.sendData(streamId, frame, false);
        client.streamSendWindow -= FRAME; // (connSendWindow is decremented inside sendData)

        sent += FRAME;
      }
      client.sendData(streamId, new byte[0], true);
      var r = client.awaitResponse(streamId, 30_000);
      Assert.assertEquals(r.status(), 200, "fast handler upload must complete");
      Assert.assertFalse(r.goaway());
    }
  }

}
