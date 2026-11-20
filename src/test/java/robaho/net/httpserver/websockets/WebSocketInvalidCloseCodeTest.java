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
 * regression guard for invalid raw close codes (audit W16, 2026-11-20): a
 * received close code that can never legally appear on the wire (RFC 6455
 * 7.4.1: 1005/1006/1015, the unassigned 1016-2999) must be answered with
 * 1002, not a codeless close. the enum mapping is null for every unmapped
 * value, so the receiver could not distinguish an ILLEGAL code from a legal
 * private-use one (3000-4999, which surface codeless by design) - the raw
 * wire value now decides. red before the fix: raw 2000 was answered with a
 * codeless close. raw-socket client: the JDK WebSocket client refuses to
 * send close codes in the reserved ranges.
 */
public class WebSocketInvalidCloseCodeTest {

  private HttpServer server;

  @BeforeMethod
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/ws", new WebSocketHandler() {

      @Override
      protected robaho.net.httpserver.websockets.WebSocket openWebSocket(HttpExchange exchange) {
        return new robaho.net.httpserver.websockets.WebSocket(exchange) {

          @Override
          protected void onOpen() {}

          @Override
          protected void onClose(CloseCode code, String reason, boolean initiatedByRemote) {}

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
  public void testUnassignedCloseCodeAnsweredWith1002() throws Exception {
    try (Socket socket = new Socket("127.0.0.1", server.getAddress().getPort())) {
      socket.setSoTimeout(5000);
      OutputStream out = socket.getOutputStream();
      out.write(("GET /ws HTTP/1.1\r\nHost: 127.0.0.1\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
          + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n")
              .getBytes(StandardCharsets.ISO_8859_1));
      out.flush();
      Assert.assertTrue(readResponseHead(socket).startsWith("HTTP/1.1 101"), "the websocket handshake must succeed");

      // a masked Close frame carrying the unassigned code 2000
      byte[] key = {1, 2, 3, 4};
      WebSocketFrame close = new WebSocketFrame(OpCode.Close, true, new byte[] {0x07, (byte) 0xD0});
      close.setMaskingKey(key);
      close.write(out);

      // the server must answer with a Close frame carrying 1002
      WebSocketFrame reply = WebSocketFrame.read(socket.getInputStream());
      Assert.assertEquals(reply.getOpCode(), OpCode.Close, "the reply must be a Close frame");
      var replyClose = new CloseFrame(reply);
      Assert.assertEquals(replyClose.getCloseCode(), CloseCode.ProtocolError,
          "an unassigned close code must be answered with 1002, got: " + replyClose.getCloseCode());
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
