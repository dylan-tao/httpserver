package robaho.net.httpserver.extras;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * End-to-end checks for the ProxyHandler forwarding path the proxied response must not carry both Content-length and
 * Transfer-encoding, and a request whose headers are all restricted must still be forwarded (previously an
 * IllegalArgumentException).
 */
public class ProxyForwardingTest {

  private HttpServer backend;

  private HttpServer front;

  @BeforeMethod
  void setUp() throws IOException {
    backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    backend.createContext("/", ProxyForwardingTest::serveFixedLength);
    backend.start();

    var target = new ProxyHandler.HostPort("127.0.0.1", backend.getAddress().getPort(), "http");
    front = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    front.createContext("/", new ProxyHandler(target));
    front.start();
  }

  @AfterMethod
  void tearDown() {
    if (front != null) {
      front.stop(0);
    }
    if (backend != null) {
      backend.stop(0);
    }
  }

  @Test
  public void testForwardedResponseHasNoContentLengthAndChunked() throws Exception {
    String response = proxyGet("User-Agent: proxy-test\r\n");
    String head = headOf(response);
    Assert.assertTrue(head.startsWith("HTTP/1.1 200"), head);
    boolean hasContentLength = head.toLowerCase().contains("content-length");
    boolean hasTransferEncoding = head.toLowerCase().contains("transfer-encoding");
    Assert.assertFalse(hasContentLength && hasTransferEncoding,
        "proxied response must not carry both Content-length and Transfer-encoding: " + head);
    Assert.assertTrue(response.contains("hello world"), "body must be forwarded");
  }

  @Test
  public void testRequestWithOnlyRestrictedHeaders() throws Exception {
    // Host and Connection are both stripped before forwarding: the empty
    // forwarded-header array must not break the upstream request
    String response = proxyGet("");
    Assert.assertTrue(response.contains("hello world"),
        "request with only restricted headers must still be proxied: " + response);
  }

  /**
   * a completed CONNECT tunnel must not fall through into the forwarding path (an earlier cleanup removed the old
   * finally-return): the tunnel itself must round-trip bytes in both directions
   */
  @Test
  public void testConnectTunnelRoundTrip() throws Exception {
    try (java.net.ServerSocket target = new java.net.ServerSocket(0, 0, java.net.InetAddress.getLoopbackAddress())) {
      Thread echoThread = new Thread(() -> {
        try (java.net.Socket t = target.accept()) {
          byte[] b = new byte[1024];
          int n;
          while ((n = t.getInputStream().read(b)) != -1) {
            t.getOutputStream().write(b, 0, n);
            t.getOutputStream().flush();
          }
        } catch (IOException ignored) {}
      });
      echoThread.setDaemon(true);
      echoThread.start();

      // the default authorizeConnect denies; allow this test's tunnel
      ProxyHandler allowing = new ProxyHandler() {

        @Override
        protected boolean authorizeConnect(HttpExchange exchange) {
          return true;
        }

      };
      HttpServer proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      proxy.createContext("/", allowing);
      proxy.start();
      // NOTE: the CONNECT target must be a hostname, not an IP
      // literal: the generic request-line URI parser rejects
      // authority-form IP targets (new URI("127.0.0.1:443") throws -
      // not a valid scheme) before ProxyHandler ever sees them
      String host = java.net.InetAddress.getLoopbackAddress().getHostName();
      try (Socket socket = new Socket("127.0.0.1", proxy.getAddress().getPort())) {
        socket.setSoTimeout(10_000);
        socket.getOutputStream().write(("CONNECT " + host + ":" + target.getLocalPort() + " HTTP/1.1\r\n" + "Host: " + host
            + ":" + target.getLocalPort() + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        socket.getOutputStream().flush();
        String head = readHead(socket);
        Assert.assertTrue(head.startsWith("HTTP/1.1 200"), "CONNECT must be tunneled: " + head);
        // bytes must flow both ways through the tunnel
        socket.getOutputStream().write("ping".getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().flush();
        byte[] b = new byte[4];
        int off = 0;
        while (off < 4) {
          int n = socket.getInputStream().read(b, off, 4 - off);
          if (n == -1) {
            throw new IOException("tunnel closed after " + off + " of 4 bytes");
          }
          off += n;
        }
        Assert.assertEquals(new String(b, StandardCharsets.UTF_8), "ping", "tunnel must echo");
      } finally {
        proxy.stop(0);
      }
      echoThread.join(5000);
    }
  }

  /** send an absolute-form GET through the proxy and return the raw response */
  private String proxyGet(String extraHeaders) throws IOException {
    String request = "GET http://127.0.0.1:" + backend.getAddress().getPort() + "/ HTTP/1.1\r\n" + "Host: 127.0.0.1:"
        + backend.getAddress().getPort() + "\r\n" + extraHeaders + "Connection: close\r\n\r\n";
    try (Socket socket = new Socket("127.0.0.1", front.getAddress().getPort())) {
      socket.setSoTimeout(10_000);
      OutputStream out = socket.getOutputStream();
      out.write(request.getBytes(StandardCharsets.ISO_8859_1));
      out.flush();
      InputStream in = socket.getInputStream();
      ByteArrayOutputStream buf = new ByteArrayOutputStream();
      byte[] b = new byte[4096];
      int n;
      while ((n = in.read(b)) != -1) {
        buf.write(b, 0, n);
      }
      return buf.toString(StandardCharsets.ISO_8859_1);
    }
  }

  private static String headOf(String response) {
    int idx = response.indexOf("\r\n\r\n");
    return idx >= 0 ? response.substring(0, idx) : response;
  }

  private static String readHead(Socket socket) throws IOException {
    InputStream in = socket.getInputStream();
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

  private static void serveFixedLength(HttpExchange exchange) throws IOException {
    byte[] body = "hello world".getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(200, body.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(body);
    }
  }

}
