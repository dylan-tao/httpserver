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

import static java.nio.charset.StandardCharsets.ISO_8859_1;

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
import java.util.Optional;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;

import robaho.net.httpserver.http2.HTTP2Connection;
import robaho.net.httpserver.http2.HTTP2ErrorCode;
import robaho.net.httpserver.http2.HTTP2Exception;
import robaho.net.httpserver.http2.HTTP2Stats;
import robaho.net.httpserver.http2.HTTP2Stream;

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
    /** O(1) live connection count: deriving it from allConnections.size()
     * walks the whole backing map on every accept */
    private final java.util.concurrent.atomic.AtomicInteger connectionCount = new java.util.concurrent.atomic.AtomicInteger();
    // in-flight exchanges (h1 + h2 doFilter sections): lets stop(delay) return
    // as soon as the server goes idle instead of sleeping the full delay
    private final java.util.concurrent.atomic.AtomicInteger activeExchanges = new java.util.concurrent.atomic.AtomicInteger();
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
    private final Timer timer;
    private final Logger logger;
    private Thread dispatcherThread;

    // statistics
    private final ServerStats stats = new ServerStats();
    private final HTTP2Stats http2Stats = new HTTP2Stats();

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
        timer = new Timer("connection-cleaner", true);
        timer.schedule(new ConnectionCleanerTask(), IDLE_TIMER_TASK_SCHEDULE, IDLE_TIMER_TASK_SCHEDULE);
        // note: the shared ActivityTimer clock runs on its own daemon timer
        // (see ActivityTimer) so it survives this server instance
        timer.schedule(Http2Exchange.createTask(),1000,1000);
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

    /** log state to assist debugging */
    private class DebugHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            logger.log(Level.INFO,"logging debug state, requestor "+exchange.getRemoteAddress());
            for(var connection : allConnections) {
                connection.debug();
            }
            Http2Exchange.debug();
            exchange.sendResponseHeaders(200,-1);
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
            // since we created it, shut it done when stopping because it is private
            de.shutdown();
        }
        long latest = System.currentTimeMillis() + delay * 1000L;
        while (System.currentTimeMillis() < latest) {
            if (activeExchanges.get() == 0) {
                // all in-flight exchanges completed and no new ones can
                // start (finished is already set): return early instead of
                // sleeping out the full delay (JDK parity)
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
        for (HttpConnection c : allConnections) {
            c.close();
        }
        allConnections.clear();
        connectionCount.set(0);
        timer.cancel();

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
                        } catch (IOException t) {
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
                            }
                        }
                    });
                    } catch (RejectedExecutionException e) {
                        s.close();
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
                SSLSocket sslSocket = (SSLSocket) ssf.createSocket(s, null, false);
                SSLConfigurator.configure(sslSocket,httpsConfig);

                sslSocket.setHandshakeApplicationProtocolSelector((_sslSocket, protocols) -> {
                    if (protocols.contains("h2") && ServerConfig.http2OverSSL()) {
                        return "h2";
                    } else {
                        return "http/1.1";
                    }
                });
                // the following forces the SSL handshake to complete in order to determine the negotiated protocol.
                // the handshake is bounded so a stalled client cannot hold the connection thread indefinitely
                sslSocket.setSoTimeout((int) ServerConfig.getSslHandshakeTimeoutMillis());
                try {
                    sslSocket.getSession();
                } finally {
                    sslSocket.setSoTimeout(0);
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

                if (http2) {
                    Http2Exchange t = new Http2Exchange(protocol, c);
                    t.run();
                } else {
                    Exchange t = new Exchange(protocol, c);
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

    private void closeConnection(HttpConnection conn) {
        logger.log(Level.TRACE, () -> "closing connection: " + conn.toString());
        conn.close();
        if (allConnections.remove(conn)) {
            connectionCount.decrementAndGet();
        }
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

    class Http2Exchange implements Runnable,HTTP2Connection.StreamHandler {
        final HttpConnection connection;
        final HTTP2Connection http2;
        final String protocol;

        private static final Set<Http2Exchange> allHttp2Exchanges = Collections.newSetFromMap(new ConcurrentHashMap<>());
        static void debug() {
            for(var exchange : allHttp2Exchanges) {
                exchange.http2.debug();
            }
        }

        Http2Exchange(String protocol, HttpConnection conn) throws IOException {
            this.connection = conn;
            this.protocol = protocol;

            if(protocol.equals("https2")) {
                http2Stats.sslConnections.incrementAndGet();
            } else {
                http2Stats.nonsslConnections.incrementAndGet();
            }

            http2 = new HTTP2Connection(conn,http2Stats,connection.getInputStream(), connection.getOutputStream(), this);
            // let the idle cleaner reach the h2 stream buffer pool for shrinking
            conn.http2Connection = http2;
        }

        static TimerTask createTask() {
            return new TimerTask() {
                @Override
                public void run() {
                    long now = System.currentTimeMillis();
                    for(var exchange : allHttp2Exchanges) {
                        if(exchange.connection.lastActivityTime + 1000 < now) {
                            try {
                                // the ping write refreshes lastActivityTime via the
                                // activity timer stream; restore it afterwards so a
                                // dead connection still appears idle to the cleaner.
                                // NOTE: a real activity landing inside the microsecond
                                // window of the ping write is clobbered by the restore;
                                // any live request generates further socket activity
                                // that re-updates the timestamp, so this micro-race is
                                // accepted (see todo.md 二次排查 item 13)
                                var before = exchange.connection.lastActivityTime;
                                exchange.http2.sendPing();
                                exchange.connection.lastActivityTime = before;
                            } catch (IOException ex) {
                                // ping failed - the connection is dead. closing the
                                // socket unblocks the reader thread, whose finally
                                // block performs the full cleanup
                                exchange.connection.close();
                            }
                        }
                    }
                }
        };
    }

        @Override
        public void run() {
            allHttp2Exchanges.add(this);

            try {
                if(!http2.hasProperPreface()) {
                    http2.sendGoAway(HTTP2ErrorCode.PROTOCOL_ERROR);
                    logger.log(Level.WARNING, "ServerImpl HTTP2 preface failed");
                    return;
                }
                http2.sendMySettings();
                http2.handle();
            } catch (HTTP2Exception ex) {
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
                allHttp2Exchanges.remove(this);
            }
        }

        @Override
        public Executor getExecutor() {
            return ServerImpl.this.executor;
        }

        @Override
        public void handleStream(HTTP2Stream stream,InputStream in, OutputStream out) throws IOException {
            connection.requestCount.incrementAndGet();
            if (ServerStats.ENABLED) stats.requestCount.incrementAndGet();

            if (HTTP2Stats.ENABLED) http2Stats.totalStreams.incrementAndGet();

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

            String uriPath = Optional.ofNullable(uri.getPath()).orElse("/");
            HttpContextImpl ctx = contexts.findContext(protocol, uriPath);
            if (ctx == null) {
                logger.log(Level.DEBUG, "No context found for request "+uriPath+", rejecting as not found");
                response.set(":status","404");
                stream.writeResponseHeaders(true);
                out.close();
                return;
            }

            logger.log(Level.TRACE,() -> "http2 request on "+connection+" "+method+" for "+uri);

            try {
                activeExchanges.incrementAndGet();
                buildChain(ctx).doFilter(new Http2ExchangeImpl(stream,uri,method,ctx,request,response,in,out));
            } catch (IOException e) {
                // common with clients breaking connections; the stream is
                // closed by the performRequest finally block
                logger.log(Level.DEBUG, "io exception on stream " + stream, e);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Dispatcher Exception on "+stream, e);
                stats.handleExceptionCount.incrementAndGet();
            } finally {
                activeExchanges.decrementAndGet();
            }
        }
    }

    /** build the user-visible filter chain: the system (internal) chain wraps
     * the handler, the user chain wraps that via a LinkHandler so both are
     * invoked in one doFilter call. shared by the http/1 and http/2 request
     * paths so the chain construction cannot drift between them */
    private Filter.Chain buildChain(HttpContextImpl ctx) {
        final List<Filter> sf = ctx.getSystemFilters();
        final List<Filter> uf = ctx.getFilters();
        final Filter.Chain sc = new Filter.Chain(sf, ctx.getHandler());
        return new Filter.Chain(uf, new LinkHandler(sc));
    }

    /* per exchange task */
    class Exchange implements Runnable {
        final HttpConnection connection;
        InputStream rawin;
        OutputStream rawout;
        String protocol;
        ExchangeImpl tx;
        HttpContextImpl ctx;

        Exchange(String protocol, HttpConnection conn) throws IOException {
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
                reject(Code.HTTP_BAD_REQUEST, "-", "Request line too long");
                return;
            }

            if("PRI * HTTP/2.0".equals(requestLine) && ServerConfig.http2OverNonSSL()) {
                logger.log(Level.DEBUG,"found http2 request on non-SSL assuming prior knowledge");
                // protocol-specific TCP_NODELAY (see ServerConfig.http2NoDelay)
                connection.getSocket().setTcpNoDelay(ServerConfig.http2NoDelay());
                Http2Exchange exchange = new Http2Exchange(protocol, connection);
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

            logger.log(Level.DEBUG, () -> "Exchange request line: "+ requestLine);
            int space = requestLine.indexOf(" ");
            if (space == -1) {
                reject(Code.HTTP_BAD_REQUEST,
                        requestLine, "Bad request line");
                return;
            }
            String method = requestLine.substring(0, space);
            int start = space + 1;
            space = requestLine.indexOf(" ", start);
            if (space == -1) {
                reject(Code.HTTP_BAD_REQUEST,
                        requestLine, "Bad request line");
                return;
            }
            String uriStr = requestLine.substring(start, space);
            // reject authority-form ambiguity: "//" can be parsed as an
            // authority component rather than a path (JDK parity)
            if (uriStr.startsWith("//")) {
                reject(Code.HTTP_BAD_REQUEST, requestLine, "Bad request URI");
                return;
            }
            URI uri;
            try {
                uri = new URI(uriStr);
            } catch (URISyntaxException e3) {
                reject(Code.HTTP_BAD_REQUEST,
                        requestLine, "URISyntaxException thrown");
                return;
            }
            start = space + 1;
            String version = requestLine.substring(start);
            Headers headers;
            try {
                headers = req.headers();
            } catch (IOException e) {
                reject(Code.HTTP_BAD_REQUEST, requestLine, "Malformed header line");
                return;
            }

            /* checks for unsupported combinations of lengths and encodings */
            List<String> clHeader = headers.get("Content-length");
            List<String> teHeader = headers.get("Transfer-encoding");
            if (clHeader != null && (teHeader != null || clHeader.size() > 1)) {
                reject(Code.HTTP_BAD_REQUEST, requestLine,
                        "Conflicting or malformed headers detected");
                return;
            }
            long clen = 0L;
            String headerValue = null;
            List<String> teValueList = teHeader;
            if (teValueList != null && !teValueList.isEmpty()) {
                headerValue = teValueList.get(0);
            }
            if (headerValue != null) {
                if (headerValue.equalsIgnoreCase("chunked") && teValueList.size() == 1) {
                    clen = -1L;
                } else {
                    reject(Code.HTTP_NOT_IMPLEMENTED,
                            requestLine, "Unsupported Transfer-Encoding value");
                    return;
                }
            } else {
                headerValue = headers.getFirst("Content-length");
                if (headerValue != null) {
                    // RFC 7230 5.3: Content-Length is 1*DIGIT only - the JDK's
                    // parseLong also accepts a leading '+' or '-'
                    if (!isDigits(headerValue)) {
                        reject(Code.HTTP_BAD_REQUEST,
                                requestLine, "Illegal Content-length value");
                        return;
                    }
                    try {
                        clen = Long.parseLong(headerValue);
                    } catch (NumberFormatException e2) {
                        reject(Code.HTTP_BAD_REQUEST,
                                requestLine, "NumberFormatException thrown");
                        return;
                    }
                }
            }
            logger.log(Level.TRACE,() -> "protocol "+protocol+" uri "+uri+" headers "+headers);
            String uriPath = Optional.ofNullable(uri.getPath()).orElse("/");
            ctx = contexts.findContext(protocol, uriPath);
            if (ctx == null) {
                reject(Code.HTTP_NOT_FOUND,
                        requestLine, "No context found for request");
                return;
            }
            connection.setContext(ctx);
            if (ctx.getHandler() == null) {
                reject(Code.HTTP_INTERNAL_ERROR,
                        requestLine, "No handler for context");
                return;
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
            if (exp != null && exp.equalsIgnoreCase("100-continue")) {
                logReply(100, requestLine, null);
                sendReply(
                        Code.HTTP_CONTINUE, false, null);
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
            activeExchanges.incrementAndGet();
            try {
                if (https) {
                    uc.doFilter(new HttpsExchangeImpl(tx));
                } else {
                    uc.doFilter(new HttpExchangeImpl(tx));
                }
            } finally {
                activeExchanges.decrementAndGet();
            }
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

        void reject(int code, String requestStr, String message) {
            logReply(code, requestStr, message);
            sendReply(
                    code, true, "<h1>" + code + Code.msg(code) + "</h1>" + message);
        }

        void sendReply(
                int code, boolean closeNow, String text) {
            try {
                StringBuilder builder = new StringBuilder(512);
                builder.append("HTTP/1.1 ")
                        .append(code).append(Code.msg(code)).append("\r\n");

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
        logger.log(Level.DEBUG, () -> "reply "+ r + " [" + code + Code.msg(code) + "] (" + (text!=null ? text : "") + ")");
    }

    HttpServer getWrapper() {
        return wrapper;
    }

    /**
     * Responsible for closing connections that have been idle or exceed other
     * limits
     */
    class ConnectionCleanerTask extends TimerTask {

        @Override
        public void run() {
            long now = ActivityTimer.now();

            for (var c : allConnections) {
                if (c.drainingAt != 0 && now- c.drainingAt >= IDLE_INTERVAL / 2) {
                    closeConnection(c);
                    continue;
                }
                if (now- c.lastActivityTime >= IDLE_INTERVAL && !c.inRequest) {
                    logger.log(Level.DEBUG, "closing idle connection");
                    stats.idleCloseCount.incrementAndGet();
                    closeConnection(c);
                } else if (MAX_REQ_TIME != -1 && c.inRequest && (now - c.lastActivityTime >= MAX_REQ_TIME)) {
                    logger.log(Level.WARNING, "closing connection due to request processing time");
                    closeConnection(c);
                } else if (!c.inRequest && now - c.lastActivityTime >= IDLE_INTERVAL / 4) {
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
                    // "idle" = no request in flight AND quiet for a while. h2
                    // connections never set inRequest (the h2c branch returns
                    // before it is set), so recency of lastActivityTime is the
                    // only signal that an h2 connection is still serving:
                    // without the recency condition the cap would count - and
                    // eventually close - ACTIVE h2 connections
                    if (!c.inRequest && now - c.lastActivityTime >= IDLE_INTERVAL / 2) {
                        idle.add(c);
                    }
                }
                if (idle.size() > MAX_IDLE_CONNECTIONS) {
                    idle.sort(java.util.Comparator.comparingLong(c -> c.lastActivityTime));
                    for (int i = 0; i < idle.size() - MAX_IDLE_CONNECTIONS; i++) {
                        logger.log(Level.DEBUG, "closing idle connection (over max idle limit)");
                        stats.idleCloseCount.incrementAndGet();
                        closeConnection(idle.get(i));
                    }
                }
            }
        }
    }

    /** returns true when s consists only of ASCII digits (1*DIGIT, RFC 7230) */
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
