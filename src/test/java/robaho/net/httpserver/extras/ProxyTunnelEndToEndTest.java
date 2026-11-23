package robaho.net.httpserver.extras;

import com.sun.net.httpserver.BasicAuthenticator;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import jdk.test.lib.net.SimpleSSLContext;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.testng.Assert.assertEquals;

/**
 * TestNG ports of the three standalone test_mains harnesses (batch 4 of the
 * re-audit, 2026-11-23): TunnelProxyTest, ProxyHandlerTest and
 * SecureProxyHandlerTest ran only via their main() methods - never in the
 * suite - so the JDK-client end-to-end paths (an HTTPS request tunneled
 * through CONNECT, plain forwarding with a POST echo, and a BasicAuth
 * challenge answered THROUGH the proxy) had no coverage. the raw-socket
 * siblings (ProxyForwardingTest, ConnectTunnelTest) pin the wire shape;
 * these pin what a real client experiences through the proxy.
 */
public class ProxyTunnelEndToEndTest {

  private static final String REALM = "U\u00ffU@realm"; // non-ASCII, from the original

  private HttpServer backend;

  private HttpServer proxy;

  private HttpsServer tlsBackend;

  /**
   * the backend of the original three harnesses: 200 + "hello", echoing a
   * POST body back
   */
  private static HttpHandler helloOrEcho() {
    return (HttpExchange exchange) -> {
      exchange.sendResponseHeaders(200, 0);
      if (exchange.getRequestMethod().equals("POST")) {
        exchange.getRequestBody().transferTo(exchange.getResponseBody());
        exchange.getResponseBody().close();
      } else {
        try (var os = exchange.getResponseBody()) {
          os.write("hello".getBytes(StandardCharsets.ISO_8859_1));
        }
      }
    };
  }

  @BeforeMethod
  void setUp() throws IOException {
    // per-method, not per-class: each test builds the servers it needs and
    // stop(0)s them itself; the fields just give tearDown a safety net
    backend = null;
    proxy = null;
    tlsBackend = null;
  }

  @AfterMethod
  void tearDown() {
    // every test closes its own servers in a finally; this is only the
    // net for a failure path that threw past it
    if (proxy != null) {
      proxy.stop(0);
    }
    if (backend != null) {
      backend.stop(0);
    }
    if (tlsBackend != null) {
      tlsBackend.stop(0);
    }
  }

  @Test
  public void testHttpsOverConnectTunnel() throws Exception {
    SSLContext sslContext = new SimpleSSLContext().get();

    tlsBackend = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 100);
    tlsBackend.setHttpsConfigurator(new HttpsConfigurator(sslContext));
    tlsBackend.createContext("/test", helloOrEcho());

    proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 100);
    proxy.createContext("/", new ProxyHandler() {

      @Override
      protected boolean authorizeConnect(HttpExchange exchange) {
        // this port exercises tunneling only; authorization is
        // ConnectDefaultDenyTest's subject
        return true;
      }

    });

    tlsBackend.start();
    proxy.start();

    // the client trusts the SimpleSSLContext CA and tunnels HTTPS through
    // the plain proxy: the CONNECT handshake, the TLS session inside the
    // tunnel, and the response all have to survive the proxy's piping
    try (HttpClient client =
        HttpClient.newBuilder().proxy(ProxySelector.of(proxy.getAddress())).sslContext(sslContext).build()) {
      HttpRequest request = HttpRequest.newBuilder()
          .uri(tlsBackend.getAddress().getHostName().equals("127.0.0.1")
              ? java.net.URI.create("https://localhost:" + tlsBackend.getAddress().getPort() + "/test")
              : java.net.URI.create(
                  "https://" + tlsBackend.getAddress().getHostName() + ":" + tlsBackend.getAddress().getPort() + "/test"))
          .build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      assertEquals(response.statusCode(), 200);
      assertEquals(response.body(), "hello");
    } finally {
      proxy.stop(0);
      tlsBackend.stop(0);
    }
  }

  @Test
  public void testPlainForwardGetAndPostEcho() throws Exception {
    backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 100);
    backend.createContext("/test", helloOrEcho());

    proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 100);
    proxy.createContext("/test", new ProxyHandler(
        new ProxyHandler.HostPort(backend.getAddress().getHostName(), backend.getAddress().getPort(), "http")));

    backend.start();
    proxy.start();

    try (HttpClient client = HttpClient.newHttpClient()) {
      java.net.URI viaProxy = java.net.URI.create("http://localhost:" + proxy.getAddress().getPort() + "/test");

      HttpResponse<String> get = client.send(HttpRequest.newBuilder(viaProxy).build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(get.statusCode(), 200);
      assertEquals(get.body(), "hello");

      HttpResponse<String> post =
          client.send(HttpRequest.newBuilder(viaProxy).POST(HttpRequest.BodyPublishers.ofString("senditback")).build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(post.statusCode(), 200);
      assertEquals(post.body(), "senditback");
    } finally {
      proxy.stop(0);
      backend.stop(0);
    }
  }

  @Test
  public void testBasicAuthChallengeAnsweredThroughProxy() throws Exception {
    backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 100);
    var ctx = backend.createContext("/test", helloOrEcho());
    ctx.setAuthenticator(new ServerAuthenticator(REALM));

    proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 100);
    proxy.createContext("/test", new ProxyHandler(
        new ProxyHandler.HostPort(backend.getAddress().getHostName(), backend.getAddress().getPort(), "http")));

    backend.start();
    proxy.start();

    // the 401 challenge (with its non-ASCII realm) must pass through the
    // proxy un-mangled for the client's Authenticator to answer it; the
    // credentials must then reach the backend intact
    try (HttpClient client = HttpClient.newBuilder().authenticator(new ClientAuthenticator()).build()) {
      java.net.URI direct = java.net.URI.create("http://localhost:" + backend.getAddress().getPort() + "/test");
      java.net.URI viaProxy = java.net.URI.create("http://localhost:" + proxy.getAddress().getPort() + "/test");

      HttpResponse<String> directGet =
          client.send(HttpRequest.newBuilder(direct).build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(directGet.statusCode(), 200);
      assertEquals(directGet.body(), "hello");

      HttpResponse<String> proxiedGet =
          client.send(HttpRequest.newBuilder(viaProxy).build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(proxiedGet.statusCode(), 200);
      assertEquals(proxiedGet.body(), "hello");

      HttpResponse<String> proxiedPost =
          client.send(HttpRequest.newBuilder(viaProxy).POST(HttpRequest.BodyPublishers.ofString("senditback")).build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(proxiedPost.statusCode(), 200);
      assertEquals(proxiedPost.body(), "senditback");
    } finally {
      proxy.stop(0);
      backend.stop(0);
    }
  }

  static class ServerAuthenticator extends BasicAuthenticator {

    ServerAuthenticator(String realm) {
      super(realm);
    }

    @Override
    public boolean checkCredentials(String username, String password) {
      return getRealm().equals(REALM) && "password".equals(password);
    }

  }

  static class ClientAuthenticator extends java.net.Authenticator {

    @Override
    public PasswordAuthentication getPasswordAuthentication() {
      if (!getRequestingPrompt().equals(REALM)) {
        // the realm crossed the proxy mangled - fail loud, not with bad
        // credentials that would just 401 again
        throw new RuntimeException("realm does not match: " + getRequestingPrompt());
      }
      return new PasswordAuthentication("username", "password".toCharArray());
    }

  }

}
