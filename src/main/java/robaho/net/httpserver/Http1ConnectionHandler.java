package robaho.net.httpserver;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.Headers;
import java.lang.System.Logger.Level;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import robaho.net.httpserver.internal.NoSyncBufferedInputStream;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

/** package-private: h1 connection-level request loop, extracted from ServerImpl */
class Http1ConnectionHandler implements Runnable {

  final ServerImpl server;

  final HttpConnection connection;

  /**
   * one-entry URI memo for the request line's URI string: keep-alive
   * clients repeat the same target, and URI's parse (JFR: 25MB/30k
   * requests on the h1 side) is skipped on a hit. one immutable record
   * behind a single volatile reference: the two fields can never tear
   */
  private volatile UriMemo uriMemo;

  private record UriMemo(String uriStr, URI uri) {}

  InputStream rawin;

  OutputStream rawout;

  String protocol;

  ExchangeImpl tx;

  HttpContextImpl ctx;

  Http1ConnectionHandler(ServerImpl server, HttpConnection conn) throws IOException {
    this.server = server;
    this.connection = conn;
    this.protocol = server.protocolToken();
  }

  @Override
  public void run() {
    this.rawin = connection.getInputStream();
    this.rawout = connection.getOutputStream();

    if (server.logger().isLoggable(Level.TRACE)) {
      server.logger().log(Level.TRACE, "exchange started " + connection);
    }

    while (!connection.closed) {
      try {
        // between requests: the reader thread owns the input buffer
        // at this point (no fill() in progress), so it is safe to
        // shrink it here. the next fill() grows it back on demand
        if (rawin instanceof NoSyncBufferedInputStream nis) {
          nis.shrinkForNextRequest();
        }
        runPerRequest();
      } catch (RequestRejectedException e) {
        // a malformed request: reply and close (sendReply's
        // closeNow sets connection.closed, ending the loop)
        reject(e.code, e.requestLine, e.getMessage());
      } catch (IOException e) {
        // these are common with clients breaking connections etc
        server.logger().log(Level.TRACE, "ServerImpl IOException", e);
        server.stats().socketExceptionCount.incrementAndGet();
        server.closeConnection(connection);
        break;
      } catch (Exception e) {
        server.logger().log(Level.WARNING, "ServerImpl unexpected exception", e);
        if (tx == null || !tx.sentHeaders || !tx.closed) {
          server.closeConnection(connection);
          break;
        }
        // the handler already responded, but its request body may
        // not have been fully consumed: apply the same EOF guard as
        // the normal path, otherwise leftover body bytes get parsed
        // as the next request on this connection (desync/smuggling)
        LeftOverInputStream is = tx.getOriginalInputStream();
        if (is == null || !is.isEOF()) {
          server.logger().log(Level.DEBUG, "connection not at EOF after exchange exception, closing");
          server.closeConnection(connection);
          break;
        }
      } catch (Throwable t) {
        server.closeConnection(connection);
        server.logger().log(Level.ERROR, "ServerImpl critical error", t);
        throw t;
      }
    }
    if (server.logger().isLoggable(Level.TRACE)) {
      server.logger().log(Level.TRACE, "exchange finished " + connection);
    }
  }

