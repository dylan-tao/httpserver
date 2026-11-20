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
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/**
 * regression guard for h1 Connection-header token-list parsing (audit W1,
 * 2026-11-20): Connection is a comma-separated token list and may arrive
 * as several header lines - a "close" token anywhere (e.g.
 * "Connection: keep-alive, close") must close the connection after the
 * response. the old parser compared only the FIRST header line's value as
 * a whole string, so such a request kept the connection alive and the
 * response carried no "Connection: close" - a differential against
 * RFC 9112 9.3-compliant front proxies (request smuggling surface).
 * <p>
 * EXPECT-RED until the W1 fix lands (token-list parsing of all values);
 * wire into testng.xml together with that fix. the single-value control
 * case is green before and after.
 */
public class ConnectionTokenCloseTest {

  private HttpServer server;

  private int port;

  @BeforeMethod
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
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
  public void testCloseTokenInListClosesConnection() throws Exception {
    Result result = request("GET / HTTP/1.1\r\nHost: x\r\nConnection: keep-alive, close\r\n\r\n");
    Assert.assertTrue(result.response().toLowerCase().contains("connection: close"),
        "a 'close' token in the Connection list must be honored: " + result.response());
    Assert.assertTrue(result.serverClosed(), "the connection must be closed after the response");
  }

  @Test
  public void testCloseTokenInSecondHeaderLineClosesConnection() throws Exception {
    Result result = request("GET / HTTP/1.1\r\nHost: x\r\nConnection: keep-alive\r\nConnection: close\r\n\r\n");
    Assert.assertTrue(result.response().toLowerCase().contains("connection: close"),
        "a 'close' token on any Connection header line must be honored: " + result.response());
    Assert.assertTrue(result.serverClosed(), "the connection must be closed after the response");
  }

  /** control: the single-value form keeps working (green before and after the fix) */
  @Test
  public void testSingleCloseValueClosesConnection() throws Exception {
    Result result = request("GET / HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n");
    Assert.assertTrue(result.response().toLowerCase().contains("connection: close"),
        "the single-value close form must keep working: " + result.response());
    Assert.assertTrue(result.serverClosed(), "the connection must be closed after the response");
  }

  private record Result(String response, boolean serverClosed) {}

  /**
   * send a raw request, read the response until EOF (the server closed the
   * connection) or a 2s timeout (the server kept it alive)
   */
  private Result request(String raw) throws Exception {
    try (Socket socket = new Socket("127.0.0.1", port)) {
      socket.setSoTimeout(2000);
      OutputStream out = socket.getOutputStream();
      out.write(raw.getBytes(StandardCharsets.ISO_8859_1));
      out.flush();
      ByteArrayOutputStream bos = new ByteArrayOutputStream();
      byte[] buf = new byte[4096];
      boolean serverClosed = false;
      try {
        int n;
        while ((n = socket.getInputStream().read(buf)) != -1) {
          bos.write(buf, 0, n);
        }
        serverClosed = true; // clean EOF: the server closed the connection
      } catch (SocketTimeoutException stillOpen) {
        // no EOF within the timeout: the server kept the connection alive
      }
      return new Result(bos.toString(StandardCharsets.ISO_8859_1), serverClosed);
    }
  }

}
