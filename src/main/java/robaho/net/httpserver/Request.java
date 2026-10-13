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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import com.sun.net.httpserver.Headers;

/**
 */
class Request {

    static final char CR = 13;
    static final char LF = 10;

    private final NoSyncBufferedInputStream is;
    private final OutputStream os;

    private final String requestLine;
    /** the protocol token of the request line, cached to avoid a substring
     * allocation on every getProtocol() call */
    private final String protocol;

    Request(InputStream rawInputStream, OutputStream rawout) throws IOException {
        // the bulk line reader needs the internal buffer access; production
        // always passes the connection's NoSyncBufferedInputStream, other
        // callers (unit tests) get wrapped
        this.is = rawInputStream instanceof NoSyncBufferedInputStream buffered ? buffered
                : new NoSyncBufferedInputStream(rawInputStream);
        os = rawout;

        requestLine = readRequestLine();
        int lastSpace = requestLine.lastIndexOf(' ');
        protocol = lastSpace >= 0 ? requestLine.substring(lastSpace + 1) : "";
    }

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
        final BufferedBuilder bb = new BufferedBuilder(64);
        while (true) {
            int len = is.readLine(ServerConfig.getMaxReqLineSize());
            if (len < 0) {
                // EOF: the old reader cleared its builder and returned ""
                return "";
            }
            byte[] raw = is.lineBuffer();
            if (len > 0 && raw[len - 1] == CR && (len == 1 || raw[len - 2] != CR)) {
                len--;
            }
            if (len > 0) {
                bb.appendBytes(raw, 0, len);
                return bb.trimmed();
            }
            // empty line: skip it
        }
    }

    /**
     * @returns the request line or the empty string if not found
     */
    public String requestLine() {
        return requestLine;
    }

    Headers hdrs = null;

    private static final class BufferedBuilder {
        private byte[] buffer;
        private int capacity;
        private int count;
        BufferedBuilder(int capacity) {
            buffer = new byte[capacity];
            this.capacity=capacity;
        }
        boolean isEmpty() {
            return count==0;
        }
        void append(char c) {
            if(count==capacity) {
                capacity *= 2;
                buffer = Arrays.copyOf(buffer, capacity);
            }
            buffer[count++]=(byte)c;
        }
        void appendBytes(byte[] src, int off, int len) {
            if(count+len>capacity) {
                capacity = Math.max(capacity*2, count+len);
                buffer = Arrays.copyOf(buffer, capacity);
            }
            System.arraycopy(src, off, buffer, count, len);
            count += len;
        }
        public String trimmed() {
            int start=0;
            while(start<count && buffer[start]==' ') start++;
            int end=count;
            while(end>0 && buffer[end-1]==' ') end--;
            count=0;
            // an all-spaces line is reachable on the REQUEST line (the
            // skip-empty-lines loop checks raw emptiness): without the clamp
            // end-start goes negative and String construction throws
            if (end < start) {
                end = start;
            }
            return new String(buffer,start,end-start,StandardCharsets.ISO_8859_1);
        }
    }

    Headers headers() throws IOException {
        if (hdrs != null) {
            return hdrs;
        }
        hdrs = new OptimizedHeaders(16);

        BufferedBuilder key = new BufferedBuilder(32);
        BufferedBuilder value = new BufferedBuilder(128);
        int headerCount = 0;
        int limit = ServerConfig.getMaxReqLineSize();
        // the raw line cap: key(limit) + ':' + value(limit) is the largest
        // line the per-builder checks accept. the old per-byte parser let
        // whitespace runs (obs-fold leading, post-colon) grow unbounded; the
        // line cap now rejects such pathological lines with the same
        // "header line too long" error
        int lineLimit = 2 * limit + 2;

        while (true) {
            int len = is.readLine(lineLimit);
            if (len < 0) {
                // EOF: a pending unterminated header is dropped, exactly as
                // the old loop dropped its builders at EOF
                break;
            }
            byte[] raw = is.lineBuffer();
            // header CR rule: ALL trailing CRs are swallowed (the old parser
            // set prevCR on each); any CR left inside the line was followed
            // by a non-CR/non-LF byte - a bare CR
            int end = len;
            while (end > 0 && raw[end - 1] == CR) {
                end--;
            }
            for (int i = 0; i < end; i++) {
                if (raw[i] == CR) {
                    throw new IOException("bare CR in header line");
                }
            }
            if (end == 0) {
                // blank line: end of headers, flushing a pending header first
                if (!key.isEmpty() || !value.isEmpty()) {
                    addHeader(key, value, ++headerCount);
                }
                break;
            }
            // obs-fold: a line starting with whitespace continues the pending
            // value (leading whitespace skipped, no separator inserted)
            if (raw[0] == ' ' || raw[0] == '\t') {
                int start = 0;
                while (start < end && (raw[start] == ' ' || raw[start] == '\t')) {
                    start++;
                }
                appendLimited(value, raw, start, end - start, limit);
                continue;
            }
            // a new header line: flush the pending one
            if (!key.isEmpty() || !value.isEmpty()) {
                addHeader(key, value, ++headerCount);
            }
            // name up to the FIRST colon (first char upper, rest lower, as
            // the old parser normalized); then the value after leading
            // whitespace. no colon: everything is the name, value empty
            int colon = 0;
            while (colon < end && raw[colon] != ':') {
                colon++;
            }
            if (colon > limit) {
                throw new IOException("header line too long (limit " + limit + " bytes)");
            }
            for (int j = 0; j < colon; j++) {
                char c = (char) (raw[j] & 0xFF);
                key.append(j == 0 ? Character.toUpperCase(c) : Character.toLowerCase(c));
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

    /** enforce the request header count limit and add the header: shared by
     * the end-of-headers and mid-headers paths so the limit cannot drift */
    private void addHeader(BufferedBuilder key, BufferedBuilder value, int count) throws IOException {
        if (count > ServerConfig.getMaxReqHeaders()) {
            throw new IOException("too many request headers (limit " + ServerConfig.getMaxReqHeaders() + ")");
        }
        hdrs.add(key.trimmed(), value.trimmed());
    }
}
