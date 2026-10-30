package robaho.net.httpserver;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.text.DateFormat;
import java.util.Date;
import java.util.logging.Logger;

public class LoggingFilter extends Filter {

  private final Logger logger;

  private final DateFormat df;

  public LoggingFilter(Logger logger) throws IOException {
    df = DateFormat.getDateTimeInstance();
    this.logger = logger;
  }

  /**
   * The filter's implementation, which is invoked by the server
   */
  public void doFilter(HttpExchange t, Filter.Chain chain) throws IOException {
    chain.doFilter(t);
    String s = df.format(new Date());
    s = s + " " + t.getRequestMethod() + " " + t.getRequestURI() + " ";
    s = s + " " + t.getResponseCode() + " " + t.getRemoteAddress();
    logger.info(s);
  }

  public void init(HttpContext ctx) {}

  public String description() {
    return "Request logger";
  }

  public void destroy(HttpContext c) {}

}
