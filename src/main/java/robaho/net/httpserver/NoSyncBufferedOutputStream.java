package robaho.net.httpserver;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * buffered output stream designed for virtual threads
 */
public class NoSyncBufferedOutputStream extends FilterOutputStream {

    private static final byte[] EMPTY = new byte[0];

    private final int bufferSize;

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
        super(out);
        if (size <= 0) {
            throw new IllegalArgumentException("buffer size " + size);
        }
        this.bufferSize = size;
    }

    /** wrap an externally owned buffer (e.g. from a pool). the caller remains
     * responsible for its lifecycle and may reclaim it with detachBuffer() */
    public NoSyncBufferedOutputStream(OutputStream out, byte[] buffer) {
        super(out);
        if (buffer == null || buffer.length == 0) {
            throw new IllegalArgumentException("empty buffer");
        }
        this.buf = buffer;
        this.bufferSize = buffer.length;
    }

    /** detach the internal buffer for reuse (e.g. return it to a pool). the
     * stream must be closed and unused afterwards: writes fail fast on the
     * empty replacement buffer */
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
     * Writes the specified byte to this buffered output stream.
     *
     * @param      b   the byte to be written.
     * @throws     IOException  if an I/O error occurs.
     */
    @Override
    public void write(int b) throws IOException {
        if (count >= buf.length) {
            flushBuffer();
            if (buf.length == 0) {
                buf = new byte[bufferSize];
            }
        }
        buf[count++] = (byte)b;
    }

    /**
     * Writes {@code len} bytes from the specified byte array
     * starting at offset {@code off} to this buffered output stream.
     *
     * <p> Ordinarily this method stores bytes from the given array into this
     * stream's buffer, flushing the buffer to the underlying output stream as
     * needed.  If the requested length is at least as large as this stream's
     * buffer, however, then this method will flush the buffer and write the
     * bytes directly to the underlying output stream.  Thus redundant
     * {@code BufferedOutputStream}s will not copy data unnecessarily.
     *
     * @param      b     the data.
     * @param      off   the start offset in the data.
     * @param      len   the number of bytes to write.
     * @throws     IOException  if an I/O error occurs.
     * @throws     IndexOutOfBoundsException {@inheritDoc}
     */
    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        // compare against the configured size, not buf.length: the buffer is
        // allocated lazily and may still be empty here
        if (len >= bufferSize) {
            /* If the request length exceeds the max size of the output buffer,
               flush the output buffer and then write the data directly.
               In this way buffered streams will cascade harmlessly. */
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
     *
     * @throws     IOException  if an I/O error occurs.
     * @see        java.io.FilterOutputStream#out
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
        flushBuffer();
        out.close();
    }
}
