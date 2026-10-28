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
import robaho.net.httpserver.internal.NoSyncBufferedInputStream;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import robaho.net.httpserver.http2.internal.Http2Connection;
import robaho.net.httpserver.http2.Http2ErrorCode;
import robaho.net.httpserver.http2.Http2Exception;
import robaho.net.httpserver.http2.Http2Stats;
import robaho.net.httpserver.http2.internal.Http2Stream;

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
import java.util.concurrent.RejectedExecutionException;

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
    /** O(1) live connection count: deriving it from allConnections.size()
     * walks the whole backing map on every accept */
    private final java.util.concurrent.atomic.AtomicInteger connectionCount = new java.util.concurrent.atomic.AtomicInteger();
    // in-flight exchanges (h1 + h2 doFilter sections): lets stop(delay) return
    // as soon as the server goes idle instead of sleeping the full delay
    private final java.util.concurrent.atomic.AtomicInteger activeExchanges = new java.util.concurrent.atomic.AtomicInteger();

    /** h2 keepalive ping interval (ServerConfig.http2PingIntervalMillis):
     * the reader loop schedules the task at this period and the task treats
     * a connection as ping-worthy after the same window - both sites read
     * the one config value. <= 0 disables the ping entirely */
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
    /** the connection-cleaner timer. created lazily by
     * connectionRegistered(): java.util.Timer starts its thread in the
     * constructor, so a server that never accepts a connection pays
     * nothing for it */
    private Timer cleanerTimer;
    /** the scheduled connection-cleaner task, or null while this server
     * has no open connections. guarded by cleanerTaskLock */
    private TimerTask cleanerTask;
    private final Object cleanerTaskLock = new Object();
    /** separate from the cleaner timer: ping writes can block on a stalled
     * peer's connection lock and must not freeze connection management.
     * created lazily by h2ConnectionRegistered(): java.util.Timer starts
     * its thread in the constructor, so a server that never sees an h2
     * connection pays nothing for it */
    private Timer pingTimer;
    /** the scheduled keepalive ping task, or null while this server has no
     * h2 connections. guarded by pingTaskLock */
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

        socket = new ServerSocket();

        // a single shared logger: per-instance logger names (identityHashCode)
        // accumulate in the LogManager forever when servers are created and
        // stopped repeatedly
        this.logger = System.getLogger("robaho.net.httpserver");

        https = protocol.equalsIgnoreCase("https");
        contexts = new ContextList();
        if (addr != null) {
            socket.bind(addr, backlog);
            bound = true;
            logger.log(Level.INFO,"server bound to "+socket.getLocalSocketAddress() + " with backlog "+backlog);
        }
        dispatcher = new Dispatcher();
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
        if(Boolean.getBoolean("robaho.net.httpserver.EnableStats")) {
            createContext("/__stats",new StatsHandler());
        }
        if(Boolean.getBoolean("robaho.net.httpserver.EnableDebug")) {
            createContext("/__debug",new DebugHandler());
        }
    }

    private class StatsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            var output =
                (
                "Active Connections: "+connectionCount.get()+"\n" +
                stats.stats()+
                http2Stats.stats()
                ).getBytes();

            exchange.sendResponseHeaders(200,output.length);
            exchange.getResponseBody().write(output);
            exchange.getResponseBody().close();
        }
    }

    /** dump connection/stream state to the response body (and the log) */
    private class DebugHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            logger.log(Level.INFO,"logging debug state, requestor "+exchange.getRemoteAddress());
            StringBuilder sb = new StringBuilder(4096);
            for(var connection : allConnections) {
                connection.debug();
                sb.append(connection.toString()).append('\n');
            }
            // per-instance registry (was static): dump only THIS server's
            // h2 connections
            for (var handler : allHttp2Connections) {
                handler.debug();
            }
            byte[] body = sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var os = exchange.getResponseBody()) {
                os.write(body);
            }
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
        logger.log(Level.INFO,"server bound to "+socket.getLocalSocketAddress()+ " with backlog "+backlog);
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

    private static class DefaultExecutor implements Executor {
        // virtual threads by default (JDK 21, JEP 444): connection handling is
        // blocking-IO-heavy (request reads, drains, SSL handshakes) and
        // thread-per-connection scales far better virtually than on the
        // platform-thread pool. all monitor-based locking has been removed
        // from the blocking paths so nothing pins a carrier thread.
        // opt out with robaho.net.httpserver.virtualThreads=false
        private final ExecutorService executor = ServerConfig.virtualThreads()
            ? Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("httpserver-vt-", 0).factory())
            : Executors.newCachedThreadPool();

        @Override
        public void execute(Runnable task) {
            executor.execute(task);
        }
        public void shutdown() {
            executor.shutdown();
        }
    }

    public Executor getExecutor() {
        return executor;
    }

    public void setHttpsConfigurator(HttpsConfigurator config) {
        if (config == null) {
            throw new NullPointerException("null HttpsConfigurator");
        }
        if (started) {
            throw new IllegalStateException("server already started");
        }
        this.httpsConfig = config;
    }

    public HttpsConfigurator getHttpsConfigurator() {
        return httpsConfig;
    }

    public final boolean isFinishing() {
        return finished;
    }

    public void stop(int delay) {
        if (delay < 0) {
            throw new IllegalArgumentException("negative delay parameter");
        }
        logger.log(Level.INFO, "server shutting down: " + protocol);
        finished = true;
        try {
            socket.close();
        } catch (IOException e) {
        }
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
                        } catch (IOException ignored) {
                        }
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

    /**
     * The Dispatcher is responsible for accepting any connections and then
     * using those connections to process incoming requests.
     */
    class Dispatcher implements Runnable {
        public void run() {
            long backoff = 1;
            while (true) {
                try {
                    Socket s = socket.accept();
                    backoff = 1;
                    try {
                        executor.execute(() -> {
                        try {
                            acceptConnection(s);
                        } catch (IOException | RuntimeException t) {
                            // socket-level failures during connection setup are
                            // client-side noise, not server faults: an RST'd
                            // connection fails setTcpNoDelay with EINVAL on
                            // macOS ("Invalid argument") and resets during the
                            // preface peek surface as SocketException too.
                            // keep them out of the WARNING stream; anything
                            // else (e.g. SSL handshake problems) stays visible
                            Level level = t instanceof java.net.SocketException ? Level.DEBUG : Level.WARNING;
                            logger.log(level, "unable to accept connection", t);
                            try {
                                s.close();
                            } catch (IOException ex) {
                                // socket already dead; nothing to close
                            }
                        }
                    });
                    } catch (RuntimeException e) {
                        // REE is the standard overload signal; any other
                        // RuntimeException from a user-supplied executor
                        // (custom bounded pools throw ISE) used to escape and
                        // kill the accept loop permanently - the server kept
                        // "started" while accepting nothing
                        logger.log(Level.DEBUG, "executor rejected connection", e);
                        try {
                            s.close();
                        } catch (IOException closeFailed) {
                            // the outer IOException catch would misattribute
                            // a close failure as an accept failure
                        }
                    }
                } catch (IOException e) {
                    if (isFinishing() || socket.isClosed()) {
                        if (!isFinishing()) {
                            logger.log(Level.ERROR, "socket accept failed", e);
                        }
                        return;
                    }
                    // a transient accept failure (e.g. EMFILE when the process
                    // runs out of file descriptors) must not kill the
                    // dispatcher: retry with exponential backoff so the server
                    // recovers once the resource is freed
                    logger.log(Level.ERROR, "socket accept failed, retrying in " + backoff + "ms", e);
                    try {
                        Thread.sleep(backoff);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    backoff = Math.min(backoff * 2, 1000);
                }
            }
        }
        private void acceptConnection(Socket s) throws IOException {
            if (isFinishing()) {
                // a queued accept task running after stop()'s connection sweep:
                // the connection would be registered into the freshly swept set
                // with the cleaner timer already cancelled, so nothing would
                // ever close it (leaked socket + blocked exchange thread)
                try {
                    s.close();
                } catch (IOException ignored) {
                }
                return;
            }
            if(logger.isLoggable(Level.TRACE)) {
                logger.log(Level.TRACE, "accepted connection: " + s.toString());
            }
            stats.connectionCount.incrementAndGet();
            if (MAX_CONNECTIONS > 0 && connectionCount.get() >= MAX_CONNECTIONS) {
                // we've hit max limit of current open connections, so we go
                // ahead and close this connection without processing it
                try {
                    stats.maxConnectionsExceededCount.incrementAndGet();
                    logger.log(Level.WARNING, "closing accepted connection due to too many connections");
                    s.close();
                } catch (IOException ignore) {
                }
                return;
            }

            if (ServerConfig.noDelay()) {
                s.setTcpNoDelay(true);
            }

            boolean http2 = false;

            if (https) {
                // for some reason, creating an SSLServerSocket and setting the default parameters would
                // not work, so upgrade to a SSLSocket after connection
                SSLSocketFactory ssf = httpsConfig.getSSLContext().getSocketFactory();
                // autoClose=true: with false, closing the layered SSLSocket
                // never closes the underlying accepted socket (JDK
                // SSLSocketImpl.closeSocket only calls super.close() when
                // autoClose || !isLayered()) - every HTTPS connection leaked
                // its raw socket fd (CLOSE_WAIT accumulation until EMFILE)
                SSLSocket sslSocket = (SSLSocket) ssf.createSocket(s, null, true);
                SSLConfigurator.configure(sslSocket,httpsConfig);

                sslSocket.setHandshakeApplicationProtocolSelector((_sslSocket, protocols) -> {
                    if (protocols.contains("h2") && ServerConfig.http2OverSSL()) {
                        return "h2";
                    }
                    if (protocols.contains("http/1.1")) {
                        return "http/1.1";
                    }
                    // RFC 7301: never answer with a protocol the client did
                    // not offer (a gRPC client offering only "h2" against
                    // http2OverSSL=false used to get "http/1.1" - strict
                    // OpenSSL peers abort the handshake on the mismatch).
                    // "" (NOT null) omits the ALPN extension from the reply:
                    // the JDK selector contract treats null as "no agreement"
                    // and answers with a fatal no_application_protocol alert,
                    // hard-failing the TLS handshake for every h2-only client
                    return "";
                });
                // the following forces the SSL handshake to complete in order to determine the negotiated protocol.
                // the handshake is bounded so a stalled client cannot hold the connection thread indefinitely
                // clamp before the int cast: a huge configured timeout
                // overflowed to negative and made every https accept throw
                sslSocket.setSoTimeout((int) Math.min(
                    Math.max(ServerConfig.getSslHandshakeTimeoutMillis(), 1), Integer.MAX_VALUE));
                try {
                    // getSession() SWALLOWS IOExceptions and returns a dummy
                    // invalid session on handshake failure (the JDK closes the
                    // socket in that case): without the validity check a dead
                    // socket proceeded into connection registration, and the
                    // finally's setSoTimeout(0) on the closed socket threw
                    // SocketException - misclassified as client noise at
                    // DEBUG, hiding every real handshake failure from the logs
                    if (!sslSocket.getSession().isValid()) {
                        throw new java.net.SocketException("SSL handshake failed");
                    }
                } finally {
                    try {
                        sslSocket.setSoTimeout(0);
                    } catch (java.net.SocketException closedDuringHandshake) {
                        // socket already closed by the failed handshake
                    }
                }
                if ("h2".equals(sslSocket.getApplicationProtocol())) {
                    logger.log(Level.DEBUG, () -> "http2 connection "+ sslSocket);
                    http2 = true;
                    // protocol-specific TCP_NODELAY (see ServerConfig.http2NoDelay)
                    sslSocket.setTcpNoDelay(ServerConfig.http2NoDelay());
                } else {
                    logger.log(Level.DEBUG, () -> "http/1.1 connection "+ sslSocket);
                }
                s = sslSocket;
            }

            HttpConnection c = new HttpConnection(s);
            try {
                allConnections.add(c);
                connectionCount.incrementAndGet();
                connectionRegistered();
                if (isFinishing()) {
                    // stop()'s sweep may have completed between the entry
                    // check and this registration (the SSL handshake can
                    // stretch the window by seconds): nothing would ever
                    // close this connection - the sweep is done and the
                    // cleaner timer is cancelled. close it here
                    closeConnection(c);
                    return;
                }

                if (http2) {
                    if (c.getInputStream() instanceof NoSyncBufferedInputStream nis) {
                        nis.growBuffer();
                    }
                    Http2ConnectionHandler t = new Http2ConnectionHandler(protocol, c);
                    t.run();
                } else {
                    Http1ConnectionHandler t = new Http1ConnectionHandler(protocol, c);
                    t.run();
                }
            } catch (Throwable t) {
                logger.log(Level.WARNING, "Dispatcher Exception", t);
                stats.handleExceptionCount.incrementAndGet();
                closeConnection(c);
            }
        }
    }

    Logger getLogger() {
        return logger;
    }

    /** true when no connection is parsing or handling a request (h1
     * inRequest, or h2 streams in progress) - used by stop(delay)'s
     * early-exit so mid-flight requests are not abandoned */
    private boolean noConnectionBusy() {
        for (var c : allConnections) {
            if (c.inRequest || (c.http2Connection != null && c.http2Connection.requestsInProgress.get() > 0)) {
                return false;
            }
        }
        return true;
    }

    /** called when an h2 connection registers: attaches the keepalive ping
     * task on the first registration, creating the timer if needed. the
     * task is detached by h2ConnectionClosed() on the last disconnect, so
     * a server with no h2 connections pays no per-second ping wakeups
     * (previously the task fired forever from server start, pinging an
     * empty registry every second).
     *
     * the timer is SEPARATE from the connection-cleaner's because the ping
     * write takes the connection lock, which a stalled h2 download can
     * hold in a blocking socket write for the TCP retransmission timeout;
     * sharing the single-threaded cleaner timer let that freeze
     * idle-close, maxReqTime and maxIdleConnections enforcement
     * server-wide.
     *
     * both this and the removal path synchronize fully on pingTaskLock and
     * mutate the registry BEFORE taking the lock: whichever side wins the
     * lock either observes the other's registry change or leaves pingTask
     * null for the latecomer to (re)schedule, so the registry can never be
     * left non-empty with no task attached */
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

    /** called when an h2 connection deregisters: detaches the keepalive ping
     * task once the LAST h2 connection is gone, so no timer fire happens
     * while the registry is empty. the timer itself survives (its thread
     * parks at zero CPU on the empty queue) and is reused by the next h2
     * connection; it dies only with stop()'s pingTimer.cancel() */
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

    /** the h2 keepalive ping task: iterates THIS server's h2 connections
     * (the registry is per-instance - a JVM-global one made every server
     * ping every other server's connections and let one server's ping
     * failure close another's live connection) */
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

    private void closeConnection(HttpConnection conn) {
        // the connection's own thread: deliver any pending coalesced response
        closeConnection(conn, true);
    }

    /** @param flushResponse false from the cleaner/stop threads: the flush
     *  can block on a stalled peer's full kernel send buffer, and those
     *  threads must never be held hostage by a remote client */
    private void closeConnection(HttpConnection conn, boolean flushResponse) {
        logger.log(Level.TRACE, () -> "closing connection: " + conn.toString());
        conn.close(flushResponse);
        if (allConnections.remove(conn)) {
            connectionCount.decrementAndGet();
        }
        connectionClosed();
    }

    /* used to link to 2 or more Filter.Chains together */
    class LinkHandler implements HttpHandler {

        Filter.Chain nextChain;

        LinkHandler(Filter.Chain nextChain) {
            this.nextChain = nextChain;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            nextChain.doFilter(exchange);
        }
    }

    class Http2ConnectionHandler implements Runnable,Http2Connection.StreamHandler {
        final HttpConnection connection;
        final Http2Connection http2;
        final String protocol;

        void debug() {
            // the CALLER already iterates allHttp2Connections: the loop here
            // made the dump quadratic (N connections x N dumps) after the
            // static-to-instance registry conversion
            http2.debug();
        }

        Http2ConnectionHandler(String protocol, HttpConnection conn) throws IOException {
            this.connection = conn;
            this.protocol = protocol;

            if(protocol.equals("https")) {
                // the server protocol is "http"/"https" (never "https2" -
                // that comparison was always false and counted every ALPN
                // h2 connection as non-SSL in the stats)
                if (Http2Stats.ENABLED) http2Stats.sslConnections.incrementAndGet();
            } else {
                if (Http2Stats.ENABLED) http2Stats.nonsslConnections.incrementAndGet();
            }

            http2 = new Http2Connection(conn,http2Stats,connection.getInputStream(), connection.getOutputStream(), this);
            // let the idle cleaner reach the h2 stream buffer pool for shrinking
            conn.http2Connection = http2;
        }

        @Override
        public void run() {
            allHttp2Connections.add(this);
            h2ConnectionRegistered();

            try {
                if(!http2.hasProperPreface()) {
                    http2.sendGoAway(Http2ErrorCode.PROTOCOL_ERROR);
                    logger.log(Level.WARNING, "ServerImpl HTTP2 preface failed");
                    return;
                }
                http2.sendMySettings();
                http2.handle();
            } catch (Http2Exception ex) {
                logger.log(Level.WARNING, "ServerImpl http2 protocol exception "+http2,ex);
            } catch (IOException ex) {
                stats.socketExceptionCount.incrementAndGet();
                logger.log(Level.DEBUG, "end of stream "+http2);
            } catch (Exception ex) {
                logger.log(Level.WARNING, "ServerImpl unexpected exception handling http2 connection "+http2, ex);
            } finally {
                try {
                    logger.log(Level.DEBUG, "closing HTTP2 connection and streams "+http2);
                    closeConnection(connection);
                    http2.close();
                } catch (Throwable t) {
                    logger.log(Level.WARNING, "error closing http2 connection "+http2, t);
                }
                allHttp2Connections.remove(this);
                h2ConnectionClosed();
            }
        }

        @Override
        public Executor getExecutor() {
            return ServerImpl.this.executor;
        }

        @Override
        public void handleStream(Http2Stream stream,InputStream in, OutputStream out) throws IOException {
            connection.requestCount.incrementAndGet();
            if (ServerStats.ENABLED) stats.requestCount.incrementAndGet();

            if (Http2Stats.ENABLED) http2Stats.totalStreams.incrementAndGet();

            var request = stream.getRequestHeaders();
            var response = stream.getResponseHeaders();

            String scheme = https ? "https" : "http";
            String authority = request.getFirst(":authority");
            String path = request.getFirst(":path");
            String query = request.getFirst(":query");

            logger.log(Level.TRACE, () -> "http2 stream started "+ stream);

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

            request.set("Host",authority);

            URI uri;
            try {
                uri = new URI(scheme,authority,path,query,null);
            } catch (URISyntaxException ex) {
                throw new IOException("invalid uri",ex);
            }

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
                logger.log(Level.DEBUG, "No context found for request "+uriPath+", rejecting as not found");
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

            logger.log(Level.TRACE,() -> "http2 request on "+connection+" "+method+" for "+uri);

            try {
                // doFilterTracked is the SINGLE counting site for the
                // in-flight exchange counter (h1 counts there too) - the old
                // outer increment/finally here double-counted every h2 request
                doFilterTracked(buildChain(ctx), new Http2ExchangeImpl(stream,uri,method,ctx,request,response,in,out));
            } catch (IOException e) {
                // common with clients breaking connections; the stream is
                // closed by the performRequest finally block
                logger.log(Level.DEBUG, "io exception on stream " + stream, e);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Dispatcher Exception on "+stream, e);
                stats.handleExceptionCount.incrementAndGet();
            }
        }
    }

    /** build the user-visible filter chain: the system (internal) chain wraps
     * the handler, the user chain wraps that via a LinkHandler so both are
     * invoked in one doFilter call. shared by the http/1 and http/2 request
     * paths so the chain construction cannot drift between them */
    private Filter.Chain buildChain(HttpContextImpl ctx) {
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

    /* per exchange task */
    class Http1ConnectionHandler implements Runnable {
        final HttpConnection connection;
        InputStream rawin;
        OutputStream rawout;
        String protocol;
        ExchangeImpl tx;
        HttpContextImpl ctx;

        Http1ConnectionHandler(String protocol, HttpConnection conn) throws IOException {
            this.connection = conn;
            this.protocol = protocol;
        }

        @Override
        public void run() {
            this.rawin = connection.getInputStream();
            this.rawout = connection.getOutputStream();

            logger.log(Level.TRACE, () -> "exchange started "+ connection);

            while (!connection.closed) {
                try {
                    // between requests: the reader thread owns the input buffer
                    // at this point (no fill() in progress), so it is safe to
                    // shrink it here. the next fill() grows it back on demand
                    if (rawin instanceof NoSyncBufferedInputStream nis) {
                        nis.shrinkForNextRequest();
                    }
                    runPerRequest();
                } catch (RequestRejectedException e) {
                    // a malformed request: reply and close (sendReply's
                    // closeNow sets connection.closed, ending the loop)
                    reject(e.code, e.requestLine, e.getMessage());
                } catch (IOException e) {
                    // these are common with clients breaking connections etc
                    logger.log(Level.TRACE, "ServerImpl IOException", e);
                    stats.socketExceptionCount.incrementAndGet();
                    closeConnection(connection);
                    break;
                } catch (Exception e) {
                    logger.log(Level.WARNING, "ServerImpl unexpected exception", e);
                    if (tx == null || !tx.sentHeaders || !tx.closed) {
                        closeConnection(connection);
                        break;
                    }
                    // the handler already responded, but its request body may
                    // not have been fully consumed: apply the same EOF guard as
                    // the normal path, otherwise leftover body bytes get parsed
                    // as the next request on this connection (desync/smuggling)
                    LeftOverInputStream is = tx.getOriginalInputStream();
                    if (is == null || !is.isEOF()) {
                        logger.log(Level.DEBUG, "connection not at EOF after exchange exception, closing");
                        closeConnection(connection);
                        break;
                    }
                } catch (Throwable t) {
                    closeConnection(connection);
                    logger.log(Level.ERROR, "ServerImpl critical error", t);
                    throw t;
                }
            }
            logger.log(Level.TRACE, () -> "exchange finished "+ connection);
        }

        private void runPerRequest() throws IOException {
            /* context will be null for new connections */

            logger.log(Level.TRACE,"reading request");

            connection.inRequest = false;

            Request req;
            final String requestLine;
            try {
                req = new Request(rawin, rawout);
                requestLine = req.requestLine();
            } catch (IOException e) {
                // mark in-request before the 400 write: with inRequest false
                // the idle cleaner may concurrently shrink the output buffer
                // the reply is written into
                connection.inRequest = true;
                // surface the ACTUAL parse error: the old fixed message
                // reported every header-line failure as "Request line too
                // long" (the differential harness hit it on the blank-line
                // cap)
                throw new RequestRejectedException(HttpStatus.HTTP_BAD_REQUEST, "-",
                    e.getMessage() == null ? "malformed request" : e.getMessage());
            }

            if("PRI * HTTP/2.0".equals(requestLine) && ServerConfig.http2OverNonSSL()) {
                // mark busy BEFORE the handoff: between the request line and
                // the h2 connection construction no busy marker existed, so
                // stop(delay)'s sweep could close a connection mid-upgrade
                connection.inRequest = true;
                logger.log(Level.DEBUG,"found http2 request on non-SSL assuming prior knowledge");
                // h2 frames (16KB DATA) must be read at the full buffer size,
                // not the 2KB idle size the connection may carry
                if (rawin instanceof NoSyncBufferedInputStream nis) {
                    nis.growBuffer();
                }
                // protocol-specific TCP_NODELAY (see ServerConfig.http2NoDelay)
                connection.getSocket().setTcpNoDelay(ServerConfig.http2NoDelay());
                Http2ConnectionHandler exchange = new Http2ConnectionHandler(protocol, connection);
                // clear the upgrade-window marker: the h2 connection is now
                // registered, and its busy state is tracked by
                // requestsInProgress (the cleaner's h2 branch). leaving
                // inRequest=true forever would make idle h2c connections
                // immortal - never idle-closed, never shrunk, never counted
                // toward maxIdleConnections (the round-2 bug, re-introduced
                // by the upgrade-window fix and caught in review)
                connection.inRequest = false;
                exchange.run();
                return;
            }

            connection.inRequest = true;

            if (requestLine.isEmpty()) {
                /* connection closed */
                logger.log(Level.DEBUG, "no request line: closing");
                closeConnection(connection);
                return;
            }
            connection.requestCount.incrementAndGet();
            if (ServerStats.ENABLED) stats.requestCount.incrementAndGet();

            logger.log(Level.DEBUG, () -> "Http1 request line: "+ requestLine);
            int space = requestLine.indexOf(" ");
            if (space == -1) {
                throw new RequestRejectedException(HttpStatus.HTTP_BAD_REQUEST, requestLine, "Bad request line");
            }
            String method = requestLine.substring(0, space);
            int start = space + 1;
            space = requestLine.indexOf(" ", start);
            if (space == -1) {
                throw new RequestRejectedException(HttpStatus.HTTP_BAD_REQUEST, requestLine, "Bad request line");
            }
            String uriStr = requestLine.substring(start, space);
            // reject authority-form ambiguity: "//" can be parsed as an
            // authority component rather than a path (JDK parity)
            if (uriStr.startsWith("//")) {
                throw new RequestRejectedException(HttpStatus.HTTP_BAD_REQUEST, requestLine, "Bad request URI");
            }
            URI uri;
            try {
                uri = new URI(uriStr);
            } catch (URISyntaxException e3) {
                throw new RequestRejectedException(HttpStatus.HTTP_BAD_REQUEST, requestLine, "URISyntaxException thrown");
            }
            start = space + 1;
            String version = requestLine.substring(start);
            if (!"HTTP/1.1".equals(version) && !"HTTP/1.0".equals(version)) {
                // the h2c PRI line was already handled above; anything else
                // is an unsupported protocol version
                throw new RequestRejectedException(HttpStatus.HTTP_VERSION, requestLine, "Unsupported HTTP version");
            }
            Headers headers;
            try {
                headers = req.headers();
            } catch (IOException e) {
                throw new RequestRejectedException(HttpStatus.HTTP_BAD_REQUEST, requestLine, "Malformed header line");
            }

            long clen = resolveBodyLength(requestLine, headers);

            // a declared large body: switch to the full-size read buffer so
            // the body is consumed in 16KB reads, not 2KB
            if (clen > NoSyncBufferedInputStream.GROW_BODY_THRESHOLD && rawin instanceof NoSyncBufferedInputStream nis) {
                nis.growBuffer();
            }
            long bodySizeLimit = ServerConfig.getMaxRequestBodySize();
            if (bodySizeLimit > 0 && clen > bodySizeLimit) {
                // declared Content-Length over the opt-in body cap. chunked
                // bodies without a declared length are bounded incrementally
                // in LeftOverInputStream
                throw new RequestRejectedException(HttpStatus.HTTP_ENTITY_TOO_LARGE, requestLine,
                        "Request body exceeds the configured maximum of " + bodySizeLimit + " bytes");
            }
            logger.log(Level.TRACE,() -> "protocol "+protocol+" uri "+uri+" headers "+headers);
            // null-check, not Optional.ofNullable: a fresh Optional per request is
            // pure allocation on the dispatch hot path
            String uriPath = uri.getPath();
            if (uriPath == null) {
                uriPath = "/";
            }
            ctx = contexts.findContext(protocol, uriPath);
            if (ctx == null) {
                throw new RequestRejectedException(HttpStatus.HTTP_NOT_FOUND, requestLine, "No context found for request");
            }
            connection.setContext(ctx);
            if (ctx.getHandler() == null) {
                throw new RequestRejectedException(HttpStatus.HTTP_INTERNAL_ERROR, requestLine, "No handler for context");
            }
            tx = new ExchangeImpl(method, uri, req, clen, connection);
            String chdr = headers.getFirst("Connection");
            Headers rheaders = tx.getResponseHeaders();

            if (chdr != null && chdr.equalsIgnoreCase("close")) {
                tx.close = true;
            }
            if (version.equalsIgnoreCase("http/1.0")) {
                tx.http10 = true;
                if (chdr == null) {
                    tx.close = true;
                } else if (chdr.equalsIgnoreCase("keep-alive")) {
                    rheaders.set("Connection", "keep-alive");
                    int idleSeconds = (int) (ServerConfig.getIdleIntervalMillis() / 1000);
                    String val = "timeout=" + idleSeconds;
                    rheaders.set("Keep-alive", val);
                }
            }

            if(tx.close) {
                rheaders.set("Connection", "close");
            }

            /*
                * check if client sent an Expect 100 Continue.
                * In that case, need to send an interim response.
                * In future API may be modified to allow app to
                * be involved in this process.
             */
            String exp = headers.getFirst("Expect");
            // HTTP/1.0 clients have no 1xx concept (RFC 7231 5.1.1: the
            // expectation MUST be ignored): the interim 100 was parsed as
            // the final response and desynced the connection
            if (exp != null && exp.equalsIgnoreCase("100-continue") && req.protocol().endsWith("1.1")) {
                logReply(100, requestLine, null);
                sendReply(
                        HttpStatus.HTTP_CONTINUE, false, null);
            }
            /*
                * uf is the list of filters seen/set by the user.
                * sf is the list of filters established internally
                * and which are not visible to the user. uc and sc
                * are the corresponding Filter.Chains.
                * They are linked together by a LinkHandler
                * so that they can both be invoked in one call.
                */
            /* set up the two stream references */
            tx.getRequestBody();
            tx.getResponseBody();
            Filter.Chain uc = buildChain(ctx);
            doFilterTracked(uc, https ? new HttpsExchangeImpl(tx) : new HttpExchangeImpl(tx));
            if (tx.close) {
                closeConnection(connection);
            } else {
                LeftOverInputStream is = tx.getOriginalInputStream();
                if (is == null || !is.isEOF()) {
                    // the request body was not fully consumed (or the connection
                    // was upgraded), so the connection cannot be safely reused
                    logger.log(Level.DEBUG, "connection not at EOF after exchange, closing");
                    closeConnection(connection);
                }
                tx = null;
            }
        }

        /** resolve the request body length from the Content-Length /
         *  Transfer-Encoding headers: 0 = none, -1 = chunked. throws
         *  RequestRejectedException on conflicting or malformed values */
        private long resolveBodyLength(String requestLine, Headers headers) throws IOException {
            List<String> clHeader = headers.get("Content-length");
            List<String> teHeader = headers.get("Transfer-encoding");
            if (clHeader != null && (teHeader != null || clHeader.size() > 1)) {
                throw new RequestRejectedException(HttpStatus.HTTP_BAD_REQUEST, requestLine,
                        "Conflicting or malformed headers detected");
            }
            if (teHeader != null && !teHeader.isEmpty()) {
                String teValue = teHeader.get(0);
                if (teValue.equalsIgnoreCase("chunked") && teHeader.size() == 1) {
                    return -1L;
                }
                throw new RequestRejectedException(HttpStatus.HTTP_NOT_IMPLEMENTED, requestLine,
                        "Unsupported Transfer-Encoding value");
            }
            String headerValue = headers.getFirst("Content-length");
            if (headerValue != null) {
                // RFC 7230 5.3: Content-Length is 1*DIGIT only - the JDK's
                // parseLong also accepts a leading '+' or '-'
                if (!isDigits(headerValue)) {
                    throw new RequestRejectedException(HttpStatus.HTTP_BAD_REQUEST, requestLine,
                            "Illegal Content-length value");
                }
                try {
                    return Long.parseLong(headerValue);
                } catch (NumberFormatException e2) {
                    throw new RequestRejectedException(HttpStatus.HTTP_BAD_REQUEST, requestLine,
                            "NumberFormatException thrown");
                }
            }
            return 0L;
        }

        /** a request was rejected during parsing: run()'s catch replies and
         * closes - replaces the 14 reject+return pairs that used to be
         * scattered through runPerRequest */
        private static class RequestRejectedException extends IOException {
            final int code;
            final String requestLine;

            RequestRejectedException(int code, String requestLine, String message) {
                super(message);
                this.code = code;
                this.requestLine = requestLine;
            }
        }
        void reject(int code, String requestStr, String message) {
            logReply(code, requestStr, message);
            sendReply(
                    code, true, "<h1>" + code + HttpStatus.msg(code) + "</h1>" + message);
        }

        void sendReply(
                int code, boolean closeNow, String text) {
            try {
                StringBuilder builder = new StringBuilder(512);
                builder.append("HTTP/1.1 ")
                        .append(code).append(HttpStatus.msg(code)).append("\r\n");

                var informational = (code >= 100 && code < 200);

                if (text != null && text.length() != 0) {
                    builder.append("Content-length: ")
                            .append(text.length()).append("\r\n")
                            .append("Content-type: text/html\r\n");
                } else {
                    if (!informational) {
                        // no body for 1xx responses
                        builder.append("Content-length: 0\r\n");
                    }
                    text = "";
                }
                if (closeNow) {
                    builder.append("Connection: close\r\n");
                }
                builder.append("\r\n").append(text);
                rawout.write(builder.toString().getBytes(ISO_8859_1));
                rawout.flush();
                if (closeNow) {
                    // a rejected request never has a tx: mark the connection
                    // directly - the peer's malformed request is the last
                    // thing it sent, so the close drain has nothing to do
                    connection.peerDoneSending = true;
                    closeConnection(connection);
                }
            } catch (IOException e) {
                logger.log(Level.TRACE, "ServerImpl.sendReply", e);
                stats.replyErrorCount.incrementAndGet();
                closeConnection(connection);
            }
        }

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
        logger.log(Level.DEBUG, () -> "reply "+ r + " [" + code + HttpStatus.msg(code) + "] (" + (text!=null ? text : "") + ")");
    }

    HttpServer getWrapper() {
        return wrapper;
    }

    /**
     * Responsible for closing connections that have been idle or exceed other
     * limits
     */
    /** called when a connection is accepted: attaches the
     * connection-cleaner task on the first registration, creating the
     * timer if needed. the task is detached by connectionClosed() when the
     * last connection closes, so a server with no open connections pays no
     * periodic wakeups at all (previously the task fired forever from
     * server start, sweeping an empty registry every 10s).
     *
     * the timer is SEPARATE from the h2 keepalive ping's because the
     * cleaner's closeConnection write path must stay independent of a
     * stalled peer (and vice versa: the original single shared timer let a
     * stalled h2 download freeze idle-close server-wide).
     *
     * both this and the removal path synchronize fully on cleanerTaskLock
     * and mutate the registry BEFORE taking the lock: whichever side wins
     * the lock either observes the other's registry change or leaves
     * cleanerTask null for the latecomer to (re)schedule, so the registry
     * can never be left non-empty with no task attached */
    private void connectionRegistered() {
        synchronized (cleanerTaskLock) {
            if (finished) {
                return;
            }
            if (cleanerTask == null) {
                if (cleanerTimer == null) {
                    cleanerTimer = new Timer("connection-cleaner", true);
                }
                cleanerTask = new ConnectionCleanerTask();
                cleanerTimer.schedule(cleanerTask, IDLE_TIMER_TASK_SCHEDULE, IDLE_TIMER_TASK_SCHEDULE);
            }
        }
    }

    /** called when a connection closes: detaches the connection-cleaner
     * task once the LAST connection is gone, so no timer fire happens
     * while the registry is empty. the timer itself survives (its thread
     * parks at zero CPU on the empty queue) and is reused by the next
     * accepted connection; it dies only with stop()'s cancel. cancelling
     * the running task from inside the cleaner itself (it just closed the
     * last connection) is legal: cancel() only prevents future runs */
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

    class ConnectionCleanerTask extends TimerTask {

        @Override
        public void run() {
            // System.currentTimeMillis, not the CachedClock snapshot: the
            // cleaner compares this against lastActivityTime/drainingAt,
            // which are plain wall-clock writes (see HttpConnection) - both
            // sides of the comparison must use the same source
            long now = System.currentTimeMillis();

            for (var c : allConnections) {
                // an h2c connection's inRequest is only true during the upgrade window
                // (cleared at the Http2ConnectionHandler handoff); ALPN h2 never sets it.
                // busy state is requestsInProgress: streams in progress must not be
                // idle-closed mid-flight
                boolean busy = c.inRequest
                    || (c.http2Connection != null && c.http2Connection.requestsInProgress.get() > 0);
                if (c.drainingAt != 0 && now- c.drainingAt >= IDLE_INTERVAL / 2) {
                    closeConnection(c, false);
                    continue;
                }
                if (now- c.lastActivityTime >= IDLE_INTERVAL && !busy) {
                    logger.log(Level.DEBUG, "closing idle connection");
                    stats.idleCloseCount.incrementAndGet();
                    closeConnection(c, false);
                } else if (MAX_REQ_TIME != -1 && busy && !c.streamsHandedOff
                        && (now - c.lastActivityTime >= MAX_REQ_TIME)) {
                    // streamsHandedOff (websocket/CONNECT tunnels) is NOT a
                    // "request": a quiet websocket session is legal (app-level
                    // keepalive), but inRequest=true made it look stalled and
                    // the cleaner killed it at maxReqTime
                    logger.log(Level.WARNING, "closing connection due to request processing time");
                    closeConnection(c, false);
                } else if (!busy && now - c.lastActivityTime >= IDLE_INTERVAL / 4) {
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
            if (MAX_IDLE_CONNECTIONS > 0) {
                List<HttpConnection> idle = new ArrayList<>();
                for (var c : allConnections) {
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
                    boolean busy = c.inRequest
                        || (c.http2Connection != null && c.http2Connection.requestsInProgress.get() > 0);
                    if (!busy && now - c.lastActivityTime >= IDLE_INTERVAL / 2) {
                        idle.add(c);
                    }
                }
                if (idle.size() > MAX_IDLE_CONNECTIONS) {
                    idle.sort(java.util.Comparator.comparingLong(c -> c.lastActivityTime));
                    for (int i = 0; i < idle.size() - MAX_IDLE_CONNECTIONS; i++) {
                        logger.log(Level.DEBUG, "closing idle connection (over max idle limit)");
                        stats.idleCloseCount.incrementAndGet();
                        closeConnection(idle.get(i), false);
                    }
                }
            }
        }
    }

    /** run the filter chain with the in-flight exchange counter maintained:
     * shared by the h1 and h2 request paths so the counting cannot drift */
    private void doFilterTracked(Filter.Chain chain, com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        activeExchanges.incrementAndGet();
        try {
            chain.doFilter(exchange);
        } finally {
            activeExchanges.decrementAndGet();
        }
    }

    /** reject an h2 stream with a bare status response: the h2 counterpart of
     * the h1 reject() helper (h1/h2 error paths must not drift apart) */
    private static void rejectStream(robaho.net.httpserver.http2.internal.Http2Stream stream,
            java.io.OutputStream out, String status) throws IOException {
        stream.getResponseHeaders().set(":status", status);
        stream.writeResponseHeaders(true);
        out.close();
    }

    private static boolean isDigits(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
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
