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
import robaho.net.httpserver.internal.NoSyncBufferedInputStream;
import robaho.net.httpserver.internal.OptimizedHeaders;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 */
class Request {

  static final char CR = 13;

  static final char LF = 10;

  /** RFC 7230 tchar: the only bytes legal in a header field name */
  private static final boolean[] TCHAR = new boolean[128];
  static {
    for (char c = '0'; c <= '9'; c++)
      TCHAR[c] = true;
    for (char c = 'a'; c <= 'z'; c++)
      TCHAR[c] = true;
    for (char c = 'A'; c <= 'Z'; c++)
      TCHAR[c] = true;
    for (char c : "!#$%&'*+-.^_`|~".toCharArray())
      TCHAR[c] = true;
  }

  private static final byte[] SP = {' '};

  private final NoSyncBufferedInputStream is;

  private final OutputStream os;

  private final String requestLine;

  /**
   * the protocol token of the request line, cached to avoid a substring
   * allocation on every getProtocol() call
   */
  private final String protocol;

  Request(InputStream rawInputStream, OutputStream rawout) throws IOException {
    this(rawInputStream, rawout, new ParseBuffers());
  }

  Request(InputStream rawInputStream, OutputStream rawout, ParseBuffers buffers) throws IOException {
    // the bulk line reader needs the internal buffer access; production
    // always passes the connection's NoSyncBufferedInputStream, other
    // callers (unit tests) get wrapped
    this.is =
        rawInputStream instanceof NoSyncBufferedInputStream buffered ? buffered : new NoSyncBufferedInputStream(rawInputStream);
    this.os = rawout;
    this.buffers = buffers;

    requestLine = readRequestLine();
    int lastSpace = requestLine.lastIndexOf(' ');
    protocol = lastSpace >= 0 ? requestLine.substring(lastSpace + 1) : "";
  }

  /**
   * per-connection parse state (moved from the static ThreadLocals, audit
   * 3.3): the h1 model is one VT per connection, so the ThreadLocal gave
   * each connection its own set anyway - but pinned on the parked VT, the
   * idle cleaner could never reclaim it (~430B per idle connection, 100k
   * connections = 43MB). as a connection field the cleaner's shrink
   * nullability applies, and the next request re-creates it on demand
   */
  static final class ParseBuffers {

    OptimizedHeaders headerTable = new OptimizedHeaders(16);

    final BufferedBuilder[] builders = {new BufferedBuilder(64), new BufferedBuilder(32), new BufferedBuilder(128)};

  }

  private final ParseBuffers buffers;

  public InputStream inputStream() {
    return is;
  }

  public OutputStream outputStream() {
    return os;
  }

  /** the protocol token from the request line, e.g. "HTTP/1.1" */
  public String protocol() {
    return protocol;
  }

  /**
   * read the request line, skipping leading empty lines (RFC 9112 3 allows
   * at least one). the request-line CR rule (preserving the old per-byte
   * reader's quirk): a trailing CR run of EXACTLY one is the CRLF
   * terminator and is stripped; a run of two or more is all content (the LF
   * then terminated the line bare); interior CRs are content.
   */
  private String readRequestLine() throws IOException {
    final BufferedBuilder bb = buffers.builders[0];
    bb.reset();
    int emptyLines = 0;
    while (true) {
      int len;
      try {
        len = is.readLine(ServerConfig.getMaxReqLineSize());
      } catch (java.net.SocketException | java.io.EOFException e) {
        // the peer disconnected mid-line: not a protocol error. a
        // 400 attempt on the dead socket is log noise and pollutes
        // the reply-error stats - close silently, like EOF
        return "";
      }
      if (len < 0) {
        // EOF: the old reader cleared its builder and returned ""
        return "";
      }
      byte[] raw = is.lineBuffer();
      int off = is.lineBufferOffset();
      if (len > 0 && raw[off + len - 1] == CR && (len == 1 || raw[off + len - 2] != CR)) {
        len--;
      }
      if (len > 0) {
        bb.appendBytes(raw, off, len);
        return bb.trimmed();
      }
      // empty line: skip it (RFC 9112 3), but bound the count - an
      // unbounded skip lets a peer hold the connection with blank
      // lines for as long as the idle timeout allows
      if (++emptyLines > 4) {
        throw new IOException("too many leading empty lines");
      }
    }
  }

