package robaho.net.httpserver.bench;

import java.io.*;
import java.net.*;
import java.util.concurrent.atomic.*;

/** count reader syscalls impact: client sends 64KB body, handler reads 0, server drains */
public class DrainBench {

  public static void main(String[] a) throws Exception {
    int port = Integer.parseInt(a[0]);
    int conns = 500, warm = 50;
    byte[] head = "POST /drain HTTP/1.1\r\nHost: x\r\nContent-Length: 65536\r\nConnection: close\r\n\r\n".getBytes();
    byte[] body = new byte[65536];
    for (int i = 0; i < 65536; i++)
      body[i] = 'd';
    for (int i = 0; i < warm; i++)
      one(port, head, body);
    AtomLong dummy = new AtomLong();
    long t0 = System.nanoTime();
    for (int i = 0; i < conns; i++)
      one(port, head, body);
    double sec = (System.nanoTime() - t0) / 1e9;
    System.out.printf("RESULT drain64k conns=%d conn_per_s=%.0f%n", conns, conns / sec);
  }

  static void one(int port, byte[] head, byte[] body) throws IOException {
    try (Socket s = new Socket("127.0.0.1", port)) {
      s.setTcpNoDelay(true);
      OutputStream o = s.getOutputStream();
      o.write(head);
      o.write(body);
      o.flush();
      InputStream in = s.getInputStream();
      byte[] b = new byte[4096];
      while (in.read(b) > 0) {}
    } catch (IOException e) {
      throw new IOException(e);
    }
  }

  static class AtomLong extends AtomicLong {}

}
