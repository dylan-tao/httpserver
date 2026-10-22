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

import java.security.PrivilegedAction;

/**
 * Parameters that users will not likely need to set
 * but are useful for debugging
 */

@SuppressWarnings("removal")
public final class ServerConfig {

    private ServerConfig() {}

    private static final int DEFAULT_IDLE_TIMER_SCHEDULE_MILLIS = 10000; // 10 sec.

    private static final long DEFAULT_IDLE_INTERVAL_IN_SECS = 30;
    private static final int DEFAULT_MAX_CONNECTIONS = -1; // no limit on maximum connections
    private static final int DEFAULT_MAX_IDLE_CONNECTIONS = 200;

    private static final long DEFAULT_MAX_REQ_TIME = -1; // default: forever
    // default timer schedule, in milli seconds, for the timer task that's
    // responsible for
    // timing out request/response if max request/response time is configured
    private static final int DEFAULT_MAX_REQ_HEADERS = 200;
    // maximum total size of a received HTTP/1.1 header block (request line +
    // all name/value bytes + 32 per pair, mirroring RFC 9113
    // SETTINGS_MAX_HEADER_LIST_SIZE accounting). the per-line and count
    // limits alone allow 200 x 128KB = ~25MB per request; this bounds it.
    // <= 0 means unlimited (same semantics as the JDK's
    // sun.net.httpserver.maxReqHeaderSize)
    private static final int DEFAULT_MAX_REQ_HEADER_SIZE = 380 * 1024;
    // maximum total request body bytes (declared Content-Length or actually
    // received for chunked/h2). opt-in: a library default must not break
    // legitimate large uploads, so the default is unlimited
    private static final long DEFAULT_MAX_REQUEST_BODY_SIZE = -1;
    private static final long DEFAULT_DRAIN_AMOUNT = 64 * 1024;

    private static final int DEFAULT_HTTP2_MAX_FRAME_SIZE = 16384;
    private static final int DEFAULT_HTTP2_INITIAL_WINDOW_SIZE = 65535;
    private static final int DEFAULT_HTTP2_CONNECTION_WINDOW_SIZE = 65535;
    // concurrent h2 streams per connection. 100 matches browser behavior
    // (Chrome's per-connection stream limit) and bounds the per-connection
    // memory (each open stream holds a 16KB output buffer + a handler task).
    // -1 disables the limit; over-limit streams get REFUSED_STREAM (retryable)
    private static final int DEFAULT_HTTP2_MAX_CONCURRENT_STREAMS = 100;
    // maximum total size of a received HTTP/2 header block (HEADERS + CONTINUATIONs)
    private static final int DEFAULT_HTTP2_MAX_HEADER_BLOCK_SIZE = 128 * 1024;
    private static final long DEFAULT_HTTP2_PING_INTERVAL_MILLIS = 1000;
    // maximum size of a single HTTP/1.1 request line or header line
    private static final int DEFAULT_MAX_REQ_LINE_SIZE = 64 * 1024;
    // maximum websocket frame / message sizes. 4MB is generous for real
    // applications (most messages are KBs) while bounding the worst case
    // per connection to ~8-16MB instead of ~128-256MB; the previous 64MB
    // default let a single slow client pin that much with one frame
    private static final int DEFAULT_MAX_WS_FRAME_SIZE = 4 * 1024 * 1024;
    private static final int DEFAULT_MAX_WS_MESSAGE_SIZE = 4 * 1024 * 1024;
    // maximum duration in seconds for the TLS handshake to complete
    private static final long DEFAULT_SSL_HANDSHAKE_TIMEOUT_SECS = 30;

    private static long idleTimerScheduleMillis;
    private static long idleIntervalMillis;
    // The maximum number of bytes to drain from an inputstream
    private static long drainAmount;
    // the maximum number of connections that the server will allow to be open
    // after which it will no longer "accept()" any new connections, till the
    // current connection count goes down due to completion of processing the
    // requests
    private static int maxConnections;
    private static int maxIdleConnections;
    // The maximum number of request headers allowable
    private static int maxReqHeaders;
    private static int maxReqHeaderSize;
    private static long maxRequestBodySize;
    // max time a request is allowed to take
    private static long maxReqTime;

    // the value of the TCP_NODELAY socket-level option
    private static boolean noDelay;
    // TCP_NODELAY for h2 connections, differentiated from h1 (measured
    // trade-off): h1 defaults ON (SSE/small-write 40ms stall protection, no
    // measured downside); h2 defaults OFF (Nagle coalesces the small
    // multiplexed frame writes - NODELAY measured -8% h2 mux throughput;
    // h2 SSE users opt in with robaho.net.httpserver.http2Nodelay=true)
    private static boolean http2NoDelay;

    // run connection handling on virtual threads (JDK 21, JEP 444). the
    // default executor and the BufferedWebSocket drain threads both honor it
    private static boolean virtualThreads;

