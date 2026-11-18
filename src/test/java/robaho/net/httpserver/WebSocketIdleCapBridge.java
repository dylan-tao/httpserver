package robaho.net.httpserver;

import com.sun.net.httpserver.HttpServer;
import java.util.ArrayList;
import java.util.List;

/**
 * package-bridge for WebSocketIdleCapTest (which lives in the websockets
 * package next to EchoWebSocketHandler): the cleaner, the ServerImpl
 * accessor and the HttpConnection activity clock are all package-private
 * in robaho.net.httpserver, so the test's clock manipulation and its
 * synchronous sweep run through here. no production visibility changes
 */
public class WebSocketIdleCapBridge {

  /** the ServerImpl behind an HttpServer.create() instance */
  private static ServerImpl impl(HttpServer server) {
    return ((HttpServerImpl) server).server;
  }

  /** run one production ConnectionCleanerTask sweep on demand */
  public static void sweep(HttpServer server) {
    new ConnectionCleanerTask(impl(server)).run();
  }

  /**
   * backdate the activity clock of every HANDED-OFF connection (websocket
   * 101 / CONNECT tunnels) by {@code ageMillis}: simulates that much
   * quiet time having passed. handed-off only - the caller's h1 filler
   * connections are aged separately so the two populations can be ordered
   */
  public static long ageHandedOffConnections(HttpServer server, long ageMillis) {
    long now = System.currentTimeMillis();
    long n = 0;
    for (HttpConnection c : impl(server).allConnections()) {
      if (c.streamsHandedOff) {
        c.setLastActivityTime(now - ageMillis);
        n++;
      }
    }
    return n;
  }

  /**
   * backdate the activity clock of every plain h1 keep-alive connection
   * (no handoff, no h2) by {@code ageMillis}. counts only filler-type
   * connections; returns how many were aged
   */
  public static long ageKeepAliveH1Connections(HttpServer server, long ageMillis) {
    long now = System.currentTimeMillis();
    long n = 0;
    for (HttpConnection c : impl(server).allConnections()) {
      if (!c.streamsHandedOff && c.http2Connection == null) {
        c.setLastActivityTime(now - ageMillis);
        n++;
      }
    }
    return n;
  }

  /** how many connections the server currently tracks */
  public static int connectionCount(HttpServer server) {
    return impl(server).allConnections().size();
  }

  /** which connections are currently alive, by remote port */
  public static List<Integer> remotePorts(HttpServer server) {
    var ports = new ArrayList<Integer>();
    for (HttpConnection c : impl(server).allConnections()) {
      var s = c.getSocket();
      if (s != null && !s.isClosed()) {
        ports.add(s.getPort());
      }
    }
    return ports;
  }

}
