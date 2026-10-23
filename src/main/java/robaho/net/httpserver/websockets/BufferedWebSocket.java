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

    /** guards lazy executor creation; a ReentrantLock rather than a static
     * synchronized method (monitor-based locking is banned for virtual thread
     * safety, JEP 444) */
    private static final ReentrantLock EXECUTOR_LOCK = new ReentrantLock();

    /** bounds the queue so a fast producer cannot exhaust memory */
    private static final int MAX_PENDING_FRAMES = 1024;

    /** wakes the drain task so it can exit when the connection closes */
    private static final WebSocketFrame POISON_PILL = new WebSocketFrame(OpCode.Close, true, new byte[0]);

    /** how long close() waits for the drain task to flush queued frames
     * (including a queued CloseFrame) before cancelling it: long enough for
     * a full queue under normal load, short enough that close() stays
     * responsive when the peer stops reading */
    private static final long DRAIN_TIMEOUT_SECONDS = 2;

    private final LinkedBlockingQueue<WebSocketFrame> queue = new LinkedBlockingQueue<>(MAX_PENDING_FRAMES);
    private final AtomicInteger pending = new AtomicInteger();
    /** total bytes of queued (not yet written) frames: the frame-count bound
     * alone allowed 1024 x frame-size retention when the consumer stalls */
    private final java.util.concurrent.atomic.AtomicLong queuedBytes = new java.util.concurrent.atomic.AtomicLong();
    /** the thread running the drain task, so doClose can detect being called
     * FROM the drain task (waiting on our own future would always time out
     * and self-interrupt via cancel(true)) */
    private volatile Thread drainThread;

    private static ExecutorService getExecutor() {
        EXECUTOR_LOCK.lock();
        try {
            if (executor == null) {
                executor = ServerConfig.virtualThreads()
                    ? Executors.newThreadPerTaskExecutor(
                        Thread.ofVirtual().name("buffered-ws-", 0).factory())
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
     *
     * @param kernelBufferSizeBytes the kernel buffer size in bytes or 0 to use
     * the default
     *
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
    void doClose(CloseCode code, String reason, boolean initiatedByRemote) {
        // do NOT cancel the drain task outright: a queued CloseFrame may still
        // need to be written. enqueue a poison pill behind it and give the
        // drain task a moment to flush before the streams are closed.
        // no lock needed here: the once-only close transition is enforced by
        // the locked super.doClose; this body is safe to run twice.
        // if the queue is full the exit signal would be dropped and the
        // drain task only exits via the future timeout + interrupt, losing
        // the queued frames: make room by dropping the OLDEST frame
        if (!queue.offer(POISON_PILL)) {
            queue.poll();
            queue.offer(POISON_PILL);
        }
        if (Thread.currentThread() != drainThread) {
            // called from the read/user thread: wait for the drain task.
            // from the drain task itself this would deadlock on our own
            // future for the full 2s timeout and then self-interrupt
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

    /** enqueue a frame for asynchronous delivery by the drain task instead
     *  of writing it synchronously: producers never block on a slow
     *  consumer. the queue is bounded by both frame count (1024, put()
     *  blocks for backpressure) and total bytes (maxBufferedWebSocketBytes,
     *  exceeding it throws MessageTooBig). close() flushes queued frames
     *  before the streams are closed */
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
                    throw new WebSocketException(CloseCode.MessageTooBig,
                        "buffered frames exceed the " + cap + " byte queue cap");
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

    /** the number of frames queued but not yet written to the connection:
     *  a stalled consumer shows up as a persistently growing value */
    public int pending() {
        return pending.get();
    }
}
