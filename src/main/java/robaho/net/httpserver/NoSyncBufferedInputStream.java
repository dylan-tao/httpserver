package robaho.net.httpserver;

import java.io.FilterInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * simple buffered input stream with no synchronization. mark/reset are not supported.
 */
public class NoSyncBufferedInputStream extends FilterInputStream {

    private static final byte[] EMPTY = new byte[0];

    private final int bufferSize;

    /** lazily allocated on first fill (connections that never read - e.g.
     * port probes - never pay for a buffer); null once closed. 16KB default:
     * large enough that a typical request (headers + small body) is read with
     * a single fill, small enough for many connections */
    protected byte[] buf = EMPTY;

    /**
     * The index one greater than the index of the last valid byte in
     * the buffer.
     * This value is always
     * in the range {@code 0} through {@code buf.length};
     * elements {@code buf[0]} through {@code buf[count-1]}
     * contain buffered input data obtained
     * from the underlying  input stream.
     */
    protected int count;

    /**
     * The current position in the buffer. This is the index of the next
     * byte to be read from the {@code buf} array.
     * <p>
     * This value is always in the range {@code 0}
     * through {@code count}. If it is less
     * than {@code count}, then  {@code buf[pos]}
     * is the next byte to be supplied as input;
     * if it is equal to {@code count}, then
     * the  next {@code read} or {@code skip}
     * operation will require more bytes to be
     * read from the contained  input stream.
     *
     * @see     java.io.BufferedInputStream#buf
     */
    protected int pos;

    /**
     * Check to make sure that underlying input stream has not been
     * nulled out due to close; if not return it;
     */
    private InputStream getInIfOpen() throws IOException {
        InputStream input = in;
        if (input == null)
            throw new IOException("Stream closed");
        return input;
    }

    /**
     * Throws IOException if the stream is closed (buf is null).
     */
    private void ensureOpen() throws IOException {
        if (buf == null) {
            throw new IOException("Stream closed");
        }
    }

    /**
     * Throws IOException if the stream is closed (buf is null).
     */
    private byte[] getBufIfOpen() throws IOException {
        if (buf == null) {
            throw new IOException("Stream closed");
        }
        return buf;
    }


    public NoSyncBufferedInputStream(InputStream in) {
        this(in, 16384);
    }

    public NoSyncBufferedInputStream(InputStream in, int size) {
        super(in);
        if (size <= 0) {
            throw new IllegalArgumentException("buffer size " + size);
        }
        this.bufferSize = size;
    }

    /**
     * Fills the buffer with more data.
     * This method also assumes that all data has already been read in,
     * hence pos > count.
     */
    private void fill() throws IOException {
        pos = 0;
        count = 0;
        byte[] buffer = getBufIfOpen();
        if (buffer.length == 0) {
            buffer = new byte[bufferSize];
            buf = buffer;
        }
        int n = getInIfOpen().read(buffer);
        if (n > 0)
            count = n;
    }

    /** reusable line buffer for {@link #readLine}: grown on demand. shrunk at
     * the start of each read when a previous long line grew it past 8KB, so
     * one huge line does not pin that memory for the connection's lifetime */
    private byte[] lineBuf;

    /**
     * Bulk line read for the request/header parsers: consumes bytes up to and
     * including the next LF, copying them into a reusable internal buffer -
     * one scan and one arraycopy per line instead of a per-byte read chain.
     * Returns the line length (excluding the LF), or -1 at EOF (partial line
     * content at EOF is discarded, matching the old per-byte readers which
     * cleared their builder). CR bytes are copied verbatim: the request-line
     * and header parsers apply different CR rules. Throws IOException when
     * the line exceeds {@code maxLen}.
     */
    int readLine(int maxLen) throws IOException {
        if (lineBuf == null) {
            lineBuf = new byte[256];
        } else if (lineBuf.length > 8192) {
            lineBuf = new byte[8192];
        }
        int lineLen = 0;
        while (true) {
            byte[] b = getBufIfOpen();
            int lf = -1;
            for (int i = pos; i < count; i++) {
                if (b[i] == '\n') {
                    lf = i;
                    break;
                }
            }
            int chunkEnd = lf >= 0 ? lf : count;
            int avail = chunkEnd - pos;
            if (avail > 0) {
                if (lineLen + avail > maxLen) {
                    throw new IOException("line too long (limit " + maxLen + " bytes)");
                }
                if (lineBuf.length < lineLen + avail) {
                    lineBuf = java.util.Arrays.copyOf(lineBuf, Math.max(lineBuf.length * 2, lineLen + avail));
                }
                System.arraycopy(b, pos, lineBuf, lineLen, avail);
                lineLen += avail;
                pos = chunkEnd;
            }
            if (lf >= 0) {
                pos = lf + 1;
                return lineLen;
            }
            // no LF buffered: refill and keep scanning
            fill();
            if (count <= 0) {
                return -1;
            }
        }
    }

    /** the buffer holding the last readLine result; valid until the next
     * readLine call on this stream */
    byte[] lineBuffer() {
        return lineBuf;
    }

    /**
     * See
     * the general contract of the {@code read}
     * method of {@code InputStream}.
     *
     * @return     the next byte of data, or {@code -1} if the end of the
     *             stream is reached.
     * @throws     IOException  if this input stream has been closed by
     *                          invoking its {@link #close()} method,
     *                          or an I/O error occurs.
     * @see        java.io.FilterInputStream#in
     */
    public int read() throws IOException {
        if (pos >= count) {
            fill();
            if (pos >= count)
                return -1;
        }
        return getBufIfOpen()[pos++] & 0xff;
    }

