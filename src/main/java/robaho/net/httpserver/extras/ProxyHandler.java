package robaho.net.httpserver.extras;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import javax.net.SocketFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Logger;

/**
 * proxy requests to another host
 */
public class ProxyHandler implements HttpHandler {
    public record HostPort(String server,int port,String scheme){}

    /** the proxy selected for the request currently being sent. bound with a
     * ScopedValue (JDK 25, JEP 506): immutable per scope, no ThreadLocalMap
     * per virtual thread, and cannot leak if a remove() is skipped. falls
     * back to the (bounded) proxies map when the client invokes the selector
     * on one of its own executor threads - outside the scope. */
    private static final ScopedValue<HostPort> CURRENT_PROXY = ScopedValue.newInstance();

    /** proxy selections keyed by URI authority - bounded by the set of distinct
     * upstream targets rather than by full request URIs */
    private final ConcurrentMap<String,HostPort> proxies = new ConcurrentHashMap<>();
    private final HttpClient proxyClient;
    private final Optional<HostPort> defaultProxy;

    protected final Logger logger = Logger.getLogger("robaho.net.httpserver.ProxyHandler");

    public ProxyHandler() {
        this(Optional.empty());
    }
    public ProxyHandler(HostPort defaultProxy) {
        this(Optional.of(defaultProxy));
    }
    private ProxyHandler(Optional<HostPort> defaultProxy) {
        this.defaultProxy = defaultProxy;
        proxyClient = HttpClient.newBuilder().proxy(new ProxySelector(){
            @Override
            public List<Proxy> select(URI uri) {
                // ScopedValue.get() throws when unbound (the selector can run
                // on HttpClient threads outside the scope): isBound() first
                var hp = CURRENT_PROXY.isBound() ? CURRENT_PROXY.get() : null;
                if (hp == null) {
                    hp = proxies.get(authorityOf(uri));
                }
                if (hp == null) {
                    // no proxy is known for this target - connect directly
                    // rather than failing with an NPE
                    return List.of(Proxy.NO_PROXY);
                }
                return List.of(new Proxy(Proxy.Type.HTTP,new InetSocketAddress(hp.server,hp.port)));
            }

            @Override
            /** {@inheritDoc} - a failed tunnel connect is logged and given up on */
            public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
                ProxyHandler.this.connectFailed(uri, sa, ioe);
            }
        }).build();
    }

    private static String authorityOf(URI uri) {
        String authority = uri.getAuthority();
        return authority != null ? authority : uri.toString();
    }

    /** forward the exchange to the target selected by the ProxySelector:
     *  streams request and response bodies through, hop-by-hop headers are
     *  not forwarded */
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if(exchange.getRequestMethod().equals("CONNECT")) {
            if(!authorizeConnect(exchange)) return;
            try (Socket s = SocketFactory.getDefault().createSocket()) {
                var uri = exchange.getRequestURI();
                int port;
                try {
                    port = Integer.parseInt(uri.getSchemeSpecificPart());
                } catch (NumberFormatException e) {
                    logger.warning("invalid CONNECT target port: " + uri);
                    exchange.sendResponseHeaders(400,-1);
                    return;
                }
                if (port < 1 || port > 65535) {
                    // parseInt accepts "-1"/"70000": InetSocketAddress would
                    // then throw IAE and kill the connection with an
                    // "unexpected exception" instead of a clean 400
                    logger.warning("CONNECT target port out of range: " + uri);
                    exchange.sendResponseHeaders(400,-1);
                    return;
                }
                var addr = new InetSocketAddress(uri.getScheme(),port);
                try {
                    // bound the connect attempt so a stalled upstream cannot
                    // hold the tunnel thread indefinitely
                    s.connect(addr, CONNECT_TIMEOUT_MILLIS);
                } catch(Exception e) {
                    logger.warning("failed to connect to "+addr+": "+e);
                    exchange.sendResponseHeaders(502,-1);
                    return;
                }
                logger.fine("connected to "+s.getRemoteSocketAddress());
                exchange.sendResponseHeaders(200,0);

                var executor = exchange.getHttpContext().getServer().getExecutor();
                if (executor == null) {
                    // no executor configured on the server: fall back rather
                    // than failing with an NPE
                    executor = java.util.concurrent.ForkJoinPool.commonPool();
                }
                // when either direction reaches EOF the remote socket is closed,
                // which unblocks the other direction's transfer
                executor.execute(() -> {
                    try {
                        transfer(s.getInputStream(),exchange.getResponseBody());
                    } catch (IOException ex) {
                        logger.fine("tunnel upstream->client ended: "+ex);
                    } finally {
                        closeQuietly(s);
                    }
                });
                try {
                    transfer(exchange.getRequestBody(),s.getOutputStream());
                } catch (IOException ex) {
                    logger.fine("tunnel client->upstream ended: "+ex);
                } finally {
                    // closing the remote socket unblocks the upstream->client transfer
                    closeQuietly(s);
                }
                logger.fine("proxy connection to "+s.getRemoteSocketAddress()+" ended");
            }
            // the CONNECT exchange is complete: return instead of falling
            // through into the forwarding path below (the P2-35 cleanup
            // removed the old finally-return without replacing it, so every
            // finished tunnel errored into the proxy-forwarding code)
            return;
        }
        var proxy = proxyTo(exchange).orElseThrow(() -> new IOException("proxy not configured for "+exchange.getRequestURI()));
        var uri = exchange.getRequestURI();
        if(uri.getScheme()==null) {
            try {
                // rebuild from RAW components: getPath()/getQuery() return
                // DECODED values and the multi-arg URI constructor does not
                // re-escape characters that are legal in path/query, so a
                // proxied "/foo%2Fbar?q=a%26b" reached the origin as
                // "/foo/bar?q=a&b" - path confusion and query splitting
                String rawPath = uri.getRawPath() == null ? "" : uri.getRawPath();
                String rawQuery = uri.getRawQuery();
                // getHostString(): getHostName() does a REVERSE-DNS lookup
                // per request (blocking resolver on the hot path)
                String target = proxy.scheme + "://" + exchange.getLocalAddress().getHostString()
                    + ":" + exchange.getLocalAddress().getPort() + rawPath
                    + (rawQuery == null ? "" : "?" + rawQuery);
                uri = new URI(target);
            } catch (URISyntaxException ex) {
                throw new IOException("invalid proxy uri ",ex);
            }
        }
        // bounded: absolute-form request targets carry client-controlled
        // authorities, so an unbounded map is attacker-driven memory growth
        if (proxies.size() < 1024) {
            proxies.put(authorityOf(uri),proxy);
        }
        // effectively final for the lambda below (uri may have been rebuilt above)
        final URI target = uri;
        try {
            // call() propagates checked exceptions; the scope is bound for the
            // whole forwarding so the selector sees CURRENT_PROXY on the
            // calling (virtual) thread, and unbinds automatically afterwards
            ScopedValue.where(CURRENT_PROXY, proxy).call(() -> {
                var requestBuilder = HttpRequest.newBuilder(target);
                String[] forwardedHeaders = headers(exchange.getRequestHeaders());
                // HttpRequest.Builder.headers() rejects an empty array: a request
                // whose headers were all restricted (e.g. only Host and Connection)
                // must skip the call entirely
                if (forwardedHeaders.length > 0) {
                    requestBuilder = requestBuilder.headers(forwardedHeaders);
                }
                var response = proxyClient.send(requestBuilder.method(exchange.getRequestMethod(),HttpRequest.BodyPublishers.ofInputStream(() -> exchange.getRequestBody())).build(),HttpResponse.BodyHandlers.ofInputStream());
                exchange.getResponseHeaders().putAll(response.headers().map());
                // strip hop-by-hop and framing headers: this server performs its own
                // chunked framing, so forwarding them would emit both
                // Content-length and Transfer-encoding (RFC 7230 6.1)
                for (String h : HOP_BY_HOP_HEADERS) {
                    exchange.getResponseHeaders().remove(h);
                }
                exchange.sendResponseHeaders(response.statusCode(),0);
                try (var os = exchange.getResponseBody ()) {
                    transfer(response.body(),os);
                }
                return null;
            });
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("unable to proxy request to "+exchange.getRequestURI(),ex);
        } catch (IOException ex) {
            throw ex;
        } catch (Exception ex) {
            // call()'s exception inference widens to Exception for a body that
            // throws both IOException and InterruptedException
            throw new IOException("unable to proxy request to "+exchange.getRequestURI(),ex);
        }
    }

    /**
     * override to check authorization headers. if returning false,
     * the implementation must call exchange.sendResponseHeaders() with the appropriate code.
     *
     * @return true if the CONNECT should proceed, else false
     */
    protected boolean authorizeConnect(HttpExchange exchange) {
        // deny by default: an unauthenticated CONNECT is an open relay
        try {
            exchange.sendResponseHeaders(403,-1);
        } catch (IOException e) {
            logger.warning("failed to send 403 for unauthorized CONNECT: "+e);
        }
        return false;
    }

    private static final int DEFAULT_BUFFER_SIZE = 16384;
    private static final int CONNECT_TIMEOUT_MILLIS = 30_000;

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }

    private static void transfer(InputStream in, OutputStream out) throws IOException {
        Objects.requireNonNull(out, "out");
        byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
        int read;
        while ((read = in.read(buffer, 0, DEFAULT_BUFFER_SIZE)) >= 0) {
            out.write(buffer, 0, read);
            out.flush();
        }
    }

    /** hop-by-hop headers that must not be forwarded in either direction (RFC 7230 6.1).
     * CONTENT-LENGTH is not hop-by-hop, but must be dropped all the same: this
     * server re-frames the proxied body (sendResponseHeaders(status,0) switches
     * to chunked), so a forwarded Content-length would coexist with the
     * Transfer-encoding header, which RFC 7230 5.1 forbids */
    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
            "CONNECTION", "CONTENT-LENGTH", "KEEP-ALIVE", "PROXY-AUTHENTICATE", "PROXY-AUTHORIZATION",
            "TE", "TRAILER", "TRANSFER-ENCODING", "UPGRADE");

    private static final Set<String> restrictedHeaders = Set.of(
            "CONNECTION","HOST","UPGRADE","CONTENT-LENGTH","TRANSFER-ENCODING",
            "TE","TRAILER","KEEP-ALIVE","PROXY-AUTHORIZATION",
            // java.net.http's builder rejects it with IllegalArgumentException,
            // which killed the proxy request after the interim 100 response
            "EXPECT");

    private static String[] headers(Headers headers) {
        // headers NAMED IN the Connection header are hop-by-hop too (RFC
        // 7230 6.1) and must be stripped alongside the static set
        Set<String> connectionListed = new java.util.HashSet<>();
        for (String v : headers.getOrDefault("Connection", List.of())) {
            for (String token : v.split(",")) {
                String t = token.trim();
                if (!t.isEmpty()) {
                    connectionListed.add(t.toUpperCase(java.util.Locale.ROOT));
                }
            }
        }
        List<String> copy = new ArrayList<>();
        // Locale.ROOT: the default locale maps i -> İ in tr/az, which would
        // break the restricted-header match and fail every proxied request
        headers.entrySet().stream().filter(e -> {
            String upper = e.getKey().toUpperCase(java.util.Locale.ROOT);
            return !restrictedHeaders.contains(upper) && !connectionListed.contains(upper);
        }).forEach(e -> e.getValue().forEach(v -> { copy.add(e.getKey()); copy.add(v);}));
        return copy.toArray(String[]::new);
    }

    protected Optional<HostPort> proxyTo(HttpExchange exchange) {
        return defaultProxy;
    }

    protected void connectFailed(URI uri, SocketAddress sa, IOException ieo) {
    }

}
