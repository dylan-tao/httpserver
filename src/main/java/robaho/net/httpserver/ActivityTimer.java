package robaho.net.httpserver;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.TimerTask;

class ActivityTimer {
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
        TIMER.schedule(createTask(), 750, 750);
    }

    private static String formatDate() {
        var now = Instant.now();
        var datetime = now.atOffset(ZoneOffset.UTC);
        StringBuilder sb = new StringBuilder(32);
        sb.append(datetime.getDayOfWeek().getDisplayName(TextStyle.SHORT,Locale.US));
        sb.append(", ");
        int day = datetime.getDayOfMonth();
        if(day<10) sb.append("0");
        sb.append(day);
        sb.append(" ");
        sb.append(datetime.getMonth().getDisplayName(TextStyle.SHORT,Locale.US));
        sb.append(" ");
        sb.append(datetime.getYear());
        sb.append(" ");
        int hour = datetime.getHour();
        if(hour<10) sb.append("0");
        sb.append(hour);
        sb.append(":");
        int minute = datetime.getMinute();
        if(minute<10) sb.append("0");
        sb.append(minute);
        sb.append(":");
        int second = datetime.getSecond();
        if(second<10) sb.append("0");
        sb.append(second);
        sb.append(" GMT");
        return sb.toString();
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