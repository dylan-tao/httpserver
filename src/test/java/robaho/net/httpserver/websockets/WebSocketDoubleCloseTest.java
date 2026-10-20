package robaho.net.httpserver.websockets;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * regression for the double-close lock deadlock: close() on an already-CLOSING
 * socket used to call doClose while holding the lock, and
 * BufferedWebSocket.doClose waits for the drain task - whose sendFrames
 * flushes under the same lock. the wait burned the full future.get timeout
 * and then cancelled the drain, losing every queued frame.
 */
public class WebSocketDoubleCloseTest {
    private static final int port = 9107;
    private static final String path = "/ws";
    private static final int FRAMES = 400;
    private static final String PAYLOAD = "x".repeat(8 * 1024);

    private static CountDownLatch queued;
    private static CountDownLatch go;
    private static CountDownLatch serverClosed;
    private static volatile long closeMillis;

    static class DoubleCloseSocket extends BufferedWebSocket {
        DoubleCloseSocket(HttpExchange exchange) {
            super(exchange);
        }

        @Override
        protected void onOpen() {
            try {
                for (int i = 0; i < FRAMES; i++) {
                    send(PAYLOAD);
                }
            } catch (IOException e) {
                // queue cap etc: the frame-count assertion will catch it
            }
            queued.countDown();
            // the app-thread double close: the socket is already CLOSING by
            // then (the client's Close frame was answered by the read loop)
            Thread.startVirtualThread(() -> {
                try {
                    go.await();
                } catch (InterruptedException e) {
                }
                long t0 = System.nanoTime();
                try {
                    close(CloseCode.NormalClosure, "double-close", false);
                } catch (IOException e) {
                }
                closeMillis = (System.nanoTime() - t0) / 1_000_000;
                serverClosed.countDown();
            });
        }

        @Override
        protected void onMessage(WebSocketFrame message) {
        }

        @Override
        protected void onClose(CloseCode code, String reason, boolean initiatedByRemote) {
        }

        @Override
        protected void onPong(WebSocketFrame pong) {
        }
    }

    HttpServer server;

    @BeforeMethod
    void setUp() throws IOException {
        queued = new CountDownLatch(1);
        go = new CountDownLatch(1);
        serverClosed = new CountDownLatch(1);
        closeMillis = -1;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext(path, new WebSocketHandler() {
            @Override
            protected WebSocket openWebSocket(HttpExchange exchange) {
                return new DoubleCloseSocket(exchange);
            }
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
    }

    @AfterMethod
    void tearDown() {
        server.stop(0);
    }

    @Test
    public void testDoubleCloseFlushesQueuedFrames() throws Exception {
        var received = new AtomicInteger();
        var clientDone = new CountDownLatch(1);
        var client = WebSocketClientUtil.createWSC(port, path, s -> {
            received.incrementAndGet();
            try {
                Thread.sleep(3); // slow the consumer: keep a drain backlog
            } catch (InterruptedException e) {
            }
        }, clientDone::countDown);

        Assert.assertTrue(queued.await(5, TimeUnit.SECONDS), "server must queue its frames");
        // the client's Close puts the server into CLOSING (the read loop's
        // close(OPEN) path queues its CloseFrame behind the backlog)
        client.sendClose(1000, "");
        Thread.sleep(400);
        go.countDown();

        Assert.assertTrue(serverClosed.await(10, TimeUnit.SECONDS), "server close must complete");
        Assert.assertTrue(clientDone.await(10, TimeUnit.SECONDS), "client must see the close");
        Assert.assertEquals(received.get(), FRAMES,
                "every queued frame must be flushed before the close; close took " + closeMillis + "ms");
        Assert.assertTrue(closeMillis < 1900,
                "close() must not burn the full future.get timeout: " + closeMillis + "ms");
    }
}
