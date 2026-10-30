/*
 * Copyright (c) 2005, 2022, Oracle and/or its affiliates. All rights reserved.
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

import robaho.net.httpserver.internal.NoSyncBufferedInputStream;
import robaho.net.httpserver.internal.NoSyncBufferedOutputStream;

import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicLong;

/**
 * encapsulates all the connection specific state for a HTTP/S connection
 * one of these is hung from the selector attachment and is used to locate
 * everything from that.
 */
class HttpConnection implements robaho.net.httpserver.internal.Http2ConnectionHandle {
    private static final Logger logger = System.getLogger("robaho.net.httpserver");

    HttpContextImpl context;

    /* low level stream that sits directly over channel */
    NoSyncBufferedInputStream is;
    OutputStream os;

    final Socket socket;
    volatile boolean closed = false;

    volatile long lastActivityTime;
    volatile boolean inRequest;
    /** reusable response-head assembly buffer: h1 responses are written on
     * the connection's single VT, so writeHeaders can reuse one scratch
     * across responses instead of allocating per response. retained up to
     * HEADER_SCRATCH_RETAIN_LIMIT; larger heads fall back to per-response
     * allocation so a huge-header application cannot pin memory */
    byte[] headerScratch;

    /** pre-encoded ISO-8859-1 bytes of response header names already
     * written on this connection: keep-alive connections re-emit the same
     * names every response (Date, Content-length, handler headers), so
     * writeHeaderLine's per-char writeAscii loop becomes one arraycopy.
     * deterministic (writeAscii is a pure function of the String), bounded
     * at 64 entries, single-threaded (the connection's writer thread, same
     * ownership as headerScratch), dies with the connection */
    private robaho.net.httpserver.internal.OpenAddressMap<String, byte[]> headerNameBytes;

    /** the pre-encoded bytes for a name previously written on this
     * connection, or null (first emission - cache it after writing) */
    byte[] cachedHeaderNameBytes(String name) {
        var m = headerNameBytes;
        return m == null ? null : m.get(name);
    }

    /** intern the just-written slice of the assembly buffer as the name's
     * encoded form; bounded: a connection emitting pathological unique
     * names stops interning rather than growing the table */
    void cacheHeaderNameBytes(String name, byte[] src, int off, int len) {
        if (len == 0 || name.length() == 0) {
            return;
        }
        var m = headerNameBytes;
        if (m == null) {
            m = new robaho.net.httpserver.internal.OpenAddressMap<>(32);
            headerNameBytes = m;
        }
        if (m.size() >= 64) {
            return;
        }
        byte[] copy = new byte[len];
        System.arraycopy(src, off, copy, 0, len);
        m.put(name, copy);
    }

    /** the connection's shared chunked-staging buffer. lent to ONE chunked
     * exchange at a time by borrowChunkBuffer(); the chunked body stream
     * runs on the connection's single writer thread and returns it at
     * close(). null while lent out - a concurrent (pipelined) chunked
     * exchange allocates privately instead */
    private byte[] chunkBuffer;

    /** borrow the shared chunked-staging buffer, or null if another
     * exchange currently holds it */
    byte[] borrowChunkBuffer() {
        // null while lent out (the holder returns it in close()); a fresh
        // allocation when no buffer is available means every lend is
        // exclusive - a pipelined concurrent chunked exchange never shares
        byte[] b = chunkBuffer;
        if (b != null) {
            chunkBuffer = null;
        } else {
            b = new byte[ChunkedOutputStream.CHUNK_SIZE + ChunkedOutputStream.OFFSET + 2];
        }
        return b;
    }

    /** return a buffer borrowed via borrowChunkBuffer */
    void returnChunkBuffer(byte[] b) {
        chunkBuffer = b;
    }

    /** set when the exchange hands the RAW streams to non-request-loop code
     * (101 upgrade, CONNECT tunnel): reads and writes then come from
     * different threads, so the pre-read flush hook must stand down - its
     * unsynchronized flush would race the external writer on this buffer */
    volatile boolean streamsHandedOff;
    volatile long drainingAt;
    /** set when the peer declared it will send nothing more (h1
     * "Connection: close" exchange, fatal parse error): the RST-prevention
     * drain in close() only exists to empty bytes the peer may STILL send,
     * so for these connections it is pure latency on a doomed exchange */
    volatile boolean peerDoneSending;

    /** the h2 protocol handler for h2c/h2 connections, set when the
     * connection is upgraded; null for plain h1. lets the idle cleaner
     * reach the h2 stream buffer pool for shrinking */
    // volatile: written by the connection's VT after registration into
    // allConnections; the cleaner (Timer thread) reads it to pick the h2
    // shrink path - a stale null would route it through the UNSYNCHRONIZED
    // h1 buffer shrink against concurrent h2 frame writes
    volatile robaho.net.httpserver.http2.internal.Http2Connection http2Connection;

    final AtomicLong requestCount = new AtomicLong();

    /** cached connection endpoints; a socket's peer and local addresses
     * never change over its lifetime */
    private volatile InetSocketAddress remoteAddress;
    private volatile InetSocketAddress localAddress;