    // hot-path stats counters are only maintained when enabled (the /__stats
    // endpoint property); gating removes ~8 volatile RMWs per request
    private static boolean statsEnabled;

    // connection-level stream buffer size (input and output), also used for
    // the h2 stream buffer pool and the multipart parser read-ahead. 16KB
    // fits a typical response into a single write; smaller values trade
    // syscalls/frames for memory, larger the reverse
    private static int bufferSize;

    // byte cap on a BufferedWebSocket's pending frame queue. the frame-count
    // bound (1024) alone allowed 1024 x frame-size retention (up to ~4GB with
    // 4MB frames) when the consumer stalls; 0 disables the byte cap
    private static int maxBufferedWebSocketBytes;

    private static boolean http2OverSSL;
    private static boolean http2OverNonSSL;
    private static int http2MaxFrameSize;
    private static int http2InitialWindowSize;
    private static int http2ConnectionWindowSize;
    private static int http2MaxConcurrentStreams;
    private static boolean http2DisableFlushDelay;
    private static int http2MaxHeaderBlockSize;
    /** keepalive ping interval for idle h2 connections; <= 0 disables the ping */
    private static long http2PingIntervalMillis;
    private static int maxReqLineSize;
    private static int maxWebSocketFrameSize;
    private static int maxWebSocketMessageSize;
    private static long sslHandshakeTimeoutMillis;

    static {
        java.security.AccessController.doPrivileged(
                new PrivilegedAction<Void>() {
                    final String pkg = "robaho.net.httpserver";
                    @Override
                    public Void run() {
                        idleIntervalMillis = Long.getLong(pkg + ".idleInterval",
                                DEFAULT_IDLE_INTERVAL_IN_SECS) * 1000;
                        if (idleIntervalMillis <= 0) {
                            idleIntervalMillis = DEFAULT_IDLE_INTERVAL_IN_SECS * 1000;
                        }

                        idleTimerScheduleMillis = Long.getLong(pkg + ".clockTick",
                                DEFAULT_IDLE_TIMER_SCHEDULE_MILLIS);
                        if (idleTimerScheduleMillis <= 0) {
                            // ignore zero or negative value and use the default schedule
                            idleTimerScheduleMillis = DEFAULT_IDLE_TIMER_SCHEDULE_MILLIS;
                        }

                        maxConnections = Integer.getInteger(
                                "jdk.httpserver.maxConnections",
                                DEFAULT_MAX_CONNECTIONS);

                        maxIdleConnections = Integer.getInteger(
                                pkg + ".maxIdleConnections",
                                DEFAULT_MAX_IDLE_CONNECTIONS);

                        drainAmount = Long.getLong(pkg + ".drainAmount",
                                DEFAULT_DRAIN_AMOUNT);

                        maxReqHeaders = Integer.getInteger(
                                pkg + ".maxReqHeaders",
                                DEFAULT_MAX_REQ_HEADERS);
                        if (maxReqHeaders <= 0) {
                            maxReqHeaders = DEFAULT_MAX_REQ_HEADERS;
                        }

                        // <= 0 means unlimited
                        maxReqHeaderSize = Math.max(
                                Integer.getInteger(pkg + ".maxReqHeaderSize",
                                        DEFAULT_MAX_REQ_HEADER_SIZE),
                                0);

                        maxRequestBodySize = Long.getLong(pkg + ".maxRequestBodySize",
                                DEFAULT_MAX_REQUEST_BODY_SIZE);

                        maxReqTime = Long.getLong(pkg + ".maxReqTime",
                                DEFAULT_MAX_REQ_TIME);

                        // TCP_NODELAY defaults ON: with small h2 frames and
                        // SSE-style writes, Nagle + delayed ACK can add ~40ms
                        // stalls. opt out with robaho.net.httpserver.nodelay=false
                        noDelay = Boolean.parseBoolean(System.getProperty(pkg + ".nodelay", "true"));
                        http2NoDelay = Boolean.parseBoolean(System.getProperty(pkg + ".http2Nodelay", "false"));

                        // default ON: the blocking-IO-heavy connection handling
                        // scales far better on virtual threads. opt out with
                        // robaho.net.httpserver.virtualThreads=false
                        virtualThreads = Boolean.parseBoolean(System.getProperty(pkg + ".virtualThreads", "true"));

                        statsEnabled = Boolean.getBoolean(pkg + ".EnableStats");

                        bufferSize = Integer.getInteger(pkg + ".bufferSize", 16384);
                        if (bufferSize < 512) {
                            // below this the syscall/frame overhead dominates
                            bufferSize = 512;
                        }

                        maxBufferedWebSocketBytes = Integer.getInteger(pkg + ".maxBufferedWebSocketBytes", 16 * 1024 * 1024);

                        http2OverSSL = Boolean.getBoolean(pkg + ".http2OverSSL");
                        http2OverNonSSL = Boolean.getBoolean(pkg + ".http2OverNonSSL");

                        http2MaxFrameSize = Integer.getInteger(pkg + ".http2MaxFrameSize", DEFAULT_HTTP2_MAX_FRAME_SIZE);
                        http2InitialWindowSize = Integer.getInteger(pkg + ".http2InitialWindowSize", DEFAULT_HTTP2_INITIAL_WINDOW_SIZE);
                        http2ConnectionWindowSize = Integer.getInteger(pkg + ".http2ConnectionWindowSize", DEFAULT_HTTP2_CONNECTION_WINDOW_SIZE);

                        http2MaxConcurrentStreams = Integer.getInteger(pkg + ".http2MaxConcurrentStreams", DEFAULT_HTTP2_MAX_CONCURRENT_STREAMS);
                        http2DisableFlushDelay = Boolean.getBoolean(pkg + ".http2DisableFlushDelay");

                        // keepalive ping for idle h2 connections. the default 1s
                        // matches the historical fixed behavior; <= 0 disables
                        // the ping entirely (dead connections are then caught
                        // by the idle cleaner, like h1)
                        http2PingIntervalMillis = Long.getLong(pkg + ".http2PingIntervalMillis",
                                DEFAULT_HTTP2_PING_INTERVAL_MILLIS);

                        http2MaxHeaderBlockSize = Integer.getInteger(pkg + ".http2MaxHeaderBlockSize", DEFAULT_HTTP2_MAX_HEADER_BLOCK_SIZE);
                        if (http2MaxHeaderBlockSize <= 0) {
                            http2MaxHeaderBlockSize = DEFAULT_HTTP2_MAX_HEADER_BLOCK_SIZE;
                        }

                        maxReqLineSize = Integer.getInteger(pkg + ".maxReqLineSize", DEFAULT_MAX_REQ_LINE_SIZE);
                        if (maxReqLineSize <= 0) {
                            maxReqLineSize = DEFAULT_MAX_REQ_LINE_SIZE;
                        }

                        maxWebSocketFrameSize = Integer.getInteger(pkg + ".maxWebSocketFrameSize", DEFAULT_MAX_WS_FRAME_SIZE);
                        if (maxWebSocketFrameSize <= 0) {
                            maxWebSocketFrameSize = DEFAULT_MAX_WS_FRAME_SIZE;
                        }

                        maxWebSocketMessageSize = Integer.getInteger(pkg + ".maxWebSocketMessageSize", DEFAULT_MAX_WS_MESSAGE_SIZE);
                        if (maxWebSocketMessageSize <= 0) {
                            maxWebSocketMessageSize = DEFAULT_MAX_WS_MESSAGE_SIZE;
                        }

                        sslHandshakeTimeoutMillis = Long.getLong(pkg + ".sslHandshakeTimeout", DEFAULT_SSL_HANDSHAKE_TIMEOUT_SECS) * 1000;
                        if (sslHandshakeTimeoutMillis <= 0) {
                            sslHandshakeTimeoutMillis = DEFAULT_SSL_HANDSHAKE_TIMEOUT_SECS * 1000;
                        }

                        return null;
                    }
                });

    }


