package robaho.net.httpserver.http2;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Live HTTP/2 counters for one server instance (exposed via the
 * {@code /__stats} endpoint when {@code robaho.net.httpserver.EnableStats}
 * is set). all counters are lock-free and only maintained when
 * {@link #ENABLED} is true, so the hot paths pay nothing by default.
 */
public class Http2Stats {
    /** hot-path counters are gated behind this compile-time constant: they
     * cost ~8 volatile RMWs per request otherwise, but are only read via
     * /__stats when robaho.net.httpserver.EnableStats is set */
    public static final boolean ENABLED = robaho.net.httpserver.ServerConfig.statsEnabled();

    public final AtomicLong activeStreams = new AtomicLong();
    public final AtomicLong bytesSent = new AtomicLong();
    public final AtomicLong framesSent = new AtomicLong();
    public final AtomicLong flushes = new AtomicLong();
    public final AtomicLong sslConnections = new AtomicLong();
    public final AtomicLong nonsslConnections = new AtomicLong();
    public final AtomicLong totalStreams = new AtomicLong();
    public final AtomicLong pauses = new AtomicLong();
    public final AtomicLong pingsSent = new AtomicLong();

    private volatile long lastStatsTime = System.currentTimeMillis();

    private final java.util.concurrent.locks.ReentrantLock statsLock = new java.util.concurrent.locks.ReentrantLock();

    /** a one-line snapshot of every counter, for logging or the stats endpoint */
    public String stats() {
        // the timestamp write + nine getAndSet(0) resets must not interleave
        // across concurrent /__stats scrapes (bogus per-sec deltas) - the
        // same race ServerStats guards against with its own lock
        statsLock.lock();
        try {
            return statsLocked();
        } finally {
            statsLock.unlock();
        }
    }

    private String statsLocked() {
        long now = System.currentTimeMillis();
        // clamp like ServerStats: two reads in the same millisecond must not
        // divide by ~0 and print Infinity
        double secs = Math.max((now-lastStatsTime)/1000.0, 0.001);
        lastStatsTime = now;

        long bytes = bytesSent.getAndSet(0);
        long frames = framesSent.getAndSet(0);

        return
                "Http2 SSL Connections Since: "+sslConnections.getAndSet(0)+"\n" +
                "Http2 Non-SSL Connections Since: "+nonsslConnections.getAndSet(0)+"\n" +
                "Http2 Streams Since: "+totalStreams.getAndSet(0)+"\n" +
                "Http2 Active Streams: "+activeStreams.get()+"\n" +
                "Http2 Frames Sent/sec: "+(long)(frames/(secs))+"\n"+
                "Http2 Bytes Sent/sec: "+(long)(bytes/(secs))+"\n"+
                "Http2 Avg Frame Size: "+ (frames==0 ? 0 : bytes/frames) +"\n"+
                "Http2 Flushes/sec: "+(long)(flushes.getAndSet(0)/(secs))+"\n"+
                "Http2 Pauses/sec: "+(long)(pauses.getAndSet(0)/(secs))+"\n"+
                "Http2 Pings Sent Since: "+pingsSent.getAndSet(0)+"\n";

    }
}