    @Override
    public long requestCount() {
        return requestCount.get();
    }

    @Override
    public void incrementRequestCount() {
        requestCount.incrementAndGet();
    }
    private final String connectionId;

    public boolean isClosed() {
        return closed;
    }

    HttpConnection(Socket socket) throws IOException {
        this.socket = socket;
        this.is = new NoSyncBufferedInputStream(new ActivityTrackingInputStream(socket.getInputStream()), ServerConfig.bufferSize());
        // configured size + 64: a full 16KB h2 DATA frame plus its 9-byte
        // frame header accumulate and flush as ONE write/packet. with a plain
        // 16KB buffer the payload bypasses the buffer and the header is
        // flushed as a separate 9-byte syscall (and, with TCP_NODELAY on, a
        // separate packet) per frame
        this.os = new NoSyncBufferedOutputStream(new ActivityTrackingOutputStream(socket.getOutputStream()), ServerConfig.bufferSize() + 64);
        // flush deferred response bytes right before a blocking input read:
        // a response deferred because pipelined requests were waiting can
        // never get stuck behind a partially-received request. skipped for
        // h2 connections - their frame writes come from handler threads and
        // would race this reader-thread flush on the shared output buffer
        // (h2 flushes under the connection lock instead)
        this.is.setPreReadFlush(() -> {
            if (http2Connection == null && !streamsHandedOff) {
                try {
                    os.flush();
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }
        });
        connectionId = "["+socket.getLocalPort()+"."+socket.getPort()+"]";
    }

    /** true when pipelined request bytes are already buffered and waiting:
     * lets response completion defer its flush so a pipelined batch
     * coalesces into one socket write instead of one per response */
    boolean hasBufferedInput() {
        return is.hasBufferedInput();
    }

    public void debug() {
        logger.log(Level.INFO, this +", inRequest "+inRequest+", request count "+requestCount.get());
    }

    public SSLSession getSSLSession() {
        return (socket instanceof SSLSocket ssl) ? ssl.getSession() : null;
    }

    @Override
    public long lastActivityTime() {
        return lastActivityTime;
    }

    @Override
    public void setLastActivityTime(long t) {
        lastActivityTime = t;
    }

    public boolean isSSL() {
        return socket instanceof SSLSocket;
    }

    @Override
    public String toString() {
        return connectionId;
    }

    public InetSocketAddress getRemoteAddress() {
        // per-connection addresses are immutable: cache them instead of
        // allocating two InetSocketAddress objects on every exchange's
        // getRemoteAddress()/getLocalAddress() calls
        if (remoteAddress == null) {
            remoteAddress = (InetSocketAddress) socket.getRemoteSocketAddress();
        }
        return remoteAddress;
    }

    public InetSocketAddress getLocalAddress() {
        if (localAddress == null) {
            localAddress = (InetSocketAddress) socket.getLocalSocketAddress();
        }
        return localAddress;
    }

    void setContext(HttpContextImpl ctx) {
        context = ctx;
    }

    Socket getSocket() {
        return socket;
    }

    /** guards the close transition. a ReentrantLock rather than a
     * synchronized monitor - historically because close() flushes and
     * closes streams (blocking IO) while holding it, which pinned a
     * virtual thread's carrier under JEP 444; JDK 24 (JEP 491) removed
     * that pinning, so this is now a plain style choice */
    private final java.util.concurrent.locks.ReentrantLock closeLock = new java.util.concurrent.locks.ReentrantLock();

    void close() {
        close(true);
    }

    /** close the connection.
     * @param flushResponse true from the connection's own thread: deliver a
     *  pending coalesced response (the pipelining optimization defers its
     *  flush until here). false from the cleaner/stop threads: a stalled
     *  peer's full kernel send buffer must not block connection management
     *  on the shared Timer thread - the response is doomed anyway
     */
    void close(boolean flushResponse) {
        closeLock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;

            if(requestCount.get()==0) {
                // a client probing the port (connect + close) lands here - debug,
                // not a warning, or TCP health checks flood the log
                logger.log(Level.DEBUG, () -> "closing connection: remote " + socket.getRemoteSocketAddress() + " with 0 requests");
            } else {
                logger.log(Level.TRACE, () -> "closing connection: remote " + socket.getRemoteSocketAddress());
            }

            if (socket.isClosed()) {
                return;
            }

            var h2 = http2Connection;

            // 1. FLUSH BEFORE is.close(): is.close() closes the socket, and
            //    the old order let the flush throw SocketException with the
            //    response still buffered - silently discarded. the fuzzer
            //    and the JDK differential both caught this: a pipelined
            //    request after Connection: close received 0 bytes
            if (flushResponse) {
                try {
                    if (h2 == null) {
                        os.flush();
                    } else if (h2.tryLock()) {
                        try {
                            os.flush();
                        } finally {
                            h2.unlock();
                        }
                    } // else: a stalled frame write holds the lock; skip
                } catch (IOException e) {
                    logger.log(Level.TRACE, "error flushing output stream", e);
                }
            }

            // 2. consume whatever the peer already sent so the kernel
            //    receive queue is empty at close: closing with unread data
            //    makes TCP send RST instead of FIN, and the RST purges the
            //    client's receive queue - erasing a response it was
            //    entitled to read (RFC 9112 9.6).
            //    HARD wall-clock deadline (the SoTimeout alone only bounds a
            //    read when NO data arrives - a peer trickling 1 byte per
            //    interval kept the loop returning forever, pinning the
            //    closing thread while it holds closeLock: stop() and the
            //    cleaner parked behind it). short per-read timeout so a
            //    quiet peer costs at most one 50ms wait; plain sockets skip
            //    entirely when nothing is pending.
            //    OWNING THREAD ONLY: for SSL sockets AppInputStream.read takes
            //    an UNTIMED readLock that the connection's own VT holds while
            //    parked on the next keep-alive request - a management-thread
            //    drain blocks on that lock BEFORE any SoTimeout applies and
            //    wedges the single cleaner Timer (or stop()) server-wide.
            //    the JDK's own deplete() uses tryLock for exactly this.
            //    management closes accept the residual RST risk instead
            try {
                boolean ssl = socket instanceof javax.net.ssl.SSLSocket;
                boolean owningThread = flushResponse;
                // peerDoneSending: the peer's request carried h1
                // "Connection: close" (or was fatally malformed and answered
                // with 400+close) - it promised to send nothing further, so
                // there is nothing the drain protects. skipping avoids the
                // up-to-250ms wait on every close-mode connection (bench and
                // health-check churn), including the SSL always-drain branch
                if (owningThread && !peerDoneSending
                        && (ssl || socket.getInputStream().available() > 0)) {
                    long drainDeadline = System.currentTimeMillis() + 250;
                    socket.setSoTimeout(50);
                    var raw = socket.getInputStream();
                    byte[] scratch = new byte[8192];
                    while (raw.read(scratch) != -1) {
                        if (System.currentTimeMillis() >= drainDeadline) {
                            break;
                        }
                    }
                }
            } catch (IOException ignored) {
            }

            // 3. close everything
            try {
                is.close();
            } catch (IOException e) {
                logger.log(Level.TRACE, "error closing input stream", e);
            }
            if (h2 == null && flushResponse) {
                // h1 only, and only from the owning thread: os.close()
                // flushes any residue, which can block on a stalled peer -
                // acceptable on the connection's own thread, never on the
                // cleaner Timer. h2 skips os.close() entirely: its
                // flushBuffer without the connection lock tears frames
                try {
                    os.close();
                } catch (IOException e) {
                    logger.log(Level.TRACE, "error closing output stream", e);
                }
            }
            try {
                socket.close();
            } catch (IOException e) {
                logger.log(Level.TRACE, "error closing socket", e);
            }
        } finally {
            closeLock.unlock();
        }
    }

