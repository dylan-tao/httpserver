package robaho.net.httpserver;

import com.sun.net.httpserver.HttpServer;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/**
 * HTTP/1.1 regressions from the 2026-10-09 audit: the exception path must not
 * skip the request-body EOF guard (request smuggling), and a fixed-length
 * response to an upgrade request must not NPE.
 */
public class Http1RegressionTest {
    private HttpServer server;

    @BeforeMethod
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    }

    @AfterMethod
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** a handler that responds and then throws must not let the connection be
     *  reused when the request body was not fully consumed: leftover body
     *  bytes would be parsed as the next request (desync/smuggling) */
    @Test
    public void testExceptionPathNoSmuggling() throws Exception {
        server.createContext("/post", exchange -> {
            exchange.sendResponseHeaders(204, -1); // noContentToSend -> close() drains only 64KB
            throw new RuntimeException("boom after 204");
        });
        server.createContext("/target", exchange -> {
            byte[] b = "SMUGGLED".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, b.length);
            try (var os = exchange.getResponseBody()) {
                os.write(b);
            }
        });
        server.start();
        try (Socket socket = new Socket("127.0.0.1", server.getAddress().getPort())) {
            socket.setSoTimeout(8000);
            // 100KB body: 64KB of junk (the drain limit), then a smuggled
            // pipelined request, then padding
            byte[] smuggled = "GET /target HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
            byte[] body = new byte[100000];
            System.arraycopy(smuggled, 0, body, 65536, smuggled.length);
            ByteArrayOutputStream request = new ByteArrayOutputStream();
            request.write("POST /post HTTP/1.1\r\nHost: x\r\nContent-Length: 100000\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            request.write(body);
            socket.getOutputStream().write(request.toByteArray());
            socket.getOutputStream().flush();

            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            try {
                while ((n = socket.getInputStream().read(b)) != -1) {
                    buf.write(b, 0, n);
                }
            } catch (SocketTimeoutException | SocketException ignored) {
                // the fixed server closes the connection (possibly with a TCP
                // reset due to the unread body): that is the correct outcome
            }
            String response = buf.toString(StandardCharsets.ISO_8859_1);
            Assert.assertTrue(response.startsWith("HTTP/1.1 204"), "first response must arrive: " + response);
            Assert.assertFalse(response.contains("SMUGGLED"),
                "a pipelined request inside the unread body must not be served");
        }
    }

    /** a fixed-length response to a request carrying Connection: Upgrade must
     *  be delivered completely (the request body is the raw stream, so there
     *  is nothing to drain - dereferencing it NPEd and tore the exchange down
     *  mid-close) */
    @Test
    public void testUpgradeRequestResponseComplete() throws Exception {
        server.createContext("/plain", exchange -> {
            exchange.sendResponseHeaders(200, 5);
            try (var os = exchange.getResponseBody()) {
                os.write("hello".getBytes(StandardCharsets.UTF_8));
            }
        });
        server.start();
        try (Socket socket = new Socket("127.0.0.1", server.getAddress().getPort())) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write("GET /plain HTTP/1.1\r\nHost: x\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            String head = readHead(socket.getInputStream());
            Assert.assertTrue(head.startsWith("HTTP/1.1 200"), head);
            Assert.assertEquals(readFully(socket.getInputStream(), 5), "hello",
                "the full response body must be delivered");
            // the connection legitimately closes afterwards: the raw request
            // stream cannot be tracked for keep-alive reuse
        }
    }

    /** InputStream.skip on the request body must go through the HTTP framing
     *  (Content-Length state): the inherited FilterInputStream.skip bypassed
     *  it, so the framework's drain then consumed pipelined request bytes as
     *  body and desynced the connection */
    @Test
    public void testSkipKeepsFraming() throws Exception {
        server.createContext("/skip", exchange -> {
            exchange.getRequestBody().skip(5);
            exchange.sendResponseHeaders(200, 2);
            try (var os = exchange.getResponseBody()) {
                os.write("ok".getBytes(StandardCharsets.UTF_8));
            }
        });
        server.createContext("/after", exchange -> {
            exchange.sendResponseHeaders(200, 2);
            try (var os = exchange.getResponseBody()) {
                os.write("ok".getBytes(StandardCharsets.UTF_8));
            }
        });
        server.start();
        try (Socket socket = new Socket("127.0.0.1", server.getAddress().getPort())) {
            socket.setSoTimeout(8000);
            // POST with a 10-byte body, immediately followed by a pipelined GET
            socket.getOutputStream().write("POST /skip HTTP/1.1\r\nHost: x\r\nContent-Length: 10\r\n\r\n0123456789GET /after HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();

            String head1 = readHead(socket.getInputStream());
            Assert.assertTrue(head1.startsWith("HTTP/1.1 200"), head1);
            Assert.assertEquals(readFully(socket.getInputStream(), 2), "ok");

            // the pipelined GET must be served as a REQUEST, not drained as body
            String head2 = readHead(socket.getInputStream());
            Assert.assertTrue(head2.startsWith("HTTP/1.1 200"),
                "pipelined request after skip() must be served, got: " + head2);
            Assert.assertEquals(readFully(socket.getInputStream(), 2), "ok");
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
                throw new IOException("EOF after " + off + " of " + length + " bytes");
            }
            off += n;
        }
        return new String(buffer, StandardCharsets.UTF_8);
    }

    /** an all-spaces first line must not crash the exchange: the request-line
     *  skip-empty-lines loop breaks on raw non-emptiness, and trimmed() on an
     *  all-spaces builder computed a negative length (StringIndexOutOfBounds
     *  instead of a clean close) */
    @Test
    public void testAllSpacesRequestLine() throws Exception {
        server.start();
        try (Socket socket = new Socket("127.0.0.1", server.getAddress().getPort())) {
            socket.setSoTimeout(8000);
            socket.getOutputStream().write("   \r\n".getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            // the line trims to an empty request line: the server closes the
            // connection cleanly (EOF, no response, no unhandled exception)
            int n = socket.getInputStream().read();
            Assert.assertEquals(n, -1, "connection must close cleanly on an all-spaces request line");
        }
    }
}