  private void runPerRequest() throws IOException {
    /* context will be null for new connections */

    server.logger().log(Level.TRACE, "reading request");

    connection.endRequest();

    Request req;
    final String requestLine;
    try {
      req = new Request(rawin, rawout);
      requestLine = req.requestLine();
    } catch (IOException e) {
      // mark in-request before the 400 write: with inRequest false
      // the idle cleaner may concurrently shrink the output buffer
      // the reply is written into
      connection.beginRequest();
      // surface the ACTUAL parse error: the old fixed message
      // reported every header-line failure as "Request line too
      // long" (the differential harness hit it on the blank-line
      // cap)
      throw new RequestRejectedException(HttpStatus.HTTP_BAD_REQUEST, "-",
          e.getMessage() == null ? "malformed request" : e.getMessage(), e);
    }

    if ("PRI * HTTP/2.0".equals(requestLine) && ServerConfig.http2OverNonSSL()) {
      // mark busy BEFORE the handoff: between the request line and
      // the h2 connection construction no busy marker existed, so
      // stop(delay)'s sweep could close a connection mid-upgrade
      connection.beginRequest();
      server.logger().log(Level.DEBUG, "found http2 request on non-SSL assuming prior knowledge");
      // h2 frames (16KB DATA) must be read at the full buffer size,
      // not the 2KB idle size the connection may carry
      if (rawin instanceof NoSyncBufferedInputStream nis) {
        nis.growBuffer();
      }
      // protocol-specific TCP_NODELAY (see ServerConfig.http2NoDelay)
      connection.getSocket().setTcpNoDelay(ServerConfig.http2NoDelay());
      ServerImpl.Http2ConnectionHandler exchange = server.newHttp2Handler(connection);
      // clear the upgrade-window marker: the h2 connection is now
      // registered, and its busy state is tracked by
      // requestsInProgress (the cleaner's h2 branch). leaving
      // inRequest=true forever would make idle h2c connections
      // immortal - never idle-closed, never shrunk, never counted
      // toward maxIdleConnections (the round-2 bug, re-introduced
      // by the upgrade-window fix and caught in review)
      connection.endRequest();
      exchange.run();
      return;
    }

    connection.beginRequest();

    if (requestLine.isEmpty()) {
      /* connection closed */
      server.logger().log(Level.DEBUG, "no request line: closing");
      server.closeConnection(connection);
      return;
    }
    connection.requestCount.incrementAndGet();
    if (ServerStats.ENABLED)
      server.stats().requestCount.incrementAndGet();

    server.logger().log(Level.DEBUG, () -> "Http1 request line: " + requestLine);
    int space = requestLine.indexOf(" ");
    if (space == -1) {
      throw new RequestRejectedException(HttpStatus.HTTP_BAD_REQUEST, requestLine, "Bad request line");
    }
    String method = requestLine.substring(0, space);
    int start = space + 1;
    space = requestLine.indexOf(" ", start);
    if (space == -1) {
      throw new RequestRejectedException(HttpStatus.HTTP_BAD_REQUEST, requestLine, "Bad request line");
    }
    String uriStr = requestLine.substring(start, space);
    // reject authority-form ambiguity: "//" can be parsed as an
    // authority component rather than a path (JDK parity)
    if (uriStr.startsWith("//")) {
      throw new RequestRejectedException(HttpStatus.HTTP_BAD_REQUEST, requestLine, "Bad request URI");
    }
    URI uri;
    UriMemo m = uriMemo;
    if (m != null && uriStr.equals(m.uriStr())) {
      uri = m.uri();
    } else {
      try {
        uri = new URI(uriStr);
      } catch (URISyntaxException e3) {
        throw new RequestRejectedException(HttpStatus.HTTP_BAD_REQUEST, requestLine, "URISyntaxException thrown", e3);
      }
      uriMemo = new UriMemo(uriStr, uri);
    }
    start = space + 1;
    String version = requestLine.substring(start);
    if (!"HTTP/1.1".equals(version) && !"HTTP/1.0".equals(version)) {
      // the h2c PRI line was already handled above; anything else
      // is an unsupported protocol version
      throw new RequestRejectedException(HttpStatus.HTTP_VERSION, requestLine, "Unsupported HTTP version");
    }
    Headers headers;
    try {
      headers = req.headers();
    } catch (IOException e) {
      throw new RequestRejectedException(HttpStatus.HTTP_BAD_REQUEST, requestLine, "Malformed header line", e);
    }

    long clen = resolveBodyLength(requestLine, headers);

    // a declared large body: switch to the full-size read buffer so
    // the body is consumed in 16KB reads, not 2KB
    if (clen > NoSyncBufferedInputStream.GROW_BODY_THRESHOLD && rawin instanceof NoSyncBufferedInputStream nis) {
      nis.growBuffer();
    }
    long bodySizeLimit = ServerConfig.getMaxRequestBodySize();
    if (bodySizeLimit > 0 && clen > bodySizeLimit) {
      // declared Content-Length over the opt-in body cap. chunked
      // bodies without a declared length are bounded incrementally
      // in LeftOverInputStream
      throw new RequestRejectedException(HttpStatus.HTTP_ENTITY_TOO_LARGE, requestLine,
          "Request body exceeds the configured maximum of " + bodySizeLimit + " bytes");
    }
    if (server.logger().isLoggable(Level.TRACE)) {
      server.logger().log(Level.TRACE, "protocol " + protocol + " uri " + uri + " headers " + headers);
    }
    // null-check, not Optional.ofNullable: a fresh Optional per request is
    // pure allocation on the dispatch hot path
    String uriPath = uri.getPath();
    if (uriPath == null) {
      uriPath = "/";
    }
    ctx = server.contexts().findContext(protocol, uriPath);
    if (ctx == null) {
      throw new RequestRejectedException(HttpStatus.HTTP_NOT_FOUND, requestLine, "No context found for request");
    }
    connection.setContext(ctx);
    if (ctx.getHandler() == null) {
      throw new RequestRejectedException(HttpStatus.HTTP_INTERNAL_ERROR, requestLine, "No handler for context");
    }
    tx = new ExchangeImpl(method, uri, req, clen, connection);
    String chdr = headers.getFirst("Connection");
    Headers rheaders = tx.getResponseHeaders();

    if (chdr != null && chdr.equalsIgnoreCase("close")) {
      tx.close = true;
    }
    if (version.equalsIgnoreCase("http/1.0")) {
      tx.http10 = true;
      if (chdr == null) {
        tx.close = true;
      } else if (chdr.equalsIgnoreCase("keep-alive")) {
        rheaders.set("Connection", "keep-alive");
        int idleSeconds = (int) (ServerConfig.getIdleIntervalMillis() / 1000);
        String val = "timeout=" + idleSeconds;
        rheaders.set("Keep-alive", val);
      }
    }

    if (tx.close) {
      rheaders.set("Connection", "close");
    }

    /*
     * check if client sent an Expect 100 Continue.
     * In that case, need to send an interim response.
     * In future API may be modified to allow app to
     * be involved in this process.
     */
    String exp = headers.getFirst("Expect");
    // HTTP/1.0 clients have no 1xx concept (RFC 7231 5.1.1: the
    // expectation MUST be ignored): the interim 100 was parsed as
    // the final response and desynced the connection
    if (exp != null && exp.equalsIgnoreCase("100-continue") && req.protocol().endsWith("1.1")) {
      server.logReply(100, requestLine, null);
      sendReply(HttpStatus.HTTP_CONTINUE, false, null);
    }
    /*
     * uf is the list of filters seen/set by the user.
     * sf is the list of filters established internally
     * and which are not visible to the user. uc and sc
     * are the corresponding Filter.Chains.
     * They are linked together by a LinkHandler
     * so that they can both be invoked in one call.
     */
    /* set up the two stream references */
    tx.getRequestBody();
    tx.getResponseBody();
    Filter.Chain uc = server.buildChain(ctx);
    server.doFilterTracked(uc, server.isHttps() ? new HttpsExchangeImpl(tx) : new HttpExchangeImpl(tx));
    if (tx.close) {
      server.closeConnection(connection);
    } else {
      LeftOverInputStream is = tx.getOriginalInputStream();
      if (is == null || !is.isEOF()) {
        // the request body was not fully consumed (or the connection
        // was upgraded), so the connection cannot be safely reused
        server.logger().log(Level.DEBUG, "connection not at EOF after exchange, closing");
        server.closeConnection(connection);
      }
      tx = null;
    }
  }

