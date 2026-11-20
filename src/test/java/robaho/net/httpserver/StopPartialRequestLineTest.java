package robaho.net.httpserver;

import com.sun.net.httpserver.HttpServer;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * regression guard for the stop(delay) mid-parse window (audit E2,
 * 2026-11-20): the h1 connection marks itself busy (inRequest) only AFTER
 * the request line is read, so a connection parked mid-request-line (a
 * client that sent "GET / HTT" and stalled) looks idle to stop(delay)'s
 * early-exit - stop(5) returned in ~9ms and the sweep abandoned the
 * in-flight request.
 * <p>
 * EXPECT-RED until the E2 fix lands (a partial-line signal included in
 * ServerImpl.noConnectionBusy); wire into testng.xml together with that
 * fix. the idle control case is green before and after and guards the
 * fix against making parked connections look permanently busy.
 */
public class StopPartialRequestLineTest {

  private HttpServer server;

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
  }

  @AfterMethod
  void tearDown() {
    if (server != null) {
      server.stop(0);
    }
  }

  /**
   * a partially received request line is in-flight work: stop(2) must wait
   * out its delay before sweeping the connection, not return immediately
   */
  @Test
  public void testStopWaitsForPartialRequestLine() throws Exception {
    try (Socket socket = new Socket("127.0.0.1", server.getAddress().getPort())) {
      OutputStream out = socket.getOutputStream();
      // a partial request line with no terminator: the connection's VT is
      // parked inside the request-line read, inRequest still false
      out.write("GET / HTT".getBytes(StandardCharsets.ISO_8859_1));
      out.flush();
      // let the server consume the bytes: the connection VT must wake from
      // its parked read, accumulate the partial line into the line buffer
      // and park again - only then is the mid-line state observable to
      // stop()'s busy check. without this settle window the test can race
      // ahead of the VT (checking before the partial line was consumed)
      // and fail on fixed code
      Thread.sleep(500);

      long start = System.currentTimeMillis();
      server.stop(2);
      long elapsed = System.currentTimeMillis() - start;
      Assert.assertTrue(elapsed >= 1500,
          "stop(2) must wait out its delay for a connection mid-request-line parse (returned in " + elapsed + "ms)");
    }
  }

  /**
   * a parked keep-alive connection that has received NOTHING must keep the
   * early-exit working: the fix must not make idle connections look busy
   */
  @Test
  public void testStopReturnsPromptlyWhenIdle() throws Exception {
    try (Socket socket = new Socket("127.0.0.1", server.getAddress().getPort())) {
      // no bytes sent: the connection parks waiting for the next request line
      long start = System.currentTimeMillis();
      server.stop(2);
      long elapsed = System.currentTimeMillis() - start;
      Assert.assertTrue(elapsed < 1500, "stop(2) must early-exit for fully idle connections (took " + elapsed + "ms)");
    }
  }

}
