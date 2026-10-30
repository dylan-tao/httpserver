package robaho.net.httpserver.internal;

import javax.net.ssl.SSLSession;

/**
 * The connection-level state the h2 engine needs from its owning connection. Implemented by the (package-private)
 * HttpConnection; living in the non-exported internal package keeps it out of the module API while the http2 package
 * can still reach it.
 */
public interface Http2ConnectionHandle {

  /** true when the connection runs over TLS */
  boolean isSSL();

  /** true once the underlying socket is closed */
  boolean isClosed();

  /** the negotiated TLS session, or null on a plain connection */
  SSLSession getSSLSession();

  /** the number of requests that have started on this connection */
  long requestCount();

  /** record that another request has started on this connection */
  void incrementRequestCount();

  /**
   * the last socket activity timestamp (for idle accounting); the h2 reader restores it around PING ACK processing so
   * keepalive ACKs do not keep an idle connection alive
   */
  long lastActivityTime();

  /** overwrite the last activity timestamp */
  void setLastActivityTime(long t);

  /** the local socket address of this connection */
  java.net.InetSocketAddress getLocalAddress();

  /** the remote (peer) socket address of this connection */
  java.net.InetSocketAddress getRemoteAddress();

}
