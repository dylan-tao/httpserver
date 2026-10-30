package robaho.net.httpserver.websockets;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * A rejected websocket handshake must deliver an actual HTTP error response (RFC 6455 4.2.1): the 2026-10-09 audit
 * found the error response stuck in the socket buffer while the raw request stream was closed, so the client saw a bare
 * connection close.
 */
public class WebSocketHandshakeErrorTest {

  private HttpServer server;

  @BeforeMethod
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/ws", new WebSocketHandler() {

      @Override
      protected WebSocket openWebSocket(HttpExchange exchange) {
        return null; // never reached: the handshake below is rejected
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
  public void testUnsupportedVersionGetsHttpResponse() throws Exception {
    try (Socket socket = new Socket("127.0.0.1", server.getAddress().getPort())) {
      socket.setSoTimeout(5000);
      socket.getOutputStream().write(("GET /ws HTTP/1.1\r\nHost: x\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
          + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 8\r\n\r\n").getBytes());
      socket.getOutputStream().flush();
      String head = readHead(socket.getInputStream());
      Assert.assertTrue(head.startsWith("HTTP/1.1 4"),
          "an unsupported Sec-WebSocket-Version must be answered with an HTTP error response, got: " + head);
    }
  }

  @Test
  public void testMissingKeyGetsHttpResponse() throws Exception {
    try (Socket socket = new Socket("127.0.0.1", server.getAddress().getPort())) {
      socket.setSoTimeout(5000);
      socket.getOutputStream().write(
          ("GET /ws HTTP/1.1\r\nHost: x\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" + "Sec-WebSocket-Version: 13\r\n\r\n")
              .getBytes());
      socket.getOutputStream().flush();
      String head = readHead(socket.getInputStream());
      Assert.assertTrue(head.startsWith("HTTP/1.1 4"),
          "a missing Sec-WebSocket-Key must be answered with an HTTP error response, got: " + head);
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
