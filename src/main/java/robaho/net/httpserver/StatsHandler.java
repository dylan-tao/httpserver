package robaho.net.httpserver;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import robaho.net.httpserver.http2.Http2Stats;

/**
 * The /__stats endpoint handler, extracted from ServerImpl (the God-class
 * diet): one response body assembled from the connection counter and the
 * two stats registries. registered only when
 * {@code robaho.net.httpserver.EnableStats} is set.
 */
final class StatsHandler implements HttpHandler {

  private final AtomicInteger connectionCount;

  private final ServerStats stats;

  private final Http2Stats http2Stats;

  StatsHandler(AtomicInteger connectionCount, ServerStats stats, Http2Stats http2Stats) {
    this.connectionCount = connectionCount;
    this.stats = stats;
    this.http2Stats = http2Stats;
  }

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    var output = ("Active Connections: " + connectionCount.get() + "\n" + stats.stats() + http2Stats.stats())
        .getBytes(java.nio.charset.StandardCharsets.UTF_8);

    exchange.sendResponseHeaders(200, output.length);
    exchange.getResponseBody().write(output);
    exchange.getResponseBody().close();
  }

}
