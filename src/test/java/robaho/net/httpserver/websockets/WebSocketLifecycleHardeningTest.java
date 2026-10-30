package robaho.net.httpserver.websockets;

import com.sun.net.httpserver.HttpServer;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * regressions for the 2026-10-23 audit websocket lifecycle findings: the close(code, null) NPE and the reserved close
 * code handling
 */
public class WebSocketLifecycleHardeningTest {

  private HttpServer server;

  private final AtomicReference<WebSocket> serverWebSocket = new AtomicReference<>();

  private CountDownLatch closeLatch = new CountDownLatch(1);

  private final AtomicReference<String> closeInfo = new AtomicReference<>();

  @BeforeMethod
  void setUp() throws IOException {
    serverWebSocket.set(null);
    // reset per test (a second latch-consuming test would otherwise
    // inherit a fired latch) - the same pattern as the sibling tests
    closeLatch = new CountDownLatch(1);
    closeInfo.set(null);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/ws", new WebSocketHandler() {

      @Override
      protected WebSocket openWebSocket(com.sun.net.httpserver.HttpExchange exchange) {
        WebSocket ws = new WebSocket(exchange) {

          @Override
          protected void onClose(CloseCode code, String reason, boolean initiatedByRemote) {
            closeInfo.set("code=" + code + ", reason=" + reason + ", remote=" + initiatedByRemote);
            closeLatch.countDown();
          }

          @Override
          protected void onMessage(WebSocketFrame message) {}

        };
        serverWebSocket.set(ws);
        return ws;
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

  private WebSocket awaitServerWebSocket() throws InterruptedException {
    long deadline = System.currentTimeMillis() + 5000;
    WebSocket ws = serverWebSocket.get();
    while (ws == null && System.currentTimeMillis() < deadline) {
      Thread.sleep(10);
      ws = serverWebSocket.get();
    }
    return ws;
  }

  /** close(code, null) is a legal call and must not NPE */
  @Test
  public void testCloseWithNullReason() throws Exception {
    var client = WebSocketClientUtil.createWSC(server.getAddress().getPort(), "/ws", s -> {}, null);
    {
      WebSocket ws = awaitServerWebSocket();
      Assert.assertNotNull(ws);
      ws.close(CloseCode.NormalClosure, null); // used to NPE in CloseFrame
      client.sendClose(1000, "done").join();
      Assert.assertTrue(closeLatch.await(5, TimeUnit.SECONDS), "onClose must fire");
    }
  }

  /**
   * codes that MUST NOT appear in a Close frame on the wire (RFC 6455 7.4.1) are sanitized to a codeless close; legal
   * ones pass through
   */
  @Test
  public void testReservedCloseCodesSanitized() {
    // must not be sent: 1005, 1006, 1015, unassigned 1016-2999
    Assert.assertNull(WebSocket.sanitizeCloseCode(CloseCode.NoStatusRcvd), "1005");
    Assert.assertNull(WebSocket.sanitizeCloseCode(CloseCode.AbnormalClosure), "1006");
    Assert.assertNull(WebSocket.sanitizeCloseCode(CloseCode.TLSHandshake), "1015");
    Assert.assertNull(WebSocket.sanitizeCloseCode(CloseCode.find(2000)), "unassigned 2000");
    Assert.assertNull(WebSocket.sanitizeCloseCode(null), "null stays null (codeless)");
    // legal to send: 1000-1003, 1007-1011, 3000-4999
    Assert.assertEquals(WebSocket.sanitizeCloseCode(CloseCode.NormalClosure), CloseCode.NormalClosure);
    Assert.assertEquals(WebSocket.sanitizeCloseCode(CloseCode.ProtocolError), CloseCode.ProtocolError);
    Assert.assertEquals(WebSocket.sanitizeCloseCode(CloseCode.find(4000)), CloseCode.find(4000));
  }

}
