package robaho.net.httpserver.http2;

import java.util.concurrent.atomic.AtomicLong;

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

    public String stats() {
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
