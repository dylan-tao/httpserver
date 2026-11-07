/*
 * Copyright (c) 2005, 2023, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package robaho.net.httpserver;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import robaho.net.httpserver.http2.Http2ErrorCode;
import robaho.net.httpserver.http2.Http2Exception;
import robaho.net.httpserver.http2.Http2Stats;
import robaho.net.httpserver.http2.internal.Http2Connection;
import robaho.net.httpserver.http2.internal.Http2Stream;
import robaho.net.httpserver.internal.NoSyncBufferedInputStream;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.ServerSocketChannel;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

/**
 * Provides implementation for both HTTP and HTTPS
 */
class ServerImpl {

  private final String protocol;

  private final boolean https;

  private Executor executor;

  private HttpsConfigurator httpsConfig;

  private final ContextList contexts;

  private final ServerSocket socket;

  private final Set<HttpConnection> allConnections = Collections.newSetFromMap(new ConcurrentHashMap<>());

  // per-instance (was static): a JVM-global registry made every server's
  // ping task ping every OTHER server's connections (N x amplification),
  // let one server's ping failure close another server's live connection,
  // and bled /__debug output across instances
  private final Set<Http2ConnectionHandler> allHttp2Connections = Collections.newSetFromMap(new ConcurrentHashMap<>());

  /**
   * O(1) live connection count: deriving it from allConnections.size()
   * walks the whole backing map on every accept
   */
  private final java.util.concurrent.atomic.AtomicInteger connectionCount = new java.util.concurrent.atomic.AtomicInteger();

  // in-flight exchanges (h1 + h2 doFilter sections): lets stop(delay) return
  // as soon as the server goes idle instead of sleeping the full delay
  private final java.util.concurrent.atomic.AtomicInteger activeExchanges = new java.util.concurrent.atomic.AtomicInteger();

  /**
   * h2 keepalive ping interval (ServerConfig.http2PingIntervalMillis):
   * the reader loop schedules the task at this period and the task treats
   * a connection as ping-worthy after the same window - both sites read
   * the one config value. <= 0 disables the ping entirely
   */
  private static final long H2_PING_INTERVAL_MILLIS = ServerConfig.http2PingIntervalMillis();

  private volatile boolean finished = false;

  private boolean bound = false;

  private boolean started = false;

  private final HttpServer wrapper;

  // schedule for the timer task that's responsible for idle connection management
  static final long IDLE_TIMER_TASK_SCHEDULE = ServerConfig.getIdleTimerScheduleMillis();

  static final int MAX_CONNECTIONS = ServerConfig.getMaxConnections();

  static final int MAX_IDLE_CONNECTIONS = ServerConfig.getMaxIdleConnections();

  // schedule for the timer task that's responsible for request/response timeout
  // management
  static final long MAX_REQ_TIME = getTimeMillis(ServerConfig.getMaxReqTime());

  // the maximum idle duration for a connection which is currently idle but has
  // served
  // some request in the past
  static final long IDLE_INTERVAL = ServerConfig.getIdleIntervalMillis();

  /**
   * the connection-cleaner timer. created lazily by
   * connectionRegistered(): java.util.Timer starts its thread in the
   * constructor, so a server that never accepts a connection pays
   * nothing for it
   */
  private Timer cleanerTimer;

  /**
   * the scheduled connection-cleaner task, or null while this server
   * has no open connections. guarded by cleanerTaskLock
   */
  private TimerTask cleanerTask;

  private final Object cleanerTaskLock = new Object();

  /**
   * separate from the cleaner timer: ping writes can block on a stalled
   * peer's connection lock and must not freeze connection management.
   * created lazily by h2ConnectionRegistered(): java.util.Timer starts
   * its thread in the constructor, so a server that never sees an h2
   * connection pays nothing for it
   */
  private Timer pingTimer;

  /**
   * the scheduled keepalive ping task, or null while this server has no
   * h2 connections. guarded by pingTaskLock
   */
  private TimerTask pingTask;

