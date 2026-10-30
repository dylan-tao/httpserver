package robaho.net.httpserver.websockets;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Frame-level websocket regressions from the 2026-10-09 audit: whole-message
 * UTF-8 validation (a legal code point split across fragments must pass,
 * invalid UTF-8 in continuation frames must fail with 1007), the control
 * frame payload cap, and application-defined close codes surfacing in onClose.
 */
public class WebSocketFrameValidationTest {

  private HttpServer server;

  private final AtomicReference<WebSocket> serverWebSocket = new AtomicReference<>();

  private final AtomicReference<String> receivedText = new AtomicReference<>();

  private final AtomicReference<String> closeInfo = new AtomicReference<>();

  // TestNG reuses one instance for all methods in a class: the latches must
  // be reset per test or a previous test's close leaks into the assertions
  private CountDownLatch messageLatch;

  private CountDownLatch closeLatch;

  @BeforeMethod
  void setUp() throws IOException {
    messageLatch = new CountDownLatch(1);
    closeLatch = new CountDownLatch(1);
    serverWebSocket.set(null);
    receivedText.set(null);
    closeInfo.set(null);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/ws", new WebSocketHandler() {

      @Override
      protected WebSocket openWebSocket(HttpExchange exchange) {
        WebSocket ws = new WebSocket(exchange) {

          @Override
          protected void onClose(CloseCode code, String reason, boolean initiatedByRemote) {
            closeInfo.set("code=" + code + ", reason=" + reason + ", remote=" + initiatedByRemote);
            closeLatch.countDown();
          }

          @Override
          protected void onMessage(WebSocketFrame message) {
            receivedText.set(message.getTextPayload());
            messageLatch.countDown();
          }

          @Override
          protected void onPong(WebSocketFrame pong) {}

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

  /**
   * a text message fragmented mid code point is legal (RFC 6455 5.6) and
   * must be delivered - per-fragment UTF-8 validation rejected it with 1007
   */
  @Test
  public void testFragmentedTextSplittingCodePoint() throws Exception {
    try (RawWsClient client = new RawWsClient(server.getAddress().getPort())) {
      // "é" = 0xC3 0xA9, split across two fragments
      client.sendFrame(0x1, false, new byte[] {(byte) 0xC3});
      client.sendFrame(0x0, true, new byte[] {(byte) 0xA9});
      Assert.assertTrue(messageLatch.await(5, TimeUnit.SECONDS), "message must be delivered");
      Assert.assertEquals(receivedText.get(), "é", "the split code point must reassemble");
    }
  }

  /**
   * invalid UTF-8 delivered in continuation frames must be detected and
   * close the websocket with 1007 (previously continuation frames were
   * never validated). the server closes the socket without a wire-level
   * close frame on error paths, so the code is asserted via onClose
   */
  @Test
  public void testInvalidUtf8InContinuationRejected() throws Exception {
    try (RawWsClient client = new RawWsClient(server.getAddress().getPort())) {
      client.sendFrame(0x1, false, new byte[] {(byte) 0xC3});
      client.sendFrame(0x0, true, new byte[] {(byte) 0xA9, (byte) 0xFF});
      Assert.assertTrue(closeLatch.await(5, TimeUnit.SECONDS), "onClose must fire");
      Assert.assertTrue(closeInfo.get().contains("code=InvalidFramePayloadData"),
          "invalid UTF-8 must close with 1007, got: " + closeInfo.get());
    }
  }

  /** control frame payloads are capped at 125 bytes (RFC 6455 5.5) */
  @Test(expectedExceptions = IllegalArgumentException.class)
  public void testPingPayloadOver125Rejected() throws Exception {
    try (RawWsClient client = new RawWsClient(server.getAddress().getPort())) {
      WebSocket ws = awaitServerWebSocket();
      Assert.assertNotNull(ws, "websocket must be open after the handshake");
      ws.ping(new byte[200]);
    }
  }

  /**
   * an application-defined close code (4000) has no CloseCode mapping: the
   * real reason and initiator must still surface in onClose instead of a
   * fabricated 1011 "handler terminated"
   */
  @Test
  public void testApplicationCloseCodeSurfaces() throws Exception {
    var client = WebSocketClientUtil.createWSC(server.getAddress().getPort(), "/ws", s -> {}, null);
    client.sendClose(4000, "session expired").join();
    Assert.assertTrue(closeLatch.await(5, TimeUnit.SECONDS), "onClose must fire");
    Assert.assertEquals(closeInfo.get(), "code=null, reason=session expired, remote=true",
        "the real reason and initiator must be reported (code is null for unmapped 3xxx/4xxx codes)");
    // the codeless close reply lets the JDK client complete its handshake
  }

  /**
   * the 101 response does not synchronize with the server-side
   * openWebSocket callback: under parallel suite load the handler may not
   * have published the WebSocket yet when the client finishes its
   * handshake, so poll briefly instead of asserting immediately (this
   * flaked ~1 run in 10 before)
   */
  private WebSocket awaitServerWebSocket() throws InterruptedException {
    long deadline = System.currentTimeMillis() + 5000;
    WebSocket ws = serverWebSocket.get();
    while (ws == null && System.currentTimeMillis() < deadline) {
      Thread.sleep(10);
      ws = serverWebSocket.get();
    }
    return ws;
  }

  /** a minimal raw websocket client speaking masked frames over a socket */
  static final class RawWsClient implements AutoCloseable {

    private final Socket socket;

    private final java.io.OutputStream out;

    private final InputStream in;

    RawWsClient(int port) throws IOException {
      socket = new Socket("127.0.0.1", port);
      socket.setSoTimeout(5000);
      out = socket.getOutputStream();
      in = socket.getInputStream();
      out.write(("GET /ws HTTP/1.1\r\nHost: x\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
          + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n")
              .getBytes(StandardCharsets.ISO_8859_1));
      out.flush();
      String head = readHead();
      Assert.assertTrue(head.startsWith("HTTP/1.1 101"), "handshake must succeed: " + head);
    }

    /** send a masked client frame (all client frames must be masked) */
    void sendFrame(int opcode, boolean fin, byte[] payload) throws IOException {
      byte[] mask = {0x12, 0x34, 0x56, 0x78};
      ByteArrayOutputStream buf = new ByteArrayOutputStream();
      buf.write(fin ? 0x80 | opcode : opcode);
      if (payload.length < 126) {
        buf.write(0x80 | payload.length);
      } else if (payload.length <= 0xFFFF) {
        buf.write(0x80 | 126);
        buf.write(payload.length >>> 8);
        buf.write(payload.length & 0xFF);
      } else {
        buf.write(0x80 | 127);
        for (int i = 7; i >= 0; i--) {
          buf.write((payload.length >>> (8 * i)) & 0xFF);
        }
      }
      buf.write(mask);
      for (int i = 0; i < payload.length; i++) {
        buf.write(payload[i] ^ mask[i & 3]);
      }
      out.write(buf.toByteArray());
      out.flush();
    }

    /** read frames until a Close frame arrives; return its status code */
    int awaitCloseCode() throws IOException {
      while (true) {
        int b0 = in.read();
        int b1 = in.read();
        Assert.assertNotEquals(b0, -1, "connection closed without a close frame");
        int opcode = b0 & 0x0F;
        int len = b1 & 0x7F;
        if (len == 126) {
          len = (in.read() & 0xFF) << 8 | in.read() & 0xFF;
        } else if (len == 127) {
          len = 0;
          for (int i = 0; i < 8; i++) {
            len = (len << 8) | (in.read() & 0xFF);
          }
        }
        byte[] payload = new byte[len];
        for (int i = 0; i < len; i++) {
          payload[i] = (byte) in.read();
        }
        if (opcode == 0x8) {
          Assert.assertTrue(len >= 2, "close frame must carry a status code");
          return (payload[0] & 0xFF) << 8 | payload[1] & 0xFF;
        }
      }
    }

    private String readHead() throws IOException {
      StringBuilder sb = new StringBuilder();
      int c;
      while ((c = in.read()) != -1) {
        sb.append((char) c);
        int n = sb.length();
        if (n >= 4 && sb.charAt(n - 4) == '\r' && sb.charAt(n - 3) == '\n' && sb.charAt(n - 2) == '\r'
            && sb.charAt(n - 1) == '\n') {
          break;
        }
      }
      return sb.toString();
    }

    @Override
    public void close() throws IOException {
      socket.close();
    }

  }

}
