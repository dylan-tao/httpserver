package robaho.net.httpserver.extras;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import robaho.net.httpserver.extras.ProxyHandler;

/**
 * End-to-end checks for the ProxyHandler forwarding path (todo.md P2-34):
 * the proxied response must not carry both Content-length and
 * Transfer-encoding, and a request whose headers are all restricted must
 * still be forwarded (previously an IllegalArgumentException).
 */
public class ProxyForwardingTest {
    private HttpServer backend;
    private HttpServer front;

    @BeforeMethod
    void setUp() throws IOException {
        backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backend.createContext("/", ProxyForwardingTest::serveFixedLength);
        backend.start();

        var target = new ProxyHandler.HostPort("127.0.0.1", backend.getAddress().getPort(), "http");
        front = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        front.createContext("/", new ProxyHandler(target));
        front.start();
    }

    @AfterMethod
    void tearDown() {
        if (front != null) {
            front.stop(0);
        }
        if (backend != null) {
            backend.stop(0);
        }
    }

    @Test
    public void testForwardedResponseHasNoContentLengthAndChunked() throws Exception {
        String response = proxyGet("User-Agent: proxy-test\r\n");
        String head = headOf(response);
        Assert.assertTrue(head.startsWith("HTTP/1.1 200"), head);
        boolean hasContentLength = head.toLowerCase().contains("content-length");
        boolean hasTransferEncoding = head.toLowerCase().contains("transfer-encoding");
        Assert.assertFalse(hasContentLength && hasTransferEncoding,
            "proxied response must not carry both Content-length and Transfer-encoding: " + head);
        Assert.assertTrue(response.contains("hello world"), "body must be forwarded");
    }

    @Test
    public void testRequestWithOnlyRestrictedHeaders() throws Exception {
        // Host and Connection are both stripped before forwarding: the empty
        // forwarded-header array must not break the upstream request
        String response = proxyGet("");
        Assert.assertTrue(response.contains("hello world"),
            "request with only restricted headers must still be proxied: " + response);
    }

    /** send an absolute-form GET through the proxy and return the raw response */
    private String proxyGet(String extraHeaders) throws IOException {
        String request = "GET http://127.0.0.1:" + backend.getAddress().getPort() + "/ HTTP/1.1\r\n"
            + "Host: 127.0.0.1:" + backend.getAddress().getPort() + "\r\n"
            + extraHeaders
            + "Connection: close\r\n\r\n";
        try (Socket socket = new Socket("127.0.0.1", front.getAddress().getPort())) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            InputStream in = socket.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) != -1) {
                buf.write(b, 0, n);
            }
            return buf.toString(StandardCharsets.ISO_8859_1);
        }
    }

    private static String headOf(String response) {
        int idx = response.indexOf("\r\n\r\n");
        return idx >= 0 ? response.substring(0, idx) : response;
    }

    private static void serveFixedLength(HttpExchange exchange) throws IOException {
        byte[] body = "hello world".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }
}
