package robaho.net.httpserver;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The default executor, extracted from ServerImpl (the God-class diet):
 * virtual threads by default (JDK 21, JEP 444) — connection handling is
 * blocking-IO-heavy (request reads, drains, SSL handshakes) and
 * thread-per-connection scales far better virtually than on the
 * platform-thread pool. the old "no monitors anywhere" rule existed
 * only for JEP 444 pinning; JDK 24 (JEP 491) removed it, and locks
 * that need tryLock (h2 connection, websocket watchdog) keep
 * ReentrantLock for that semantic, not for pinning.
 * opt out with {@code robaho.net.httpserver.virtualThreads=false}
 */
final class DefaultExecutor implements Executor {

  private final ExecutorService executor =
      ServerConfig.virtualThreads() ? Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("httpserver-vt-", 0).factory())
          : Executors.newCachedThreadPool();

  @Override
  public void execute(Runnable task) {
    executor.execute(task);
  }

  public void shutdown() {
    executor.shutdown();
  }

}
