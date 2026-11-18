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
    // collected in the same pass as the sweep: the old shape ran a
    // SECOND full O(n) scan whose busy/idle test duplicated this
    // loop's verbatim
    List<HttpConnection> idle = ServerImpl.MAX_IDLE_CONNECTIONS > 0 ? new ArrayList<>() : null;

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
          } else {
            if (!busy && now - c.lastActivityTime >= ServerImpl.IDLE_INTERVAL / 4) {
              // idle but not yet closeable: release the 16KB input/output
              // buffers and the line buffer. the connection stays alive
              // (keep-alive) and the buffers re-allocate on the next
              // read/write. this is the memory optimization for
              // long-lived idle connections
              c.shrinkBuffers();
            }
            if (idle != null && (!busy || c.streamsHandedOff) && now - c.lastActivityTime() >= ServerImpl.IDLE_INTERVAL / 2) {
              // candidate for the idle-count cap. recency of
              // lastActivityTime is the additional signal that an h2
              // connection is still serving: without it the cap would
              // count - and eventually close - ACTIVE h2 connections.
              // the busy check matches the main idle-close branch:
              // without it the cap sweep killed quiet-but-busy h2
              // streams (a handler mid-DB-query sorted as the "oldest
              // idle" and was closed preferentially once the cap was
              // exceeded)
              //
              // handed-off connections (websocket 101 / CONNECT tunnels)
              // are ALWAYS busy - inRequest stays true for the tunnel's
              // whole lifetime - yet they are deliberately exempt from
              // the idle-close above (a quiet websocket session is
              // legal). the idle CAP is different: without counting
              // quiet tunnels, maxIdleConnections was unbounded for
              // websockets (N quiet sessions = N retained buffers and
              // stacks, forever). their activity clock still runs (WS
              // reads/writes flow through the activity-tracking stream
              // chain), so a tunnel counts toward the cap only after
              // IDLE_INTERVAL/2 with no traffic in either direction -
              // an actively-pushing or chattering websocket never counts
              idle.add(c);
            }
          }
    }
    // enforce the idle connection count limit: close the OLDEST idle
    // connections beyond MAX_IDLE_CONNECTIONS. the enforcement existed
    // only as a comment before, so idle keep-alive connections (and
    // their buffers) were unbounded despite the config
    if (idle != null && idle.size() > ServerImpl.MAX_IDLE_CONNECTIONS) {
      idle.sort(java.util.Comparator.comparingLong(c -> c.lastActivityTime));
      for (int i = 0; i < idle.size() - ServerImpl.MAX_IDLE_CONNECTIONS; i++) {
        server.logger().log(java.lang.System.Logger.Level.DEBUG, "closing idle connection (over max idle limit)");
        server.stats().idleCloseCount.incrementAndGet();
        // for a handed-off tunnel (websocket/CONNECT) this close is an
        // abnormal shutdown from the peer's perspective: the tunnel's
        // read blocks in the socket read and surfaces the close as EOF,
        // which the websocket close chain handles (its close path is
        // hardened by WebSocketDoubleCloseTest)
        server.closeConnection(idle.get(i), false);
      }
    }
  }

}
