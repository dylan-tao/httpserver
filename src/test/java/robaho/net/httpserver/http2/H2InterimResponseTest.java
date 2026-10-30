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

/**
 * regression for the 0de50bb h2 interim-response fix: a handler sending a 1xx (e.g. 103 Early Hints) then the final
 * response must deliver BOTH - the one-shot headersSent latch used to swallow the final response. also covers the RFC
 * 9110 8.6 Content-Length suppression for 204. uses the JDK HttpClient over ALPN: it processes 1xx transparently, so a
 * swallowed final response surfaces as a hang/failure here. this was the highest-value test gap - the harness bug that
 * masked the server bug (decodeStatus huffman) was fixed, but no automated test ever exercised the fixed behavior.
 */
public class H2InterimResponseTest {

  private HttpsServer server;

  private SSLContext ctx;

  @BeforeMethod
  void setUp() throws IOException {
    ctx = new SimpleSSLContext().get();
    server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setHttpsConfigurator(new HttpsConfigurator(ctx));
    server.createContext("/hints", exchange -> {
      exchange.getResponseHeaders().add("Link", "</style.css>; rel=preload");
      exchange.sendResponseHeaders(103, -1);
      byte[] body = "final".getBytes(java.nio.charset.StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, body.length);
      try (var os = exchange.getResponseBody()) {
        os.write(body);
      }
    });
    server.createContext("/nocontent", exchange -> {
      // 204 with a positive length: h1 forces -1 and warns; h2 must
      // suppress the body AND the Content-Length header (RFC 9110 8.6)
      exchange.sendResponseHeaders(204, 100);
    });
    server.start();
  }

  @AfterMethod
  void tearDown() {
    if (server != null) {
      server.stop(0);
    }
  }

  private HttpResponse<String> send(HttpClient client, String path) throws Exception {
    return client.send(HttpRequest.newBuilder(new URI("https://127.0.0.1:" + server.getAddress().getPort() + path)).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  public void testInterimThenFinalResponse() throws Exception {
    try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).sslContext(ctx)
        .connectTimeout(Duration.ofSeconds(10)).build()) {
      HttpResponse<String> r = send(client, "/hints");
      Assert.assertEquals(r.version(), HttpClient.Version.HTTP_2, "must negotiate h2");
      Assert.assertEquals(r.statusCode(), 200, "the FINAL status must be the exchange's last word");
      Assert.assertEquals(r.body(), "final", "the final body must be delivered after the 1xx");
    }
  }

  @Test
  public void test204SuppressesContentLength() throws Exception {
    try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).sslContext(ctx)
        .connectTimeout(Duration.ofSeconds(10)).build()) {
      HttpResponse<String> r = send(client, "/nocontent");
      Assert.assertEquals(r.statusCode(), 204);
      Assert.assertFalse(r.headers().firstValue("content-length").isPresent(),
          "204 must not carry Content-Length (RFC 9110 8.6)");
    }
  }

}
