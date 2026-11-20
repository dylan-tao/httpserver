package robaho.net.httpserver;

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
 * regression guard for an EOF-truncated header block (audit W2, 2026-11-20):
 * a peer that closes the connection mid-header-line (e.g. after
 * "GET / HTTP/1.1\r\nHost: x\r\nContent-Length: 10" with no CRLF) left the
 * pending header dropped and the request dispatched as if complete - a
 * Content-Length read as 0 (body desync / request-smuggling differential
 * against strict front proxies). RFC 9112 3 requires the message to be
 * rejected. red before the fix: the request was served with 200.
 */
public class TruncatedHeaderBlockTest {

  private HttpServer server;

  private int port;

  @BeforeMethod
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      // a desync would surface here as a bogus second request; answer so
      // the test observes the response the truncated request got
      exchange.sendResponseHeaders(200, 2);
      try (var os = exchange.getResponseBody()) {
        os.write("ok".getBytes(StandardCharsets.UTF_8));
      }
    });
    server.start();
    port = server.getAddress().getPort();
  }

  @AfterMethod
  void tearDown() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  public void testTruncatedHeaderLineIsRejected() throws Exception {
    try (Socket socket = new Socket("127.0.0.1", port)) {
      socket.setSoTimeout(5000);
      OutputStream out = socket.getOutputStream();
      // a request whose last header line has NO CRLF, then the peer
      // half-closes: the server reads EOF mid-header-line
      out.write("GET / HTTP/1.1\r\nHost: x\r\nContent-Length: 1".getBytes(StandardCharsets.ISO_8859_1));
      out.flush();
      socket.shutdownOutput();

      String response = readAll(socket);
      Assert.assertTrue(response.startsWith("HTTP/1.1 400"),
          "an EOF-truncated header block must be rejected with 400, got: " + oneLine(response));
    }
  }

  /** control: a cleanly terminated header block is a complete request */
  @Test
  public void testCompleteRequestServed() throws Exception {
    try (Socket socket = new Socket("127.0.0.1", port)) {
      socket.setSoTimeout(5000);
      OutputStream out = socket.getOutputStream();
      out.write("GET / HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
      out.flush();

      // read the status line only: the connection stays keep-alive, so
      // there is no EOF to wait for (the truncated case's400 carries
      // Connection: close and DOES end with EOF)
      String statusLine = readLine(socket);
      Assert.assertTrue(statusLine.startsWith("HTTP/1.1 200"), "a complete request must be served, got: " + statusLine);
    }
  }

  private static String readAll(Socket socket) throws IOException {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    byte[] buf = new byte[4096];
    int n;
    while ((n = socket.getInputStream().read(buf)) != -1) {
      bos.write(buf, 0, n);
    }
    return bos.toString(StandardCharsets.ISO_8859_1);
  }

  private static String oneLine(String response) {
    int i = response.indexOf("\r\n");
    return i < 0 ? response : response.substring(0, i);
  }

  /** read the first CRLF-terminated line of the response */
  private static String readLine(Socket socket) throws IOException {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    int c;
    while ((c = socket.getInputStream().read()) != -1) {
      bos.write(c);
      if (c == '\n') {
        break;
      }
    }
    return bos.toString(StandardCharsets.ISO_8859_1).trim();
  }

}
