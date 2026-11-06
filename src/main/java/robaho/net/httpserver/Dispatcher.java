package robaho.net.httpserver;

import java.io.IOException;
import java.lang.System.Logger.Level;
import java.net.Socket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import robaho.net.httpserver.internal.NoSyncBufferedInputStream;

/**
 * The accept loop, extracted from ServerImpl (the structural diet): owns the
 * listening socket, accepts connections, performs the TLS handshake with
 * ALPN selection when the server fronts https, and hands each connection
 * to its handler (h2 for the negotiated/preface paths, h1 otherwise) on
 * the executor. all policy and state stay on ServerImpl; this class only
 * sequences the accept-to-handoff lifecycle.
 */
class Dispatcher implements Runnable {

  private final ServerImpl server;

  Dispatcher(ServerImpl server) {
    this.server = server;
  }

  @Override
  public void run() {
    long backoff = 1;
    while (true) {
      try {
        Socket s = server.serverSocket().accept();
        backoff = 1;
        try {
          server.getExecutor().execute(() -> {
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
              server.logger().log(level, "unable to accept connection", t);
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
          server.logger().log(Level.DEBUG, "executor rejected connection", e);
          try {
            s.close();
          } catch (IOException closeFailed) {
            // the outer IOException catch would misattribute
            // a close failure as an accept failure
          }
        }
      } catch (IOException e) {
        if (server.isFinishing() || server.serverSocket().isClosed()) {
          if (!server.isFinishing()) {
            server.logger().log(Level.ERROR, "socket accept failed", e);
          }
          return;
        }
        // a transient accept failure (e.g. EMFILE when the process
        // runs out of file descriptors) must not kill the
        // dispatcher: retry with exponential backoff so the server
        // recovers once the resource is freed
        server.logger().log(Level.ERROR, "socket accept failed, retrying in " + backoff + "ms", e);
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
    if (server.isFinishing()) {
      // a queued accept task running after stop()'s connection sweep:
      // the connection would be registered into the freshly swept set
      // with the cleaner timer already cancelled, so nothing would
      // ever close it (leaked socket + blocked exchange thread)
      try {
        s.close();
      } catch (IOException ignored) {}
      return;
    }
    if (server.logger().isLoggable(Level.TRACE)) {
      server.logger().log(Level.TRACE, "accepted connection: " + s.toString());
    }
    server.stats().connectionCount.incrementAndGet();
    if (ServerImpl.MAX_CONNECTIONS > 0 && server.connectionCount().get() >= ServerImpl.MAX_CONNECTIONS) {
      // we've hit max limit of current open connections, so we go
      // ahead and close this connection without processing it
      try {
        server.stats().maxConnectionsExceededCount.incrementAndGet();
        server.logger().log(Level.WARNING, "closing accepted connection due to too many connections");
        s.close();
      } catch (IOException ignore) {}
      return;
    }

    if (ServerConfig.noDelay()) {
      s.setTcpNoDelay(true);
    }

    boolean http2 = false;

    if (server.isHttps()) {
      // for some reason, creating an SSLServerSocket and setting the default parameters would
      // not work, so upgrade to a SSLSocket after connection
      SSLSocketFactory ssf = server.httpsConfig().getSSLContext().getSocketFactory();
      // autoClose=true: with false, closing the layered SSLSocket
      // never closes the underlying accepted socket (JDK
      // SSLSocketImpl.closeSocket only calls super.close() when
      // autoClose || !isLayered()) - every HTTPS connection leaked
      // its raw socket fd (CLOSE_WAIT accumulation until EMFILE)
      SSLSocket sslSocket = (SSLSocket) ssf.createSocket(s, null, true);
      SSLConfigurator.configure(sslSocket, server.httpsConfig());

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
      sslSocket.setSoTimeout((int) Math.min(Math.max(ServerConfig.getSslHandshakeTimeoutMillis(), 1), Integer.MAX_VALUE));
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
        server.logger().log(Level.DEBUG, () -> "http2 connection " + sslSocket);
        http2 = true;
        // protocol-specific TCP_NODELAY (see ServerConfig.http2NoDelay)
        sslSocket.setTcpNoDelay(ServerConfig.http2NoDelay());
      } else {
        server.logger().log(Level.DEBUG, () -> "http/1.1 connection " + sslSocket);
      }
      s = sslSocket;
    }

    HttpConnection c = new HttpConnection(s);
    try {
      server.allConnections().add(c);
      server.connectionCount().incrementAndGet();
      server.connectionRegistered();
      if (server.isFinishing()) {
        // stop()'s sweep may have completed between the entry
        // check and this registration (the SSL handshake can
        // stretch the window by seconds): nothing would ever
        // close this connection - the sweep is done and the
        // cleaner timer is cancelled. close it here
        server.closeConnection(c);
        return;
      }

      if (http2) {
        if (c.getInputStream() instanceof NoSyncBufferedInputStream nis) {
          nis.growBuffer();
        }
        ServerImpl.Http2ConnectionHandler t = server.newHttp2Handler(c);
        t.run();
      } else {
        Http1ConnectionHandler t = new Http1ConnectionHandler(server, c);
        t.run();
      }
    } catch (Throwable t) {
      server.logger().log(Level.WARNING, "Dispatcher Exception", t);
      server.stats().handleExceptionCount.incrementAndGet();
      server.closeConnection(c);
    }
  }

}
