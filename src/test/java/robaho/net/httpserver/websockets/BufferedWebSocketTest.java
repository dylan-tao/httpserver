package robaho.net.httpserver.websockets;

import com.sun.net.httpserver.HttpExchange;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * First coverage for BufferedWebSocket
 * the byte cap on the pending queue. the frame-count bound (1024) alone
 * allowed 1024 x frame-size retention when the consumer stalls.
 */
public class BufferedWebSocketTest {

  /**
   * a response stream that blocks forever on write: the drain task parks
   * on the first frame and the queue accumulates, exactly like a stalled
   * network consumer
   */
  static final class BlockedOutputStream extends OutputStream {

    @Override
    public void write(int b) throws IOException {
      try {
        Thread.sleep(Long.MAX_VALUE);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException("interrupted");
      }
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      write(0);
    }

  }

  /**
   * the minimal exchange surface WebSocket/BufferedWebSocket touch; every
   * other abstract method is unreachable in this test
   */
  static HttpExchange stubExchange() {
    return new HttpExchange() {

      @Override
      public URI getRequestURI() { return URI.create("/ws"); }

      @Override
      public InputStream getRequestBody() { return new ByteArrayInputStream(new byte[0]); }

      @Override
      public OutputStream getResponseBody() { return new BlockedOutputStream(); }

      @Override
      public void close() {}

      @Override
      public com.sun.net.httpserver.HttpPrincipal getPrincipal() {
        throw new UnsupportedOperationException();
      }

      @Override
      public com.sun.net.httpserver.Headers getRequestHeaders() {
        throw new UnsupportedOperationException();
      }

      @Override
      public com.sun.net.httpserver.Headers getResponseHeaders() {
        throw new UnsupportedOperationException();
      }

      @Override
      public void sendResponseHeaders(int rCode, long contentLength) {
        throw new UnsupportedOperationException();
      }

      @Override
      public String getRequestMethod() { return "GET"; }

      @Override
      public com.sun.net.httpserver.HttpContext getHttpContext() {
        throw new UnsupportedOperationException();
      }

      @Override
      public java.net.InetSocketAddress getLocalAddress() {
        throw new UnsupportedOperationException();
      }

      @Override
      public java.net.InetSocketAddress getRemoteAddress() {
        throw new UnsupportedOperationException();
      }

      @Override
      public String getProtocol() { return "HTTP/1.1"; }

      @Override
      public Object getAttribute(String name) {
        return null;
      }

      @Override
      public void setAttribute(String name, Object value) {}

      @Override
      public void setStreams(InputStream in, OutputStream out) {
        throw new UnsupportedOperationException();
      }

      @Override
      public int getResponseCode() { return -1; }

    };
  }

  @Test
  public void testPendingQueueByteCap() throws Exception {
    CountDownLatch closed = new CountDownLatch(1);
    AtomicInteger closeCount = new AtomicInteger();
    BufferedWebSocket ws = new BufferedWebSocket(stubExchange()) {

      @Override
      protected void onClose(CloseCode code, String reason, boolean initiatedByRemote) {
        closeCount.incrementAndGet();
        closed.countDown();
      }

      @Override
      protected void onMessage(WebSocketFrame message) {}

      @Override
      protected void onPong(WebSocketFrame pong) {}

    };

    // 1MB binary frames: the drain task parks on frame 1 (blocked output),
    // the queue accumulates, and the 16MB default byte cap must trip
    byte[] payload = new byte[1024 * 1024];
    try {
      for (int i = 0; i < 32; i++) {
        ws.sendFrame(new WebSocketFrame(OpCode.Binary, true, payload));
      }
      Assert.fail("the byte cap must trip before 32MB of queued frames");
    } catch (WebSocketException expected) {
      // MessageTooBig: the retained bytes are bounded
      Assert.assertEquals(expected.getCode(), CloseCode.MessageTooBig);
    } finally {
      // unblocks the drain task (future.get times out and cancels with
      // interrupt) and runs the close path exactly once
      ws.doClose(CloseCode.NormalClosure, "test done", false);
    }
    Assert.assertTrue(closed.await(5, TimeUnit.SECONDS), "onClose must fire exactly once");
    Assert.assertEquals(closeCount.get(), 1, "onClose must not double-fire");
  }

  /**
   * regression guard for the full-queue/close race (audit E5, 2026-11-20):
   * the CLOSED check and the enqueue were not atomic - a close completing
   * between them left the producer in an unbounded put() on a queue whose
   * drain task is exiting (the poison pill never frees a FULL queue and
   * nobody holds the future to interrupt it), parking the producer
   * virtual thread forever and leaking its frame. with the offer-loop fix
   * the producer notices the close within one offer window and exits with
   * an IOException. pre-fix this test fails by the producer join timing
   * out (verified against the stashed fix: the send thread never returns).
   */
  @Test(timeOut = 15_000)
  public void testFullQueueSendExitsOnConcurrentClose() throws Exception {
    CountDownLatch closed = new CountDownLatch(1);
    BufferedWebSocket ws = new BufferedWebSocket(stubExchange()) {

      @Override
      protected void onClose(CloseCode code, String reason, boolean initiatedByRemote) {
        closed.countDown();
      }

      @Override
      protected void onMessage(WebSocketFrame message) {}

    };

    // park the drain task on frame 1 (blocked output stream): every
    // further frame stays queued, so the 1024-frame queue fills
    byte[] payload = new byte[16];

    // the racing producer: fills the queue then keeps sending against the
    // FULL queue (the state is still OPEN). pre-fix this thread parks in
    // put() forever once the queue is full and close() runs
    var sendResult = new java.util.concurrent.atomic.AtomicReference<IOException>();
    var sending = new CountDownLatch(1);
    Thread producer = Thread.ofVirtual().start(() -> {
      try {
        sending.countDown();
        // 1024 slots: fill them, plus extra frames that must park on the
        // full queue until the close frees them (or park forever pre-fix)
        for (int i = 0; i < 1024 + 64; i++) {
          ws.sendFrame(new WebSocketFrame(OpCode.Binary, true, payload));
        }
        sendResult.set(new IOException("all frames enqueued - queue never observed full"));
      } catch (IOException e) {
        sendResult.set(e);
      }
    });
    Assert.assertTrue(sending.await(5, TimeUnit.SECONDS));

    // let the producer reach the full-queue park: 1088 frames against
    // 1024 slots - any frame past 1024 parks in the offer/put wait
    Thread.sleep(500);

    // the concurrent close: completes the close handshake (blocked output
    // throws in the drain, doCloseLocked runs, state -> CLOSED). the
    // parked producer must observe CLOSED within one offer window
    ws.doClose(CloseCode.NormalClosure, "closing while queue full", false);
    Assert.assertTrue(closed.await(5, TimeUnit.SECONDS), "the close must complete");

    producer.join(10_000);
    Assert.assertFalse(producer.isAlive(),
        "the producer must exit after the close instead of parking forever on the full queue");
    Assert.assertNotNull(sendResult.get(), "the producer must terminate with the closed-websocket IOException");
    Assert.assertEquals(sendResult.get().getMessage(), "websocket is closed",
        "the exit reason must be the closed state, not a byte-cap or interruption");

    // the accounting the leak left behind: whatever the producer enqueued
    // before the close is the drain's poison-pill-cleared remainder - the
    // producer's own exit proves no frame is stuck in put() anymore
  }

}