    /**
     * Read bytes into a portion of an array, reading from the underlying
     * stream at most once if necessary.
     */
    private int read1(byte[] b, int off, int len) throws IOException {
        int avail = count - pos;
        if (avail <= 0) {
            // compare against the configured size, not buf.length: the buffer
            // is allocated lazily and may still be empty here
            if (len >= bufferSize) {
                return getInIfOpen().read(b, off, len);
            }
            fill();
            avail = count - pos;
            if (avail <= 0) return -1;
        }
        int cnt = (avail < len) ? avail : len;
        System.arraycopy(getBufIfOpen(), pos, b, off, cnt);
        pos += cnt;
        return cnt;
    }

    /**
     * Reads bytes from this byte-input stream into the specified byte array,
     * starting at the given offset.
     *
     * <p> This method implements the general contract of the corresponding
     * {@link InputStream#read(byte[], int, int) read} method of
     * the {@link InputStream} class.  As an additional
     * convenience, it attempts to read as many bytes as possible by repeatedly
     * invoking the {@code read} method of the underlying stream.  This
     * iterated {@code read} continues until one of the following
     * conditions becomes true: <ul>
     *
     *   <li> The specified number of bytes have been read,
     *
     *   <li> The {@code read} method of the underlying stream returns
     *   {@code -1}, indicating end-of-file, or
     *
     *   <li> The {@code available} method of the underlying stream
     *   returns zero, indicating that further input requests would block.
     *
     * </ul> If the first {@code read} on the underlying stream returns
     * {@code -1} to indicate end-of-file then this method returns
     * {@code -1}.  Otherwise, this method returns the number of bytes
     * actually read.
     *
     * <p> Subclasses of this class are encouraged, but not required, to
     * attempt to read as many bytes as possible in the same fashion.
     *
     * @param      b     destination buffer.
     * @param      off   offset at which to start storing bytes.
     * @param      len   maximum number of bytes to read.
     * @return     the number of bytes read, or {@code -1} if the end of
     *             the stream has been reached.
     * @throws     IOException  if this input stream has been closed by
     *                          invoking its {@link #close()} method,
     *                          or an I/O error occurs.
     * @throws     IndexOutOfBoundsException {@inheritDoc}
     */
    public int read(byte[] b, int off, int len) throws IOException {
        ensureOpen();
        if ((off | len | (off + len) | (b.length - (off + len))) < 0) {
            throw new IndexOutOfBoundsException();
        } else if (len == 0) {
            return 0;
        }

        int n = 0;
        for (;;) {
            int nread = read1(b, off + n, len - n);
            if (nread <= 0)
                return (n == 0) ? nread : n;
            n += nread;
            if (n >= len)
                return n;
            // if not closed but no bytes available, return
            InputStream input = in;
            if (input != null && input.available() <= 0)
                return n;
        }
    }

    /**
     * See the general contract of the {@code skip}
     * method of {@code InputStream}.
     *
     * @throws IOException  if this input stream has been closed by
     *                      invoking its {@link #close()} method,
     *                      {@code in.skip(n)} throws an IOException,
     *                      or an I/O error occurs.
     */
    public long skip(long n) throws IOException {
        ensureOpen();
        if (n <= 0) {
            return 0;
        }
        long avail = count - pos;

        if (avail <= 0) {
            // If no mark position set then don't keep in buffer
            return getInIfOpen().skip(n);
        }

        long skipped = (avail < n) ? avail : n;
        pos += (int)skipped;
        return skipped;
    }

    /**
     * Returns an estimate of the number of bytes that can be read (or
     * skipped over) from this input stream without blocking by the next
     * invocation of a method for this input stream. The next invocation might be
     * the same thread or another thread.  A single read or skip of this
     * many bytes will not block, but may read or skip fewer bytes.
     * <p>
     * @return     an estimate of the number of bytes that can be read (or skipped
     *             over) from this input stream without blocking.
     * @throws     IOException  if this input stream has been closed by
     *                          invoking its {@link #close()} method,
     *                          or an I/O error occurs.
     */
    public int available() throws IOException {
        if (in == null)
            throw new IOException("Stream closed");
        // since it is an estimate, avoid calling underlying stream if the buffer
        // has some bytes
        int n = count - pos;
        if(n>0) return n;
        return getInIfOpen().available();
    }

    /**
     * Closes this input stream and releases any system resources
     * associated with the stream.
     * Once the stream has been closed, further read(), available(), reset(),
     * or skip() invocations will throw an IOException.
     * Closing a previously closed stream has no effect.
     *
     * @throws     IOException  if an I/O error occurs.
     */
    public void close() throws IOException {
        if(buf!=null) {
            buf = null;
            super.close();
        }
    }

    @Override
    public long transferTo(OutputStream out) throws IOException {
        ensureOpen();
        int avail = count - pos;
        if (avail > 0) {
            out.write(buf, pos, avail);
            pos = count;
        }
        long total = avail;
        while (true) {
            fill();
            if (count <= 0) {
                break;
            }
            out.write(buf, 0, count);
            total += count;
        }
        return total;
    }
}
