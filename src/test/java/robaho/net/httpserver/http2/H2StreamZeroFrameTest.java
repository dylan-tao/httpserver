package robaho.net.httpserver.http2;

import com.sun.net.httpserver.HttpServer;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.net.InetSocketAddress;

/**
 * RFC 7540 compliance guards for stream-0 frames and connection-specific
 * request headers (audit W19, 2026-11-20): frames that address a single
 * stream must never carry stream 0, a WINDOW_UPDATE must not interleave
 * with an incomplete header block even at the connection level, and the
 * connection-specific headers (keep-alive, proxy-connection, upgrade) are
 * as prohibited as "connection" itself. all three were silently accepted
 * before the fix (stream-0 DATA even debited the connection window on its
 * way to the closed-stream ignore branch).
 */
public class H2StreamZeroFrameTest {

  private HttpServer server;

  @BeforeMethod
  void setUp() throws IOException {
    if (!"true".equals(System.getProperty("robaho.net.httpserver.http2OverNonSSL"))) {
      throw new SkipException("robaho.net.httpserver.http2OverNonSSL is not enabled");
    }
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/echo", exchange -> {
      byte[] body = exchange.getRequestBody().readAllBytes();
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
  public void testStreamZeroDataIsConnectionError() throws Exception {
    try (H2Client client = new H2Client(server.getAddress().getPort())) {
      client.writeRawFrame(H2Client.TYPE_DATA, 0, 0, new byte[] {'x'});
      var response = client.awaitResponse(1);
      Assert.assertTrue(response.goaway(), "stream-0 DATA must be a connection error, not silently ignored");
      Assert.assertEquals(client.goawayErrorCode, 0x1, "the GOAWAY must carry PROTOCOL_ERROR");
    }
  }

  @Test
  public void testConnectionSpecificHeaderIsConnectionError() throws Exception {
    try (H2Client client = new H2Client(server.getAddress().getPort())) {
      // "connection" itself was already prohibited; the sibling
      // connection-specific headers were not
      int stream = client.sendRequest("GET", "/echo", true, "Upgrade", "websocket");
      var response = client.awaitResponse(stream);
      Assert.assertTrue(response.goaway(), "an h2 request carrying 'Upgrade' must be a connection error");
      Assert.assertEquals(client.goawayErrorCode, 0x1, "the GOAWAY must carry PROTOCOL_ERROR");
    }
  }

  @Test
  public void testStreamZeroWindowUpdateDuringHeaderBlockIsConnectionError() throws Exception {
    try (H2Client client = new H2Client(server.getAddress().getPort())) {
      // an incomplete header block: HEADERS without END_HEADERS
      byte[] block = H2Client.encodeHeaders(":method", "GET", ":scheme", "http", ":path", "/echo", ":authority", "127.0.0.1");
      client.writeRawFrame(H2Client.TYPE_HEADERS, 0, 1, block);
      // a connection-level WINDOW_UPDATE during the open block: RFC 7540
      // 6.2 allows only CONTINUATION - it used to be accepted and credited
      client.writeWindowUpdate(0, 1000);
      // complete the block: pre-fix the request was served normally
      client.writeRawFrame(H2Client.TYPE_CONTINUATION, H2Client.FLAG_END_HEADERS, 1, new byte[0]);

      var response = client.awaitResponse(1);
      Assert.assertTrue(response.goaway(), "a stream-0 WINDOW_UPDATE during a header block must be a connection error");
      Assert.assertEquals(client.goawayErrorCode, 0x1, "the GOAWAY must carry PROTOCOL_ERROR");
    }
  }

}
