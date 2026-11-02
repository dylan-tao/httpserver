package robaho.net.httpserver.bench;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.Executors;

/** load-test server: h1 + h2 (prior-knowledge) on 18080 */
public class BenchServer {

  public static void main(String[] args) throws Exception {
    int port = args.length > 0 ? Integer.parseInt(args[0]) : 18080;
    Path big = Path.of(System.getProperty("java.io.tmpdir"), "benchserver-payload10m.bin");
    if (!Files.exists(big)) {
      byte[] chunk = new byte[1 << 16];
      Arrays.fill(chunk, (byte) 'x');
      try (OutputStream out = Files.newOutputStream(big)) {
        for (int i = 0; i < 160; i++)
          out.write(chunk); // 160 * 64KB = 10MB
      }
    }
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 1024);
    server.createContext("/hello", ex -> {
      byte[] body = new byte[128];
      Arrays.fill(body, (byte) 'h');
      ex.getResponseHeaders().set("Content-Type", "text/plain");
      ex.sendResponseHeaders(200, body.length);
      try (OutputStream os = ex.getResponseBody()) {
        os.write(body);
      }
    });
    // H2Bench upload contract: "ok {bytes}"
    server.createContext("/echo", ex -> {
      long n = 0;
      try (InputStream in = ex.getRequestBody()) {
        n = in.transferTo(OutputStream.nullOutputStream());
      }
      byte[] body = ("ok " + n).getBytes(StandardCharsets.UTF_8);
      ex.sendResponseHeaders(200, body.length);
      try (OutputStream os = ex.getResponseBody()) {
        os.write(body);
      }
    });
    server.createContext("/large64k", fixed(64 * 1024));
    // h2 flush/frame counters for the B2 analysis (EnableStats on)
    server.createContext("/h2stats", ex -> {
      String body = "see /stats via server API or jconsole; this probe removed";
      ex.sendResponseHeaders(200, body.getBytes().length);
      try (OutputStream os = ex.getResponseBody()) {
        os.write(body.getBytes());
      }
    });
    // handler that never reads the body: forces the server's drain path
    server.createContext("/drain", ex -> {
      ex.sendResponseHeaders(200, 2);
      try (OutputStream os = ex.getResponseBody()) {
        os.write("ok".getBytes());
      }
    });
    // pre-rendered response shape: the whole body written in ONE os.write call
    server.createContext("/bulk64k", ex -> {
      byte[] body = new byte[64 * 1024];
      java.util.Arrays.fill(body, (byte) 'b');
      ex.getResponseHeaders().set("Content-Type", "application/octet-stream");
      ex.sendResponseHeaders(200, body.length);
      try (OutputStream os = ex.getResponseBody()) {
        os.write(body);
      }
    });
    // 1MB in 16 x 64KB single-call writes: only the FIRST gathers with the head
    server.createContext("/bulkx16", ex -> {
      byte[] chunk = new byte[64 * 1024];
      java.util.Arrays.fill(chunk, (byte) 'c');
      ex.getResponseHeaders().set("Content-Type", "application/octet-stream");
      ex.sendResponseHeaders(200, 16L * chunk.length);
      try (OutputStream os = ex.getResponseBody()) {
        for (int i = 0; i < 16; i++)
          os.write(chunk);
      }
    });
    server.createContext("/large1m", fixed(1024 * 1024));
    // classic user-space streaming (works on every build)
    server.createContext("/file", ex -> {
      long len = Files.size(big);
      ex.getResponseHeaders().set("Content-Type", "application/octet-stream");
      ex.sendResponseHeaders(200, len);
      try (OutputStream os = ex.getResponseBody(); InputStream in = Files.newInputStream(big)) {
        in.transferTo(os);
      }
    });
    // library sendfile path (extras.Sendfile, new builds only; reflection for baseline builds)
    server.createContext("/sendfile", ex -> {
      try {
        Class<?> c = Class.forName("robaho.net.httpserver.extras.Sendfile");
        Method m = c.getMethod("serve", com.sun.net.httpserver.HttpExchange.class, Path.class, String.class);
        m.invoke(null, ex, big, "application/octet-stream");
      } catch (ClassNotFoundException e) {
        // baseline build: fall back to the classic path
        long len = Files.size(big);
        ex.getResponseHeaders().set("Content-Type", "application/octet-stream");
        ex.sendResponseHeaders(200, len);
        try (OutputStream os = ex.getResponseBody(); InputStream in = Files.newInputStream(big)) {
          in.transferTo(os);
        }
      } catch (ReflectiveOperationException | RuntimeException e) {
        throw new IOException("sendfile invoke failed", e);
      }
    });
    // WS echo endpoint for the P2 mask/frame writer benchmarks
    server.createContext("/ws", new robaho.net.httpserver.websockets.WebSocketHandler() {

      @Override
      protected robaho.net.httpserver.websockets.WebSocket openWebSocket(HttpExchange ex) {
        return new robaho.net.httpserver.websockets.WebSocket(ex) {

          @Override
          protected void onMessage(robaho.net.httpserver.websockets.WebSocketFrame message) {
            try {
              sendFrame(message);
            } catch (IOException e) {}
          }

          @Override
          protected void onClose(robaho.net.httpserver.websockets.CloseCode code, String reason, boolean remote) {}

        };
      }

    });
    // virtual-thread-per-task: this server's blocking model parks a thread
    // per keep-alive connection, so platform threads cap concurrency
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.start();
    System.out.println("BenchServer listening on 127.0.0.1:" + port + " (h1 + h2)");
    synchronized (BenchServer.class) {
      while (true) {
        try {
          BenchServer.class.wait();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }
  }

  private static HttpHandler fixed(int size) {
    byte[] body = new byte[size];
    Arrays.fill(body, (byte) 'd');
    return ex -> {
      ex.getResponseHeaders().set("Content-Type", "application/octet-stream");
      ex.sendResponseHeaders(200, body.length);
      try (OutputStream os = ex.getResponseBody()) {
        os.write(body);
      }
    };
  }

}
