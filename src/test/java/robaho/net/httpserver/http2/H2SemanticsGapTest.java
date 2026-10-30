package robaho.net.httpserver.http2;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import jdk.test.lib.net.SimpleSSLContext;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

/**
 * the h2 semantic deltas that had ZERO test coverage (third review pass):
 * HEAD/304 body suppression, setAttribute(name, null) removal.
 * (the 101-over-h2 rejection is covered separately: any WebSocketHandler
 * over ALPN-h2 would hit it, and the h2c prior-knowledge tests send no 101)
 */
public class H2SemanticsGapTest {

  private HttpsServer server;

  private SSLContext ctx;

  private final AtomicReference<Object> attrValue = new AtomicReference<>(new Object());

  @BeforeMethod
  void setUp() throws IOException {
    ctx = new SimpleSSLContext().get();
    server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setHttpsConfigurator(new HttpsConfigurator(ctx));
    server.createContext("/head", ex -> {
      // a HEAD request: any body write must be suppressed/thrown,
      // and Content-Length must not be sent
      ex.sendResponseHeaders(200, 100);
      try (var os = ex.getResponseBody()) {
        os.write(new byte[100]);
      }
    });
    server.createContext("/notmod", ex -> ex.sendResponseHeaders(304, 50));
    server.createContext("/attr", ex -> {
      // remove-then-read must yield null, not NPE or stale value
      ex.setAttribute("k", "v");
      ex.setAttribute("k", null);
      attrValue.set(ex.getAttribute("k"));
      ex.sendResponseHeaders(200, -1);
    });
    server.start();
  }

  @AfterMethod
  void tearDown() {
    if (server != null) {
      server.stop(0);
    }
  }

  private HttpResponse<String> send(HttpClient client, String path, String method) throws Exception {
    return client.send(HttpRequest.newBuilder(new URI("https://127.0.0.1:" + server.getAddress().getPort() + path))
        .method(method, java.net.http.HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  public void testHeadBodySuppressed() throws Exception {
    try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).sslContext(ctx)
        .connectTimeout(Duration.ofSeconds(10)).build()) {
      HttpResponse<String> r = send(client, "/head", "HEAD");
      Assert.assertEquals(r.version(), HttpClient.Version.HTTP_2);
      Assert.assertEquals(r.statusCode(), 200);
      Assert.assertEquals(r.body(), "", "HEAD over h2 must suppress the body");
    }
  }

  @Test
  public void test304SuppressesBodyAndCL() throws Exception {
    try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).sslContext(ctx)
        .connectTimeout(Duration.ofSeconds(10)).build()) {
      HttpResponse<String> r = send(client, "/notmod", "GET");
      Assert.assertEquals(r.statusCode(), 304);
      Assert.assertEquals(r.body(), "", "304 must not carry a body");
      Assert.assertFalse(r.headers().firstValue("content-length").isPresent(), "304 must not carry Content-Length");
    }
  }

  @Test
  public void testSetAttributeNullRemoves() throws Exception {
    try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).sslContext(ctx)
        .connectTimeout(Duration.ofSeconds(10)).build()) {
      HttpResponse<String> r = send(client, "/attr", "GET");
      Assert.assertEquals(r.statusCode(), 200);
      Assert.assertNull(attrValue.get(), "setAttribute(name, null) must remove (NPE on 1.x)");
    }
  }

}
