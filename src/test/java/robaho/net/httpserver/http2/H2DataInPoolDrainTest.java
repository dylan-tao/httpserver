package robaho.net.httpserver.http2;

import com.sun.net.httpserver.HttpServer;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * white-box regression for the DataIn pool drain (audit W7, 2026-11-20): a
 * stream abandoned with queued DATA buffers (a handler that responds without
 * reading the body, an RST, an early close) dropped every still-queued
 * pooled 16KB array for the GC - 100 cancelled uploads stranded up to
 * ~100MB and returned nothing to the global pool. the close path now hands
 * the still-queued buffers back (guarded: only when no other thread can be
 * mid-copy - see DataIn.close).
 * <p>
 * white-box on purpose: the observable is the global DataFrameBodyPool's
 * occupancy, which has no public surface. verified red via stash pre-fix
 * (the pool never grows), green after.
 */
public class H2DataInPoolDrainTest {

  private HttpServer server;

  @BeforeMethod
  void setUp() throws IOException {
    if (!"true".equals(System.getProperty("robaho.net.httpserver.http2OverNonSSL"))) {
      throw new SkipException("robaho.net.httpserver.http2OverNonSSL is not enabled");
    }
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.start();
  }

  @AfterMethod
  void tearDown() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  public void testAbandonedQueuedFramesReturnToPool() throws Exception {
    var release = new CountDownLatch(1);
    var blindContext = new AtomicInteger(0);
    server.createContext("/blind", exchange -> {
      // respond without ever reading the request body - the queued DATA
      // buffers must go back to the pool at close, not to the GC
      if (blindContext.incrementAndGet() == 1) {
        try {
          release.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      exchange.sendResponseHeaders(200, -1);
    });

    // drain the GLOBAL pool to a known empty start: the pool is shared by
    // every h2 test in the suite, so borrowed leftovers from earlier tests
    // would absorb this test's returned frames (an absolute occupancy
    // assertion raced those borrows). from empty, any observed occupancy
    // below must come from THIS test's abandoned frames
    drainPool();

    try (H2Client client = new H2Client(server.getAddress().getPort())) {
      int stream = client.sendRequest("POST", "/blind", false);
      // two FULL-SIZE (bucket-size) DATA frames: poolable on release
      client.sendData(stream, new byte[16384], false);
      client.sendData(stream, new byte[16384], true);

      // let the server's reader PARSE and enqueue both frames before the
      // handler wakes: without this settle the handler can wake from the
      // latch, respond, and run the close drain against an EMPTY queue
      // (the frames arrive after the close, hit the closed-stream ignore
      // path, and their arrays are dropped) - the peak then races between
      // 0/1/2
      Thread.sleep(200);

      release.countDown();
      var response = client.awaitResponse(stream);
      Assert.assertEquals(response.status(), 200, "the blind handler must respond");

      // the drain runs on the close path right after the response flush:
      // watch the pool until the two frames land (bounded). with an empty
      // start the two borrowed frames were fresh allocations, so the two
      // returns must be observable unless the drain never ran
      long deadline = System.currentTimeMillis() + 5_000;
      int seen = 0;
      while (System.currentTimeMillis() < deadline) {
        seen = Math.max(seen, poolSize());
        if (seen >= 2) {
          break;
        }
        Thread.sleep(20);
      }
      Assert.assertTrue(seen >= 2,
          "the close path must return the abandoned queued DATA buffers to the pool (peak observed=" + seen + ")");
    }
  }

  @SuppressWarnings("unchecked")
  private static int poolSize() throws Exception {
    Field f = robaho.net.httpserver.http2.frame.DataFrameBodyPool.class.getDeclaredField("POOL");
    f.setAccessible(true);
    var pool = (java.util.concurrent.ArrayBlockingQueue<byte[]>) f.get(null);
    synchronized (pool) {
      return pool.size();
    }
  }

  @SuppressWarnings("unchecked")
  private static void drainPool() throws Exception {
    Field f = robaho.net.httpserver.http2.frame.DataFrameBodyPool.class.getDeclaredField("POOL");
    f.setAccessible(true);
    var pool = (java.util.concurrent.ArrayBlockingQueue<byte[]>) f.get(null);
    pool.clear();
  }

}