    InputStream getInputStream() {
        return is;
    }

    OutputStream getOutputStream() {
        return os;
    }

    /**
     * Shrink the internal buffers for an idle connection: the 16KB output
     * buffer and (for h2) the pooled stream buffers are released. the next
     * write/stream re-allocates on demand. only call when the connection
     * is idle (not in a request) and no write is in progress.
     */
    void shrinkBuffers() {
        if (http2Connection != null) {
            // h2 frame writes go through this connection's output buffer from
            // handler threads under the h2 connection lock: shrink it while
            // holding that lock, or the buf swap races a concurrent write
            http2Connection.shrinkConnectionOutputBuffer();
            http2Connection.shrinkBuffers();
        } else if (os instanceof NoSyncBufferedOutputStream nos) {
            nos.shrinkBuffers();
            // the response-head assembly scratch: only touched by this
            // connection's single VT during request processing, so it is
            // safe to release here (same argument as the output buffer)
            headerScratch = null;
        }
    }

    HttpContextImpl getHttpContext() {
        return context;
    }

    private class ActivityTrackingInputStream extends FilterInputStream {

        private ActivityTrackingInputStream(InputStream inputStream) {
            super(inputStream);
            lastActivityTime = System.currentTimeMillis();
        }

        @Override
        public int read() throws IOException {
            try {
                return super.read();
            } finally {
                lastActivityTime = System.currentTimeMillis();
            }
        }

        @Override
        public long skip(long n) throws IOException {
            try {
                return super.skip(n);
            } finally {
                lastActivityTime = System.currentTimeMillis();
            }
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            try {
                return super.read(b, off, len);
            } finally {
                lastActivityTime = System.currentTimeMillis();
            }
        }

    }

    private class ActivityTrackingOutputStream extends FilterOutputStream {

        private ActivityTrackingOutputStream(OutputStream outputStream) {
            super(outputStream);
            lastActivityTime = System.currentTimeMillis();
        }
        @Override
        public void write(int b) throws IOException {
            try {
                out.write(b);
            } finally {
                lastActivityTime = System.currentTimeMillis();
            }
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            try {
                out.write(b, off, len);
            } finally {
                lastActivityTime = System.currentTimeMillis();
            }
        }
    }
}