  private final Object pingTaskLock = new Object();

  private final Logger logger;

  private Thread dispatcherThread;

  // statistics
  private final ServerStats stats = new ServerStats();

  private final Http2Stats http2Stats = new Http2Stats();

  ServerImpl(HttpServer wrapper, String protocol, InetSocketAddress addr, int backlog) throws IOException {

    this.protocol = protocol;
    this.wrapper = wrapper;

    // channel-backed so accepted sockets expose a SocketChannel (the
    // sendfile fast path needs it for FileChannel.transferTo). all
    // stream IO is unchanged - blocking semantics and the accept loop
    // behave exactly as with a plain ServerSocket
    socket = ServerSocketChannel.open().socket();

    // a single shared logger: per-instance logger names (identityHashCode)
    // accumulate in the LogManager forever when servers are created and
    // stopped repeatedly
    this.logger = System.getLogger("robaho.net.httpserver");

    https = protocol.equalsIgnoreCase("https");
    contexts = new ContextList();
    if (addr != null) {
      socket.bind(addr, backlog);
      bound = true;
      logger.log(Level.INFO, "server bound to " + socket.getLocalSocketAddress() + " with backlog " + backlog);
    }
    dispatcher = new Dispatcher(this);
    // the connection-cleaner task is NOT scheduled here: it is attached
    // lazily to its own timer on the first accepted connection and
    // detached when the last one closes (see connectionRegistered), so
    // an idle server has no cleaner timer thread at all
    // note: the shared CachedClock Date cache is refreshed on demand
    // from the response path (see CachedClock) and has no timer or
    // per-instance lifecycle at all
    // the h2 keepalive ping task is NOT scheduled here: it is attached
    // lazily to its own timer on the first h2 connection and detached
    // on the last disconnect (see h2ConnectionRegistered)
    logger.log(Level.DEBUG, "HttpServer created " + protocol + " " + addr);
    if (Boolean.getBoolean("robaho.net.httpserver.EnableStats")) {
      createContext("/__stats", new StatsHandler(connectionCount, stats, http2Stats));
    }
    if (Boolean.getBoolean("robaho.net.httpserver.EnableDebug")) {
      createContext("/__debug", new DebugHandler(allConnections, allHttp2Connections, logger));
    }
  }

  public void bind(InetSocketAddress addr, int backlog) throws IOException {
    if (bound) {
      throw new BindException("HttpServer already bound");
    }
    if (addr == null) {
      throw new NullPointerException("null address");
    }
    socket.bind(addr, backlog);
    logger.log(Level.INFO, "server bound to " + socket.getLocalSocketAddress() + " with backlog " + backlog);
    bound = true;
  }

  public void start() {
    if (!bound || started || finished) {
      throw new IllegalStateException("server in wrong state");
    }
    if (https && httpsConfig == null) {
      throw new IllegalStateException("no HttpsConfigurator set on HttpsServer");
    }
    if (executor == null) {
      executor = new DefaultExecutor();
    }
    logger.log(Level.INFO, "using " + executor + " as executor");
    // report the JVM-level performance features (ZGC / compact object
    // headers) once; see ServerConfig.logJvmFeatures for the fallback story
    ServerConfig.logJvmFeatures(logger);
    dispatcherThread = new Thread(null, dispatcher, "HTTP-Dispatcher", 0, false);
    started = true;
    dispatcherThread.start();
  }

  public void setExecutor(Executor executor) {
    if (started) {
      throw new IllegalStateException("server already started");
    }
    this.executor = executor;
  }

  public Executor getExecutor() { return executor; }

  public void setHttpsConfigurator(HttpsConfigurator config) {
    if (config == null) {
      throw new NullPointerException("null HttpsConfigurator");
    }
    if (started) {
      throw new IllegalStateException("server already started");
    }
    this.httpsConfig = config;
  }

  public HttpsConfigurator getHttpsConfigurator() { return httpsConfig; }

