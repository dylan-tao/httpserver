package robaho.net.httpserver.websockets;

import com.sun.net.httpserver.HttpServer;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * regression: "Connection: keep-alive, Upgrade" (the token-list form Firefox
 * sends, RFC 7230 7) must complete the websocket handshake AND keep the
 * session alive. the old whole-value equalsIgnoreCase let the 101 succeed
 * but handed the websocket a 0-length fixed stream - instant EOF after the
 * handshake for every token-list client.
 */
public class TokenListConnectionUpgradeTest {

  private HttpServer server;

  private final CountDownLatch messageLatch = new CountDownLatch(1);

  private final AtomicReference<String> received = new AtomicReference<>();

  @BeforeMethod
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/ws", new WebSocketHandler() {

      @Override
      protected WebSocket openWebSocket(com.sun.net.httpserver.HttpExchange exchange) {
        WebSocket ws = new WebSocket(exchange) {

          @Override
          protected void onClose(CloseCode code, String reason, boolean initiatedByRemote) {}

          @Override
          protected void onMessage(WebSocketFrame message) {
            received.set(message.getTextPayload());
            messageLatch.countDown();
          }

        };
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

  @Test
  public void testTokenListConnectionHeaderKeepsSessionAlive() throws Exception {
    try (Socket s = new Socket("127.0.0.1", server.getAddress().getPort())) {
      s.setSoTimeout(8000);
      OutputStream out = s.getOutputStream();
      // the token-list form: Firefox's exact header shape
      out.write(("GET /ws HTTP/1.1\r\nHost: x\r\nUpgrade: websocket\r\n" + "Connection: keep-alive, Upgrade\r\n"
          + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" + "Sec-WebSocket-Version: 13\r\n\r\n")
              .getBytes(StandardCharsets.ISO_8859_1));
      out.flush();

      String head = readHead(s.getInputStream());
      Assert.assertTrue(head.startsWith("HTTP/1.1 101"), "handshake must succeed: " + head);

      // a masked text frame AFTER the 101 must be delivered (the old
      // bug: instant EOF right after the handshake)
      byte[] mask = {1, 2, 3, 4};
      byte[] payload = "hello".getBytes(StandardCharsets.UTF_8);
      out.write(0x81); // FIN + text
      out.write(0x80 | payload.length); // masked, len 5
      out.write(mask);
      for (int i = 0; i < payload.length; i++) {
        out.write(payload[i] ^ mask[i & 3]);
      }
      out.flush();

      Assert.assertTrue(messageLatch.await(5, TimeUnit.SECONDS), "the session must survive the handshake: no message received");
      Assert.assertEquals(received.get(), "hello");
    }
  }

  private static String readHead(InputStream in) throws IOException {
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

}