  /**
   * resolve the request body length from the Content-Length /
   * Transfer-Encoding headers: 0 = none, -1 = chunked. throws
   * RequestRejectedException on conflicting or malformed values
   */
  private long resolveBodyLength(String requestLine, Headers headers) throws IOException {
    List<String> clHeader = headers.get("Content-length");
    List<String> teHeader = headers.get("Transfer-encoding");
    if (clHeader != null && (teHeader != null || clHeader.size() > 1)) {
      throw new RequestRejectedException(HttpStatus.HTTP_BAD_REQUEST, requestLine, "Conflicting or malformed headers detected");
    }
    if (teHeader != null && !teHeader.isEmpty()) {
      String teValue = teHeader.get(0);
      if (teValue.equalsIgnoreCase("chunked") && teHeader.size() == 1) {
        return -1L;
      }
      throw new RequestRejectedException(HttpStatus.HTTP_NOT_IMPLEMENTED, requestLine, "Unsupported Transfer-Encoding value");
    }
    String headerValue = headers.getFirst("Content-length");
    if (headerValue != null) {
      // RFC 7230 5.3: Content-Length is 1*DIGIT only - the JDK's
      // parseLong also accepts a leading '+' or '-'
      if (!isDigits(headerValue)) {
        throw new RequestRejectedException(HttpStatus.HTTP_BAD_REQUEST, requestLine, "Illegal Content-length value");
      }
      try {
        return Long.parseLong(headerValue);
      } catch (NumberFormatException e2) {
        throw new RequestRejectedException(HttpStatus.HTTP_BAD_REQUEST, requestLine, "NumberFormatException thrown", e2);
      }
    }
    return 0L;
  }

