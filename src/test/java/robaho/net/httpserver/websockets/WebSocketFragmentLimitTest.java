package robaho.net.httpserver.websockets;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * regression guard for the websocket fragment-count cap (audit W13,
 * 2026-11-20): the byte cap alone bounded the aggregated payload but not
 * the object count - a 1-byte x 4M-fragment flood stayed under the 4MB byte
 * cap while retaining ~330MB of frame objects on a single connection.
 * beyond MAX_FRAGMENTS (1024) fragments the connection must close with
 * MessageTooBig. the client is a raw socket with hand-built masked frames
 * (the JDK client's partial-send semantics made the flood unobservable);
 * the close code is asserted server-side via onClose, the same contract
 * WebSocketFrameValidationTest pins for the error-path close (which sends
 * no Close frame on the wire). red before the fix: the flood was accepted,
 * no close ever arrived.
 */
public class WebSocketFragmentLimitTest {

  private HttpServer server;

  private final java.util.concurrent.atomic.AtomicReference<String> closeInfo =
      new java.util.concurrent.atomic.AtomicReference<>();

  private java.util.concurrent.CountDownLatch closeLatch;

  @BeforeMethod
  void setUp() throws IOException {
    closeInfo.set(null);
    closeLatch = new java.util.concurrent.CountDownLatch(1);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/ws", new WebSocketHandler() {

      @Override
      protected robaho.net.httpserver.websockets.WebSocket openWebSocket(HttpExchange exchange) {
        // fully qualified only for symmetry with the (absent) client
        // import; nothing shadows the same-package server class here
        return new robaho.net.httpserver.websockets.WebSocket(exchange) {

          @Override
          protected void onOpen() {}

          @Override
          protected void onClose(CloseCode code, String reason, boolean initiatedByRemote) {
            closeInfo.set("code=" + code + ", reason=" + reason + ", remote=" + initiatedByRemote);
            closeLatch.countDown();
          }

          @Override
          protected void onMessage(WebSocketFrame message) {}

        };
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

  @Test
  public void testFragmentFloodClosesWith1009() throws Exception {
    try (Socket socket = new Socket("127.0.0.1", server.getAddress().getPort())) {
      socket.setSoTimeout(5000);
      OutputStream out = socket.getOutputStream();
      out.write(("GET /ws HTTP/1.1\r\nHost: 127.0.0.1\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
          + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n")
              .getBytes(StandardCharsets.ISO_8859_1));
      out.flush();
      Assert.assertTrue(readResponseHead(socket).startsWith("HTTP/1.1 101"), "the websocket handshake must succeed");

      // frame 1: Text, fin=false; then continuation fragments. EXACTLY
      // 1025 frames total: the 1025th trips the cap (1024 already held),
      // and nothing may remain unread inbound when the server closes -
      // extra fragments in the server's receive buffer would turn the
      // close into an RST
      byte[] key = {1, 2, 3, 4};
      byte[] payload = {'x'};
      WebSocketFrame first = new WebSocketFrame(OpCode.Text, false, payload);
      first.setMaskingKey(key);
      first.write(out);
      for (int i = 1; i <= 1024; i++) {
        WebSocketFrame fragment = new WebSocketFrame(OpCode.Continuation, false, payload);
        fragment.setMaskingKey(key);
        fragment.write(out);
      }
      out.flush();

      // the connection must END: the error-path close tears the socket
      // down (clean EOF, or a reset on some platforms)
      try {
        WebSocketFrame.read(socket.getInputStream());
        Assert.fail("the fragment flood must end the connection");
      } catch (java.io.EOFException | java.net.SocketException expected) {
        // the server closed
      }

      // the code is asserted via onClose (the error-path doClose sends no
      // Close frame - the same contract WebSocketFrameValidationTest pins)
      Assert.assertTrue(closeLatch.await(5, java.util.concurrent.TimeUnit.SECONDS),
          "the fragment flood must close the connection instead of accumulating frames");
      Assert.assertTrue(String.valueOf(closeInfo.get()).contains("code=MessageTooBig"),
          "the fragment-count cap must close with MessageTooBig, got: " + closeInfo.get());
    }
  }

  private static String readResponseHead(Socket socket) throws IOException {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    byte[] buf = new byte[1024];
    while (true) {
      int n = socket.getInputStream().read(buf);
      if (n < 0) {
        throw new IOException("EOF while reading the handshake response");
      }
      bos.write(buf, 0, n);
      String s = bos.toString(StandardCharsets.ISO_8859_1);
      if (s.contains("\r\n\r\n")) {
        return s;
      }
    }
  }

}
