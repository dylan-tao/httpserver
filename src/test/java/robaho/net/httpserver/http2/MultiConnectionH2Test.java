package robaho.net.httpserver.http2;

import com.sun.net.httpserver.HttpServer;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.net.InetSocketAddress;

/**
 * Browser-shaped h2 load: several concurrent connections, each with several interleaved streams. every other h2 test
 * uses a single connection; the connection-scoped state (flow-control windows, the HPACK dynamic table, frame
 * interleaving) is exactly where cross-connection bugs hide. each request carries a unique marker that the handler
 * echoes, so any cross-stream or cross-connection bleed shows up as a mismatched body.
 */
public class MultiConnectionH2Test {

  private HttpServer server;

  @BeforeMethod
  void setUp() throws IOException {
    if (!"true".equals(System.getProperty("robaho.net.httpserver.http2OverNonSSL"))) {
      throw new org.testng.SkipException("robaho.net.httpserver.http2OverNonSSL is not enabled");
    }
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/echo", exchange -> {
      // echo the request path as a marker: /echo/conn0-stream3 style
      String marker = exchange.getRequestURI().getPath();
      byte[] body = marker.getBytes(java.nio.charset.StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, body.length);
      try (var os = exchange.getResponseBody()) {
        os.write(body);
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
  public void testMultiConnectionInterleavedStreams() throws Exception {
    int conns = 3;
    int streamsPerConn = 4;
    H2Client[] clients = new H2Client[conns];
    try {
      for (int c = 0; c < conns; c++) {
        clients[c] = new H2Client(server.getAddress().getPort());
      }
      // open all streams first: requests are in flight concurrently on
      // every connection before any response is read
      int[][] streamIds = new int[conns][streamsPerConn];
      for (int c = 0; c < conns; c++) {
        for (int s = 0; s < streamsPerConn; s++) {
          streamIds[c][s] = clients[c].sendRequest("GET", "/echo/conn" + c + "-stream" + s, true);
        }
      }
      // then collect: every response must carry its own marker
      for (int c = 0; c < conns; c++) {
        for (int s = 0; s < streamsPerConn; s++) {
          var response = clients[c].awaitResponse(streamIds[c][s]);
          Assert.assertFalse(response.goaway(), "connection " + c + " must not see a GOAWAY");
          Assert.assertNull(response.rstErrorCode(), "stream " + s + " on connection " + c + " must not be reset");
          Assert.assertEquals(response.status(), 200, "stream " + s + " on connection " + c);
          String expected = "/echo/conn" + c + "-stream" + s;
          Assert.assertEquals(new String(response.data(), java.nio.charset.StandardCharsets.UTF_8), expected,
              "response bleed on connection " + c + " stream " + s);
        }
      }
    } finally {
      for (H2Client c : clients) {
        if (c != null) {
          c.close();
        }
      }
    }
  }

}
