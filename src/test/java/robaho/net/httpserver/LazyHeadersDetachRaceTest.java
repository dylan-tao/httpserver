package robaho.net.httpserver;

import org.testng.Assert;
import org.testng.annotations.Test;
import robaho.net.httpserver.internal.LazyRequestHeaders;
import robaho.net.httpserver.internal.OptimizedHeaders;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * deterministic regression guard for the lazy-materialize ordering (audit
 * E6, 2026-11-20): materialize() detached the view from the parse table
 * BEFORE copying it, so OptimizedHeaders.clear() (the next request's
 * parse-table reuse) read a null pendingView, skipped the view's
 * force-materialize, and cleared the table while the copy was still
 * iterating it - an async handler materializing its request headers across
 * the request boundary observed an empty or half-empty header map (an
 * Authorization header vanishing).
 * <p>
 * determinism: the parse table is a subclass whose copyInto blocks until
 * the concurrent clear has run, pinning the interleaving that the
 * production race only hits by chance. red before the fix (the view
 * copies an already-cleared table); green after (the clear waits on the
 * view's monitor until the copy completes).
 */
public class LazyHeadersDetachRaceTest {

  @Test
  public void testClearDuringMaterializeKeepsTheCopyWhole() throws Exception {
    var inCopy = new CountDownLatch(1);
    var releaseCopy = new CountDownLatch(1);
    var corrupt = new AtomicBoolean(false);

    // the parse table, with a hook pinning the reader INSIDE the copy
    // window: the production race window is the whole copyInto duration,
    // but only while the view is detached - exactly what the fix reorders
    OptimizedHeaders table = new OptimizedHeaders(16) {

      @Override
      public void copyInto(com.sun.net.httpserver.Headers target) {
        inCopy.countDown();
        try {
          releaseCopy.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        super.copyInto(target);
      }

    };
    table.addNormalized("Authorization", "Bearer secret");
    for (int i = 0; i < 29; i++) {
      table.addNormalized("X-Hdr-" + i, "v" + i);
    }

    LazyRequestHeaders view = new LazyRequestHeaders(table);
    // handed to application code: clear() must force-materialize it
    view.markExposed();

    // the late first read (an async getRequestHeaders after the connection
    // moved on): materializes the view while the table is being reused
    Thread reader = new Thread(() -> {
      if (!"Bearer secret".equals(view.getFirst("Authorization")) || view.size() != 30) {
        corrupt.set(true);
      }
    });
    reader.start();
    Assert.assertTrue(inCopy.await(5, TimeUnit.SECONDS), "the reader must reach the copy window");

    // the next request's parse-table reuse, racing the copy: pre-fix it
    // ran straight through (pendingView already null), post-fix it blocks
    // on the view's monitor inside materialize() until the copy completes
    Thread clearer = new Thread(table::clear);
    clearer.start();
    // let the clear arrive (and, pre-fix, complete) before the copy is
    // released - without this the clear could still land after the copy
    // and the test would pass on unfixed code
    TimeUnit.MILLISECONDS.sleep(50);
    releaseCopy.countDown();

    reader.join(5_000);
    clearer.join(5_000);
    Assert.assertFalse(reader.isAlive(), "the reader must finish");
    Assert.assertFalse(clearer.isAlive(), "the clear must finish");
    Assert.assertFalse(corrupt.get(), "a materialize racing a table clear must copy the whole header set (Authorization="
        + view.getFirst("Authorization") + ", size=" + view.size() + ")");
  }

}