  /**
   * a request was rejected during parsing: run()'s catch replies and
   * closes - replaces the 14 reject+return pairs that used to be
   * scattered through runPerRequest
   */
  private static boolean isDigits(String s) {
    if (s.isEmpty()) {
      return false;
    }
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c < '0' || c > '9') {
        return false;
      }
    }
    return true;
  }

  private static class RequestRejectedException extends IOException {

    private static final long serialVersionUID = 8410134731992176435L;

    final int code;

    final String requestLine;

    RequestRejectedException(int code, String requestLine, String message) {
      super(message);
      this.code = code;
      this.requestLine = requestLine;
    }

    RequestRejectedException(int code, String requestLine, String message, Throwable cause) {
      super(message, cause);
      this.code = code;
      this.requestLine = requestLine;
    }

  }

  void reject(int code, String requestStr, String message) {
    server.logReply(code, requestStr, message);
    sendReply(code, true, "<h1>" + code + HttpStatus.msg(code) + "</h1>" + message);
  }

  void sendReply(int code, boolean closeNow, String text) {
    try {
      StringBuilder builder = new StringBuilder(512);
      builder.append("HTTP/1.1 ").append(code).append(HttpStatus.msg(code)).append("\r\n");

      var informational = (code >= 100 && code < 200);

      if (text != null && text.length() != 0) {
        builder.append("Content-length: ").append(text.length()).append("\r\n").append("Content-type: text/html\r\n");
      } else {
        if (!informational) {
          // no body for 1xx responses
          builder.append("Content-length: 0\r\n");
        }
        text = "";
      }
      if (closeNow) {
        builder.append("Connection: close\r\n");
      }
      builder.append("\r\n").append(text);
      rawout.write(builder.toString().getBytes(ISO_8859_1));
      rawout.flush();
      if (closeNow) {
        // a rejected request never has a tx: mark the connection
        // directly - the peer's malformed request is the last
        // thing it sent, so the close drain has nothing to do
        connection.peerIsDoneSending();
        server.closeConnection(connection);
      }
    } catch (IOException e) {
      server.logger().log(Level.TRACE, "ServerImpl.sendReply", e);
      server.stats().replyErrorCount.incrementAndGet();
      server.closeConnection(connection);
    }
  }

}
