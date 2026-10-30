package robaho.net.httpserver;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpPrincipal;
import com.sun.net.httpserver.HttpsExchange;
import robaho.net.httpserver.http2.internal.Http2Stream;

import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;

class Http2ExchangeImpl extends HttpsExchange implements AuthFilter.PrincipalExchange {

  private final Headers request;

  private final Headers response;

  private final InputStream in;

  private final OutputStream out;

  private final URI uri;

  private final String method;

  private final HttpContext ctx;

  protected final Http2Stream stream;

  private HttpPrincipal principal;

  private int responseCode;

  public Http2ExchangeImpl(Http2Stream stream, URI uri, String method, HttpContext ctx, Headers request, Headers response,
      InputStream in, OutputStream out) {
    this.request = request;
    this.response = response;
    this.stream = stream;
    this.in = in;
    this.out = out;
    this.uri = uri;
    this.method = method;
    this.ctx = ctx;
  }

  @Override
  public Headers getRequestHeaders() { return request; }

  @Override
  public Headers getResponseHeaders() { return response; }

  @Override
  public InputStream getRequestBody() { return in; }

  @Override
  public OutputStream getResponseBody() { return out; }

  @Override
  public URI getRequestURI() { return uri; }

  @Override
  public String getRequestMethod() { return method; }

  @Override
  public HttpContext getHttpContext() { return ctx; }

  @Override
  public void close() {
    stream.close();
  }

  @Override
  public void sendResponseHeaders(int rCode, long responseLength) throws IOException {
    if (rCode == 101) {
      // RFC 7540 8.1.1: HTTP/2 does not support 101. the old code sent
      // an interim :status 101 HEADERS frame and the websocket read
      // loop then consumed h2 framing as websocket frames - protocol
      // garbage on a wedged connection. reset the stream instead
      throw new java.io.IOException("HTTP/2 does not support 101 Switching Protocols (use RFC 8441 extended CONNECT)");
    }
    // RFC 9110 8.6: a server MUST NOT send Content-Length with 1xx or
    // 204 (h1 suppresses it for the same codes; the body suppression
    // alone was only half the parity)
    if (responseLength > 0 && rCode != 204 && rCode != 304 && (rCode < 100 || rCode >= 200)) {
      response.set("Content-length", Long.toString(responseLength));
    }
    // 0: no body, no chunked encoding in h2; -1: no data will be sent,
    // END_STREAM is set via closeStream below
    response.set(":status", Long.toString(rCode));
    // h1/h2 parity: h1 sends Date for every non-1xx response (RFC 9110
    // 6.5.1 SHOULD) and skips informational ones - match that here.
    // HPACK has 'date' in its static table so encoding is cheap
    if (rCode < 100 || rCode >= 200) {
      response.set("Date", CachedClock.dateAndTime());
    }
    responseCode = rCode;
    if (rCode >= 100 && rCode < 200) {
      // interim response (100/102/103...): h1 supports the documented
      // 1xx-then-final pattern (sentHeaders stays false), h2 must too.
      // the old path consumed the one-shot headersSent latch here, so
      // the FINAL response was silently swallowed - the client got a
      // 1xx with the body attached. interim frames never END_STREAM
      // (RFC 7540 8.1) and carry no body
      stream.writeInterimResponseHeaders();
      return;
    }
    // HEAD/204/304 parity: h1 discards the body and forces no
    // content-length for these; h2 sent raw DATA on a HEAD stream
    boolean noBody = rCode == 204 || rCode == 304 || stream.isHeadRequest();
    if (noBody) {
      responseLength = -1;
    }
    stream.writeResponseHeaders(responseLength == -1 || noBody);
  }

  @Override
  public InetSocketAddress getRemoteAddress() { return stream.getRemoteAddress(); }

  @Override
  public InetSocketAddress getLocalAddress() { return stream.getLocalAddress(); }

  @Override
  public int getResponseCode() { return responseCode; }

  @Override
  public String getProtocol() { return "HTTP/2"; }

  @Override
  public Object getAttribute(String name) {
    return ctx.getAttributes().get(name);
  }

  @Override
  public void setAttribute(String name, Object value) {
    // h1 parity: a null value REMOVES the attribute; the attributes map
    // is a ConcurrentHashMap, whose put(name, null) throws NPE
    if (value == null) {
      ctx.getAttributes().remove(name);
    } else {
      ctx.getAttributes().put(name, value);
    }
  }

  @Override
  public void setStreams(InputStream i, OutputStream o) {
    throw new UnsupportedOperationException("Not implemented yet");
  }

  @Override
  public HttpPrincipal getPrincipal() { return principal; }

  @Override
  public SSLSession getSSLSession() { return stream.getSSLSession(); }

  @Override
  public void setPrincipal(HttpPrincipal p) { principal = p; }

}
