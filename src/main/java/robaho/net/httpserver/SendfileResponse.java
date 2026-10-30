package robaho.net.httpserver;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.locks.LockSupport;

/**
 * response body stream for the sendfile attribute
 * ({@code robaho.net.httpserver.sendFile}, see extras.Sendfile): the library
 * streams the file itself, the handler must not write. the kernel fast path
 * (FileChannel.transferTo) is used when the response is plain h1 on a
 * channel-backed socket - the bytes never enter user space on the handler
 * thread. TLS falls back to a user-space copy through the normal
 * fixed-length accounting.
 */
final class SendfileResponse extends OutputStream {

  static final String ATTRIBUTE = "robaho.net.httpserver.sendFile";

  private final ExchangeImpl t;

  private final FixedLengthOutputStream inner;

  private final Path file;

  private final long length;

  private boolean closed;

  private SendfileResponse(ExchangeImpl t, FixedLengthOutputStream inner, Path file, long length) {
    this.t = t;
    this.inner = inner;
    this.file = file;
    this.length = length;
  }

  /**
   * claims the attribute (sets it to null: a null on re-read is how the
   * extras helper learns the library owns the body - h2 exchanges ignore
   * the attribute and the helper streams the file itself)
   */
  static SendfileResponse create(ExchangeImpl t, OutputStream ros, long contentLen) {
    if (contentLen > 0 && t.getAttribute(ATTRIBUTE) instanceof Path p) {
      t.setAttribute(ATTRIBUTE, null);
      return new SendfileResponse(t, new FixedLengthOutputStream(t, ros, contentLen), p, contentLen);
    }
    return null;
  }

  @Override
  public void write(int b) throws IOException {
    throw new IOException("sendfile response: the library streams the body, the handler must not write");
  }

  @Override
  public void write(byte[] b, int off, int len) throws IOException {
    throw new IOException("sendfile response: the library streams the body, the handler must not write");
  }

  @Override
  public void close() throws IOException {
    if (closed) {
      return;
    }
    closed = true;
    WritableByteChannel channel = t.getConnection().sendfileChannel();
    if (channel != null) {
      // headers first: the transfer bypasses the connection's buffered
      // output, so the response head must be on the wire before any file
      // bytes - pipelined coalescing cannot apply here
      inner.flush();
      try (FileChannel fc = FileChannel.open(file, StandardOpenOption.READ)) {
        long pos = 0;
        long parkNanos = 0;
        while (pos < length) {
          long n = fc.transferTo(pos, length - pos, channel);
          if (n > 0) {
            pos += n;
            parkNanos = 0;
            continue;
          }
          if (pos >= fc.size()) {
            // the file is exhausted short of the declared length
            break;
          }
          // zero bytes transferred with the file not exhausted: on a
          // virtual thread the socket fd is non-blocking (the VT
          // read/write machinery emulates blocking in software) and
          // sendfile honors the fd state - it returns 0 when the send
          // buffer is full instead of blocking. wait for the kernel to
          // drain, then retry. the park frees the carrier thread
          if (parkNanos == 0) {
            parkNanos = 100_000;
          } else if (parkNanos < 2_000_000) {
            parkNanos *= 2;
          }
          LockSupport.parkNanos(parkNanos);
        }
        if (pos < length) {
          t.close();
          throw new IOException("sendfile: file is shorter than the declared content-length");
        }
      }
      inner.markBodySent();
    } else {
      // TLS (or no channel): user-space copy through the normal path.
      // a file longer than the content-length fails the inner write guard,
      // a shorter one fails the close guard - both are existing semantics
      try (InputStream in = Files.newInputStream(file)) {
        in.transferTo(inner);
      }
    }
    inner.close();
  }

}
