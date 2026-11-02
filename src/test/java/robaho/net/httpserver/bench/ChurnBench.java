package robaho.net.httpserver.bench;

import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** multi-threaded connection-churn: N threads doing connect->GET->close loops */
public class ChurnBench {

  static final AtomicInteger CONNS = new AtomicInteger();

  static final AtomicInteger FAILS = new AtomicInteger();

  static final AtomicLong LAT_SUM = new AtomicLong();

  static final AtomicLong LAT_MAX = new AtomicLong();

  public static void main(String[] args) throws Exception {
    int port = Integer.parseInt(args[0]);
    int threads = Integer.parseInt(args[1]);
    int seconds = Integer.parseInt(args[2]);
    byte[] req = "GET /hello HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n".getBytes();

    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch stop = new CountDownLatch(1);
    for (int t = 0; t < threads; t++) {
      pool.submit(() -> {
        while (stop.getCount() > 0) {
          long t0 = System.nanoTime();
          try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            s.setTcpNoDelay(true);
            OutputStream out = s.getOutputStream();
            out.write(req);
            out.flush();
            InputStream in = s.getInputStream();
            byte[] buf = new byte[512];
            int total = 0, n;
            while ((n = in.read(buf)) > 0)
              total += n;
            if (total < 100)
              throw new IOException("short response " + total);
          } catch (Exception e) {
            FAILS.incrementAndGet();
          }
          long dt = (System.nanoTime() - t0) / 1000;
          LAT_SUM.addAndGet(dt);
          LAT_MAX.accumulateAndGet(dt, Math::max);
          CONNS.incrementAndGet();
        }
      });
    }
    long start = System.currentTimeMillis();
    Thread.sleep(seconds * 1000L);
    stop.countDown();
    pool.shutdown();
    pool.awaitTermination(10, TimeUnit.SECONDS);
    double sec = (System.currentTimeMillis() - start) / 1000.0;
    System.out.printf("RESULT threads=%d conns=%d fails=%d conn_per_s=%.0f lat_avg_us=%.0f lat_max_us=%d%n", threads,
        CONNS.get(), FAILS.get(), CONNS.get() / sec, (double) LAT_SUM.get() / Math.max(1, CONNS.get()), LAT_MAX.get());
  }

}
