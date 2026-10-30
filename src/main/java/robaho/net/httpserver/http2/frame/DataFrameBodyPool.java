package robaho.net.httpserver.http2.frame;

import robaho.net.httpserver.ServerConfig;

import java.util.concurrent.ArrayBlockingQueue;

/**
 * Bounded pool for full-size DATA frame bodies.
 * the frame parser allocates one {@code byte[frameLength]} per received frame;
 * on upload-heavy connections those are almost always exactly
 * {@link ServerConfig#http2MaxFrameSize()} bytes (senders emit full frames),
 * which is pure allocation churn: the body is copied out in
 * {@code Http2Stream.DataIn.read} and dropped. this pool reuses those arrays.
 * safety argument: a body array has exactly one owner at any time - the
 * reader thread (parser) until it is enqueued on the target stream, the
 * handler thread (DataIn) afterwards. the ONLY return point is the
 * {@code queue.poll()} after the last byte is consumed, where no reference
 * remains. every other path (PADDED bodies - which are copied to a shorter
 * array and never match the bucket - abandoned queues on close/reset, frames
 * for unknown streams) simply drops the array for the GC to collect: a pooled
 * array that is never returned is garbage, never corruption.
 * the pool is global and bounded in bytes, so idle connections retain
 * nothing and the worst-case retention is {@link #MAX_RETAINED_BYTES}.
 */
public final class DataFrameBodyPool {

  /**
   * hard cap on pooled bytes: with the default 16KB frame size this is
   * 256 retained arrays
   */
  private static final int MAX_RETAINED_BYTES = 4 * 1024 * 1024;

  /**
   * only full-size bodies are pooled: partial frames (the tail of a body)
   * would leave the pool with mostly-unusable short arrays
   */
  private static final int BUCKET_SIZE = ServerConfig.http2MaxFrameSize();

  private static final ArrayBlockingQueue<byte[]> POOL =
      new ArrayBlockingQueue<>(Math.max(1, MAX_RETAINED_BYTES / BUCKET_SIZE));

  private DataFrameBodyPool() {}

  /**
   * @return a buffer for an incoming frame body: a pooled array when
   *         {@code length} is the bucket size, a fresh allocation otherwise
   */
  public static byte[] borrow(int length) {
    if (length != BUCKET_SIZE) {
      return new byte[length];
    }
    byte[] pooled = POOL.poll();
    return pooled != null ? pooled : new byte[length];
  }

  /**
   * return a fully-consumed body to the pool; arrays of any other length
   * (and a full pool) are silently discarded for GC
   */
  public static void release(byte[] body) {
    if (body.length == BUCKET_SIZE) {
      POOL.offer(body);
    }
  }

}
