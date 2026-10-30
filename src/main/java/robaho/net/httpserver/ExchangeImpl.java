/*
 * Copyright (c) 2005, 2023, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package robaho.net.httpserver;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpPrincipal;

import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

class ExchangeImpl {

  Headers reqHdrs;

  Headers rspHdrs;

  Request req;

  String method;

  URI uri;

  HttpConnection connection;

  long reqContentLen;

  long rspContentLen;

  /* raw streams which access the socket directly */
  InputStream ris;

  OutputStream ros;

  /* close the underlying connection when this exchange finished */
  boolean close;

  /** the Date header value this exchange set (see sendResponseHeaders) */
  private String frameworkDate;

  /**
   * the clock snapshot captured with the framework Date header: keeps
   * the string and the encoded bytes in the same generation
   */
  private CachedClock.ClockValue frameworkClock;

  boolean closed;

  boolean http10 = false;

  private static final String HEAD = "HEAD";

  private static final String CONNECT = "CONNECT";

  /*
   * streams which take care of the HTTP protocol framing
   * and are passed up to higher layers
   */
  InputStream uis;

  OutputStream uos;

  LeftOverInputStream uisOrig; // uis may have be a user supplied wrapper

  PlaceholderOutputStream uosOrig;

  /* true after response headers sent */
  volatile boolean sentHeaders;

  Map<String, Object> attributes;

  int rcode = -1;

  HttpPrincipal principal;

  boolean connectionUpgraded = false;

  ExchangeImpl(String m, URI u, Request req, long len, HttpConnection connection) throws IOException {
    this.req = req;
    // a mutable view over the parse table, materializing on first Map
    // access: most handlers never read request headers, so the eager
    // per-request copy (HashMap + Node + ArrayList per header, ~28% of
    // the benchmark allocation profile) is skipped entirely for them.
    // the view force-materializes when the table is cleared for the
    // next request (same connection thread, after this handler
    // returns), so a retained reference always sees a snapshot
    // (LazyRequestHeaders)
    if (req.headers() instanceof robaho.net.httpserver.internal.OptimizedHeaders optimized) {
      this.reqHdrs = new robaho.net.httpserver.internal.LazyRequestHeaders(optimized);
    } else {
      this.reqHdrs = new Headers();
      reqHdrs.putAll(req.headers());
    }
    // OpenAddressMap-backed: no HashMap table/Node/LinkedList per header
    // on the response path (the JDK Headers allocation that showed up at
    // ~20% of profile pressure). OptimizedHeaders extends Headers, so
    // the reflective test contract (HeadersTest swapping the parent
    // 'map' field) keeps working - writeHeaders merges any swapped
    // entries out before encoding
    this.rspHdrs = new robaho.net.httpserver.internal.OptimizedHeaders(16);
    this.method = m;
    this.uri = u;
    this.connection = connection;
    this.reqContentLen = len;
    /* ros only used for headers, body written directly to stream */
    this.ros = req.outputStream();
    this.ris = req.inputStream();
  }

  public Headers getRequestHeaders() {
    if (reqHdrs instanceof robaho.net.httpserver.internal.LazyRequestHeaders lazy) {
      // from here the view may be retained past response completion:
      // the parse table's clear() must snapshot it before reuse
      lazy.markExposed();
    }
    return reqHdrs;
  }

  public Headers getResponseHeaders() { return rspHdrs; }

  public URI getRequestURI() { return uri; }

  public String getRequestMethod() { return method; }

  public HttpContextImpl getHttpContext() { return connection.getHttpContext(); }

  private boolean isHeadRequest() { return HEAD.equals(getRequestMethod()); }

  private boolean isConnectRequest() { return CONNECT.equals(getRequestMethod()); }

  public void close() {
    if (closed) {
      return;
    }
    closed = true;

    /*
     * close the underlying connection if,
     * a) the streams not set up yet, no response can be sent, or
     * b) if the wrapper output stream is not set up, or
     * c) if the close of the input/output streams fails
     */
    try {
      if (uisOrig == null || uos == null) {
        connection.close();
        return;
      }
      if (!uosOrig.isWrapped()) {
        connection.close();
        return;
      }
      if (!uisOrig.isClosed()) {
        uisOrig.close();
      }
      uos.close();
    } catch (IOException e) {
      connection.close();
    }
  }

  public InputStream getRequestBody() {
    if (connectionUpgraded && uis != null && uis != ris) {
      // the 101 just switched the connection to a tunnel: the framed
      // stream cached before the upgrade (ServerImpl pre-reads the
      // body) must not be handed to the upgraded protocol. deciding
      // the handoff from the request headers ALONE was wrong: the JDK
      // HttpClient sends "Connection: Upgrade, HTTP2-Settings" +
      // "Upgrade: h2c" on EVERY h1 request (an h2c upgrade attempt
      // this server never completes - prior-knowledge only), so those
      // ordinary requests got the raw unframed socket stream (an auth
      // 401 hung reading it). the handoff now keys on the ACTUAL
      // upgrade having happened
      uis = ris;
      uisOrig = null;
    }
    if (uis != null) {
      return uis;
    }
    if (connectionUpgraded || isConnectRequest()) {
      // connection cannot be re-used
      uis = ris;
    } else if (reqContentLen == -1L) {
      uisOrig = new ChunkedInputStream(this, ris);
      uis = uisOrig;
    } else {
      uisOrig = new FixedLengthInputStream(this, ris, reqContentLen);
      uis = uisOrig;
    }
    return uis;
  }

  LeftOverInputStream getOriginalInputStream() { return uisOrig; }

  /**
   * drain and close the request stream so the connection can be reused.
   * the original stream is null for upgrade/CONNECT requests (the request
   * body is the raw stream): nothing to drain, and dereferencing it would
   * tear down the connection after a perfectly good response
   */
  void drainOriginalInputStream() {
    LeftOverInputStream is = getOriginalInputStream();
    if (is != null && !is.isClosed()) {
      try {
        is.close();
      } catch (IOException e) {
        getServerImpl().getLogger().log(Level.DEBUG, "error draining request stream", e);
      }
    }
  }

  public int getResponseCode() { return rcode; }

  public OutputStream getResponseBody() {
    /*
     * TODO. Change spec to remove restriction below. Filters
     * cannot work with this restriction
     * if (!sentHeaders) {
     * throw new IllegalStateException ("headers not sent");
     * }
     */
    if (uos == null) {
      uosOrig = new PlaceholderOutputStream(null);
      uos = uosOrig;
    }
    return uos;
  }

  /*
   * returns the place holder stream, which is the stream
   * returned from the 1st call to getResponseBody()
   * The "real" ouputstream is then placed inside this
   */
  PlaceholderOutputStream getPlaceholderResponseBody() {
    getResponseBody();
    return uosOrig;
  }

  // hard-coded formatting for Date header, rather than using slower DateFormatter

  public void sendResponseHeaders(int rCode, long contentLen) throws IOException {
    final Logger logger = getServerImpl().getLogger();
    if (sentHeaders) {
      throw new IOException("headers already sent");
    }
    this.rcode = rCode;
    PlaceholderOutputStream o = getPlaceholderResponseBody();
    // the status line is written as part of writeHeaders' single buffer:
    // if header validation fails nothing reaches the stream, so a
    // handler that catches and retries cannot emit a double status line
    boolean noContentToSend = false; // assume there is content
    boolean noContentLengthHeader = false; // must not be sent if set

    Integer bufferSize = (Integer) this.getAttribute(AttributeKeys.SOCKET_WRITE_BUFFER);
    if (bufferSize != null) {
      getConnection().getSocket().setOption(StandardSocketOptions.SO_SNDBUF, bufferSize);
    }

    boolean flush = false;

    /* check for response type that is not allowed to send a body */
    var informational = rCode >= 100 && rCode < 200;

    if (informational) {
      if (rCode == 101) {
        logger.log(Level.DEBUG, () -> "switching protocols");
        if (contentLen != 0) {
          String msg = "sendResponseHeaders: rCode = " + rCode + ": forcing contentLen = 0";
          logger.log(Level.WARNING, msg);
          contentLen = 0;
        }
        connectionUpgraded = true;
      }
      noContentLengthHeader = true; // the Content-length header must not be set for interim responses as they cannot have a body
    } else if ((rCode == 204) /* no content */
        || (rCode == 304)) /* not modified */
    {
      if (contentLen != -1) {
        String msg = "sendResponseHeaders: rCode = " + rCode + ": forcing contentLen = -1";
        logger.log(Level.WARNING, msg);
      }
      contentLen = -1;
      noContentLengthHeader = true;
    }

    if (!informational) {
      // keep the reference: writeHeaders compares against it to use the
      // cached pre-encoded Date bytes (identity comparison is safe
      // because BOTH sides go through this single assignment)
      // ONE snapshot: the old code read the string here and the
      // bytes again in writeHeaders - two volatile reads that could
      // straddle the 750ms tick and pair different generations
      frameworkClock = CachedClock.snapshot();
      frameworkDate = frameworkClock.dateAndTime();
      rspHdrs.set("Date", frameworkDate);
    }

    if (isHeadRequest() || rCode == 304) {
      /*
       * HEAD requests or 304 responses should not set a content length by passing it
       * through this API, but should instead manually set the required
       * headers.
       */
      if (contentLen >= 0) {
        logger.log(Level.WARNING, "sendResponseHeaders: being invoked with a content length for a HEAD request");
      }
      noContentToSend = true;
      contentLen = 0;
      o.setWrappedStream(new FixedLengthOutputStream(this, ros, contentLen));
    } else if (informational && !connectionUpgraded) {
      // don't want to set the stream for 1xx responses, except 101, the handler must call sendResponseHeaders again with the final code
      flush = true;
    } else if (connectionUpgraded || isConnectRequest()) {
      o.setWrappedStream(ros);
      close = true;
      flush = true;
      // the raw streams now belong to the upgrade/tunnel code: reads
      // and writes may come from different threads, so the
      // connection's pre-read flush hook must stand down
      connection.streamsHandedOff = true;
    } else { /* standard response with possible response data */
      if (contentLen == 0) {
        if (http10) {
          o.setWrappedStream(new UndefinedLengthOutputStream(this, ros));
          close = true;
        } else {
          rspHdrs.set("Transfer-encoding", "chunked");
          o.setWrappedStream(new ChunkedOutputStream(this, ros));
        }
      } else {
        if (contentLen == -1) {
          noContentToSend = true;
          contentLen = 0;
        }
        if (!noContentLengthHeader) {
          rspHdrs.set("Content-length", contentLengthString(contentLen));
        }
        o.setWrappedStream(new FixedLengthOutputStream(this, ros, contentLen));
      }
    }

    // A custom handler can request that the connection be
    // closed after the exchange by supplying Connection: close
    // to the response header. Nothing to do if the exchange is
    // already set up to be closed.
    if (!close) {
      List<String> values = rspHdrs.get("Connection");
      if (values != null) {
        for (var val : values) {
          if (val.equalsIgnoreCase("close")) {
            logger.log(Level.DEBUG, "Connection: close requested by handler");
            close = true;
            break;
          }
        }
      }
    }
    if (close) {
      // every path that lands here (client "Connection: close",
      // HTTP/1.0 semantics, handler-requested close, upgrade/CONNECT)
      // means the peer will send nothing after its request: tell the
      // connection so its close() skips the RST-prevention input
      // drain - for these exchanges the drain is pure latency
      connection.peerDoneSending = true;
    }

    writeHeaders(statusLineBytes(rCode), rspHdrs, ros);
    this.rspContentLen = contentLen;
    sentHeaders = !informational;
    if (logger.isLoggable(Level.TRACE)) {
      logger.log(Level.TRACE,
          "sendResponseHeaders(), code=" + rCode + ", noContentToSend=" + noContentToSend + ", contentLen=" + contentLen);
    }
    if (flush) {
      ros.flush();
    }
    if (noContentToSend) {
      close();
    }
    getServerImpl().logReply(rCode, req.requestLine(), null);
  }

  static final Charset ISO_CHARSET = StandardCharsets.ISO_8859_1;

  /**
   * digit strings for the common small content lengths: the
   * Content-Length header was the last per-response String allocation on
   * the hot path (Long.toString). shared immutable Strings are safe in
   * the headers map; lengths beyond the table (large bodies) fall back
   * to Long.toString, where one small String is noise next to the body
   */
  private static final String[] SMALL_LENGTHS = new String[1024];
  static {
    for (int i = 0; i < SMALL_LENGTHS.length; i++) {
      SMALL_LENGTHS[i] = Integer.toString(i);
    }
  }

  private static String contentLengthString(long len) {
    return len >= 0 && len < SMALL_LENGTHS.length ? SMALL_LENGTHS[(int) len] : Long.toString(len);
  }

  private static final byte[] STATUS_101 = "HTTP/1.1 101 Switching Protocols\r\n".getBytes(ISO_CHARSET);

  /**
   * status lines are immutable per code: cache the encoded bytes instead of
   * rebuilding the String (and re-encoding it) on every response. a plain
   * array indexed by code, not a ConcurrentHashMap: the map boxed the int
   * key on every response (all real codes are above the Integer cache
   * range), and codes are dense in [100, 599]. the racy store is benign:
   * both racers encode the same immutable value. the release store +
   * acquire load pair is REQUIRED for safe publication: a plain array
   * store has no happens-before edge, so under a weak memory model a
   * reader could observe the non-null reference while the array ELEMENTS
   * still read as zero - a corrupted status line on the wire
   */
  private static final byte[][] STATUS_LINES = new byte[600][];

  private static final java.lang.invoke.VarHandle STATUS_LINES_HANDLE =
      java.lang.invoke.MethodHandles.arrayElementVarHandle(byte[][].class);

  private static byte[] statusLineBytes(int code) {
    if (code == 101) {
      return STATUS_101;
    }
    if (code < 100 || code >= STATUS_LINES.length) {
      // out of range (sendResponseHeaders validates >= 100, but a
      // custom exchange could bypass): fall back to per-call encoding
      return ("HTTP/1.1 " + code + HttpStatus.msg(code) + "\r\n").getBytes(ISO_CHARSET);
    }
    byte[] line = (byte[]) STATUS_LINES_HANDLE.getAcquire(STATUS_LINES, code);
    if (line == null) {
      line = ("HTTP/1.1 " + code + HttpStatus.msg(code) + "\r\n").getBytes(ISO_CHARSET);
      STATUS_LINES_HANDLE.setRelease(STATUS_LINES, code, line);
    }
    return line;
  }

  /**
   * encode a String as ISO-8859-1 directly into the target buffer (zero
   * allocation; unmappable chars become '?' exactly like the charset)
   */
  private static int writeAscii(String s, byte[] buffer, int pos) {
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      buffer[pos++] = (byte) (c > 0xFF ? '?' : c);
    }
    return pos;
  }

  void writeHeaders(byte[] statusLine, Headers map, OutputStream os) throws IOException {
    // the reflective HeadersTest contract: entries swapped into the
    // PARENT map field (impossible via any API) merge out here, after
    // handlers ran, so the NPE/null validation they expect is produced.
    // no-op on production JVMs (reflective access denied: the check is
    // behind a static final null and JIT-derived to nothing)
    if (map instanceof robaho.net.httpserver.internal.OptimizedHeaders optimized) {
      optimized.mergeReflectivelySwappedEntries();
    }
    // assemble the status line and the whole head into ONE buffer and
    // write it once: the old per-header 4-write pattern cost ~18 virtual
    // calls and ~9 byte[] allocations per response. ISO-8859-1 encodes
    // one byte per char, so the char counts size the buffer exactly.
    // including the status line here gives all-or-nothing semantics: a
    // validation failure leaves nothing on the wire
    int total = getTotal(statusLine, map);
    // per-connection scratch: the h1 response head is written on the
    // connection's single VT, so the assembly buffer is reused across
    // responses (this was a per-response allocation)
    byte[] head = connection.headerScratch;
    if (head == null || head.length < total) {
      head = new byte[total];
      if (total <= 8192) {
        connection.headerScratch = head;
      }
    }
    final byte[] buf = head;
    int pos = 0;
    System.arraycopy(statusLine, 0, buf, pos, statusLine.length);
    pos += statusLine.length;
    if (map instanceof robaho.net.httpserver.internal.OptimizedHeaders optimized) {
      // fast path: raw scan of the parallel arrays, no entrySet
      // wrappers. values were validated by getTotal's validation pass
      final int[] posRef = {pos};
      optimized.forEachRaw((key, raw) -> {
        if (raw instanceof String single) {
          posRef[0] = writeHeaderLine(buf, posRef[0], key, single);
        } else if (raw instanceof List<?> list) {
          // indexed loop: an enhanced-for would allocate an
          // Iterator per multi-valued header per response pass
          // (this loop runs twice - validation and write)
          for (int li = 0; li < list.size(); li++) {
            posRef[0] = writeHeaderLine(buf, posRef[0], key, (String) list.get(li));
          }
        }
      });
      pos = posRef[0];
    } else {
      for (Map.Entry<String, List<String>> entry : map.entrySet()) {
        String key = entry.getKey();
        for (String val : entry.getValue()) {
          pos = writeHeaderLine(buf, pos, key, val);
        }
      }
    }
    buf[pos++] = '\r';
    buf[pos++] = '\n';
    os.write(buf, 0, pos);
  }

  /**
   * one "Key: value CRLF" line into the head buffer, with the Date
   * identity fast path (the framework-set Date uses the SAME snapshot's
   * pre-encoded bytes as the string exposed to handlers) and the
   * per-connection pre-encoded name table (keep-alive connections emit
   * the same names every response: one arraycopy instead of the per-char
   * writeAscii loop)
   */
  private int writeHeaderLine(byte[] head, int pos, String key, String val) {
    byte[] encodedName = connection.cachedHeaderNameBytes(key);
    if (encodedName != null) {
      System.arraycopy(encodedName, 0, head, pos, encodedName.length);
      pos += encodedName.length;
    } else {
      int nameStart = pos;
      pos = writeAscii(key, head, pos);
      connection.cacheHeaderNameBytes(key, head, nameStart, pos - nameStart);
    }
    head[pos++] = ':';
    head[pos++] = ' ';
    if (Objects.equals(val, frameworkDate) && frameworkClock != null) {
      byte[] dateBytes = frameworkClock.dateAndTimeBytes();
      System.arraycopy(dateBytes, 0, head, pos, dateBytes.length);
      pos += dateBytes.length;
    } else {
      pos = writeAscii(val, head, pos);
    }
    head[pos++] = '\r';
    head[pos++] = '\n';
    return pos;
  }

  private static int getTotal(byte[] statusLine, Headers map) throws IOException {
    if (map instanceof robaho.net.httpserver.internal.OptimizedHeaders optimized) {
      // fast path: raw scan of the parallel arrays, no entrySet
      // wrapper allocations (SimpleEntry + ArrayList per header).
      // validation first (BiConsumers cannot throw checked
      // exceptions), then a pure counting pass
      validateResponseHeaderValues(optimized);
      final int[] totalRef = {statusLine.length + 2}; // trailing CRLF
      optimized.forEachRaw((key, raw) -> {
        int keyLen = key.length();
        if (raw instanceof String single) {
          totalRef[0] += keyLen + 2 + single.length() + 2;
        } else if (raw instanceof List<?> list) {
          for (int li = 0; li < list.size(); li++) {
            // nulls were rejected by validation; toString is the
            // same value the generic path writes
            totalRef[0] += keyLen + 2 + ((String) list.get(li)).length() + 2;
          }
        }
      });
      return totalRef[0];
    }
    int total = statusLine.length + 2; // trailing CRLF
    for (Map.Entry<String, List<String>> entry : map.entrySet()) {
      String key = entry.getKey();
      for (String val : entry.getValue()) {
        total += checkedValueLen(key, val);
      }
    }
    return total;
  }

  /**
   * reject null / CR / LF values in any stored form (response splitting
   * and the null-value contract), walking the raw storage directly
   */
  private static void validateResponseHeaderValues(robaho.net.httpserver.internal.OptimizedHeaders headers) throws IOException {
    // a lambda cannot throw a checked exception, so the scan collects the
    // first violation and this method throws after the pass
    var violation = new String[2];
    headers.forEachRaw((key, raw) -> {
      if (violation[0] != null) {
        return;
      }
      if (raw instanceof String single) {
        if (single.indexOf('\r') >= 0 || single.indexOf('\n') >= 0) {
          violation[0] = key;
        }
      } else if (raw instanceof List<?> list) {
        for (int li = 0; li < list.size(); li++) {
          String v = (String) list.get(li);
          if (v == null || v.indexOf('\r') >= 0 || v.indexOf('\n') >= 0) {
            violation[0] = key;
            break;
          }
        }
      } else {
        // the NULL_VALUE marker: a null stored through the lenient
        // mutation paths (or merged from a reflectively swapped map
        // carrying [null]) - illegal as a response header value
        violation[0] = key;
      }
    });
    if (violation[0] != null) {
      throw new IOException("illegal response header value for " + violation[0]);
    }
  }

  private static int checkedValueLen(String key, String val) throws IOException {
    // a CR/LF in a response header value would split the response
    // (response splitting); refuse rather than write it through
    if (val == null || val.indexOf('\r') >= 0 || val.indexOf('\n') >= 0) {
      throw new IOException("illegal response header value for " + key);
    }
    return val.length();
  }

  public InetSocketAddress getRemoteAddress() { return connection.getRemoteAddress(); }

  public InetSocketAddress getLocalAddress() { return connection.getLocalAddress(); }

  public String getProtocol() { return req.protocol(); }

  public SSLSession getSSLSession() { return connection.getSSLSession(); }

  public Object getAttribute(String name) {
    if (name == null) {
      throw new NullPointerException("null name parameter");
    }
    if (attributes == null) {
      attributes = getHttpContext().getAttributes();
    }
    return attributes.get(name);
  }

  public void setAttribute(String name, Object value) {
    if (name == null) {
      throw new NullPointerException("null name parameter");
    }
    if (attributes == null) {
      attributes = getHttpContext().getAttributes();
    }
    if (value != null) {
      attributes.put(name, value);
    } else {
      attributes.remove(name);
    }
  }

  public void setStreams(InputStream i, OutputStream o) {
    assert uis != null;
    if (i != null) {
      uis = i;
    }
    if (o != null) {
      uos = o;
    }
  }

  /**
   * PP
   */
  HttpConnection getConnection() { return connection; }

  ServerImpl getServerImpl() { return getHttpContext().getServerImpl(); }

  public HttpPrincipal getPrincipal() { return principal; }

  void setPrincipal(HttpPrincipal principal) { this.principal = principal; }

}

/**
 * An OutputStream which wraps another stream
 * which is supplied either at creation time, or sometime later.
 * If a caller/user tries to write to this stream before
 * the wrapped stream has been provided, then an IOException will
 * be thrown.
 */
class PlaceholderOutputStream extends java.io.OutputStream {

  OutputStream wrapped;

  PlaceholderOutputStream(OutputStream os) {
    wrapped = os;
  }

  void setWrappedStream(OutputStream os) { wrapped = os; }

  boolean isWrapped() { return wrapped != null; }

  private void checkWrap() throws IOException {
    if (wrapped == null) {
      throw new IOException("response headers not sent yet");
    }
  }

  public void write(int b) throws IOException {
    checkWrap();
    wrapped.write(b);
  }

  @Override
  public void write(byte[] b) throws IOException {
    checkWrap();
    wrapped.write(b);
  }

  @Override
  public void write(byte[] b, int off, int len) throws IOException {
    checkWrap();
    wrapped.write(b, off, len);
  }

  @Override
  public void flush() throws IOException {
    checkWrap();
    wrapped.flush();
  }

  @Override
  public void close() throws IOException {
    checkWrap();
    wrapped.close();
  }

}
