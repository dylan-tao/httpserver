package robaho.net.httpserver;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;

/**
 * The filter-chain tail, extracted from ServerImpl (the God-class diet):
 * hands the exchange to the next filter link. a separate class (rather
 * than a lambda) keeps the chain object identity stable for tests.
 */
final class LinkHandler implements HttpHandler {

  private final Filter.Chain nextChain;

  LinkHandler(Filter.Chain nextChain) {
    this.nextChain = nextChain;
  }

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    nextChain.doFilter(exchange);
  }

}
