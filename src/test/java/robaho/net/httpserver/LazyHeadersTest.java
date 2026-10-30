package robaho.net.httpserver;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/**
 * LazyRequestHeaders semantics: the request-headers view handed to
 * handlers materializes only on first access; a view handed out but
 * never read is force-copied when the parse table is cleared for the
 * next request; a handler retaining the reference past response
 * completion still observes its own request's headers, not the next
 * one's.
 */
public class LazyHeadersTest {

    // --- unit level: view lifecycle against the parse table ---

    @Test
    public void testUnreadUnexposedViewIsSkippedByClear() {
        var table = new robaho.net.httpserver.internal.OptimizedHeaders(16);
        table.add("Key", "value");
        var view = new robaho.net.httpserver.internal.LazyRequestHeaders(table);
        // never exposed via getRequestHeaders: clear() skips the snapshot
        // copy (the never-read fast path) and RETIRES the view - a late
        // getRequestHeaders through a retained exchange then reads EMPTY
        // (see testLateUnexposedReadNeverSeesNextRequestData), never the
        // refilled table
        table.clear();
        assertTrue(view.isEmpty());
    }

    @Test
    public void testLateUnexposedReadNeverSeesNextRequestData() {
        // the cross-request LEAK regression: an exchange never touched
        // getRequestHeaders during handle, an async task then surfaces it
        // only AFTER the connection reparsed the next request. the view
        // must read EMPTY - reading the refilled table would hand request
        // N's code request N+1's headers
        var table = new robaho.net.httpserver.internal.OptimizedHeaders(16);
        table.add("Secret", "request-one");
        var stale = new robaho.net.httpserver.internal.LazyRequestHeaders(table);
        table.clear();                                    // skipped: unexposed
        table.add("Secret", "request-two");               // the next request
        // late first access, long after the swap
        assertTrue(stale.isEmpty());
        assertNull(stale.getFirst("Secret"));
    }

    @Test
    public void testExposedUnreadViewIsForceCopied() {
        var table = new robaho.net.httpserver.internal.OptimizedHeaders(16);
        table.add("Key", "value");
        var view = new robaho.net.httpserver.internal.LazyRequestHeaders(table);
        view.markExposed(); // handed out via getRequestHeaders, never read
        table.clear();
        assertEquals(view.getFirst("Key"), "value");
    }

    @Test
    public void testReadViewSurvivesTableReuse() {
        var table = new robaho.net.httpserver.internal.OptimizedHeaders(16);
        table.add("A", "1");
        var view = new robaho.net.httpserver.internal.LazyRequestHeaders(table);
        assertEquals(view.getFirst("A"), "1");           // materialize
        table.add("B", "fresh");                          // reuse is allowed post-copy
        table.clear();                                    // next-request reset
        table.add("C", "next-request");
        // snapshot semantics: the materialized view is immune
        assertEquals(view.getFirst("A"), "1");
        assertNull(view.getFirst("C"));
    }

    @Test
    public void testForcedMaterializationOnClearSeesOwnEntries() {
        var table = new robaho.net.httpserver.internal.OptimizedHeaders(16);
        table.add("X-Req", "first");
        var first = new robaho.net.httpserver.internal.LazyRequestHeaders(table);
        first.markExposed();
        table.clear();                                    // force-copy 'first'
        table.add("X-Req", "second");
        var second = new robaho.net.httpserver.internal.LazyRequestHeaders(table);
        second.markExposed();
        table.clear();                                    // force-copy 'second'
        assertEquals(first.getFirst("X-Req"), "first");
        assertEquals(second.getFirst("X-Req"), "second");
    }

    @Test
    public void testMaterializedViewIsMutableCopy() {
        var table = new robaho.net.httpserver.internal.OptimizedHeaders(16);
        table.add("H", "v");
        var view = new robaho.net.httpserver.internal.LazyRequestHeaders(table);
        List<String> got = view.get("H");                 // materialize
        got.add("v2");                                    // handler mutation contract
        assertEquals(view.get("H"), List.of("v", "v2"));
        // table is untouched by view mutation
        assertEquals(table.getFirst("H"), "v");
    }

    // --- integration level: raw socket, same connection, table reuse ---

    @Test
    public void testHandlerRetainedViewAcrossRequests() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 32);
        // a handler that RETAINS the headers object on request 1 without
        // reading it, then reads it during request 2 - AFTER request 2's
        // parse has cleared and refilled the shared connection table
        List<Headers> retained = new CopyOnWriteArrayList<>();
        List<String> late = new CopyOnWriteArrayList<>();
        AtomicBoolean readLate = new AtomicBoolean(false);
        server.createContext("/lazy", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                Headers h = ex.getRequestHeaders();
                if (readLate.compareAndSet(false, true)) {
                    retained.add(h); // do NOT read it yet
                } else {
                    late.add(retained.getFirst().getFirst("X-Seq"));
                }
                byte[] body = "ok".getBytes();
                ex.sendResponseHeaders(200, body.length);
                try (OutputStream os = ex.getResponseBody()) { os.write(body); }
            }
        });
        server.start();
        try (java.net.Socket s = new java.net.Socket("127.0.0.1", server.getAddress().getPort())) {
            s.setSoTimeout(5000);
            var out = s.getOutputStream();
            var in = s.getInputStream();
            // request 1: the handler retains its un-read view
            out.write(("GET /lazy HTTP/1.1\r\nHost: x\r\nX-Seq: first\r\n\r\n").getBytes());
            out.flush();
            drainResponse(in);
            // request 2 on the SAME connection: its parse clears the table
            // (force-copying request 1's view) before the handler reads it
            out.write(("GET /lazy HTTP/1.1\r\nHost: x\r\nX-Seq: second\r\n\r\n").getBytes());
            out.flush();
            drainResponse(in);
            assertEquals(late.isEmpty() ? "none" : late.getFirst(), "first");
        } finally {
            server.stop(0);
        }
    }

    /** consume one response: headers through the blank line, then the
     * fixed-length 2-byte body */
    private static void drainResponse(java.io.InputStream in) throws java.io.IOException {
        // read until CRLFCRLF (end of headers); keep last 4 bytes seen
        int prev1 = -1, prev2 = -1, prev3 = -1; // last three bytes
        while (!(prev1 == '\n' && prev2 == '\r' && prev3 == '\n')) {
            int p4 = in.read();
            assertTrue(p4 >= 0, "eof in headers");
            prev1 = prev2; prev2 = prev3; prev3 = p4;
        }
        // 2-byte fixed body ("ok")
        assertEquals(in.read(), 'o');
        assertEquals(in.read(), 'k');
    }
}
