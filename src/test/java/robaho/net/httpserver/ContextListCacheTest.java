package robaho.net.httpserver;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Guards the ContextList lookup-cache invariant: the cache only stores exact
 * matches, so registering a more specific context after requests have already
 * been served must not be shadowed by a stale cache entry. If the caching
 * policy is ever relaxed to prefix matches, add() must invalidate and this
 * test will fail.
 */
public class ContextListCacheTest {
    private HttpServer server;
    private HttpClient client;

    @BeforeMethod
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        client = HttpClient.newHttpClient();
    }

    @AfterMethod
    void tearDown() {
        server.stop(0);
    }

    @Test
    public void addAfterLookupNotShadowedByCache() throws Exception {
        AtomicInteger rootHits = new AtomicInteger();
        AtomicInteger fooHits = new AtomicInteger();
        server.createContext("/", ex -> {
            rootHits.incrementAndGet();
            respond(ex, "root");
        });
        server.start();
        int port = server.getAddress().getPort();
        URI foo = URI.create("http://127.0.0.1:" + port + "/foo");

        // prime the lookup path before /foo exists: the prefix match "/"
        // must not be cached for /foo (only exact matches are cached)
        Assert.assertEquals(get(foo), "root");

        // register the more specific context; subsequent lookups must see it
        server.createContext("/foo", ex -> {
            fooHits.incrementAndGet();
            respond(ex, "foo");
        });

        Assert.assertEquals(get(foo), "foo");
        Assert.assertEquals(get(foo), "foo");
        Assert.assertEquals(rootHits.get(), 1, "the priming request is the only one hitting /");
        Assert.assertEquals(fooHits.get(), 2, "both post-add requests must hit the new context");
    }

    static void respond(HttpExchange ex, String body) throws IOException {
        ex.sendResponseHeaders(200, body.getBytes().length);
        try (var os = ex.getResponseBody()) {
            os.write(body.getBytes());
        }
    }

    String get(URI uri) throws Exception {
        HttpResponse<String> r = client.send(HttpRequest.newBuilder(uri).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        Assert.assertEquals(r.statusCode(), 200);
        return r.body();
    }
}
