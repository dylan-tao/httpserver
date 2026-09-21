package robaho.net.httpserver.websockets;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;

import robaho.net.httpserver.Attributes;

/**
 * a BufferedWebSocket allows the producer to buffer in user space (by limited
 * kernel buffer size) and provides properties to determine if the consumer is
 * reading the messages via pending()
 */
public abstract class BufferedWebSocket extends WebSocket {

    private static ExecutorService executor;

    /** bounds the queue so a fast producer cannot exhaust memory */
    private static final int MAX_PENDING_FRAMES = 1024;

    /** wakes the drain task so it can exit when the connection closes */
    private static final WebSocketFrame POISON_PILL = new WebSocketFrame(OpCode.Close, true, new byte[0]);

    private final LinkedBlockingQueue<WebSocketFrame> queue = new LinkedBlockingQueue<>(MAX_PENDING_FRAMES);
    private final AtomicInteger pending = new AtomicInteger();

    private synchronized static ExecutorService getExecutor() {
        if (executor == null) {
            executor = Executors.newCachedThreadPool();
        }
        return executor;
    }

    private final Future<?> future;

    public static synchronized void setExecutor(ExecutorService executor) {
        if (executor != null) {
            throw new IllegalStateException("executor is already set");
        }
        BufferedWebSocket.executor = executor;
    }

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
            exchange.setAttribute(Attributes.SOCKET_WRITE_BUFFER, kernelBufferSizeBytes);
        }
        future = getExecutor().submit(() -> sendFrames());
    }

    @Override
    void doClose(CloseCode code, String reason, boolean initiatedByRemote) {
        // do NOT cancel the drain task outright: a queued CloseFrame may still
        // need to be written. enqueue a poison pill behind it and give the
        // drain task a moment to flush before the streams are closed.
        queue.offer(POISON_PILL);
        try {
            future.get(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception ignored) {
            future.cancel(true);
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
            }
        } catch (IOException ex) {
            onException(ex);
            doClose(CloseCode.AbnormalClosure, ex.toString(), false);
        } catch (InterruptedException ex) {
        }
    }

    @Override
    public void sendFrame(WebSocketFrame frame) throws IOException {
        pending.incrementAndGet();
        try {
            // put() blocks when the queue is full, providing backpressure
            // against a producer that outpaces the consumer
            queue.put(frame);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while queueing frame", ex);
        }
    }

    public int pending() {
        return pending.get();
    }
}
