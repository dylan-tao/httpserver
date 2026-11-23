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

  /** read-silence bound for the tunnel drain tests: shrunk from the 30s default */
  private static final int TEST_DRAIN_SILENCE_MILLIS = 500;

  /**
   * a SILENT upstream after the client half-closed must not park the tunnel
   * forever (re-audit N2, 2026-11-23): the unbounded await held the handler
   * VT + executor VT + upstream fd permanently (the idle cleaner exempts
   * handed-off tunnels). the drain read timeout (shrunk to 500ms) must fail
   * the response pump and let handle() return. red pre-fix: the connection
   * never ended
   */
  @Test
  public void testSilentUpstreamAfterHalfCloseIsBounded() throws Exception {
    try (ServerSocket upstream = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      // the upstream reads everything, answers NOTHING, and stays connected
      // (ignores the mirrored FIN) - the pathological drain case
      Thread.ofVirtual().start(() -> {
        try (Socket up = upstream.accept()) {
          up.getInputStream().readAllBytes();
          // hold the socket open: neither EOF nor data
          Thread.sleep(60_000);
        } catch (IOException | InterruptedException ignored) {
          // the proxy closed the socket on the drain timeout: exactly the
          // bound this test pins
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
      var previous = ProxyHandler.tunnelDrainSilenceMillis;
      ProxyHandler.tunnelDrainSilenceMillis = TEST_DRAIN_SILENCE_MILLIS;
      try (Socket client = new Socket("127.0.0.1", proxy.getAddress().getPort())) {
        client.setSoTimeout(10_000);
        OutputStream out = client.getOutputStream();
        out.write(("CONNECT localhost:" + upstream.getLocalPort() + " HTTP/1.1\r\nHost: localhost\r\n\r\n")
            .getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
        Assert.assertTrue(readResponseHead(client).startsWith("HTTP/1.1 200"), "the tunnel must be established");

        out.write("hello-upstream".getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
        client.shutdownOutput();

        // the connection must END within the drain bound (plus slack for
        // the half-close propagation), not hang forever: read to EOF
        long start = System.currentTimeMillis();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = client.getInputStream().read(buf)) != -1) {
          bos.write(buf, 0, n);
        }
        long elapsed = System.currentTimeMillis() - start;
        Assert.assertTrue(elapsed < 10_000,
            "the silent-upstream drain must be bounded by the read timeout, took " + elapsed + "ms");
      } finally {
        ProxyHandler.tunnelDrainSilenceMillis = previous;
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
