package robaho.net.httpserver.websockets;

import com.sun.net.httpserver.Filter;
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
import java.util.concurrent.atomic.AtomicReference;

/**
 * regression guard for a null openWebSocket (audit W15, 2026-11-20): the
 * 101 was already on the wire, and the late-500 attempt died on the
 * "headers already sent" IOException - a user handler legitimately
 * rejecting the handshake produced an exception through the filter chain
 * instead of a clean teardown. the exchange now closes cleanly right
 * after the 101; a recording filter must see no exception. red before
 * the fix: the IOException("headers already sent") escaped to the filter.
 */
public class WebSocketOpenNullTest {

  private HttpServer server;

  private final AtomicReference<Throwable> filterCaught = new AtomicReference<>();

  @BeforeMethod
  void setUp() throws IOException {
    filterCaught.set(null);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var context = server.createContext("/ws", new WebSocketHandler() {

      @Override
      protected robaho.net.httpserver.websockets.WebSocket openWebSocket(HttpExchange exchange) {
        // the user handler's own rejection of the handshake
        return null;
      }

    });
    // record anything escaping the handler through the chain
    context.getFilters().add(new Filter() {

      @Override
      public void doFilter(HttpExchange exchange, Filter.Chain chain) throws IOException {
        try {
          chain.doFilter(exchange);
        } catch (Throwable t) {
          filterCaught.compareAndSet(null, t);
          throw t;
        }
      }

      @Override
      public String description() {
        return "exception recorder";
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
  public void testNullOpenWebSocketClosesCleanly() throws Exception {
    try (Socket socket = new Socket("127.0.0.1", server.getAddress().getPort())) {
      socket.setSoTimeout(5000);
      OutputStream out = socket.getOutputStream();
      out.write(("GET /ws HTTP/1.1\r\nHost: 127.0.0.1\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
          + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n")
              .getBytes(StandardCharsets.ISO_8859_1));
      out.flush();

      // the client sees the 101, then the tunnel ends (EOF): the
      // handshake completed, the null rejection ends it right after
      String head = readResponseHead(socket);
      Assert.assertTrue(head.startsWith("HTTP/1.1 101"), "the 101 must be sent: " + head);
      int trailing = socket.getInputStream().read();
      Assert.assertEquals(trailing, -1, "the connection must close after the 101");

      // the teardown must be clean: no exception may escape to filters
      Assert.assertNull(filterCaught.get(),
          "a null openWebSocket must not throw through the chain, caught: " + filterCaught.get());
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
