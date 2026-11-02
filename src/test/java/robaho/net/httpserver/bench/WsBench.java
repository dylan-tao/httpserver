package robaho.net.httpserver.bench;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.Random;

/** WS echo load client: handshake, send masked binary frames, verify echoed bytes */
public class WsBench {

  public static void main(String[] args) throws Exception {
    String host = "127.0.0.1";
    int port = Integer.parseInt(args[0]);
    int size = Integer.parseInt(args[1]); // payload bytes per frame
    int count = Integer.parseInt(args[2]); // frames
    boolean verify = args.length < 4 || !args[3].equals("noverify");
    int warm = 3;

    Socket s = new Socket(host, port);
    s.setTcpNoDelay(true);
    OutputStream out = s.getOutputStream();
    InputStream in = s.getInputStream();

    // --- handshake ---
    byte[] nonce = new byte[16];
    new Random(1234).nextBytes(nonce);
    String key = Base64.getEncoder().encodeToString(nonce);
    String req = "GET /ws HTTP/1.1\r\nHost: " + host + ":" + port + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
        + "Sec-WebSocket-Key: " + key + "\r\nSec-WebSocket-Version: 13\r\n\r\n";
    out.write(req.getBytes(StandardCharsets.ISO_8859_1));
    out.flush();
    // read 101 response headers
    ByteArrayOutputStream hs = new ByteArrayOutputStream();
    while (!hs.toString().endsWith("\r\n\r\n")) {
      int c = in.read();
      if (c < 0)
        throw new IOException("handshake EOF");
      hs.write(c);
    }
    String resp = hs.toString();
    if (!resp.contains("101"))
      throw new IOException("handshake failed: " + resp.split("\r\n")[0]);

    // --- frames ---
    Random rnd = new Random(42);
    byte[] key4 = new byte[4];
    rnd.nextBytes(key4);
    byte[] payload = new byte[size];
    rnd.nextBytes(payload);
    byte[] sha = MessageDigest.getInstance("SHA-256").digest(payload);

    for (int i = 0; i < warm; i++) {
      sendFrame(out, payload, key4);
      readEcho(in, size, payload, key4, verify);
    }
    long t0 = System.nanoTime();
    for (int i = 0; i < count; i++) {
      sendFrame(out, payload, key4);
      readEcho(in, size, payload, key4, verify);
    }
    long t1 = System.nanoTime();
    double sec = (t1 - t0) / 1e9;
    // sanity: the original payload digest must still match (no corruption ever observed)
    if (!Arrays.equals(sha, MessageDigest.getInstance("SHA-256").digest(payload)))
      throw new IllegalStateException("payload mutated");
    System.out.printf("RESULT frames=%d size=%d wall_s=%.3f fps=%.0f mbps=%.1f%n", count, size, sec, count / sec,
        (count * (long) size / 1e6) / sec);
    s.close();
  }

  static void sendFrame(OutputStream out, byte[] payload, byte[] key) throws IOException {
    int len = payload.length;
    ByteBuffer hdr = ByteBuffer.allocate(14);
    hdr.put((byte) 0x82); // FIN + binary
    if (len <= 125) {
      hdr.put((byte) (0x80 | len));
    } else if (len <= 0xFFFF) {
      hdr.put((byte) (0x80 | 126)).putShort((short) len);
    } else {
      hdr.put((byte) (0x80 | 127)).putLong(len);
    }
    hdr.put(key);
    byte[] masked = new byte[len];
    for (int i = 0; i < len; i++)
      masked[i] = (byte) (payload[i] ^ key[i & 3]);
    out.write(hdr.array(), 0, hdr.position());
    out.write(masked);
    out.flush();
  }

  static void readEcho(InputStream in, int size, byte[] payload, byte[] key, boolean verify) throws IOException {
    int h0 = readByte(in);
    int h1 = readByte(in);
    boolean masked = (h1 & 0x80) != 0;
    int len = h1 & 0x7F;
    if (len == 126)
      len = (readByte(in) << 8) | readByte(in);
    else if (len == 127) {
      long l = 0;
      for (int i = 0; i < 8; i++)
        l = (l << 8) | readByte(in);
      len = (int) l;
    }
    if (len != size)
      throw new IOException("echo size mismatch: " + len);
    if (masked) {
      byte[] k = new byte[4];
      readFully(in, k);
    } // server must NOT mask, but tolerate
    byte[] body = new byte[size];
    readFully(in, body);
    if (verify) {
      for (int i = 0; i < size; i++) {
        int expect = payload[i] & 0xFF;
        int got = body[i] & 0xFF;
        if (got != expect)
          throw new IOException("echo corruption at " + i + ": got " + got + " want " + expect);
      }
    }
  }

  static int readByte(InputStream in) throws IOException {
    int b = in.read();
    if (b < 0)
      throw new EOFException();
    return b;
  }

  static void readFully(InputStream in, byte[] b) throws IOException {
    int r = 0;
    while (r < b.length) {
      int n = in.read(b, r, b.length - r);
      if (n < 0)
        throw new EOFException();
      r += n;
    }
  }

}
