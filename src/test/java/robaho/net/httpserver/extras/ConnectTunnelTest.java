package robaho.net.httpserver.extras;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.testng.Assert;
import org.testng.annotations.Test;
import robaho.net.httpserver.extras.ProxyHandler.HostPort;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/**
 * end-to-end coverage for the CONNECT tunnel (audit E3, 2026-11-20, and the
 * modernization of the main()-only TunnelProxyTest): a client half-close
 * (TLS-over-CONNECT close_notify flows, HTTP/1.0-style clients) must not
 * truncate the upstream response still in flight. the old pump closed the
 * whole remote socket on either direction's EOF, so the trailing upstream
 * bytes never reached the client.
 */
public class ConnectTunnelTest {

  private static final String TRAILING = "upstream-trailing-payload";

  @Test
  public void testClientHalfCloseDoesNotTruncateUpstreamResponse() throws Exception {
    try (ServerSocket upstream = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      // the upstream: reads until EOF (the proxy mirrors the client's
      // half-close), then answers with a trailing payload and closes
      Thread.ofVirtual().start(() -> {
        try (Socket up = upstream.accept()) {
          up.getInputStream().readAllBytes();
          up.getOutputStream().write(TRAILING.getBytes(StandardCharsets.ISO_8859_1));
          up.getOutputStream().flush();
          up.close();
        } catch (IOException ignored) {
          // the proxy side tore down first (the pre-fix behavior): nothing
          // more to assert on this side - the client-side assertions carry
          // the test
        }
      });

      HttpServer proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      proxy.createContext("/", new ProxyHandler(new HostPort("127.0.0.1", upstream.getLocalPort(), "http")) {

        @Override
        protected boolean authorizeConnect(HttpExchange exchange) {
          return true;
        }

      });
      proxy.start();
      try (Socket client = new Socket("127.0.0.1", proxy.getAddress().getPort())) {
        client.setSoTimeout(5000);
        OutputStream out = client.getOutputStream();
        // "localhost:PORT": the CONNECT target is parsed as a URI whose
        // scheme is the host and whose scheme-specific part is the port (a
        // numeric-leading host like 127.0.0.1 is not a valid URI scheme)
        out.write(("CONNECT localhost:" + upstream.getLocalPort() + " HTTP/1.1\r\nHost: localhost\r\n\r\n")
            .getBytes(StandardCharsets.ISO_8859_1));
        out.flush();

        String head = readResponseHead(client);
        Assert.assertTrue(head.startsWith("HTTP/1.1 200"), "the tunnel must be established: " + head);

        // tunnel payload, then the client half-closes: it is done sending,
        // but the upstream response has not even started
        out.write("hello-upstream".getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
        client.shutdownOutput();

        // the trailing upstream payload must still arrive, then a clean EOF
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        try {
          int n;
          while ((n = client.getInputStream().read(buf)) != -1) {
            bos.write(buf, 0, n);
          }
        } catch (SocketTimeoutException e) {
          Assert.fail("the tunnel must deliver the trailing upstream payload after the client half-close");
        }
        String received = bos.toString(StandardCharsets.ISO_8859_1);
        Assert.assertTrue(received.contains(TRAILING),
            "the upstream response must not be truncated by the client's half-close, received: " + received);
      } finally {
        proxy.stop(0);
      }
    }
  }

  /** read the response head (status line + headers) up to the empty line */
  private static String readResponseHead(Socket socket) throws IOException {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    byte[] buf = new byte[1024];
    while (true) {
      int n = socket.getInputStream().read(buf);
      if (n < 0) {
        throw new IOException("EOF while reading the CONNECT response head");
      }
      bos.write(buf, 0, n);
      String s = bos.toString(StandardCharsets.ISO_8859_1);
      if (s.contains("\r\n\r\n")) {
        return s;
      }
    }
  }

}
