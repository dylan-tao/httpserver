package robaho.net.httpserver.http2;

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
 * Direct assertions for all four request-body-cap enforcement paths
 * (robaho.net.httpserver.maxRequestBodySize, set to 1MB for the test JVM):
 * 1. h1 + declared Content-Length -> 413 response (RequestRejectedException)
 * 2. h1 + chunked (no declared length) -> the incremental cap in
 * LeftOverInputStream kills the exchange mid-body (5xx/connection
 * close, NOT a 413 - the framing is already desynced at that point)
 * 3. h2 + declared content-length -> 413 via rejectStream at dispatch
 * 4. h2 + DATA without declared length -> RST_STREAM ENHANCE_YOUR_CALM
 * the four paths deliberately have four different observable behaviors;
 * these tests fixate each so a refactor cannot silently drop one.
 */
public class RequestBodyCapTest {

  private static final int CAP = 1024 * 1024; // must match the surefire property

  private HttpServer server;

  @BeforeMethod
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/echo", exchange -> {
      byte[] body = exchange.getRequestBody().readAllBytes();
      exchange.sendResponseHeaders(200, body.length);
      try (var os = exchange.getResponseBody()) {
        os.write(body);
      }
    });
  }

  @AfterMethod
  void tearDown() {
    if (server != null) {
      server.stop(0);
    }
  }

  /**
   * path 1: a declared Content-Length above the cap is rejected up front
   * with 413 - no body bytes need to arrive
   */
  @Test
  public void testH1DeclaredLengthRejectedWith413() throws Exception {
    server.start();
    try (Socket socket = new Socket("127.0.0.1", server.getAddress().getPort())) {
      socket.setSoTimeout(8000);
      socket.getOutputStream().write(("POST /echo HTTP/1.1\r\nHost: x\r\nContent-Length: " + (CAP * 2) + "\r\n\r\n")
          .getBytes(StandardCharsets.ISO_8859_1));
      socket.getOutputStream().flush();
      String head = readHead(socket.getInputStream());
      Assert.assertTrue(head.startsWith("HTTP/1.1 413"), "declared over-cap Content-Length must get 413, got: " + head);
    }
  }

  /**
   * path 2: a chunked body that grows past the cap incrementally cannot
   * answer 413 (the framing is desynced mid-stream); the exchange must
   * fail and the connection must not serve a smuggled follow-up request
   */
  @Test
  public void testH1ChunkedIncrementalKillsConnection() throws Exception {
    server.start();
    try (Socket socket = new Socket("127.0.0.1", server.getAddress().getPort())) {
      socket.setSoTimeout(8000);
      var out = socket.getOutputStream();
      out.write("POST /echo HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
      byte[] chunk = new byte[16384];
      int sent = 0;
      try {
        while (sent <= CAP + chunk.length) {
          out.write((Integer.toHexString(chunk.length) + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
          out.write(chunk);
          out.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
          out.flush();
          sent += chunk.length;
        }
      } catch (IOException expected) {
        // the server closing mid-upload surfaces as a write failure
      }
      String head;
      try {
        head = readHeadOrNull(socket.getInputStream());
      } catch (java.net.SocketException resetExpected) {
        // the server killed the connection mid-body: the intended
        // incremental-cap behavior, nothing left to assert
        return;
      }
      if (head != null) {
        Assert.assertFalse(head.startsWith("HTTP/1.1 200"), "an over-cap chunked body must not be served, got: " + head);
        Assert.assertFalse(head.startsWith("HTTP/1.1 413"), "the incremental h1 path kills the exchange, it cannot answer 413");
      }
      // the connection must be dead: no smuggled second response
      socket.setSoTimeout(3000);
      try {
        String next = readHeadOrNull(socket.getInputStream());
        Assert.assertNull(next, "connection must be closed after the incremental cap trips");
      } catch (java.net.SocketTimeoutException ignored) {
        // no further bytes: fine
      }
    }
  }

  /**
   * path 3: an h2 request with a declared content-length above the cap is
   * rejected at dispatch with a 413 response on that stream
   */
  @Test
  public void testH2DeclaredLengthRejectedWith413() throws Exception {
    server.start();
    try (H2Client client = new H2Client(server.getAddress().getPort())) {
      int stream = client.sendRequest("POST", "/echo", false, "content-length", String.valueOf(CAP * 2));
      var response = client.awaitResponse(stream);
      Assert.assertFalse(response.goaway(), "a per-stream cap must not kill the connection");
      Assert.assertNull(response.rstErrorCode(), "declared-length rejection is a 413 response, not a RST");
      Assert.assertEquals(response.status(), 413, "declared over-cap content-length must get 413");
    }
  }

  /**
   * path 4: h2 DATA frames without a declared length trip the incremental
   * cap in the stream: RST_STREAM ENHANCE_YOUR_CALM, connection survives
   */
  @Test
  public void testH2IncrementalResetWithEnhanceYourCalm() throws Exception {
    server.start();
    try (H2Client client = new H2Client(server.getAddress().getPort())) {
      int stream = client.sendRequest("POST", "/echo", false);
      long streamWindow = 65535, connWindow = 65535;
      byte[] chunk = new byte[16384];
      int sent = 0;
      int target = CAP + chunk.length;
      while (sent < target) {
        if (streamWindow < chunk.length || connWindow < chunk.length) {
          int[] wu = client.readWindowUpdate();
          if (wu[0] == 0) {
            connWindow += wu[1];
          } else if (wu[0] == stream) {
            streamWindow += wu[1];
          }
          continue;
        }
        client.sendData(stream, chunk, false);
        streamWindow -= chunk.length;
        connWindow -= chunk.length;
        sent += chunk.length;
      }
      var response = client.awaitResponse(stream);
      Assert.assertFalse(response.goaway(), "the incremental cap must stay stream-scoped");
      Assert.assertNotNull(response.rstErrorCode(), "the incremental cap must RST the stream");
      Assert.assertEquals(response.rstErrorCode().intValue(), 0x0b,
          "the incremental h2 cap uses ENHANCE_YOUR_CALM (RFC 7540 6.5.2 DoS mitigation)");
      // the connection must survive for other streams
      int ok = client.sendRequest("GET", "/echo", true);
      var okResponse = client.awaitResponse(ok);
      Assert.assertEquals(okResponse.status(), 200, "connection must stay usable after a stream cap reset");
    }
  }

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

}