  public final boolean isFinishing() { return finished; }

  public void stop(int delay) {
    if (delay < 0) {
      throw new IllegalArgumentException("negative delay parameter");
    }
    logger.log(Level.INFO, "server shutting down: " + protocol);
    finished = true;
    try {
      socket.close();
    } catch (IOException e) {}
    if (executor instanceof DefaultExecutor de) {
      // since we created it, shut it down when stopping because it is private
      de.shutdown();
    }
    long latest = System.currentTimeMillis() + delay * 1000L;
    while (System.currentTimeMillis() < latest) {
      if (activeExchanges.get() == 0 && noConnectionBusy()) {
        // all in-flight exchanges completed, no connection is
        // parsing or handling a request, and no new ones can start
        // (finished is already set): return early instead of
        // sleeping out the full delay. the busy check is required:
        // activeExchanges only increments at doFilter entry, so a
        // request mid-parse (or an h2 stream mid-flight) was
        // invisible to the old check and was abandoned by the sweep
        break;
      }
      // sleep the remaining interval (in coarse steps) instead of a
      // fixed yield+sleep that overshoots the deadline
      long remaining = latest - System.currentTimeMillis();
      try {
        Thread.sleep(Math.min(remaining, 200));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    // graceful h2 shutdown (RFC 7540 6.8 SHOULD): tell live h2 peers
    // which streams were processed so they can retry safely on a new
    // connection instead of guessing at a bare TCP close
    // best-effort GOAWAY on a helper thread with a HARD bound: the
    // GOAWAY write itself can block on a stalled peer's full kernel
    // send buffer (verified: stop(0) hung until the peer's socket was
    // force-closed) - the operator's stop() must never be hostage to a
    // remote client. a skipped GOAWAY only loses the graceful-drain hint
    var goawayExecutor = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
      var t = new Thread(r, "h2-goaway");
      t.setDaemon(true);
      return t;
    });
    try {
      for (HttpConnection c : allConnections) {
        if (c.http2Connection != null) {
          goawayExecutor.execute(() -> {
            try {
              c.http2Connection.sendGoAwayIfIdle(Http2ErrorCode.NO_ERROR);
            } catch (IOException ignored) {}
          });
        }
      }
      goawayExecutor.shutdown();
      goawayExecutor.awaitTermination(500, java.util.concurrent.TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      goawayExecutor.shutdownNow();
    }
    for (HttpConnection c : allConnections) {
      c.close(false);
    }
    allConnections.clear();
    connectionCount.set(0);
    // under cleanerTaskLock: connectionRegistered() re-checks finished
    // inside the same lock, so an accept racing this cancel either sees
    // finished (and skips) or its timer is covered by this cancel - a
    // bare racy null-check could strand a live cleaner timer past stop
    synchronized (cleanerTaskLock) {
      if (cleanerTimer != null) {
        cleanerTimer.cancel();
      }
    }
    // under pingTaskLock: h2ConnectionRegistered() re-checks finished
    // inside the same lock, so a registration racing this cancel either
    // sees finished (and skips) or its timer is covered by this cancel -
    // a bare racy null-check could strand a live ping timer past stop
    synchronized (pingTaskLock) {
      if (pingTimer != null) {
        pingTimer.cancel();
      }
    }

    if (dispatcherThread != null && dispatcherThread != Thread.currentThread()) {
      try {
        dispatcherThread.join();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        logger.log(Level.TRACE, "ServerImpl.stop: ", e);
      }
    }
  }

  Dispatcher dispatcher;

  public HttpContextImpl createContext(String path, HttpHandler handler) {
    if (handler == null || path == null) {
      throw new NullPointerException("null handler, or path parameter");
    }
    HttpContextImpl context = new HttpContextImpl(protocol, path, handler, this);
    contexts.add(context);
    logger.log(Level.DEBUG, "context created: " + path);
    return context;
  }

  public HttpContextImpl createContext(String path) {
    if (path == null) {
      throw new NullPointerException("null path parameter");
    }
    HttpContextImpl context = new HttpContextImpl(protocol, path, null, this);
    contexts.add(context);
    logger.log(Level.DEBUG, "context created: " + path);
    return context;
  }

  public void removeContext(String path) throws IllegalArgumentException {
    if (path == null) {
      throw new NullPointerException("null path parameter");
    }
    contexts.remove(protocol, path);
    logger.log(Level.DEBUG, "context removed: " + path);
  }

  public void removeContext(HttpContext context) throws IllegalArgumentException {
    if (!(context instanceof HttpContextImpl)) {
      throw new IllegalArgumentException("wrong HttpContext type");
    }
    contexts.remove((HttpContextImpl) context);
    logger.log(Level.DEBUG, "context removed: " + context.getPath());
  }

  public InetSocketAddress getAddress() {
    // no AccessController.doPrivileged: the socket is owned by this
    // instance and reading its local address needs no privileges
    return (InetSocketAddress) socket.getLocalSocketAddress();
  }

  Logger getLogger() { return logger; }

  /**
   * true when no connection is parsing or handling a request (h1
   * inRequest, or h2 streams in progress) - used by stop(delay)'s
   * early-exit so mid-flight requests are not abandoned
   */
  private boolean noConnectionBusy() {
    for (var c : allConnections) {
      if (c.inRequest || (c.http2Connection != null && c.http2Connection.requestsInProgress.get() > 0)) {
        return false;
      }
    }
    return true;
  }

  /**
   * called when an h2 connection registers: attaches the keepalive ping
   * task on the first registration, creating the timer if needed. the
   * task is detached by h2ConnectionClosed() on the last disconnect, so
   * a server with no h2 connections pays no per-second ping wakeups
   * (previously the task fired forever from server start, pinging an
   * empty registry every second).
   * the timer is SEPARATE from the connection-cleaner's because the ping
   * write takes the connection lock, which a stalled h2 download can
   * hold in a blocking socket write for the TCP retransmission timeout;
   * sharing the single-threaded cleaner timer let that freeze
   * idle-close, maxReqTime and maxIdleConnections enforcement
   * server-wide.
   * both this and the removal path synchronize fully on pingTaskLock and
   * mutate the registry BEFORE taking the lock: whichever side wins the
   * lock either observes the other's registry change or leaves pingTask
   * null for the latecomer to (re)schedule, so the registry can never be
   * left non-empty with no task attached
   */
  private void h2ConnectionRegistered() {
    synchronized (pingTaskLock) {
      if (finished || H2_PING_INTERVAL_MILLIS <= 0) {
        return;
      }
      if (pingTask == null) {
        if (pingTimer == null) {
          pingTimer = new Timer("h2-keepalive-ping", true);
        }
        pingTask = createH2PingTask();
        pingTimer.schedule(pingTask, H2_PING_INTERVAL_MILLIS, H2_PING_INTERVAL_MILLIS);
      }
    }
  }

  /**
   * called when an h2 connection deregisters: detaches the keepalive ping
   * task once the LAST h2 connection is gone, so no timer fire happens
   * while the registry is empty. the timer itself survives (its thread
   * parks at zero CPU on the empty queue) and is reused by the next h2
   * connection; it dies only with stop()'s pingTimer.cancel()
   */
  private void h2ConnectionClosed() {
    if (allHttp2Connections.isEmpty()) {
      synchronized (pingTaskLock) {
        if (allHttp2Connections.isEmpty() && pingTask != null) {
          pingTask.cancel();
          pingTimer.purge();
          pingTask = null;
        }
      }
    }
  }

  /**
   * the h2 keepalive ping task: iterates THIS server's h2 connections
   * (the registry is per-instance - a JVM-global one made every server
   * ping every other server's connections and let one server's ping
   * failure close another's live connection)
   */
  private TimerTask createH2PingTask() {
    return new TimerTask() {

      @Override
      public void run() {
        long now = System.currentTimeMillis();
        for (var exchange : allHttp2Connections) {
          if (exchange.connection.lastActivityTime + H2_PING_INTERVAL_MILLIS < now) {
            try {
              // the ping write refreshes lastActivityTime via
              // the activity timer stream; restore it afterwards
              // so a dead connection still appears idle to the
              // cleaner. NOTE: a real activity landing inside the
              // microsecond window of the ping write is clobbered
              // by the restore; any live request generates
              // further socket activity that re-updates the
              // timestamp, so this micro-race is accepted
              var before = exchange.connection.lastActivityTime;
              exchange.http2.sendPing();
              exchange.connection.lastActivityTime = before;
            } catch (IOException ex) {
              // ping failed - the connection is dead. closing
              // the socket unblocks the reader thread, whose
              // finally block performs the full cleanup
              // management thread: no flush (a stalled peer
              // must not stall keepalive detection)
              exchange.connection.close(false);
            }
          }
        }
      }

    };
  }

  // package-private accessors for the extracted ConnectionCleanerTask
  Set<HttpConnection> allConnections() {
    return allConnections;
  }

  Logger logger() {
    return logger;
  }

  ServerStats stats() {
    return stats;
  }

  void closeConnection(HttpConnection conn) {
    // the connection's own thread: deliver any pending coalesced response
    closeConnection(conn, true);
  }

  /** the protocol token ("http"/"https") for context matching. */
  boolean isHttps() { return https; }

  /** the h2c preface hands the connection to this handler (step-4 inner). */
  Http2ConnectionHandler newHttp2Handler(HttpConnection connection) throws IOException {
    return new Http2ConnectionHandler(protocol, connection);
  }

  ContextList contexts() {
    return contexts;
  }

  String protocolToken() {
    return protocol;
  }

  java.util.concurrent.atomic.AtomicInteger connectionCount() {
    return connectionCount;
  }

  HttpsConfigurator httpsConfig() {
    return httpsConfig;
  }

  /** the accepting ServerSocket (the dispatcher owns its accept loop). */
  java.net.ServerSocket serverSocket() {
    return socket;
  }

  /**
   * @param flushResponse false from the cleaner/stop threads: the flush
   *          can block on a stalled peer's full kernel send buffer, and those
   *          threads must never be held hostage by a remote client
   */
  // package-private: the extracted cleaner closes through this seam
  void closeConnection(HttpConnection conn, boolean flushResponse) {
    logger.log(Level.TRACE, () -> "closing connection: " + conn.toString());
    conn.close(flushResponse);
    if (allConnections.remove(conn)) {
      connectionCount.decrementAndGet();
    }
    connectionClosed();
  }

  /* used to link to 2 or more Filter.Chains together */
  class Http2ConnectionHandler implements Runnable, Http2Connection.StreamHandler {

    final HttpConnection connection;

    final Http2Connection http2;

    final String protocol;

    /**
     * one-entry URI memo (Netty-style decode caching, zero-dependency):
     * h2 multiplexes many requests over one connection and workloads
     * overwhelmingly repeat the same :path - the multi-arg URI
     * constructor (quote + re-parse + StringBuilder, JFR: 29.8MB/30k
     * requests) is skipped entirely on a hit. a single immutable record
     * published via one volatile reference: concurrent stream handlers
     * can race the memo, but each miss builds its own correct URI and
     * the published one always matches its recorded components
     */
    private volatile UriMemo uriMemo;

    private record UriMemo(String scheme, String authority, String path, String query, URI uri) {}

    private URI internUri(String scheme, String authority, String path, String query) throws IOException {
      UriMemo m = uriMemo;
      if (m != null && scheme.equals(m.scheme()) && authority.equals(m.authority()) && path.equals(m.path())
          && java.util.Objects.equals(query, m.query())) {
        return m.uri();
      }
      try {
        URI uri = new URI(scheme, authority, path, query, null);
        uriMemo = new UriMemo(scheme, authority, path, query, uri);
        return uri;
      } catch (URISyntaxException ex) {
        throw new IOException("invalid uri", ex);
      }
    }

    void debug() {
      // the CALLER already iterates allHttp2Connections: the loop here
      // made the dump quadratic (N connections x N dumps) after the
      // static-to-instance registry conversion
      http2.debug();
    }

    Http2ConnectionHandler(String protocol, HttpConnection conn) throws IOException {
      this.connection = conn;
      this.protocol = protocol;

      if (protocol.equals("https")) {
        // the server protocol is "http"/"https" (never "https2" -
        // that comparison was always false and counted every ALPN
        // h2 connection as non-SSL in the stats)
        if (Http2Stats.ENABLED)
          http2Stats.sslConnections.incrementAndGet();
      } else {
        if (Http2Stats.ENABLED)
          http2Stats.nonsslConnections.incrementAndGet();
      }

      http2 = new Http2Connection(conn, http2Stats, connection.getInputStream(), connection.getOutputStream(), this);
      // let the idle cleaner reach the h2 stream buffer pool for shrinking
      conn.attachHttp2(http2);
    }

    @Override
    public void run() {
      allHttp2Connections.add(this);
      h2ConnectionRegistered();

      try {
        if (!http2.hasProperPreface()) {
          http2.sendGoAway(Http2ErrorCode.PROTOCOL_ERROR);
          logger.log(Level.WARNING, "ServerImpl HTTP2 preface failed");
          return;
        }
        http2.sendMySettings();
        http2.handle();
      } catch (Http2Exception ex) {
        logger.log(Level.WARNING, "ServerImpl http2 protocol exception " + http2, ex);
      } catch (IOException ex) {
        stats.socketExceptionCount.incrementAndGet();
        logger.log(Level.DEBUG, "end of stream " + http2);
      } catch (Exception ex) {
        logger.log(Level.WARNING, "ServerImpl unexpected exception handling http2 connection " + http2, ex);
      } finally {
        try {
          logger.log(Level.DEBUG, "closing HTTP2 connection and streams " + http2);
          closeConnection(connection);
          http2.close();
        } catch (Throwable t) {
          logger.log(Level.WARNING, "error closing http2 connection " + http2, t);
        }
        allHttp2Connections.remove(this);
        h2ConnectionClosed();
      }
    }

    @Override
    public Executor getExecutor() { return ServerImpl.this.executor; }

    @Override
    public void handleStream(Http2Stream stream, InputStream in, OutputStream out) throws IOException {
      connection.requestCount.incrementAndGet();
      if (ServerStats.ENABLED)
        stats.requestCount.incrementAndGet();

      if (Http2Stats.ENABLED)
        http2Stats.totalStreams.incrementAndGet();

      var request = stream.getRequestHeaders();
      var response = stream.getResponseHeaders();

      String scheme = https ? "https" : "http";
      String authority = request.getFirst(":authority");
      String path = request.getFirst(":path");
      String query = request.getFirst(":query");

      if (logger.isLoggable(Level.TRACE)) {
        // gate: the TRACE supplier captures the per-request `stream`
        // local, so the lambda instance allocates even with TRACE off
        // (R1 JFR: the top remaining per-request allocation)
        logger.log(Level.TRACE, "http2 stream started " + stream);
      }

      if (authority == null || path == null) {
        throw new IOException("Invalid HTTP/2 headers: missing :authority or :path");
      }

      // the query string is part of :path in HTTP/2 (RFC 7540 8.1.2.3);
      // there is no :query pseudo header, so split it off if present
      if (query == null) {
        int q = path.indexOf('?');
        if (q >= 0) {
          query = path.substring(q + 1);
          path = path.substring(0, q);
        }
      }

      request.set("Host", authority);

      URI uri = internUri(scheme, authority, path, query);

      String method = request.getFirst(":method");
      if (method == null) {
        throw new IOException("Invalid HTTP/2 headers: missing :method");
      }

      // Validate Transfer-Encoding headers for HTTP/2
      List<String> transferEncoding = request.get("Transfer-encoding");
      if (transferEncoding != null && !transferEncoding.isEmpty()) {
        if (transferEncoding.size() > 1 || !transferEncoding.get(0).equalsIgnoreCase("chunked")) {
          throw new IOException("Unsupported Transfer-Encoding value");
        }
      }

      // opt-in request body cap: reject a declared Content-Length up
      // front (bodies without a declared length are bounded
      // incrementally in the stream's DATA path)
      long bodySizeLimit = ServerConfig.getMaxRequestBodySize();
      if (bodySizeLimit > 0) {
        String contentLength = request.getFirst("content-length");
        if (contentLength != null) {
          try {
            if (Long.parseLong(contentLength) > bodySizeLimit) {
              rejectStream(stream, out, "413");
              return;
            }
          } catch (NumberFormatException ignored) {
            // malformed content-length: the stream-level
            // expectedSize check rejects it on END_STREAM
          }
        }
      }

      // null-check, not Optional.ofNullable: a fresh Optional per request is
      // pure allocation on the dispatch hot path
      String uriPath = uri.getPath();
      if (uriPath == null) {
        uriPath = "/";
      }
      HttpContextImpl ctx = contexts.findContext(protocol, uriPath);
      if (ctx == null) {
        logger.log(Level.DEBUG, "No context found for request " + uriPath + ", rejecting as not found");
        rejectStream(stream, out, "404");
        return;
      }
      if (ctx.getHandler() == null) {
        // h1 parity: a context without a handler gets a 500 there
        // (ServerImpl's h1 path checks this); h2 NPE'd inside the
        // filter chain instead, resetting the stream with
        // INTERNAL_ERROR and logging a WARNING
        logger.log(Level.WARNING, "no handler configured for context " + uriPath);
        rejectStream(stream, out, "500");
        return;
      }

      if (logger.isLoggable(Level.TRACE)) {
        logger.log(Level.TRACE, "http2 request on " + connection + " " + method + " for " + uri);
      }

      try {
        // doFilterTracked is the SINGLE counting site for the
        // in-flight exchange counter (h1 counts there too) - the old
        // outer increment/finally here double-counted every h2 request
        doFilterTracked(buildChain(ctx), new Http2ExchangeImpl(stream, uri, method, ctx, request, response, in, out));
      } catch (IOException e) {
        // common with clients breaking connections; the stream is
        // closed by the performRequest finally block
        logger.log(Level.DEBUG, "io exception on stream " + stream, e);
      } catch (Exception e) {
        logger.log(Level.WARNING, "Dispatcher Exception on " + stream, e);
        stats.handleExceptionCount.incrementAndGet();
      }
    }

  }

  /**
   * build the user-visible filter chain: the system (internal) chain wraps
   * the handler, the user chain wraps that via a LinkHandler so both are
   * invoked in one doFilter call. shared by the http/1 and http/2 request
   * paths so the chain construction cannot drift between them
   */
  Filter.Chain buildChain(HttpContextImpl ctx) {
    // the whole chain is cached on the context for the common shapes:
    // a Filter$Chain over an immutable empty list never advances its
    // iterator, so it is stateless and safely shared across concurrent
    // exchanges. user filters present -> only the thin per-exchange
    // user Chain is built (its iterator is per-request state)
    Filter.Chain cached = ctx.getFilterChain();
    if (cached != null) {
      return cached;
    }
    // fallback for a nonstandard system filter list (unreachable today:
    // only the constructor's AuthFilter is ever added): the old
    // per-request build, iterator state and all
    final List<Filter> sf = ctx.getSystemFilters();
    final List<Filter> uf = ctx.getFilters();
    final Filter.Chain sc = new Filter.Chain(sf, ctx.getHandler());
    return new Filter.Chain(uf, new LinkHandler(sc));
  }

  void logReply(int code, String requestStr, String text) {
    if (!logger.isLoggable(Level.DEBUG)) {
      return;
    }
    CharSequence r;
    if (requestStr.length() > 80) {
      r = requestStr.substring(0, 80) + "<TRUNCATED>";
    } else {
      r = requestStr;
    }
    logger.log(Level.DEBUG, () -> "reply " + r + " [" + code + HttpStatus.msg(code) + "] (" + (text != null ? text : "") + ")");
  }

  HttpServer getWrapper() { return wrapper; }

  /**
   * Responsible for closing connections that have been idle or exceed other
   * limits
   */
  /**
   * called when a connection is accepted: attaches the
   * connection-cleaner task on the first registration, creating the
   * timer if needed. the task is detached by connectionClosed() when the
   * last connection closes, so a server with no open connections pays no
   * periodic wakeups at all (previously the task fired forever from
   * server start, sweeping an empty registry every 10s).
   * the timer is SEPARATE from the h2 keepalive ping's because the
   * cleaner's closeConnection write path must stay independent of a
   * stalled peer (and vice versa: the original single shared timer let a
   * stalled h2 download freeze idle-close server-wide).
   * both this and the removal path synchronize fully on cleanerTaskLock
   * and mutate the registry BEFORE taking the lock: whichever side wins
   * the lock either observes the other's registry change or leaves
   * cleanerTask null for the latecomer to (re)schedule, so the registry
   * can never be left non-empty with no task attached
   */
  void connectionRegistered() {
    synchronized (cleanerTaskLock) {
      if (finished) {
        return;
      }
      if (cleanerTask == null) {
        if (cleanerTimer == null) {
          cleanerTimer = new Timer("connection-cleaner", true);
        }
        cleanerTask = new ConnectionCleanerTask(this);
        cleanerTimer.schedule(cleanerTask, IDLE_TIMER_TASK_SCHEDULE, IDLE_TIMER_TASK_SCHEDULE);
      }
    }
  }

  /**
   * called when a connection closes: detaches the connection-cleaner
   * task once the LAST connection is gone, so no timer fire happens
   * while the registry is empty. the timer itself survives (its thread
   * parks at zero CPU on the empty queue) and is reused by the next
   * accepted connection; it dies only with stop()'s cancel. cancelling
   * the running task from inside the cleaner itself (it just closed the
   * last connection) is legal: cancel() only prevents future runs
   */
  private void connectionClosed() {
    if (allConnections.isEmpty()) {
      synchronized (cleanerTaskLock) {
        if (allConnections.isEmpty() && cleanerTask != null) {
          cleanerTask.cancel();
          cleanerTimer.purge();
          cleanerTask = null;
        }
      }
    }
  }

  /**
   * run the filter chain with the in-flight exchange counter maintained:
   * shared by the h1 and h2 request paths so the counting cannot drift
   */
  void doFilterTracked(Filter.Chain chain, com.sun.net.httpserver.HttpExchange exchange) throws IOException {
    activeExchanges.incrementAndGet();
    try {
      chain.doFilter(exchange);
    } finally {
      activeExchanges.decrementAndGet();
    }
  }

  /**
   * reject an h2 stream with a bare status response: the h2 counterpart of
   * the h1 reject() helper (h1/h2 error paths must not drift apart)
   */
  private static void rejectStream(robaho.net.httpserver.http2.internal.Http2Stream stream, java.io.OutputStream out,
      String status) throws IOException {
    stream.getResponseHeaders().set(":status", status);
    stream.writeResponseHeaders(true);
    out.close();
  }

  /**
   * Converts and returns the passed {@code secs} as milli seconds. If the
   * passed {@code secs} is negative or zero or if the conversion from seconds
   * to milli seconds results in a negative number, then this method returns
   * -1.
   */
  private static long getTimeMillis(long secs) {
    if (secs <= 0) {
      return -1;
    }
    final long milli = secs * 1000;
    // this handles potential numeric overflow that may have happened during
    // conversion
    return milli > 0 ? milli : -1;
  }

}