    /**
     * {@return Returns the maximum duration, in milli seconds, a connection can be
     * idle}
     */
    static long getIdleIntervalMillis() {
        return idleIntervalMillis;
    }

    /**
     * {@return Returns the schedule, in milli seconds, for the timer task that is
     * responsible
     * for managing the idle connections}
     */
    static long getIdleTimerScheduleMillis() {
        return idleTimerScheduleMillis;
    }

    /**
     * @return Returns the maximum number of connections that can be open at any
     *         given time.
     *         This method can return a value of 0 or negative to represent that the
     *         limit hasn't
     *         been configured.
     */
    static int getMaxConnections() {
        return maxConnections;
    }

    /**
     * @return Returns the maximum number of connections that can be idle. This
     *         method
     *         can return a value of 0 or negative.
     */
    static int getMaxIdleConnections() {
        return maxIdleConnections;
    }

    static long getDrainAmount() {
        return drainAmount;
    }

    static int getMaxReqHeaders() {
        return maxReqHeaders;
    }

    /** total received HTTP/1.1 header block budget in bytes; <= 0 means
     * unlimited (mirrors sun.net.httpserver.maxReqHeaderSize) */
    static int getMaxReqHeaderSize() {
        return maxReqHeaderSize;
    }

    /** total request body budget in bytes (declared Content-Length or bytes
     * actually received); <= 0 means unlimited. opt-in: the default is
     * unlimited so legitimate large uploads are not broken */
    public static long getMaxRequestBodySize() {
        return maxRequestBodySize;
    }

