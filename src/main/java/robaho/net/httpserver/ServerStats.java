package robaho.net.httpserver;

import java.util.concurrent.atomic.AtomicLong;

class ServerStats {
    /** hot-path counters are gated behind this compile-time constant (see
     * Http2Stats.ENABLED) */
    static final boolean ENABLED = ServerConfig.statsEnabled();

    final AtomicLong connectionCount = new AtomicLong();
    final AtomicLong requestCount = new AtomicLong();
    final AtomicLong handleExceptionCount = new AtomicLong();
    final AtomicLong socketExceptionCount = new AtomicLong();
    final AtomicLong idleCloseCount = new AtomicLong();
    final AtomicLong replyErrorCount = new AtomicLong();
    final AtomicLong maxConnectionsExceededCount = new AtomicLong();

    private long lastStatsTime = System.currentTimeMillis();

    /** guards the time snapshot and counter resets: concurrent calls must not
     * interleave them (which produced bogus requests/sec values). kept as a
     * ReentrantLock for no urgent reason: monitors stopped pinning virtual
     * threads in JDK 24 (JEP 491), so either primitive is safe here */
    private final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();

    public String stats() {
        lock.lock();
        try {
            long now = System.currentTimeMillis();
            // guard against division by ~0 when two calls land in the same millisecond
            double secs = Math.max((now - lastStatsTime) / 1000.0, 0.001);
            lastStatsTime = now;

            long _requests = requestCount.getAndSet(0);

            return
                    "Connections Since: "+connectionCount.getAndSet(0)+"\n" +
                    "Requests Since: "+_requests+"\n" +
                    "Requests/sec: "+(long)(_requests/secs)+"\n"+
                    "Total Handler Exceptions: "+handleExceptionCount.get()+"\n"+
                    "Total Socket Exceptions: "+socketExceptionCount.get()+"\n"+
                    "Total Max Connections Exceeded: "+maxConnectionsExceededCount.get()+"\n"+
                    "Total Idle Closes: "+idleCloseCount.get()+"\n"+
                    "Total Reply Errors: "+replyErrorCount.get()+"\n";
        } finally {
            lock.unlock();
        }
    }
}