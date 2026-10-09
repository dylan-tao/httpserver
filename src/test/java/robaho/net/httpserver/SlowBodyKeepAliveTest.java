package robaho.net.httpserver;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A client that sends its (small) request body slowly must keep the
 * connection alive: the response is flushed first, then drain() collects the
 * straggling body bytes within its bounded wait (todo.md 复查-3). The original
 * non-blocking drain gave up as soon as available() hit zero and closed the
 * connection.
 */
public class SlowBodyKeepAliveTest {
    private HttpServer server;

    @BeforeMethod
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // the handler deliberately does NOT read the request body
        server.createContext("/slow", (HttpExchange exchange) -> {
            exchange.sendResponseHeaders(200, 2);
            try (var os = exchange.getResponseBody()) {
                os.write("ok".getBytes(StandardCharsets.UTF_8));
            }
        });
        server.start();
    }

    @AfterMethod
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    public void testSlowBodyKeepsConnectionAlive() throws Exception {
        byte[] chunk = new byte[5120];
        try (Socket socket = new Socket("127.0.0.1", server.getAddress().getPort())) {
            socket.setSoTimeout(15_000);
            var out = socket.getOutputStream();
            var in = socket.getInputStream();

            // POST 30KB in 6 x 5KB chunks, 150ms apart
            out.write("POST /slow HTTP/1.1\r\nHost: x\r\nContent-Length: 30720\r\n\r\n".getBytes());
            out.write(chunk);
            out.flush();

            // the first response must arrive while the body is still in flight
            String head = readHead(in);
            Assert.assertTrue(head.startsWith("HTTP/1.1 200"), "first response must arrive early: " + head);
            Assert.assertEquals(readFully(in, 2), "ok");

            for (int i = 0; i < 5; i++) {
                Thread.sleep(150);
                out.write(chunk);
                out.flush();
            }

            // the connection must have been reused: a second request is served
            out.write("GET /slow HTTP/1.1\r\nHost: x\r\n\r\n".getBytes());
            out.flush();
            String head2 = readHead(in);
            Assert.assertTrue(head2.startsWith("HTTP/1.1 200"),
                "second request must be served on the same connection: " + head2);
            Assert.assertEquals(readFully(in, 2), "ok");
        }
    }

    private static String readHead(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            sb.append((char) c);
            int n = sb.length();
            if (n >= 4 && sb.charAt(n - 4) == '\r' && sb.charAt(n - 3) == '\n'
                && sb.charAt(n - 2) == '\r' && sb.charAt(n - 1) == '\n') {
                break;
            }
        }
        return sb.toString();
    }

    private static String readFully(InputStream in, int length) throws IOException {
        byte[] buffer = new byte[length];
        int off = 0;
        while (off < length) {
            int n = in.read(buffer, off, length - off);
            if (n == -1) {
                throw new IOException("connection closed after " + off + " of " + length + " bytes");
            }
            off += n;
        }
        return new String(buffer, StandardCharsets.UTF_8);
    }
}