    /**
     * @return Returns the maximum amount of time the server will wait for the
     *         request to be read
     *         completely. This method can return a value of 0 or negative to imply
     *         no maximum limit has
     *         been configured.
     */
    static long getMaxReqTime() {
        return maxReqTime;
    }

    static boolean noDelay() {
        return noDelay;
    }
    static boolean http2NoDelay() {
        return http2NoDelay;
    }

    public static boolean http2OverSSL() {
        return http2OverSSL;
    }
    public static boolean http2OverNonSSL() {
        return http2OverNonSSL;
    }
    /** true (default) when connection handling runs on virtual threads */
    public static boolean virtualThreads() {
        return virtualThreads;
    }
    /** hot-path stats counters are maintained only when this is true */
    public static boolean statsEnabled() {
        return statsEnabled;
    }
    /** connection-level stream buffer size (configurable, default 16KB) */
    public static int bufferSize() {
        return bufferSize;
    }
    /** byte cap on a BufferedWebSocket's pending queue (default 16MB, 0 = unlimited) */
    public static int maxBufferedWebSocketBytes() {
        return maxBufferedWebSocketBytes;
    }

    /** one-shot guard for the startup feature log */
    private static final java.util.concurrent.atomic.AtomicBoolean JVM_FEATURES_LOGGED = new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * log the state of the JVM-level performance features once at startup.
     * These are LAUNCH flags the deployer controls (they cannot be switched at
     * runtime); when they are on the JVM applies them automatically, and when
     * they are off the code-level optimizations (buffer pooling, single
     * allocation encoding, gated stats) remain in effect as the fallback - no
     * configuration is needed for either mode.
     */
    static void logJvmFeatures(System.Logger logger) {
        if (!JVM_FEATURES_LOGGED.compareAndSet(false, true)) {
            return;
        }
        boolean zgc = false;
        try {
            for (var gc : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
                if (gc.getName().contains("ZGC")) {
                    zgc = true;
                    break;
                }
            }
        } catch (Throwable ignored) {
            // management beans unavailable: report unknown
        }
        String compactHeaders = "unknown";
        try {
            for (String arg : java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments()) {
                if (arg.contains("UseCompactObjectHeaders")) {
                    compactHeaders = arg.startsWith("-XX:+") ? "on" : "off";
                }
            }
        } catch (Throwable ignored) {
        }
        logger.log(System.Logger.Level.INFO, "JVM features: virtual threads=" + (virtualThreads ? "on" : "off")
            + ", ZGC=" + zgc + ", compact object headers=" + compactHeaders
            + (compactHeaders.equals("unknown") ? " (JDK < 24 or flag not set)" : ""));
        if (!zgc) {
            logger.log(System.Logger.Level.DEBUG,
                "tip: -XX:+UseZGC (generational since JDK 23) suits this allocation-heavy workload");
        }
        if (!"on".equals(compactHeaders)) {
            logger.log(System.Logger.Level.DEBUG,
                "tip: -XX:+UseCompactObjectHeaders (JDK 24+) shrinks per-object overhead by 20-30%");
        }
    }
    public static int http2MaxFrameSize() {
        return http2MaxFrameSize;
    }
    public static int http2InitialWindowSize() {
        return http2InitialWindowSize;
    }
    public static int http2ConnectionWindowSize() {
        return http2ConnectionWindowSize;
    }
    /**
     * @return the maximum number of concurrent streams per connection, or -1 for no limit
     */
    public static int http2MaxConcurrentStreams() {
        return http2MaxConcurrentStreams;
    }
    /**
     * @return true if delaying flush is enabled. disabling the flush delay can improve
     * latency at the expense of throughput
     */
    public static boolean http2DisableFlushDelay() {
        return http2DisableFlushDelay;
    }

    /** @return the maximum total size in bytes of a received HTTP/2 header block */
    public static int http2MaxHeaderBlockSize() {
        return http2MaxHeaderBlockSize;
    }

    /** @return the keepalive ping interval for idle h2 connections in
     * millis, or a value &lt;= 0 when the ping is disabled */
    public static long http2PingIntervalMillis() {
        return http2PingIntervalMillis;
    }

    /** @return the maximum size in bytes of a single HTTP/1.1 request/header line */
    static int getMaxReqLineSize() {
        return maxReqLineSize;
    }

    /** @return the maximum size in bytes of a single websocket frame */
    public static int getMaxWebSocketFrameSize() {
        return maxWebSocketFrameSize;
    }

    /** @return the maximum size in bytes of an aggregated websocket message */
    public static int getMaxWebSocketMessageSize() {
        return maxWebSocketMessageSize;
    }

    /** @return the maximum duration in milliseconds for the TLS handshake to complete */
    static long getSslHandshakeTimeoutMillis() {
        return sslHandshakeTimeoutMillis;
    }

}
