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
public class HttpConnection {
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

    /** set when the exchange hands the RAW streams to non-request-loop code
     * (101 upgrade, CONNECT tunnel): reads and writes then come from
     * different threads, so the pre-read flush hook must stand down - its
     * unsynchronized flush would race the external writer on this buffer */
    volatile boolean streamsHandedOff;
    volatile long drainingAt;

    /** the h2 protocol handler for h2c/h2 connections, set when the
     * connection is upgraded; null for plain h1. lets the idle cleaner
     * reach the h2 stream buffer pool for shrinking */
    robaho.net.httpserver.http2.HTTP2Connection http2Connection;

    public AtomicLong requestCount = new AtomicLong();
    private final String connectionId;

    public boolean isClosed() {
        return closed;
    }

    HttpConnection(Socket socket) throws IOException {
        this.socket = socket;
        this.is = new NoSyncBufferedInputStream(new ActivityTimerInputStream(socket.getInputStream()), ServerConfig.bufferSize());
        // configured size + 64: a full 16KB h2 DATA frame plus its 9-byte
        // frame header accumulate and flush as ONE write/packet. with a plain
        // 16KB buffer the payload bypasses the buffer and the header is
        // flushed as a separate 9-byte syscall (and, with TCP_NODELAY on, a
        // separate packet) per frame
        this.os = new NoSyncBufferedOutputStream(new ActivityTimerOutputStream(socket.getOutputStream()), ServerConfig.bufferSize() + 64);
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

    public boolean isSSL() {
        return socket instanceof SSLSocket;
    }

    @Override
    public String toString() {
        return connectionId;
    }

    public InetSocketAddress getRemoteAddress() {
        return (InetSocketAddress) socket.getRemoteSocketAddress();
    }

    public InetSocketAddress getLocalAddress() {
        return (InetSocketAddress) socket.getLocalSocketAddress();
    }

    void setContext(HttpContextImpl ctx) {
        context = ctx;
    }

    Socket getSocket() {
        return socket;
    }

    /** guards the close transition. a ReentrantLock, NOT a synchronized
     * monitor: close() flushes and closes streams (blocking IO) while holding
     * it, which would pin a virtual thread's carrier (JEP 444) */
    private final java.util.concurrent.locks.ReentrantLock closeLock = new java.util.concurrent.locks.ReentrantLock();

    void close() {
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
            try {
                /* ensure the socket is closed */
                is.close();
            } catch (IOException e) {
                logger.log(Level.TRACE, "error closing input stream", e);
            }
            try {
                // see issue #19, flush before closing, in case of pending data
                os.flush();
                os.close();
            } catch (IOException e) {
                logger.log(Level.TRACE, "error closing output stream", e);
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
        }
    }

    HttpContextImpl getHttpContext() {
        return context;
    }

    private class ActivityTimerInputStream extends FilterInputStream {

        private ActivityTimerInputStream(InputStream inputStream) {
            super(inputStream);
            lastActivityTime = ActivityTimer.now();
        }

        @Override
        public int read() throws IOException {
            try {
                return super.read();
            } finally {
                lastActivityTime = ActivityTimer.now();
            }
        }

        @Override
        public long skip(long n) throws IOException {
            try {
                return super.skip(n);
            } finally {
                lastActivityTime = ActivityTimer.now();
            }
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            try {
                return super.read(b, off, len);
            } finally {
                lastActivityTime = ActivityTimer.now();
            }
        }

    }

    private class ActivityTimerOutputStream extends FilterOutputStream {

        private ActivityTimerOutputStream(OutputStream outputStream) {
            super(outputStream);
            lastActivityTime = ActivityTimer.now();
        }
        @Override
        public void write(int b) throws IOException {
            try {
                out.write(b);
            } finally {
                lastActivityTime = ActivityTimer.now();
            }
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            try {
                out.write(b, off, len);
            } finally {
                lastActivityTime = ActivityTimer.now();
            }
        }
    }
}
