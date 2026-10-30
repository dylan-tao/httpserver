package robaho.net.httpserver;

import com.sun.net.httpserver.HttpServer;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * HTTP/1.1 chunked request bodies with trailer field lines (RFC 9112 7.1.2): the trailer must be consumed without
 * desyncing the connection, and malformed trailers must fail loudly rather than poison keep-alive. the runnable suite
 * had zero chunked coverage (the old chunked tests live in test_mains, which surefire never runs).
 */
public class ChunkedTrailerTest {

  private HttpServer server;

  @BeforeMethod
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
  }

  @AfterMethod
  void tearDown() {
    if (server != null) {
      server.stop(0);
    }
  }

  /**
   * a chunked body with trailer lines must be delivered completely, and the connection must stay usable: a trailer
   * parser that over- or under-reads would desync the next request on this connection
   */
  @Test
  public void testChunkedTrailerThenConnectionReuse() throws Exception {
    server.createContext("/echo", exchange -> {
      byte[] body = exchange.getRequestBody().readAllBytes();
      exchange.getResponseHeaders().set("X-Len", String.valueOf(body.length));
      exchange.sendResponseHeaders(200, body.length);
      try (var os = exchange.getResponseBody()) {
        os.write(body);
      }
    });
    server.start();
    try (Socket socket = new Socket("127.0.0.1", server.getAddress().getPort())) {
      socket.setSoTimeout(8000);
      var out = socket.getOutputStream();
      out.write("POST /echo HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
      out.write("5\r\nhello\r\n6\r\n world\r\n".getBytes(StandardCharsets.ISO_8859_1));
      // the final zero chunk, two trailer field lines, then the empty
      // line that terminates the trailer section
      out.write("0\r\nX-Checksum: abc\r\nX-Other: def\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
      out.flush();

      String head = readHead(socket.getInputStream());
      Assert.assertTrue(head.startsWith("HTTP/1.1 200"), head);
      Assert.assertTrue(head.toLowerCase().contains("x-len: 11"), head);
      Assert.assertEquals(readFully(socket.getInputStream(), 11), "hello world",
          "the chunked body must be reassembled in full");

      // keep-alive: the trailer bytes must not leak into this request
      out.write("GET /echo HTTP/1.1\r\nHost: x\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
      out.flush();
      String head2 = readHead(socket.getInputStream());
      Assert.assertTrue(head2.startsWith("HTTP/1.1 200"), "connection must be reusable after a trailer, got: " + head2);
      Assert.assertTrue(head2.toLowerCase().contains("x-len: 0"), head2);
    }
  }

  /**
   * a trailer section exceeding the 100-line bound must fail the request (error or connection close), and the excess
   * lines must never be parsed as a fresh request
   */
  @Test
  public void testTooManyTrailerLinesRejected() throws Exception {
    server.createContext("/echo", exchange -> {
      byte[] body = exchange.getRequestBody().readAllBytes();
      exchange.sendResponseHeaders(200, body.length);
      try (var os = exchange.getResponseBody()) {
        os.write(body);
      }
    });
    server.start();
    try (Socket socket = new Socket("127.0.0.1", server.getAddress().getPort())) {
      socket.setSoTimeout(8000);
      var out = socket.getOutputStream();
      out.write("POST /echo HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
      out.write("0\r\n".getBytes(StandardCharsets.ISO_8859_1));
      StringBuilder junk = new StringBuilder();
      for (int i = 0; i < 150; i++) {
        junk.append("X-Junk-").append(i).append(": v\r\n");
      }
      // a smuggled request hidden after the bound: it must never be
      // served as a request
      junk.append("GET /echo HTTP/1.1\r\nHost: x\r\nContent-Length: 0\r\n\r\n");
      out.write(junk.toString().getBytes(StandardCharsets.ISO_8859_1));
      out.flush();

      // the request must fail: either an error status or a connection
      // close. the smuggled request must not produce a second 200
      String head;
      try {
        head = readHeadOrNull(socket.getInputStream());
      } catch (java.net.SocketException resetExpected) {
        // the server killed the connection mid-junk: exactly the
        // intended behavior, nothing left to assert
        return;
      }
      if (head != null) {
        Assert.assertTrue(head.startsWith("HTTP/1.1 5") || head.startsWith("HTTP/1.1 4"),
            "oversized trailer must fail the request, got: " + head);
        Assert.assertFalse(head.startsWith("HTTP/1.1 200"), "smuggled request must not be served");
      }
      // if the server answered the failed request, the connection must
      // be closed afterwards - reading again must hit EOF quickly
      socket.setSoTimeout(3000);
      boolean sawSecond200 = false;
      try {
        String next = readHeadOrNull(socket.getInputStream());
        if (next != null && next.startsWith("HTTP/1.1 200")) {
          sawSecond200 = true;
        }
      } catch (java.net.SocketTimeoutException ignored) {
        // no further bytes: fine
      }
      Assert.assertFalse(sawSecond200, "the smuggled request after the trailer bound must not be served");
    }
  }

  /**
   * a bare CR inside a trailer line is a framing error (RFC 9112 2.2): it must be rejected, not silently swallowed
   */
  @Test
  public void testBareCrInTrailerRejected() throws Exception {
    server.createContext("/echo", exchange -> {
      byte[] body = exchange.getRequestBody().readAllBytes();
      exchange.sendResponseHeaders(200, body.length);
      try (var os = exchange.getResponseBody()) {
        os.write(body);
      }
    });
    server.start();
    try (Socket socket = new Socket("127.0.0.1", server.getAddress().getPort())) {
      socket.setSoTimeout(8000);
      var out = socket.getOutputStream();
      out.write("POST /echo HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
      out.write("0\r\nX-Bad: a\rX\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
      out.flush();

      String head;
      try {
        head = readHeadOrNull(socket.getInputStream());
      } catch (java.net.SocketException resetExpected) {
        return;
      }
      if (head != null) {
        Assert.assertFalse(head.startsWith("HTTP/1.1 200"), "a bare CR in the trailer must fail the request, got: " + head);
      }
    }
  }

  /** read the response head (up to the blank line); null on EOF */
  private static String readHeadOrNull(InputStream in) throws IOException {
    StringBuilder sb = new StringBuilder();
    int c;
    while ((c = in.read()) != -1) {
      sb.append((char) c);
      int n = sb.length();
      if (n >= 4 && sb.charAt(n - 4) == '\r' && sb.charAt(n - 3) == '\n' && sb.charAt(n - 2) == '\r'
          && sb.charAt(n - 1) == '\n') {
        return sb.toString();
      }
    }
    return sb.isEmpty() ? null : sb.toString();
  }

  private static String readHead(InputStream in) throws IOException {
    String head = readHeadOrNull(in);
    Assert.assertNotNull(head, "connection closed before the response head");
    return head;
  }

  private static String readFully(InputStream in, int length) throws IOException {
    byte[] buffer = new byte[length];
    int off = 0;
    while (off < length) {
      int n = in.read(buffer, off, length - off);
      if (n == -1) {
        throw new IOException("EOF after " + off + " of " + length + " bytes");
      }
      off += n;
    }
    return new String(buffer, StandardCharsets.UTF_8);
  }

}
