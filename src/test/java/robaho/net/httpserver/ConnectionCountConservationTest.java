package robaho.net.httpserver;

import com.sun.net.httpserver.HttpServer;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * regression guard for the connection-count conservation (re-audit N1,
 * 2026-11-23): the W4 reservation placed incrementAndGet() on the RIGHT side
 * of {@code MAX_CONNECTIONS > 0 && ...}, so with the limit disabled (the
 * default -1) the increment was short-circuited away while closeConnection
 * always decrements - the /__stats "Active Connections" value drifted one
 * lower per closed connection (the re-audit's PoC measured 0 -> -6 -> -7).
 * the increment now runs unconditionally; once every cycle's close has
 * settled, the only count should be the /__stats probe connection itself.
 */
public class ConnectionCountConservationTest {

  private HttpServer server;

  @BeforeMethod
  void setUp() throws IOException {
    // /__stats is registered at server construction when this is set
    System.setProperty("robaho.net.httpserver.EnableStats", "true");
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      exchange.sendResponseHeaders(200, 2);
      try (var os = exchange.getResponseBody()) {
        os.write("ok".getBytes(StandardCharsets.UTF_8));
      }
    });
    server.start();
  }

  @AfterMethod
  void tearDown() {
    if (server != null) {
      server.stop(0);
    }
    System.clearProperty("robaho.net.httpserver.EnableStats");
  }

  @Test
  public void testConnectionCyclesConserveTheCount() throws Exception {
    int port = server.getAddress().getPort();

    // cycle a handful of complete connections (request + Connection:
    // close, then wait for the close to run) - each close decrements;
    // pre-fix the matching increment never ran (limit disabled by default)
    for (int i = 0; i < 6; i++) {
      HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/").openConnection();
      conn.setRequestProperty("Connection", "close");
      Assert.assertEquals(conn.getResponseCode(), 200);
      conn.getInputStream().readAllBytes();
      conn.disconnect();
    }
    // a couple of bare connects closed by the peer: the 0-request close
    // path decrements the same way
    for (int i = 0; i < 2; i++) {
      try (Socket probe = new Socket("127.0.0.1", port)) {
        probe.getOutputStream().flush();
      }
    }

    // poll /__stats until every cycle's close has settled: with all eight
    // cycles processed the only count left is the /__stats probe
    // connection itself (it increments on accept before its handler runs).
    // pre-fix the count sits at -8 + 1 = -7 and never reaches 1
    long deadline = System.currentTimeMillis() + 5_000;
    int active = readActiveConnections(port);
    while (active != 1 && System.currentTimeMillis() < deadline) {
      Thread.sleep(50);
      active = readActiveConnections(port);
    }
    Assert.assertEquals(active, 1,
        "the connection count must be conserved across connection cycles (the /__stats probe is the only live connection) - negative means the reservation increment was skipped (N1)");
  }

  /** the /__stats "Active Connections" value */
  private int readActiveConnections(int port) throws Exception {
    HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/__stats").openConnection();
    conn.setRequestProperty("Connection", "close");
    try {
      Assert.assertEquals(conn.getResponseCode(), 200, "/__stats must be registered");
      String body = new String(conn.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
      for (String line : body.split("\n")) {
        if (line.startsWith("Active Connections:")) {
          return Integer.parseInt(line.substring("Active Connections:".length()).trim());
        }
      }
      throw new IllegalStateException("no Active Connections line in /__stats: " + body);
    } finally {
      conn.disconnect();
    }
  }

}