  /**
   * @return the request line or the empty string if not found
   */
  public String requestLine() {
    return requestLine;
  }

  Headers hdrs = null;

  /**
   * a request with an extraordinary header count grows the table well
   * past the common size; do not pin that capacity for the connection's
   * lifetime (the same policy the parse builders apply)
   */
  private static final int HEADER_TABLE_RETAIN_LIMIT = 64;

  private static final class BufferedBuilder {

    private byte[] buffer;

    private int capacity;

    private int count;

    BufferedBuilder(int capacity) {
      buffer = new byte[capacity];
      this.capacity = capacity;
    }

    boolean isEmpty() { return count == 0; }

    void append(char c) {
      if (count == capacity) {
        capacity *= 2;
        buffer = Arrays.copyOf(buffer, capacity);
      }
      buffer[count++] = (byte) c;
    }

    void appendBytes(byte[] src, int off, int len) {
      if (count + len > capacity) {
        capacity = Math.max(capacity * 2, count + len);
        buffer = Arrays.copyOf(buffer, capacity);
      }
      System.arraycopy(src, off, buffer, count, len);
      count += len;
    }

    /**
     * clear the content for reuse; a builder grown by one huge line is
     * shrunk back instead of pinning that capacity for the connection's
     * lifetime (the same policy the input stream applies to lineBuf)
     */
    void reset() {
      count = 0;
      if (buffer.length > 8192) {
        buffer = new byte[256];
        capacity = 256;
      }
    }

    public String trimmed() {
      int start = 0;
      while (start < count && buffer[start] == ' ')
        start++;
      int end = count;
      // RFC 9110 5.5: a field value excludes leading AND trailing
      // whitespace, and OWS is SP or HTAB - stripping only SP let a
      // trailing tab leak into every consumer ("Content-Length: 5\t"
      // was rejected as non-digits, "Connection: close\t" ignored)
      while (end > 0 && (buffer[end - 1] == ' ' || buffer[end - 1] == '\t'))
        end--;
      count = 0;
      // an all-spaces line is reachable on the REQUEST line (the
      // skip-empty-lines loop checks raw emptiness): without the clamp
      // end-start goes negative and String construction throws
      if (end < start) {
        end = start;
      }
      return new String(buffer, start, end - start, StandardCharsets.ISO_8859_1);
    }

  }

