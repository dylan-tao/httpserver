/*
 * Copyright (c) 2005, 2007, Oracle and/or its affiliates. All rights reserved.
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

import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.locks.ReentrantLock;

/**
 * a (filter) input stream which can tell us if bytes are "left over"
 * on the underlying stream which can be read (without blocking)
 * on another instance of this class.
 * <p>
 * The class can also report if all bytes "expected" to be read
 * were read, by the time close() was called. In that case,
 * bytes may be drained to consume them (by calling drain() ).
 * <p>
 * isEOF() returns true, when all expected bytes have been read
 */
abstract class LeftOverInputStream extends FilterInputStream {
    final ExchangeImpl t;
    final ServerImpl server;
    protected volatile boolean closed = false;
    protected volatile boolean eof = false;
    byte[] one = new byte[1];

    protected LeftOverInputStream(ExchangeImpl t, InputStream src) {
        super(src);
        this.t = t;
        this.server = t.getServerImpl();
    }

    /** guards read/skip/close against each other. a ReentrantLock rather
     * than a synchronized monitor: purely historical - monitors stopped
     * pinning virtual threads' carriers in JDK 24 (JEP 491), so a plain
     * synchronized would also be correct today */
    private final ReentrantLock lock = new ReentrantLock();

    /** matches read(): a concurrent close (e.g. from a timeout task) must not
     * interleave with a read and corrupt stream state */
    @Override
    public void close() throws IOException {
        lock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            if (!eof) {
                eof = drain(ServerConfig.getDrainAmount());
            }
        } finally {
            lock.unlock();
        }
    }

    public boolean isClosed() {
        return closed;
    }

    /** returns true if all expected bytes have been read (or drained) */
    public boolean isEOF() {
        return eof;
    }

    /** these streams carry HTTP framing state (Content-Length / chunked):
     * mark and reset would desync it, so they are unsupported regardless of
     * what the underlying stream supports (FilterInputStream would otherwise
     * delegate) */
    @Override
    public boolean markSupported() {
        return false;
    }

    @Override
    public void mark(int l) {
    }

    @Override
    public void reset() throws IOException {
        throw new IOException("mark/reset not supported");
    }

    protected abstract int readImpl(byte[] b, int off, int len) throws IOException;

    /** returns true if the stream is already at eof without reading. the
     * default reflects the last readImpl result; subclasses with a known
     * length can detect eof without any read at all. */
    protected boolean atEof() {
        return eof;
    }

    @Override
    public int read() throws IOException {
        lock.lock();
        try {
            if (closed) {
                throw new EOFException("Stream is closed");
            }
            int c = readImpl(one, 0, 1);
            if (c == -1) {
                return c;
            }
            long cap = ServerConfig.getMaxRequestBodySize();
            if (cap > 0 && ++totalRead > cap) {
                throw new IOException("request body exceeds the configured maximum of " + cap + " bytes");
            }
            return one[0] & 0xFF;
        } finally {
            lock.unlock();
        }
    }

    /** total body bytes delivered through this stream: enforces the opt-in
     * maxRequestBodySize cap incrementally for chunked bodies (a declared
     * Content-Length is rejected up front in the request parser) */
    private long totalRead;

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        lock.lock();
        try {
            if (closed) {
                throw new EOFException("Stream is closed");
            }
            int n = readImpl(b, off, len);
            if (n > 0) {
                long cap = ServerConfig.getMaxRequestBodySize();
                if (cap > 0 && (totalRead += n) > cap) {
                    throw new IOException("request body exceeds the configured maximum of " + cap + " bytes");
                }
            }
            return n;
        } finally {
            lock.unlock();
        }
    }

    /** FilterInputStream.skip would delegate to the raw socket stream and
     * bypass HTTP framing entirely (Content-Length/chunked state would not
     * advance), desyncing the connection: discard through the framing-aware
     * readImpl instead */
    @Override
    public long skip(long n) throws IOException {
        lock.lock();
        try {
            if (closed) {
                throw new EOFException("Stream is closed");
            }
            long remaining = n;
            while (remaining > 0) {
                int len = (int) Math.min(drainBuffer.length, remaining);
                len = readImpl(drainBuffer, 0, len);
                if (len == -1) {
                    break;
                }
                // the same incremental body cap the read paths enforce:
                // skip() used to bypass it, so a handler discarding the body
                // via skip() consumed an unbounded chunked stream
                long cap = ServerConfig.getMaxRequestBodySize();
                if (cap > 0 && (totalRead += len) > cap) {
                    throw new IOException("request body exceeds the configured maximum of " + cap + " bytes");
                }
                remaining -= len;
            }
            return n - remaining;
        } finally {
            lock.unlock();
        }
    }

    // ok to concurrently share this since the data isn't used
    private static final byte[] drainBuffer = new byte[2048];
    /** how long drain() waits for straggling body bytes before giving up on
     * keep-alive: a slow client still sending its body keeps the connection;
     * a client that will never send more releases the exchange within a second */
    private static final long MAX_DRAIN_WAIT_MILLIS = 1000;

    /**
     * read and discard up to l bytes or "eof" occurs,
     * (whichever is first). Then return true if the stream
     * is at eof (ie. all bytes were read) or false if not
     * (still bytes to be read).
     *
     * Data that has not arrived yet is waited for, bounded by
     * MAX_DRAIN_WAIT_MILLIS: a blocking read with no bound would stall the
     * response forever when the client stops sending its body, while giving
     * up immediately would drop keep-alive for slow clients that are still
     * sending a small body.
     */
    public boolean drain(long l) throws IOException {
        final long deadline = System.currentTimeMillis() + MAX_DRAIN_WAIT_MILLIS;
        try {
            while (l > 0) {
                if (server.isFinishing()) {
                    break;
                }
                if (atEof()) {
                    // the stream is at eof without needing to read (e.g. a
                    // zero length request body): readImpl would never be
                    // called to set the flag, so check it here
                    eof = true;
                    return true;
                }
                int available = in.available();
                if (available <= 0) {
                    // nothing has arrived yet: block for the remaining window
                    // instead of 10ms polling. one bounded read reacts
                    // instantly to straggler bytes (the old loop cost up to
                    // 100 sleep/wake cycles plus ~100 available() calls per
                    // teardown). the socket read timeout is set to the
                    // remaining window and restored afterwards
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) {
                        break;
                    }
                    t.connection.drainingAt = System.currentTimeMillis();
                    var socket = t.connection.getSocket();
                    int previousTimeout = socket.getSoTimeout();
                    try {
                        socket.setSoTimeout((int) Math.min(remaining, Integer.MAX_VALUE));
                        int len = readImpl(drainBuffer, 0, (int) Math.min(drainBuffer.length, l));
                        if (len == -1) {
                            eof = true;
                            return true;
                        }
                        l -= len;
                    } catch (java.net.SocketTimeoutException e) {
                        break;
                    } finally {
                        socket.setSoTimeout(previousTimeout);
                    }
                    continue;
                }
                t.connection.drainingAt = System.currentTimeMillis();
                // the buffered bytes may not complete the chunk framing
                // (a withheld CRLF makes readImpl block in a socket read):
                // bound it with the same SoTimeout the empty branch uses,
                // or the documented 1s drain bound is bypassed and the
                // connection VT is pinned until the cleaner's 15s sweep
                var socket = t.connection.getSocket();
                int previousTimeout = socket.getSoTimeout();
                int len;
                try {
                    // the REMAINING budget (deadline already includes the
                    // wait): the old formula (remaining + MAX) went NEGATIVE
                    // once elapsed exceeded 2s (a drip-fed chunked body
                    // reaches that), and setSoTimeout(<0) throws
                    // IllegalArgumentException; clamp to >=1ms
                    socket.setSoTimeout((int) Math.max(
                        deadline - System.currentTimeMillis(), 1));
                    len = (int) Math.min(Math.min(drainBuffer.length, available), l);
                    len = readImpl(drainBuffer, 0, len);
                } catch (java.net.SocketTimeoutException e) {
                    break;
                } finally {
                    socket.setSoTimeout(previousTimeout);
                }
                if (len == -1) {
                    eof = true;
                    return true;
                } else {
                    l = l - len;
                }
            }
            return false;
        } finally {
            t.connection.drainingAt = 0;
        }
    }
}
