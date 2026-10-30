package robaho.net.httpserver.websockets;

import com.sun.net.httpserver.HttpExchange;
import robaho.net.httpserver.AttributeKeys;
import robaho.net.httpserver.ServerConfig;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * a BufferedWebSocket allows the producer to buffer in user space (by limited
 * kernel buffer size) and provides properties to determine if the consumer is
 * reading the messages via pending()
 */
public abstract class BufferedWebSocket extends WebSocket {

  private static ExecutorService executor;

  /**
   * guards lazy executor creation; a ReentrantLock rather than a static
   * synchronized method (monitor-based locking is banned for virtual thread
   * safety, JEP 444)
   */
  private static final ReentrantLock EXECUTOR_LOCK = new ReentrantLock();

  /** bounds the queue so a fast producer cannot exhaust memory */
  private static final int MAX_PENDING_FRAMES = 1024;

  /** wakes the drain task so it can exit when the connection closes */
  private static final WebSocketFrame POISON_PILL = new WebSocketFrame(OpCode.Close, true, new byte[0]);

  /**
   * how long close() waits for the drain task to flush queued frames
   * (including a queued CloseFrame) before cancelling it: long enough for
   * a full queue under normal load, short enough that close() stays
   * responsive when the peer stops reading
   */
  // 6s: flushing a healthy consumer's backlog is milliseconds in
  // isolation, but under load (GC pressure, CPU contention) even 200KB
  // through a backpressured socket exceeded 2s - measured as a ~44%
  // flake rate in full-suite runs. 2s failed under load; 5s was
  // verified green 5x but leaves thin headroom on slower hosts; 6s adds
  // margin while staying a bound: a STALLED consumer (the budget's
  // purpose) blocks indefinitely on the socket write and is detected in
  // 6s exactly as well as in 2s
  private static final long DRAIN_TIMEOUT_SECONDS = 6;

  private final LinkedBlockingQueue<WebSocketFrame> queue = new LinkedBlockingQueue<>(MAX_PENDING_FRAMES);

  private final AtomicInteger pending = new AtomicInteger();

  /**
   * total bytes of queued (not yet written) frames: the frame-count bound
   * alone allowed 1024 x frame-size retention when the consumer stalls
   */
  private final java.util.concurrent.atomic.AtomicLong queuedBytes = new java.util.concurrent.atomic.AtomicLong();

  /**
   * the thread running the drain task, so doClose can detect being called
   * FROM the drain task (waiting on our own future would always time out
   * and self-interrupt via cancel(true))
   */
  private volatile Thread drainThread;