  Headers headers() throws IOException {
    if (hdrs != null) {
      return hdrs;
    }
    OptimizedHeaders table = buffers.headerTable;
    if (table.size() > HEADER_TABLE_RETAIN_LIMIT) {
      table = new OptimizedHeaders(16);
      buffers.headerTable = table;
    } else {
      table.clear();
    }
    hdrs = table;

    BufferedBuilder[] builders = buffers.builders;
    BufferedBuilder key = builders[1];
    BufferedBuilder value = builders[2];
    key.reset();
    value.reset();
    int headerCount = 0;
    int limit = ServerConfig.getMaxReqLineSize();
    // the raw line cap: key(limit) + ':' + value(limit) is the largest
    // line the per-builder checks accept. the old per-byte parser let
    // whitespace runs (obs-fold leading, post-colon) grow unbounded; the
    // line cap now rejects such pathological lines with the same
    // "header line too long" error
    int lineLimit = 2 * limit + 2;
    // total header block budget (RFC 9113 SETTINGS_MAX_HEADER_LIST_SIZE
    // accounting): request line + every raw line + 32 per pair. without
    // it the per-line and count limits alone allow ~25MB per request
    int budget = ServerConfig.getMaxReqHeaderSize();
    long consumed = (requestLine == null ? 0 : requestLine.length()) + 32L;

    while (true) {
      int len = is.readLine(lineLimit);
      if (len < 0) {
        // EOF: a pending unterminated header is dropped, exactly as
        // the old loop dropped its builders at EOF
        break;
      }
      consumed += len + 2L;
      if (budget > 0 && consumed > budget) {
        throw new IOException("Maximum header size (" + budget + ") exceeded");
      }
      byte[] raw = is.lineBuffer();
      // the line may be a zero-copy view into the stream buffer: all
      // indexes below are offset-relative
      int off = is.lineBufferOffset();
      // header CR rule: ALL trailing CRs are swallowed (the old parser
      // set prevCR on each); any CR left inside the line was followed
      // by a non-CR/non-LF byte - a bare CR
      int end = off + len;
      while (end > off && raw[end - 1] == CR) {
        end--;
      }
      for (int i = off; i < end; i++) {
        if (raw[i] == CR) {
          throw new IOException("bare CR in header line");
        }
      }
      if (end == off) {
        // blank line: end of headers, flushing a pending header first
        if (!key.isEmpty() || !value.isEmpty()) {
          addHeader(table, key, value, ++headerCount);
        }
        break;
      }
      // obs-fold: a line starting with whitespace continues the pending
      // value (RFC 9112 5.2: replace obs-fold with SP)
      if (raw[off] == ' ' || raw[off] == '\t') {
        if (key.isEmpty() && value.isEmpty()) {
          // a continuation line before any header field: RFC 9112
          // 5.2 makes the whole message malformed. the old code
          // flushed it as a header with an EMPTY NAME
          throw new IOException("obs-fold line before any header field");
        }
        int start = off;
        while (start < end && (raw[start] == ' ' || raw[start] == '\t')) {
          start++;
        }
        if (!value.isEmpty() && start < end) {
          appendLimited(value, SP, 0, 1, limit);
        }
        appendLimited(value, raw, start, end - start, limit);
        continue;
      }
      // a new header line: flush the pending one
      if (!key.isEmpty() || !value.isEmpty()) {
        addHeader(table, key, value, ++headerCount);
      }
      consumed += 32; // per-pair overhead, RFC 9113 accounting
      // name up to the FIRST colon (first char upper, rest lower, as
      // the old parser normalized); then the value after leading
      // whitespace
      int colon = off;
      while (colon < end && raw[colon] != ':') {
        colon++;
      }
      if (colon - off > limit) {
        throw new IOException("header line too long (limit " + limit + " bytes)");
      }
      if (colon == off || colon >= end) {
        // empty name, or no colon at all: both are malformed
        // field-lines (RFC 9112 5) and rejected with 400 by the JDK
        throw new IOException(colon == off ? "empty header name" : "header line without colon");
      }
      // RFC 9112 5.1: "No whitespace is allowed between the field
      // name and colon... A server MUST reject, with a response status
      // code of 400, any received request message that contains
      // whitespace between a header field name and colon." the old
      // lenient trim (historical "KEY : VAL" behavior) is a request-
      // smuggling differential against RFC-compliant front proxies
      // (the JDK 400s it; the differential harness caught the gap)
      int nameEnd = colon;
      if (nameEnd > off && (raw[nameEnd - 1] == ' ' || raw[nameEnd - 1] == '\t')) {
        throw new IOException("whitespace between header field name and colon");
      }
      for (int j = off; j < nameEnd; j++) {
        char c = (char) (raw[j] & 0xFF);
        if (c >= 128 || !TCHAR[c]) {
          throw new IOException("invalid character in header name");
        }
        key.append(j == off ? Character.toUpperCase(c) : Character.toLowerCase(c));
      }
      if (colon < end) {
        int v = colon + 1;
        while (v < end && (raw[v] == ' ' || raw[v] == '\t')) {
          v++;
        }
        appendLimited(value, raw, v, end - v, limit);
      }
    }
    return hdrs;
  }

  /** append a raw slice to a builder under the per-builder line limit */
  private void appendLimited(BufferedBuilder b, byte[] src, int off, int len, int limit) throws IOException {
    if (b.count + len > limit) {
      throw new IOException("header line too long (limit " + limit + " bytes)");
    }
    b.appendBytes(src, off, len);
  }

  /**
   * enforce the request header count limit and add the header: shared by
   * the end-of-headers and mid-headers paths so the limit cannot drift.
   * the key builder already produced the normalized shape while
   * validating TCHAR (first char upper, rest lower), so the redundant
   * normalize scan in add() is skipped (addNormalized)
   */
  private void addHeader(OptimizedHeaders table, BufferedBuilder key, BufferedBuilder value, int count) throws IOException {
    if (count > ServerConfig.getMaxReqHeaders()) {
      throw new IOException("too many request headers (limit " + ServerConfig.getMaxReqHeaders() + ")");
    }
    table.addNormalized(key.trimmed(), value.trimmed());
  }

}
