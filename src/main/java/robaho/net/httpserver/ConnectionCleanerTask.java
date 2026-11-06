package robaho.net.httpserver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.TimerTask;

/**
 * The idle-connection sweeper, extracted from ServerImpl (the God-class
 * diet): one Timer pass over the server's connections that (a) closes
 * draining/idle/timed-out connections, (b) shrinks the buffers of
 * quiet-but-alive keep-alive connections, and (c) enforces the idle-count
 * cap by closing the OLDEST idle connections beyond the limit.
 * All policy constants live on {@link ServerImpl} (static, config-sourced)
 * and every action routes back through the context methods — the task
 * owns no state and mutates nothing but the connections it sweeps.
 */
class ConnectionCleanerTask extends TimerTask {

  private final ServerImpl server;

  ConnectionCleanerTask(ServerImpl server) {
    this.server = server;
  }

  @Override
  public void run() {
    // System.currentTimeMillis, not the CachedClock snapshot: the
    // cleaner compares this against lastActivityTime/drainingAt,
    // which are plain wall-clock writes (see HttpConnection) - both
    // sides of the comparison must use the same source
    long now = System.currentTimeMillis();

    for (HttpConnection c : server.allConnections()) {
      // an h2c connection's inRequest is only true during the upgrade window
      // (cleared at the Http2ConnectionHandler handoff); ALPN h2 never sets it.
      // busy state is requestsInProgress: streams in progress must not be
      // idle-closed mid-flight
      boolean busy = c.inRequest || (c.http2Connection != null && c.http2Connection.requestsInProgress.get() > 0);
      if (c.drainingAt != 0 && now - c.drainingAt >= ServerImpl.IDLE_INTERVAL / 2) {
        server.closeConnection(c, false);
        continue;
      }
      if (now - c.lastActivityTime >= ServerImpl.IDLE_INTERVAL && !busy) {
        server.logger().log(java.lang.System.Logger.Level.DEBUG, "closing idle connection");
        server.stats().idleCloseCount.incrementAndGet();
        server.closeConnection(c, false);
      } else if (ServerImpl.MAX_REQ_TIME != -1 && busy && !c.streamsHandedOff
          && (now - c.lastActivityTime >= ServerImpl.MAX_REQ_TIME)) {
            // streamsHandedOff (websocket/CONNECT tunnels) is NOT a
            // "request": a quiet websocket session is legal (app-level
            // keepalive), but inRequest=true made it look stalled and
            // the cleaner killed it at maxReqTime
            server.logger().log(java.lang.System.Logger.Level.WARNING, "closing connection due to request processing time");
            server.closeConnection(c, false);
          } else
        if (!busy && now - c.lastActivityTime >= ServerImpl.IDLE_INTERVAL / 4) {
          // idle but not yet closeable: release the 16KB input/output
          // buffers and the line buffer. the connection stays alive
          // (keep-alive) and the buffers re-allocate on the next
          // read/write. this is the memory optimization for
          // long-lived idle connections
          c.shrinkBuffers();
        }
    }
    // enforce the idle connection count limit: close the OLDEST idle
    // connections beyond MAX_IDLE_CONNECTIONS. the enforcement existed
    // only as a comment before, so idle keep-alive connections (and
    // their buffers) were unbounded despite the config
    if (ServerImpl.MAX_IDLE_CONNECTIONS > 0) {
      List<HttpConnection> idle = new ArrayList<>();
      for (HttpConnection c : server.allConnections()) {
        // "idle" = no request in flight AND quiet for a while.
        // h2 busy state is requestsInProgress (inRequest is only
        // the brief h2c upgrade window, cleared at the handoff);
        // ALPN h2 never sets it. recency of lastActivityTime is
        // the additional signal that an h2 connection is still
        // serving: without it the cap would count - and
        // eventually close - ACTIVE h2 connections. the busy
        // check matches the main idle-close branch: without it
        // the cap sweep killed quiet-but-busy h2 streams (a
        // handler mid-DB-query sorted as the "oldest idle" and
        // was closed preferentially once the cap was exceeded)
        boolean busy = c.inRequest || (c.http2Connection != null && c.http2Connection.requestsInProgress.get() > 0);
        if (!busy && now - c.lastActivityTime() >= ServerImpl.IDLE_INTERVAL / 2) {
          idle.add(c);
        }
      }
      if (idle.size() > ServerImpl.MAX_IDLE_CONNECTIONS) {
        idle.sort(java.util.Comparator.comparingLong(c -> c.lastActivityTime));
        for (int i = 0; i < idle.size() - ServerImpl.MAX_IDLE_CONNECTIONS; i++) {
          server.logger().log(java.lang.System.Logger.Level.DEBUG, "closing idle connection (over max idle limit)");
          server.stats().idleCloseCount.incrementAndGet();
          server.closeConnection(idle.get(i), false);
        }
      }
    }
  }

}
