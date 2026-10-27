package robaho.net.httpserver;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.TimerTask;

class CachedClock {
    /** RFC 9110 IMF-fixdate, e.g. "Sun, 06 Nov 1994 08:49:37 GMT" */
    private static final DateTimeFormatter HTTP_DATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);

    /** immutable snapshot of (now, date string, encoded bytes): ONE
     * volatile publish per tick, so a reader can never pair the string of
     * one generation with the bytes of another (the wire Date header and
     * getResponseHeaders().getFirst("Date") could differ by a tick) */
    public record ClockValue(long now, String dateAndTime, byte[] dateAndTimeBytes) {}

    /** the current snapshot: readers that need BOTH the string and the bytes
     * (the response Date fast path) must take ONE snapshot - two separate
     * accessor calls can straddle a tick and re-introduce the cross-
     * generation pairing the snapshot exists to prevent */
    public static ClockValue snapshot() {
        return current;
    }

    private static volatile ClockValue current = initialClockValue();

    /** format once: the old initializer called formatDate() twice, so the
     * very first snapshot could itself pair two different seconds */
    private static ClockValue initialClockValue() {
        String s = formatDate();
        return new ClockValue(System.currentTimeMillis(), s,
            s.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
    }

    private static final java.util.Timer TIMER = new java.util.Timer("httpserver-activity-timer", true);

    static {
        // the cached clock is shared by ALL server instances; it is driven by
        // its own daemon timer so it keeps ticking independent of any single
        // server's lifecycle (previously it was scheduled per server instance,
        // and the clock froze for everyone once the last instance stopped)
        // 750ms: the refresh must be comfortably under 1s so the cached
        // Date header is never more than ~1.75s stale (RFC 9110 allows
        // clocks up to 60s skew, so this is far within tolerance)
        TIMER.schedule(createTask(), 750, 750);
    }

    private static String formatDate() {
        return Instant.now().atOffset(ZoneOffset.UTC).format(HTTP_DATE);
    }

    public static long now() {
        return current.now();
    }
    /**
     * return the formatted current date and time suitable for use with the Date http header. this
     * is OK to cache since the resolution is only seconds, and we will update more often than that
     */
    public static String dateAndTime() {
        return current.dateAndTime();
    }

    /** the cached Date header value, pre-encoded as ISO-8859-1 */
    static byte[] dateAndTimeBytes() {
        return current.dateAndTimeBytes();
    }

    static void updateNow() {
        long t = System.currentTimeMillis();
        String fresh = formatDate();
        current = new ClockValue(t, fresh,
            fresh.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
    }

    static TimerTask createTask() {
        return new TimerTask() {
            @Override
            public void run() {
                updateNow();
            }
        };
    }
}
