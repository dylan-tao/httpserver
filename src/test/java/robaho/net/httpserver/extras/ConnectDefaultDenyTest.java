package robaho.net.httpserver.extras;

import com.sun.net.httpserver.HttpServer;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * the default ProxyHandler must DENY CONNECT (403) - 1.x allowed every target. this is a deliberate security hardening;
 * every other proxy test overrides authorizeConnect, so the deny side had zero coverage
 */
public class ConnectDefaultDenyTest {

  private HttpServer proxy;

  @BeforeMethod
  void setUp() throws IOException {
    proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    // default authorizeConnect - no override
    proxy.createContext("/", new ProxyHandler());
    proxy.start();
  }

  @AfterMethod
  void tearDown() {
    if (proxy != null) {
      proxy.stop(0);
    }
  }

  @Test
  public void testDefaultConnectIsDenied() throws Exception {
    try (Socket s = new Socket("127.0.0.1", proxy.getAddress().getPort())) {
      s.setSoTimeout(5000);
      s.getOutputStream().write("CONNECT example.com:443 HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
      s.getOutputStream().flush();
      String head = readHead(s.getInputStream());
      Assert.assertTrue(head.startsWith("HTTP/1.1 403"),
          "default ProxyHandler must deny CONNECT, got: " + head.split("\r\n")[0]);
    }
  }

  private static String readHead(InputStream in) throws IOException {
    StringBuilder sb = new StringBuilder();
    int c;
    while ((c = in.read()) != -1) {
      sb.append((char) c);
      int n = sb.length();
      if (n >= 4 && sb.charAt(n - 4) == '\r' && sb.charAt(n - 3) == '\n' && sb.charAt(n - 2) == '\r'
          && sb.charAt(n - 1) == '\n') {
        break;
      }
    }
    return sb.toString();
  }

}
