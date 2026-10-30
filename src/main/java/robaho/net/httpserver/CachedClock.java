package robaho.net.httpserver;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * The RFC 9110 Date header clock, refreshed on demand from the response
 * path. There is no background timer: the old design ran a daemon timer
 * every 750ms whether or not any response was being written (80 wakeups
 * plus 80 formats per minute on a fully idle server, and an always-live
 * timer thread even for a JVM that never served a request). A response
 * now takes a {@link #snapshot()} and only reformats when the cached
 * second has gone stale, so the format cost is at most once per second
 * under sustained load and exactly zero while idle.
 * Wall-clock timestamps for idle/timeout bookkeeping (connection
 * lastActivityTime, drainingAt, the connection-cleaner's now) must NOT
 * use this class: they need a clock that advances on its own, and a
 * demand-driven cache freezes while nothing is written. Those call sites
 * use System.currentTimeMillis() directly - reading it is nanoseconds
 * and was never the cost the Date caching exists to avoid (the
 * DateTimeFormatter format is).
 */
class CachedClock {

  /** RFC 9110 IMF-fixdate, e.g. "Sun, 06 Nov 1994 08:49:37 GMT" */
  private static final DateTimeFormatter HTTP_DATE = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);

  /**
   * immutable snapshot of (now, date string, encoded bytes): ONE
   * volatile publish per refresh, so a reader can never pair the string
   * of one generation with the bytes of another (the wire Date header and
   * getResponseHeaders().getFirst("Date") could differ by a refresh)
   */
  public record ClockValue(long now, String dateAndTime, byte[] dateAndTimeBytes) {}

  /** the current snapshot */
  private static volatile ClockValue current = initialClockValue();

  /**
   * single-flight for the stale-second refresh: at a second boundary
   * every concurrent response sees "stale" at once; one thread formats
   * and publishes, the rest take the refreshed snapshot under the lock
   */
  private static final Object REFRESH_LOCK = new Object();

  /**
   * format once: an initializer that called formatDate() twice could
   * pair two different seconds into the very first snapshot
   */
  private static ClockValue initialClockValue() {
    String s = formatDate();
    return new ClockValue(System.currentTimeMillis(), s, s.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
  }

  private static String formatDate() {
    return Instant.now().atOffset(ZoneOffset.UTC).format(HTTP_DATE);
  }

  /**
   * the current clock snapshot, refreshed first if the cached Date
   * second no longer matches the wall clock. the fast path is one
   * volatile read plus one System.currentTimeMillis and reuses the same
   * snapshot for every response within the same second (the IMF-fixdate
   * format has second resolution, so same-second snapshots are
   * identical); the slow path formats at most once per second.
   * RFC 9110 allows up to 60s clock skew, so a response that reads a
   * snapshot milliseconds before a boundary is far within tolerance.
   * readers that need BOTH the string and the bytes (the response Date
   * fast path) must take ONE snapshot - two separate accessor calls can
   * straddle a refresh and re-introduce the cross-generation pairing the
   * snapshot exists to prevent
   */
  public static ClockValue snapshot() {
    ClockValue c = current;
    long now = System.currentTimeMillis();
    if (c.now / 1000 == now / 1000) {
      return c;
    }
    synchronized (REFRESH_LOCK) {
      c = current;
      now = System.currentTimeMillis();
      if (c.now / 1000 == now / 1000) {
        // another thread refreshed while we waited for the lock
        return c;
      }
      String fresh = formatDate();
      c = new ClockValue(now, fresh, fresh.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
      current = c;
      return c;
    }
  }

  /**
   * the formatted current date and time suitable for the Date http
   * header; refreshed on demand via {@link #snapshot()}
   */
  public static String dateAndTime() {
    return snapshot().dateAndTime();
  }

}
