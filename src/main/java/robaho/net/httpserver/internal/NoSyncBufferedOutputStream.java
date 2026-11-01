package robaho.net.httpserver.internal;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.GatheringByteChannel;

/**
 * buffered output stream designed for virtual threads
 */
public class NoSyncBufferedOutputStream extends FilterOutputStream {

  private static final byte[] EMPTY = new byte[0];

  private final int bufferSize;

  /**
   * writev target: set ONLY on plain h1 connections whose socket exposes
   * a channel (see HttpConnection). when a body chunk arrives that would
   * bypass the buffer (len >= bufferSize), any pending head bytes ride
   * along in a single gathering write instead of a separate flush.
   * null => classic flush-then-write cascade (TLS, h2, tests)
   */
  private GatheringByteChannel gather;

  /**
   * The internal buffer where data is stored. lazily allocated on the first
   * buffered write (connections that only write large chunks bypass it
   * entirely); EMPTY after detachBuffer.
   */
  protected byte[] buf = EMPTY;

  /**
   * The number of valid bytes in the buffer. This value is always
   * in the range {@code 0} through {@code buf.length}; elements
   * {@code buf[0]} through {@code buf[count-1]} contain valid
   * byte data.
   */
  protected int count;

  public NoSyncBufferedOutputStream(OutputStream out) {
    // 16KB: large enough that a typical response is written with a
    // single flush, small enough for many connections
    this(out, 16384);
  }

  public NoSyncBufferedOutputStream(OutputStream out, int size) {
    if (size <= 0) {
      throw new IllegalArgumentException("buffer size " + size);
    }
    super(out);
    this.bufferSize = size;
  }

  /**
   * enable the writev fast path: big body chunks coalesce with pending
   * head bytes into one gathering write. the channel must be the SAME
   * resource the wrapped stream writes to (the socket), and all writes
   * stay on this stream's owning thread
   */
  public void setGatherTarget(GatheringByteChannel channel) { this.gather = channel; }

  /**
   * wrap an externally owned buffer (e.g. from a pool). the caller remains
   * responsible for its lifecycle and may reclaim it with detachBuffer()
   */
  public NoSyncBufferedOutputStream(OutputStream out, byte[] buffer) {
    super(out);
    if (buffer == null || buffer.length == 0) {
      throw new IllegalArgumentException("empty buffer");
    }
    this.buf = buffer;
    this.bufferSize = buffer.length;
  }

  /**
   * detach the internal buffer for reuse (e.g. return it to a pool). the
   * stream must be closed and unused afterwards: writes fail fast on the
   * empty replacement buffer
   */
  public byte[] detachBuffer() {
    byte[] b = buf;
    buf = EMPTY;
    count = 0;
    return b;
  }

  /** Flush the internal buffer */
  private void flushBuffer() throws IOException {
    if (count > 0) {
      out.write(buf, 0, count);
      count = 0;
    }
  }

  /**
   * writev the pending head bytes together with the direct body chunk.
   * gathering writes may return short even on blocking channels, so the
   * loop drains both buffers; on a virtual thread a zero-progress return
   * (non-blocking fd state, see the sendfile note) parks briefly before
   * retrying instead of spinning. on failure count is left untouched,
   * matching flushBuffer's contract: the connection dies with the
   * exception either way
   */
  private void gatherPendingPlus(byte[] b, int off, int len) throws IOException {
    ByteBuffer head = ByteBuffer.wrap(buf, 0, count);
    ByteBuffer body = ByteBuffer.wrap(b, off, len);
    ByteBuffer[] bufs = {head, body};
    long parkNanos = 0;
    while (head.hasRemaining() || body.hasRemaining()) {
      long n = gather.write(bufs);
      if (n > 0) {
        parkNanos = 0;
        if (!head.hasRemaining()) {
          count = 0;
        }
        continue;
      }
      if (parkNanos == 0) {
        parkNanos = 100_000;
      } else if (parkNanos < 2_000_000) {
        parkNanos *= 2;
      }
      java.util.concurrent.locks.LockSupport.parkNanos(parkNanos);
    }
    count = 0;
  }

  /**
   * Writes the specified byte to this buffered output stream.
   * @param b the byte to be written.
   * @throws IOException if an I/O error occurs.
   */
  @Override
  public void write(int b) throws IOException {
    if (count >= buf.length) {
      flushBuffer();
      if (buf.length == 0) {
        buf = new byte[bufferSize];
      }
    }
    buf[count++] = (byte) b;
  }

  /**
   * Writes {@code len} bytes from the specified byte array
   * starting at offset {@code off} to this buffered output stream.
   * <p> Ordinarily this method stores bytes from the given array into this
   * stream's buffer, flushing the buffer to the underlying output stream as
   * needed. If the requested length is at least as large as this stream's
   * buffer, however, then this method will flush the buffer and write the
   * bytes directly to the underlying output stream. Thus redundant
   * {@code BufferedOutputStream}s will not copy data unnecessarily.
   * @param b the data.
   * @param off the start offset in the data.
   * @param len the number of bytes to write.
   * @throws IOException if an I/O error occurs.
   * @throws IndexOutOfBoundsException {@inheritDoc}
   */
  @Override
  public void write(byte[] b, int off, int len) throws IOException {
    // compare against the configured size, not buf.length: the buffer is
    // allocated lazily and may still be empty here
    if (len >= bufferSize) {
      /*
       * If the request length exceeds the max size of the output buffer,
       * flush the output buffer and then write the data directly.
       * In this way buffered streams will cascade harmlessly.
       */
      if (count > 0 && gather != null) {
        // writev: the pending head bytes (status line + headers) and
        // this direct body chunk leave as ONE gathering write instead
        // of a flush syscall plus a body syscall. only plain h1
        // connections carry a gather target; everyone else cascades
        // through the buffered path below
        gatherPendingPlus(b, off, len);
        return;
      }
      flushBuffer();
      out.write(b, off, len);
      return;
    }
    if (buf.length == 0) {
      buf = new byte[bufferSize];
    }
    if (len > buf.length - count) {
      flushBuffer();
    }
    System.arraycopy(b, off, buf, count, len);
    count += len;
  }

  /**
   * Flushes this buffered output stream. This forces any buffered
   * output bytes to be written out to the underlying output stream.
   * @throws IOException if an I/O error occurs.
   * @see java.io.FilterOutputStream#out
   */
  @Override
  public void flush() throws IOException {
    flushBuffer();
    out.flush();
  }

  /**
   * Close: move any buffered bytes into the underlying stream WITHOUT
   * flushing it - the underlying close performs the final flush.
   * FilterOutputStream's close would flush() first, which for the h2
   * stream buffer propagates through Http2OutputStream.flush() to a
   * connection socket flush and splits every response into two writes
   * (HEADERS+DATA, then END_STREAM) - one extra syscall per response.
   */
  @Override
  public void close() throws IOException {
    try {
      flushBuffer();
    } finally {
      // the underlying stream must close even when the flush fails:
      // a FileOutputStream skipped here (disk full mid-multipart)
      // leaks its fd until the GC cleaner runs
      out.close();
    }
  }

  /**
   * Shrink the internal buffer for an idle connection: the 16KB write
   * buffer is released. the next write re-allocates on demand. only call
   * when no write is in progress (the connection is idle and not in a
   * request) - buffered data (count > 0) would otherwise be lost.
   */
  public void shrinkBuffers() {
    if (count == 0) {
      buf = EMPTY;
    }
  }

}
