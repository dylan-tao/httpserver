package robaho.net.httpserver;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;

/**
 * The /__debug endpoint handler, extracted from ServerImpl (the God-class
 * diet): dumps the state of every live connection and h2 connection to
 * the log and the response body. registered only when
 * {@code robaho.net.httpserver.EnableDebug} is set.
 */
final class DebugHandler implements HttpHandler {

  private final Iterable<HttpConnection> connections;

  private final Iterable<ServerImpl.Http2ConnectionHandler> http2Connections;

  private final System.Logger logger;

  DebugHandler(Iterable<HttpConnection> connections, Iterable<ServerImpl.Http2ConnectionHandler> http2Connections,
      System.Logger logger) {
    this.connections = connections;
    this.http2Connections = http2Connections;
    this.logger = logger;
  }

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    logger.log(System.Logger.Level.INFO, "logging debug state, requestor " + exchange.getRemoteAddress());
    StringBuilder sb = new StringBuilder(4096);
    for (var connection : connections) {
      connection.debug();
      sb.append(connection).append('\n');
    }
    // per-instance registry (was static): dump only THIS server's
    // h2 connections
    for (var handler : http2Connections) {
      handler.debug();
    }
    byte[] body = sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(200, body.length);
    try (var os = exchange.getResponseBody()) {
      os.write(body);
    }
  }

}
