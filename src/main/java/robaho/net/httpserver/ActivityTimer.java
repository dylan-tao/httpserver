package robaho.net.httpserver;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.TimerTask;

class ActivityTimer {
    /** RFC 9110 IMF-fixdate, e.g. "Sun, 06 Nov 1994 08:49:37 GMT" */
    private static final DateTimeFormatter HTTP_DATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);

    private static volatile long now = System.currentTimeMillis();
    private static volatile String dateAndTime = formatDate();
    /** the Date header value pre-encoded: written on every response, encoding
     * the string each time is pure waste */
    private static volatile byte[] dateAndTimeBytes = dateAndTime.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);

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
        return now;
    }
    /**
     * return the formatted current date and time suitable for use with the Date http header. this
     * is OK to cache since the resolution is only seconds, and we will update more often than that
     */
    public static String dateAndTime() {
        return dateAndTime;
    }

    /** the cached Date header value, pre-encoded as ISO-8859-1 */
    static byte[] dateAndTimeBytes() {
        return dateAndTimeBytes;
    }

    static void updateNow() {
        now = System.currentTimeMillis();
        dateAndTime = formatDate();
        dateAndTimeBytes = dateAndTime.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
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
