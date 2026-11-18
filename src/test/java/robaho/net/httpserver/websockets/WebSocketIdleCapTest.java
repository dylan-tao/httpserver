package robaho.net.httpserver.websockets;

import com.sun.net.httpserver.HttpServer;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Batch-4 M6 regression: a handed-off connection (websocket 101 / CONNECT
 * tunnel) keeps inRequest true for its whole lifetime, which made it
 * permanently "busy" to the cleaner - so it never counted toward
 * maxIdleConnections. N quiet websocket sessions therefore pinned N
 * connections (buffers + virtual-thread stacks) with no bound, in the
 * DEFAULT deployment.
 * <p>
 * the fix counts a handed-off connection toward the idle cap once its
 * activity clock (refreshed by WS reads AND writes through the
 * activity-tracking stream chain) has been quiet for IDLE_INTERVAL/2. an
 * actively chattering websocket never counts; the deliberate idle-close
 * exemption for websockets (a quiet session is legal) is untouched - the
 * idle-close branch still requires !busy, which a websocket never is.
 * <p>
 * determinism strategy: the production constants (cap=200, IDLE_INTERVAL
 * =30s) are static-final, so the test does NOT touch them. instead it
 * fills the server with plain h1 keep-alive connections and backdates the
 * POPULATIONS' activity clocks (HttpConnection.setLastActivityTime is
 * public; the sweep and counters are reached through the package bridge):
 * fillers aged 20s sit inside the idle-close threshold (30s) but over the
 * cap-listing threshold (15s); websockets aged 25s are the OLDEST idle
 * population. one synchronous production sweep then must trim exactly the
 * websockets beyond the cap. removal from the registry is synchronous
 * (closeConnection removes inline), so the post-sweep counts assert
 * immediately; only the client-side close notification is async (awaited)
 */
public class WebSocketIdleCapTest {

  private HttpServer server;

  private final AtomicInteger wsOpens = new AtomicInteger();

  @BeforeMethod
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/ws", new EchoWebSocketHandler());
    server.createContext("/filler", ex -> {
      // plain keep-alive response: the connection parks after this
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
   * open one websocket session and warm it with a verified echo round
   * trip: the received echo proves the SERVER's echo write already
   * happened (so no trailing traffic can re-fresh the activity clock
   * after the test backdates it)
   */
  private java.net.http.WebSocket openWs(CountDownLatch closed) throws Exception {
    CountDownLatch echo = new CountDownLatch(1);
    var ws = WebSocketClientUtil.createWSC(server.getAddress().getPort(), "/ws", msg -> echo.countDown(), closed::countDown);
    wsOpens.incrementAndGet();
    ws.sendText("warmup-" + wsOpens.get(), true).join();
    if (!echo.await(5, TimeUnit.SECONDS)) {
      throw new IllegalStateException("websocket echo warmup failed");
    }
    return ws;
  }

  /** open one plain h1 keep-alive filler connection */
  private Socket openFiller() throws IOException {
    var s = new Socket("127.0.0.1", server.getAddress().getPort());
    s.setSoTimeout(5000);
    s.getOutputStream().write(("GET /filler HTTP/1.1\r\nHost: x\r\n\r\n").getBytes());
    // consume the response head so the connection parks in keep-alive
    var in = s.getInputStream();
    byte[] buf = new byte[512];
    int total = 0;
    while (total < 4) {
      int n = in.read(buf);
      if (n == -1) {
        throw new IOException("filler connection closed before parking");
      }
      total += n;
    }
    return s;
  }

  /** wait until the registry count reaches {@code expected} (bounded) */
  private static void awaitCount(HttpServer server, int expected) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 10_000;
    while (robaho.net.httpserver.WebSocketIdleCapBridge.connectionCount(server) != expected) {
      if (System.currentTimeMillis() > deadline) {
        Assert.fail("registry stuck at " + robaho.net.httpserver.WebSocketIdleCapBridge.connectionCount(server) + ", expected "
            + expected);
      }
      Thread.sleep(50);
    }
  }

  /**
   * the cap sweep must trim QUIET websockets: 200 fillers aged 20s (exactly
   * the cap) + 6 quiet websockets aged 25s (the oldest population) - one
   * sweep trims exactly the 6 websockets. their clients observe the
   * abnormal close; every filler within the cap survives
   */
  @Test
  public void testQuietWebSocketsCountTowardIdleCap() throws Exception {
    List<Socket> fillers = new ArrayList<>();
    for (int i = 0; i < 200; i++) {
      fillers.add(openFiller());
    }
    Assert.assertEquals(robaho.net.httpserver.WebSocketIdleCapBridge.connectionCount(server), 200,
        "filler connections must be tracked");

    CountDownLatch closed = new CountDownLatch(6);
    for (int i = 0; i < 6; i++) {
      openWs(closed);
    }
    Assert.assertEquals(robaho.net.httpserver.WebSocketIdleCapBridge.connectionCount(server), 206);

    // age the populations: fillers 20s quiet, websockets 25s quiet (the
    // oldest). both under the 30s idle-close threshold; both over the
    // 15s cap-listing threshold
    Assert.assertEquals(robaho.net.httpserver.WebSocketIdleCapBridge.ageKeepAliveH1Connections(server, 20_000), 200,
        "all fillers aged");
    Assert.assertEquals(robaho.net.httpserver.WebSocketIdleCapBridge.ageHandedOffConnections(server, 25_000), 6,
        "all websockets aged (handed-off)");

    // ONE production sweep: 206 idle candidates > 200 cap -> trim the 6
    // oldest = the websockets
    robaho.net.httpserver.WebSocketIdleCapBridge.sweep(server);

    Assert.assertEquals(robaho.net.httpserver.WebSocketIdleCapBridge.connectionCount(server), 200,
        "exactly the 6 websockets beyond the cap are trimmed");
    Assert.assertTrue(closed.await(10, TimeUnit.SECONDS), "the trimmed websockets' clients must observe the close");
    for (Socket s : fillers) {
      Assert.assertTrue(!s.isClosed() && s.isConnected(), "filler keep-alives within the cap must survive");
    }
  }

  /**
   * an ACTIVE websocket never counts toward the cap: its activity clock is
   * refreshed by the chatter itself, so with 202 quiet fillers aged 20s
   * (2 beyond the cap) and one chatting websocket, the sweep trims the 2
   * OLDEST FILLERS - proving the sweep ran and exerted the cap - and
   * spares the fresh websocket
   */
  @Test
  public void testActiveWebSocketNotCountedTowardCap() throws Exception {
    List<Socket> fillers = new ArrayList<>();
    for (int i = 0; i < 202; i++) {
      fillers.add(openFiller());
    }
    var closed = new CountDownLatch(1);
    var active = openWs(closed);

    // age ONLY the fillers; the websocket stays fresh (chatter within ms)
    Assert.assertEquals(robaho.net.httpserver.WebSocketIdleCapBridge.ageKeepAliveH1Connections(server, 20_000), 202);

    robaho.net.httpserver.WebSocketIdleCapBridge.sweep(server);

    // the sweep trimmed the 2 oldest fillers down to the cap: 200
    // fillers + the still-fresh websocket remain
    Assert.assertEquals(robaho.net.httpserver.WebSocketIdleCapBridge.connectionCount(server), 201,
        "the sweep must trim fillers to the cap");
    Assert.assertEquals(closed.getCount(), 1L, "an actively-chattering websocket must never be idle-capped");
    active.sendText("still-alive", true).join();
  }

}