  private static ExecutorService getExecutor() {
    EXECUTOR_LOCK.lock();
    try {
      if (executor == null) {
        executor = ServerConfig.virtualThreads()
            ? Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("buffered-ws-", 0).factory())
            : Executors.newCachedThreadPool();
      }
      return executor;
    } finally {
      EXECUTOR_LOCK.unlock();
    }
  }

  private final Future<?> future;

  /**
   * a BufferedWebSocket that uses the kernel default buffer sizes
   */
  public BufferedWebSocket(HttpExchange exchange) {
    this(exchange, 0);
  }

  /**
   * a BufferedWebSocket that allows control over the kernel buffer sizes
   * @param kernelBufferSizeBytes the kernel buffer size in bytes or 0 to use
   *          the default
   */
  public BufferedWebSocket(HttpExchange exchange, int kernelBufferSizeBytes) {
    super(exchange);
    if (kernelBufferSizeBytes != 0) {
      exchange.setAttribute(AttributeKeys.SOCKET_WRITE_BUFFER, kernelBufferSizeBytes);
    }
    future = getExecutor().submit(() -> {
      drainThread = Thread.currentThread();
      sendFrames();
    });
  }

  @Override
  void prepareForcedClose() {
    // stop the drain task: without the pill it stays parked in
    // queue.take() after the forced close (leaked virtual thread). the
    // future wait/cancel of the graceful path is skipped here on purpose
    // - the peer is unresponsive, waiting for a stalled drain write
    // would only delay the teardown; the drain exits on the pill as soon
    // as its current write fails
    enqueuePoisonPill();
  }

  /**
   * GUARANTEE the exit pill lands in the queue WITHOUT dropping a live
   * frame:
   * 1. contains() fast path: an earlier close path's pill serves as-is
   * 2. offer() first: the pill is APPENDED behind every queued frame. a
   * queued CloseFrame is the peer's close handshake - the old
   * poll-then-put unconditionally dropped the head frame to make room,
   * and when the drain was between the backlog and the CloseFrame that
   * dropped frame WAS the CloseFrame: the peer got EOF with no close
   * completion and hung (observed as the WebSocketDoubleCloseTest
   * full-suite flake - the JDK client's EOF handling fires onClose(1006)
   * only sometimes; with the CloseFrame delivered it fires onClose(1000)
   * deterministically)
   * 3. the full-queue fallback (offer failed = 1024 queued frames): poll
   * one doomed frame and put() - cannot lose the freed slot to a racing
   * producer. the remaining producers are close-racing sends whose
   * frames are doomed anyway; the read-loop's own CloseFrame enqueue
   * blocks in put() while the queue is full, so it is never the dropped
   * head
   * 4. every branch returns - single iteration in practice; the loop
   * shape only covers the contains()-excluded pill-poll case
   */
  private void enqueuePoisonPill() {
    // fast path: a pill is already queued by an earlier close-path call
    if (queue.contains(POISON_PILL)) {
      return;
    }
    // append the pill BEHIND every queued frame: offer() never touches
    // the live frames, and the drain task (a plain take() loop) is
    // guaranteed to write each of them before it reaches the pill
    if (queue.offer(POISON_PILL)) {
      return;
    }
    while (true) {
      WebSocketFrame dropped = queue.poll();
      if (dropped == null) {
        // momentarily empty: the pill slots straight in
        queue.offer(POISON_PILL);
        return;
      }
      if (dropped == POISON_PILL) {
        // unreachable given the contains() fast path; keep the pill
        queue.offer(POISON_PILL);
        return;
      }
      pending.decrementAndGet();
      var payload = dropped.getBinaryPayload();
      if (payload != null) {
        queuedBytes.addAndGet(-payload.length);
      }
      // put (not offer): cannot lose to a racing producer. the only
      // producers left are close-racing sends whose frames are doomed
      try {
        queue.put(POISON_PILL);
        return;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        queue.offer(POISON_PILL);
        return;
      }
    }
  }

  @Override
  void doClose(CloseCode code, String reason, boolean initiatedByRemote) {
    // do NOT cancel the drain task outright: a queued CloseFrame may still
    // need to be written. enqueue a poison pill behind it and give the
    // drain task a moment to flush before the streams are closed.
    // no lock needed here: the once-only close transition is enforced by
    // the locked super.doClose; this body is safe to run twice.
    enqueuePoisonPill();
    if (Thread.currentThread() != drainThread) {
      // called from the read/user thread: wait for the drain task.
      // from the drain task itself this would deadlock on our own
      // future for the full drain timeout (DRAIN_TIMEOUT_SECONDS)
      // and then self-interrupt
      try {
        future.get(DRAIN_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
      } catch (Exception ignored) {
        future.cancel(true);
      }
    }
    super.doClose(code, reason, initiatedByRemote);
  }

  private void sendFrames() {
    try {
      while (true) {
        var frame = queue.take();
        if (frame == POISON_PILL) {
          return;
        }
        super.sendFrame(frame);
        pending.decrementAndGet();
        var payload = frame.getBinaryPayload();
        if (payload != null) {
          queuedBytes.addAndGet(-payload.length);
        }
      }
    } catch (IOException ex) {
      onException(ex);
      doClose(CloseCode.AbnormalClosure, ex.toString(), false);
    } catch (InterruptedException ex) {
      // the drain task is cancelled via future.cancel(true) on the
      // close timeout path - interruption is the expected exit signal
    }
  }

  /**
   * enqueue a frame for asynchronous delivery by the drain task instead
   * of writing it synchronously: producers never block on a slow
   * consumer. the queue is bounded by both frame count (1024, put()
   * blocks for backpressure) and total bytes (maxBufferedWebSocketBytes,
   * exceeding it throws MessageTooBig). close() flushes queued frames
   * before the streams are closed
   */
  @Override
  public void sendFrame(WebSocketFrame frame) throws IOException {
    if (state == WebSocketLifecycle.CLOSED) {
      // after the drain task exited there is no consumer: frames would
      // be silently lost, and once the queue fills, put() blocks the
      // producer virtual thread forever (nobody holds the future to
      // interrupt it). fail fast like the unbuffered sendFrame does
      throw new IOException("websocket is closed");
    }
    var payload = frame.getBinaryPayload();
    int frameBytes = payload == null ? 0 : payload.length;
    int cap = robaho.net.httpserver.ServerConfig.maxBufferedWebSocketBytes();
    if (cap != 0) {
      // bound the retained bytes, not just the frame count: a stalled
      // consumer with 1024 queued frames could otherwise pin
      // 1024 x frame-size of memory. CAS loop, not add-then-rollback:
      // a concurrent in-cap producer observing an over-cap producer's
      // transient inflated total was wrongly rejected
      while (true) {
        long current = queuedBytes.get();
        if (current + frameBytes > cap) {
          throw new WebSocketException(CloseCode.MessageTooBig, "buffered frames exceed the " + cap + " byte queue cap");
        }
        if (queuedBytes.compareAndSet(current, current + frameBytes)) {
          break;
        }
      }
    }
    pending.incrementAndGet();
    try {
      // put() blocks when the queue is full, providing backpressure
      // against a producer that outpaces the consumer
      queue.put(frame);
    } catch (InterruptedException ex) {
      queuedBytes.addAndGet(-frameBytes);
      pending.decrementAndGet();
      Thread.currentThread().interrupt();
      throw new IOException("interrupted while queueing frame", ex);
    }
  }

  /**
   * the number of frames queued but not yet written to the connection:
   * a stalled consumer shows up as a persistently growing value
   */
  public int pending() {
    return pending.get();
  }

}
