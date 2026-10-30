package jdk.test.lib;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.Socket;

import static java.nio.charset.StandardCharsets.UTF_8;

public class RawClient {

  /**
   * the first response line and body of the last invocation, for callers that want to assert on them
   */
  public static final java.util.concurrent.atomic.AtomicReference<String> lastStatusLine =
      new java.util.concurrent.atomic.AtomicReference<>();

  public static final java.util.concurrent.atomic.AtomicReference<String> lastBody =
      new java.util.concurrent.atomic.AtomicReference<>();

  /**
   * performs an HTTP request using a raw socket.
   * @param address
   *          the server address
   * @param port
   *          the server port
   * @param context
   *          the server context (i.e. endpoint)
   * @param data
   *          the data to send in the request body
   * @param contentLength
   *          the content length, if > 0, this will be used as the Content-length header value which can be used to
   *          simulate the client advertising more data than it sends. in almost all cases this parameter should be
   *          0, upon which the actual length of the data is used.
   * @throws Exception
   */
  public static void runRawSocketHttpClient(InetAddress address, int port, String context, String data, int contentLength)
      throws Exception {
    Socket socket = null;
    PrintWriter writer = null;
    BufferedReader reader = null;
    final String CRLF = "\r\n";
    // reset per invocation: a server that dies without responding must
    // not leave the previous test's status line behind
    lastStatusLine.set(null);
    lastBody.set(null);
    try {
      socket = new Socket(address, port);
      // a regression of the pipelining hang manifests as a blocked
      // read - without a timeout the whole suite hangs instead of
      // failing this test
      socket.setSoTimeout(10_000);
      writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()));
      System.out.println("Client connected by socket: " + socket);
      String body = data == null ? "" : data;
      if (contentLength <= 0) {
        contentLength = body.getBytes(UTF_8).length;
      }

      writer.print("GET " + context + "/ HTTP/1.1" + CRLF);
      writer.print("User-Agent: Java/" + System.getProperty("java.version") + CRLF);
      writer.print("Host: " + address.getHostName() + CRLF);
      writer.print("Accept: */*" + CRLF);
      writer.print("Content-Length: " + contentLength + CRLF);
      writer.print("Connection: keep-alive" + CRLF);
      writer.print(CRLF); // Important, else the server will expect that
      // there's more into the request.
      writer.flush();
      System.out.println("Client wrote request to socket: " + socket);
      writer.print(body);
      writer.flush();

      reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
      System.out.println("Client start reading from server:");
      StringBuilder headSb = new StringBuilder();
      String line = reader.readLine();
      if (line != null) {
        lastStatusLine.set(line);
        headSb.append(line).append('\n');
      }
      for (; line != null; line = reader.readLine()) {
        if (line.isEmpty()) {
          break;
        }
        headSb.append(line).append('\n');
        System.out.println("\"" + line + "\"");
      }
      System.out.println("Client finished reading from server");
      // read the body by its DECLARED length: readLine() would block
      // forever on a terminator-less body (e.g. a fixed-length 8-byte
      // "oversized" with no trailing CRLF). a truncated body is then a
      // detectable EOF instead of a hang
      java.util.regex.Matcher cl = java.util.regex.Pattern.compile("(?i)Content-Length: (\\d+)").matcher(headSb);
      if (line != null && cl.find()) {
        int n = Integer.parseInt(cl.group(1));
        char[] bodyChars = new char[n];
        int off = 0;
        while (off < n) {
          int r = reader.read(bodyChars, off, n - off);
          if (r < 0)
            break;
          off += r;
        }
        lastBody.set(new String(bodyChars, 0, off));
      }
    } finally {
      // give time to the server to try & drain its input stream
      Thread.sleep(500);
      // closes the client outputstream while the server is draining
      // it
      if (writer != null) {
        writer.close();
      }
      // give time to the server to trigger its assertion
      // error before closing the connection
      Thread.sleep(500);
      if (reader != null)
        try {
          reader.close();
        } catch (IOException logOrIgnore) {
          logOrIgnore.printStackTrace();
        }
      if (socket != null) {
        try {
          socket.close();
        } catch (IOException logOrIgnore) {
          logOrIgnore.printStackTrace();
        }
      }
    }
    System.out.println("Client finished.");
  }

}
