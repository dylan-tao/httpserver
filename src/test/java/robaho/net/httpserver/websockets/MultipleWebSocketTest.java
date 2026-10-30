package robaho.net.httpserver.websockets;

import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import robaho.net.httpserver.LoggingFilter;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.testng.Assert.fail;

public class MultipleWebSocketTest {

  static {
    System.setProperty("jdk.httpclient.HttpClient.log", "all");
    System.setProperty("jdk.internal.httpclient.websocket.debug", "true");
  }

  private static final int port = 9000;

  private static final String path = "/ws";

  private static final String path2 = "/ws/sub";

  HttpServer server;

  @BeforeMethod
  public void setUp() throws IOException {
    Logger logger = Logger.getLogger(WebSocketTest.class.getName());
    ConsoleHandler ch = new ConsoleHandler();
    logger.setLevel(Level.ALL);
    ch.setLevel(Level.ALL);
    logger.addHandler(ch);

    server = HttpServer.create(new InetSocketAddress(port), 0);
    HttpHandler h = new EchoWebSocketHandler();
    HttpContext c = server.createContext(path, h);
    c.getFilters().add(new LoggingFilter(logger));
    HttpContext c2 = server.createContext(path2, h);
    server.setExecutor(Executors.newCachedThreadPool());
    server.start();
  }

  @AfterMethod
  public void tearDown() {
    server.stop(0);
  }

  @Test
  public void testEchoMultiple() throws InterruptedException {
    // fail() on the JDK listener thread never propagates to the test
    // thread: record what arrived and assert on the test thread
    var wrongMsg = new java.util.concurrent.atomic.AtomicReference<String>();
    CountDownLatch latch = new CountDownLatch(1);
    var client = WebSocketClientUtil.createWSC(9000, path, s -> {
      if ("a_message".equals(s)) {
        latch.countDown();
      } else {
        wrongMsg.compareAndSet(null, s);
      }
    }, null);

    CountDownLatch latch2 = new CountDownLatch(1);
    var client2 = WebSocketClientUtil.createWSC(9000, path2, s -> {
      if ("a_message".equals(s)) {
        latch2.countDown();
      } else {
        wrongMsg.compareAndSet(null, s);
      }
    }, null);

    client.sendText("a_message", true);
    client2.sendText("a_message", true);

    if (!latch.await(5, TimeUnit.SECONDS)) {
      fail("did not receive message");
    }
    if (!latch2.await(5, TimeUnit.SECONDS)) {
      fail("did not receive message on client 2");
    }
    org.testng.Assert.assertNull(wrongMsg.get(), "received wrong message: " + wrongMsg.get());
  }

}
