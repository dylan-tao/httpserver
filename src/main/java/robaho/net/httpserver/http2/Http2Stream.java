package robaho.net.httpserver.http2;
import robaho.net.httpserver.internal.NoSyncBufferedOutputStream;
import robaho.net.httpserver.internal.OptimizedHeaders;

import com.sun.net.httpserver.Headers;
import robaho.net.httpserver.ServerConfig;
import robaho.net.httpserver.http2.frame.BaseFrame;
import robaho.net.httpserver.http2.frame.DataFrame;
import robaho.net.httpserver.http2.frame.DataFrameBodyPool;
import robaho.net.httpserver.http2.frame.FrameFlag;
import robaho.net.httpserver.http2.frame.FrameFlag.FlagSet;
import robaho.net.httpserver.http2.frame.FrameHeader;
import robaho.net.httpserver.http2.frame.FrameType;
import robaho.net.httpserver.http2.frame.ResetStreamFrame;
import robaho.net.httpserver.http2.frame.SettingIdentifier;
import robaho.net.httpserver.http2.frame.SettingParameter;
import robaho.net.httpserver.http2.frame.WindowUpdateFrame;
import robaho.net.httpserver.http2.hpack.HPackContext;

import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

public class Http2Stream {

    private final int streamId;

    // needs to be accessible for connection to adjust based on SettingsFrame
    final AtomicLong sendWindow = new AtomicLong(SettingParameter.INITIAL_WINDOW_SIZE);
    private final AtomicLong receiveWindow = new AtomicLong(SettingParameter.INITIAL_WINDOW_SIZE);
    private final int initialWindowSize;

    private final Http2Connection connection;
    private final Logger logger;
    private final robaho.net.httpserver.internal.NoSyncBufferedOutputStream outputStream;
    private final DataIn dataIn;
    private final Http2Connection.StreamHandler handler;
    private final Headers requestHeaders;
    private final Headers responseHeaders = new OptimizedHeaders(16);
    private final AtomicBoolean headersSent = new AtomicBoolean(false);

    private volatile Thread thread;
    private volatile boolean streamOpen = true;
    // halfClosed is set when a END_STREAM is received. The streams are bidirectional.
    private volatile boolean halfClosed = false;
    // streamOutputClosed is when the handler, either via close(), or sendResponseHeaders(code,-1) closes the output
    private volatile boolean streamOutputClosed = false;
    // set when the peer sent RST_STREAM: no further frames may be sent for this stream
    private volatile boolean rstReceived = false;
    private final AtomicBoolean handlingRequest = new AtomicBoolean(false);
    /** the handler thread currently blocked writing (flow control); woken
     * when a WINDOW_UPDATE or a SETTINGS window growth arrives so it does
     * not have to wait out the 50ms safety-net park */
    private volatile Thread writerThread;

    /** stream window credit for PADDED octets, accumulated by the reader thread
     * (which discards them on receipt) and folded into the next WINDOW_UPDATE
     * by the handler thread as it consumes the data */
    private final java.util.concurrent.atomic.AtomicLong pendingPadding = new java.util.concurrent.atomic.AtomicLong();

    private long dataInSize = 0;

    public Http2Stream(int streamId, Http2Connection connection, Headers requestHeaders, Http2Connection.StreamHandler handler) throws IOException {
        this.streamId = streamId;
        this.connection = connection;
        this.logger = connection.logger;
        this.requestHeaders = requestHeaders;
        this.handler = handler;
        this.dataIn = new DataIn();
        // the stream level buffer bounds the DATA frame size: use 16KB (the
        // default max frame size) so small writes coalesce into full frames
        // instead of 1KB frames with per frame header overhead
        this.outputStream = new NoSyncBufferedOutputStream(new Http2OutputStream(streamId), connection.pollStreamBuffer());
        var setting = connection.getRemoteSettings().get(SettingIdentifier.SETTINGS_INITIAL_WINDOW_SIZE);
        if(setting!=null) {
            sendWindow.set((int)(setting.value));
        }
        setting = connection.getLocalSettings().get(SettingIdentifier.SETTINGS_INITIAL_WINDOW_SIZE);
        if(setting!=null) {
            receiveWindow.set((int)(setting.value));
            initialWindowSize = (int)setting.value;
        } else {
            initialWindowSize = (int) SettingParameter.INITIAL_WINDOW_SIZE;
        }
        if(logger.isLoggable(Level.TRACE)) {
            logger.log(Level.TRACE,() -> "new stream, send window size "+sendWindow.get()+", receive window size "+receiveWindow.get()+" on stream "+streamId);
        }
    }

    public Headers getRequestHeaders() {
        return requestHeaders;
    }

    public Headers getResponseHeaders() {
        return responseHeaders;
    }

    @Override
    public String toString() {
        return connection.httpConnection.toString()+" stream "+streamId;
    }

    public void debug() {
        logger.log(Level.INFO,connection.toString()+", stream "+streamId+" open "+streamOpen+" half closed "+halfClosed+", streamOutputClosed "+streamOutputClosed+", thread "+thread);
        logger.log(Level.INFO, connection +", stream "+streamId+" data in size "+dataInSize+" expected "+expectedSize());
        // thread is assigned inside the executor lambda: a stream reset
        // before dispatch (or registered-but-not-yet-dispatched) has null
        // here, and an NPE would abort the whole /debug dump
        if (thread != null) {
            logger.log(Level.INFO, Arrays.toString(thread.getStackTrace()));
        }
    }

    public boolean isOpen() {
        return streamOpen;
    }

    public boolean isHalfClosed() {
        return halfClosed;
    }

    public SSLSession getSSLSession() {
        return connection.httpConnection.getSSLSession();
    }

    private long expectedSize() {
        String value = requestHeaders.getFirst("Content-length");
        if (value != null) {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException e) {
                // an invalid length forces a mismatch on END_STREAM so the
                // stream is reset, instead of an uncaught NumberFormatException
                logger.log(Level.DEBUG, "invalid content-length {0} on stream {1}", value, streamId);
                return -2;
            }
        }
        return -1;
    }

    public void close() {
        streamOpen = false;

        // remove() returns the removed stream (null when already removed):
        // the first closer wins, later closes return - same guard the old
        // null-tombstone put provided
        if(connection.http2Streams.remove(streamId)==null) {
            return;
        }

        if (rstReceived) {
            // the peer reset the stream: do NOT flush the stream buffer.
            // flushing would route the buffered bytes through write(), whose
            // rstReceived guard throws - and the catch below escalated that
            // into a whole-connection close. the peer cancelled: the bytes
            // are unwanted (RFC 7540 5.1). also do NOT detach/return the
            // buffer: this runs on the reader thread and a handler thread
            // may be mid-write into it (the arraycopy would hit the swapped
            // EMPTY buffer or a pooled buffer now owned by another stream).
            // the stream object - buffer included - becomes garbage when
            // the handler exits.
            try {
                dataIn.close();
            } catch (IOException e) {
                logger.log(Level.DEBUG, () -> "error closing dataIn on reset stream " + streamId);
            }
            if (thread != null) {
                thread.interrupt();
            }
            return;
        }

        logger.log(Level.TRACE,() -> "closing stream "+streamId);

        try {
            dataIn.close();
            outputStream.close();
            if(thread!=null)
                thread.interrupt();
        } catch (IOException e) {
            if(!connection.isClosed()) {
                connection.close();
                logger.log(connection.httpConnection.requestCount.get()>0 ? Level.WARNING : Level.DEBUG, "IOException closing http2 stream",e);
            }
        } finally {
            // return the stream buffer to the connection pool: sequential
            // streams on a connection reuse it instead of allocating 16KB
            // per stream (at high h2 RPS this dominates young-gen allocation)
            connection.offerStreamBuffer(outputStream.detachBuffer());
        }
    }

    /** a trailer HEADERS block was received on this open stream: its contents
     * are discarded by the connection; if it carried END_STREAM the stream
     * is half closed */
    void receiveTrailer(boolean endStream) {
        if (endStream) {
            long expected = expectedSize();
            if (expected != -1 && dataInSize != expected) {
                // RFC 7540 8.1.2.6: a content-length mismatch is a stream
                // error regardless of which frame carries END_STREAM - the
                // DATA path enforces this, the trailer path must too, or a
                // short body is silently treated as the complete request
                try {
                    resetAndClose(Http2ErrorCode.PROTOCOL_ERROR);
                } catch (IOException e) {
                    logger.log(Level.DEBUG, () -> "error resetting stream " + streamId + " on content-length mismatch", e);
                }
                return;
            }
            halfClosed = true;
            dataIn.wakeupReader();
        }
    }

    /** reset the stream and close it. ordering invariants: rstReceived must
     * be set before close() - close() flushes the stream buffer and must not
     * emit frames for a stream the server just reset (RFC 7540 5.1 closed
     * state); halfClosed must be set before close() so the data input does
     * not block waiting for more data on a reset stream */
    private void resetAndClose(Http2ErrorCode code) throws IOException {
        connection.sendResetStream(code, streamId);
        rstReceived = true;
        halfClosed = true;
        close();
    }

    public void processFrame(BaseFrame frame) throws Http2Exception, IOException {

        switch (frame.getHeader().type()) {
        case HEADERS:
        case CONTINUATION:
            if(halfClosed) {
                // stream error (RFC 7540 5.1): reset the stream, not the connection
                connection.sendResetStream(Http2ErrorCode.STREAM_CLOSED, streamId);
                return;
            }
            halfClosed = frame.getHeader().flags().contains(FrameFlag.END_STREAM);
            performRequest();
            break;
        case DATA:
            DataFrame dataFrame = (DataFrame) frame;
            if(logger.isLoggable(Level.TRACE)) {
                logger.log(Level.TRACE,()->"received data frame, length "+dataFrame.body.length+" on stream "+streamId);
            }
            if(halfClosed) {
                connection.sendResetStream(Http2ErrorCode.STREAM_CLOSED, streamId);
                return;
            }
            if(!streamOpen) {
                connection.sendResetStream(Http2ErrorCode.PROTOCOL_ERROR, streamId);
                return;
            }
            // stream level flow control accounting happens on receipt and uses
            // the full frame payload length (including PADDED bytes, RFC 7540 6.9);
            // the window is re-opened as the handler consumes the data
            if (receiveWindow.addAndGet(-frame.getHeader().length()) < 0) {
                // the peer sent beyond the stream flow control window
                resetAndClose(Http2ErrorCode.FLOW_CONTROL_ERROR);
                return;
            }
            // the padding octets are discarded on receipt (DataFrame.parse strips
            // them): queue their window credit so the handler's read folds them
            // into the next WINDOW_UPDATE - without this a padded upload
            // permanently leaks the stream window and deadlocks (RFC 7540 6.9)
            int padding = frame.getHeader().length() - dataFrame.body.length;
            if (padding > 0) {
                pendingPadding.addAndGet(padding);
            }
            dataIn.enqueue(dataFrame.body);
            dataInSize += dataFrame.body.length;
            // opt-in request body cap, enforced incrementally for bodies
            // without a declared Content-Length (a declared length is
            // rejected up front at dispatch with a 413)
            long bodySizeLimit = ServerConfig.getMaxRequestBodySize();
            if (bodySizeLimit >= 0 && dataInSize > bodySizeLimit) {
                resetAndClose(Http2ErrorCode.ENHANCE_YOUR_CALM);
                return;
            }
            if (dataFrame.getHeader().flags().contains(FrameFlag.END_STREAM)) {
                long expected = expectedSize();
                if(expected!=-1 && dataInSize!=expected) {
                    resetAndClose(Http2ErrorCode.PROTOCOL_ERROR);
                    break;
                }
                halfClosed = true;
                dataIn.wakeupReader();
            }
            break;
        case RST_STREAM:
            ResetStreamFrame resetFrame = (ResetStreamFrame) frame;
            logger.log(Level.DEBUG, () -> "received reset stream " + resetFrame.errorCode + ", on stream " + streamId);
            halfClosed = true;
            rstReceived = true;
            close();
            break;
        case WINDOW_UPDATE:
            int windowSizeIncrement = ((WindowUpdateFrame)frame).getWindowSizeIncrement();
            if(sendWindow.addAndGet(windowSizeIncrement)> Integer.MAX_VALUE) {
                connection.sendResetStream(Http2ErrorCode.FLOW_CONTROL_ERROR, streamId);
                close();
            }
            // wake a writer blocked on the send window immediately
            wakeupWriter();
            if(logger.isLoggable(Level.DEBUG)) {
                logger.log(Level.DEBUG, () -> "received window update " + windowSizeIncrement + ", new size " + sendWindow.get() + ", on stream " + streamId);
            }
            break;
        default:
            break;
        }
    }

    /** wake the handler thread if it is parked waiting for a flow-control
     * window. three callers: this stream's own WINDOW_UPDATE (processFrame),
     * connection-level WINDOW_UPDATE credit (a writer parked on the
     * connection window never sees its own stream's update, because its data
     * cannot be sent and so never generates client credit), and a
     * SETTINGS_INITIAL_WINDOW_SIZE growth */
    void wakeupWriter() {
        LockSupport.unpark(writerThread);
    }

    private void performRequest() throws IOException, Http2Exception {
        if(!handlingRequest.compareAndSet(false, true)) {
            throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR,"already received headers for stream "+streamId);
        }
        connection.httpConnection.requestCount.incrementAndGet();
        connection.requestsInProgress.incrementAndGet();
        if (Http2Stats.ENABLED) connection.stats.activeStreams.incrementAndGet();

        InputStream in = halfClosed ? InputStream.nullInputStream() : dataIn;

        handler.getExecutor().execute(() -> {
                thread = Thread.currentThread();
                try {
                    handler.handleStream(this,in,outputStream);
                } catch (IOException ex) {
                    logger.log(Level.DEBUG,"io exception on stream "+streamId,ex);
                } catch (Exception ex) {
                    logger.log(Level.WARNING,"unexpected exception on stream "+streamId,ex);
                } finally {
                    // close the stream even if the handler threw or forgot to
                    // close the exchange, so counters are decremented and the
                    // peer is not left waiting for END_STREAM
                    close();
                }
        });
    }
    /**
     * @param closeStream if true the output stream is closed, and any attempts
     * to write data to the stream will fail. This is an optimization that
     * allows the CLOSE_STREAM bit to be set in the Headers frame, reducing the
     * packet count.
     */
    public void writeResponseHeaders(boolean closeStream) throws IOException {
        if (headersSent.compareAndSet(false, true)) {
            try {
                // encode OUTSIDE the connection lock: the encoding is
                // stream-local, only the frame writes need mutual exclusion.
                // this shrinks the per-response critical section ~10x so
                // concurrent streams scale with cores
                var frames = HPackContext.encodeHeaderFrames(responseHeaders, streamId, closeStream,
                    connection.remoteMaxFrameSize());
                connection.lock();
                try {
                    for (byte[] frame : frames) {
                        connection.outputStream.write(frame);
                    }
                    if (closeStream) {
                        streamOutputClosed = true;
                    }
                } finally {
                    connection.unlock();
                }
            } catch (IOException e) {
                // the response could not be encoded (e.g. missing :status):
                // fail this stream rather than leaving the request hanging
                connection.sendResetStream(Http2ErrorCode.INTERNAL_ERROR, streamId);
                throw e;
            }
        }
    }

    public InetSocketAddress getLocalAddress() {
        return connection.httpConnection.getLocalAddress();
    }

    public InetSocketAddress getRemoteAddress() {
        return connection.httpConnection.getRemoteAddress();
    }

    class Http2OutputStream extends OutputStream {
        private static final FlagSet END_STREAM = FlagSet.of(FrameFlag.END_STREAM);

        private final int streamId;
        /** CAS-guarded: the handler thread and the reader thread (RST_STREAM)
         * race into close(); only the winner may run the close body and
         * decrement requestsInProgress */
        private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();

        public Http2OutputStream(int streamId) {
            this.streamId = streamId;
        }

        /** flush the connection buffer under the connection lock */
        private void flushConnection() throws IOException {
            connection.lock();
            try {
                connection.outputStream.flush();
                if (Http2Stats.ENABLED) connection.stats.flushes.incrementAndGet();
            } finally {
                connection.unlock();
            }
        }

        /** scratch for the single-byte write: avoids a byte[1] allocation
         * per call on the SSE-style handler path */
        private final byte[] single = new byte[1];

        @Override
        public void write(int b) throws IOException {
            single[0] = (byte) b;
            write(single, 0, 1);
        }

        @Override
        public void write(byte[] b) throws IOException {
            write(b, 0, b.length);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (Http2Stats.ENABLED) connection.stats.bytesSent.addAndGet(len);
            // test outside of lock so other streams can progress. the park is
            // bounded only as a safety net: a WINDOW_UPDATE (or stream close)
            // unparks the writer immediately. writerThread is recorded only
            // when parking: a volatile store on EVERY write() call was pure
            // overhead. the re-check after recording closes the
            // check-then-record race - a WINDOW_UPDATE landing between the
            // loop condition and the assignment unparks a stale writerThread
            // and would otherwise cost a full park despite the open window.
            // the 50ms bound is pure insurance for unknown races: both park
            // sites are woken by WINDOW_UPDATE (stream-level unparks this
            // writer, connection-level unparks every writer)
            while(sendWindow.get()<=0 && !connection.isClosed() && !rstReceived) {
                writerThread = Thread.currentThread();
                if (sendWindow.get()>0) {
                    break;
                }
                if (Http2Stats.ENABLED) connection.stats.pauses.incrementAndGet();
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(50));
            }
            if (rstReceived) {
                throw new IOException("stream " + streamId + " was reset by the peer");
            }
            // volatile-read fast path: after the headers are long sent, the
            // CAS in writeResponseHeaders ran per DATA frame for nothing
            if (!headersSent.get()) {
                writeResponseHeaders(false);
            }
            if(streamOutputClosed) {
                throw new IOException("output stream was closed during headers send");
            }
            while(len>0) {
                long available = Math.min(connection.sendWindow.get(), sendWindow.get());
                if (available > Integer.MAX_VALUE) {
                    // a window at or above 2^31 is an overflow state pending
                    // connection teardown: clamp instead of letting the int
                    // cast wrap negative (a negative _len with a positive
                    // long re-check hot-spins this loop without ever parking)
                    available = Integer.MAX_VALUE;
                }
                final int _len = (int) Math.min(Math.min(len, connection.remoteMaxFrameSize()), available);
                if(_len<=0) {
                    if (rstReceived) {
                        // the pre-loop check cannot see a reset that lands
                        // mid-write: without this check the interrupt from
                        // close() makes the 50ms park return immediately
                        // (sticky interrupt) and the writer spins hot, then
                        // writes DATA to a reset stream once credit arrives
                        throw new IOException("stream " + streamId + " was reset by the peer");
                    }
                    // record before parking: the pre-loop record only runs
                    // when the STREAM window is exhausted, but a writer can
                    // park here on the CONNECTION window (exhausted by other
                    // streams) with a healthy stream window - without this
                    // record neither the stream-level nor the connection-level
                    // WINDOW_UPDATE can find this thread to unpark it
                    writerThread = Thread.currentThread();
                    // counted BEFORE the re-check: a connection WINDOW_UPDATE
                    // processed after this point sees the count and wakes this
                    // writer; one processed before it is caught by the
                    // re-check. the count gates the connection WU handler's
                    // wake-all loop
                    connection.parkedWriters.incrementAndGet();
                    try {
                        // re-check with AND (the exact negation of the _len<=0
                        // condition): with OR, a writer whose stream window is
                        // healthy but whose connection window is exhausted would
                        // spin hot - continue recomputes _len to 0 and re-enters
                        // this branch without ever parking
                        if (rstReceived) {
                            throw new IOException("stream " + streamId + " was reset by the peer");
                        }
                        if (connection.sendWindow.get()>0 && sendWindow.get()>0) {
                            // credit landed between the _len computation and the
                            // record: retry immediately instead of parking
                            continue;
                        }
                        if (Http2Stats.ENABLED) connection.stats.pauses.incrementAndGet();
                        flushConnection();
                        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(50));
                        if(connection.isClosed()) {
                            throw new IOException("connection closed");
                        }
                    } finally {
                        connection.parkedWriters.decrementAndGet();
                    }
                    continue;
                }
                if(connection.sendWindow.addAndGet(-_len)<0) {
                    // if we can't get the space from the connection window, need to retry
                    connection.sendWindow.addAndGet(_len);
                    continue;
                }
                if (rstReceived) {
                    // a reset can land mid-write while the windows are
                    // healthy: do not send DATA on a reset stream
                    // (RFC 7540 5.1 closed state)
                    connection.sendWindow.addAndGet(_len);
                    throw new IOException("stream " + streamId + " was reset by the peer");
                }
                connection.lock();
                try {
                    // header through the per-connection scratch (lock-held):
                    // one 9-byte allocation per DATA frame saved
                    FrameHeader.encodeInto(connection.frameHeaderScratch, _len, FrameType.DATA, FrameFlag.NONE, streamId);
                    connection.outputStream.write(connection.frameHeaderScratch, 0, FrameHeader.LENGTH);
                    connection.outputStream.write(b,off,_len);
                    if (Http2Stats.ENABLED) connection.stats.framesSent.incrementAndGet();
                    if (ServerConfig.http2DisableFlushDelay()) {
                        connection.outputStream.flush();
                        if (Http2Stats.ENABLED) connection.stats.flushes.incrementAndGet();
                    }
                } finally {
                    connection.unlock();
                }
                off+=_len;
                len-=_len;
                sendWindow.addAndGet(-_len);
                if(logger.isLoggable(Level.TRACE)) {
                    logger.log(Level.TRACE,() -> "sent data frame, length "+_len+", new send window "+sendWindow.get()+" on stream "+streamId);
                }
            }
        }
        @Override
        public void flush() throws IOException {
            // an explicit flush must push data out of the connection level
            // buffer; otherwise small events (e.g. SSE) can stay buffered
            // indefinitely when no other traffic fills the buffer
            flushConnection();
        }
        @Override
        public void close() throws IOException {
            // CAS (not a monitor): the handler thread and the connection
            // reader (on RST_STREAM) can race here, and only the winner may
            // decrement requestsInProgress. a synchronized method would also
            // pin a virtual thread's carrier while writing frames (JEP 444)
            if(!closed.compareAndSet(false, true)) return;
            try {
                if(connection.isClosed()) {
                    if(headersSent.compareAndSet(false,true)) {
                        logger.log(Level.WARNING,"stream connection is closed and headers not sent on stream "+streamId);
                    }
                    // still release the in-progress count: a leaked increment
                    // would stop the final flush from ever firing
                    connection.requestsInProgress.decrementAndGet();
                    return;
                }
                if(rstReceived) {
                    // the peer reset this stream: no further frames may be
                    // sent for it (RFC 7540 5.1, closed state)
                    connection.requestsInProgress.decrementAndGet();
                    return;
                }
                writeResponseHeaders(false);
                connection.lock();
                boolean lastRequest = connection.requestsInProgress.decrementAndGet() == 0;
                try {
                    if(!streamOutputClosed) {
                        FrameHeader.encodeInto(connection.frameHeaderScratch, 0, FrameType.DATA, END_STREAM, streamId);
                        connection.outputStream.write(connection.frameHeaderScratch, 0, FrameHeader.LENGTH);
                        if (Http2Stats.ENABLED) connection.stats.framesSent.incrementAndGet();
                    }
                    if(lastRequest) {
                        connection.outputStream.flush();
                        if (Http2Stats.ENABLED) connection.stats.flushes.incrementAndGet();
                    }
                } finally {
                    connection.unlock();
                }
                dataIn.close();
            } finally {
                if (Http2Stats.ENABLED) connection.stats.activeStreams.decrementAndGet();
                Http2Stream.this.close();
            }
        }
    }

    // the data InputStream passed to handlers
    private class DataIn extends InputStream {
        private final ConcurrentLinkedQueue<byte[]> queue = new ConcurrentLinkedQueue<>();
        private volatile Thread reader;
        /** offset into the top of the queue array */
        private int offset = 0;
        /** set when the stream is closed; blocked and future reads return -1 */
        private volatile boolean closed = false;
        /** bytes consumed by the handler but not yet returned to the peer's
         * window; sent as WINDOW_UPDATE once it reaches half the window */
        private long pendingWindowUpdate = 0;

        public DataIn() {
        }

        void enqueue(byte[] data) {
            queue.add(data);
            LockSupport.unpark(reader);
        }

        void wakeupReader() {
            LockSupport.unpark(reader);
        }

        @Override
        public void close() throws IOException {
            // do NOT drain the remaining data here: reading would park forever
            // when the peer has not sent END_STREAM yet (and the close may be
            // called from the connection reader thread, which would wedge the
            // whole connection). Just mark closed and wake any blocked reader.
            closed = true;
            LockSupport.unpark(reader);
        }

        private final byte[] single = new byte[1];

        @Override
        public int read() throws IOException {
            int n = read(single, 0, 1);
            return n == -1 ? -1 : single[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int read = 0;
            try {
                // recorded at method entry: dataIn.close() unparks this field,
                // and assigning it only before the park opened a lost-unpark
                // window (close between the flag check and the assignment
                // unparks a stale reader; park() has no timeout to self-heal)
                reader = Thread.currentThread();
                while (len>0) {
                    byte[] data;
                    while((data=queue.peek())==null) {
                        if(read>0) {
                            return read;
                        }
                        if(closed || halfClosed) {
                            // the producer enqueues BEFORE setting halfClosed, so
                            // data may have arrived together with the flag: re-check
                            // the queue or the final body bytes are stranded behind
                            // the EOF (check-then-act race)
                            data = queue.peek();
                            if (data != null) {
                                break;
                            }
                            return -1;
                        }
                        LockSupport.park();
                        if(Thread.interrupted()) {
                            throw new IOException("interrupted");
                        }
                    }
                    int available = data.length-offset;
                    int bytesToRead = Math.min(len, available);
                    System.arraycopy(data, offset, b, off, bytesToRead);
                    offset+=bytesToRead;
                    off+=bytesToRead;
                    len-=bytesToRead;
                    available-=bytesToRead;
                    read+=bytesToRead;
                    if(available==0) { // remove top buffer from queue
                        // the sole owner (this thread) just consumed the last
                        // byte: full-size DATA bodies go back to the pool,
                        // everything else is GC'd
                        DataFrameBodyPool.release(queue.poll());
                        offset=0;
                    }
                }
                return read;
            } finally {
                if(read>0) {
                    // the stream window is debited on frame receipt (processFrame);
                    // re-open it as the handler consumes the queued data. the
                    // padding credit (folded here) keeps padded uploads from
                    // permanently leaking the window. plain read first: most
                    // reads carry no padding and the getAndSet RMW is skipped
                    long padding = pendingPadding.get();
                    if (padding != 0) {
                        padding = pendingPadding.getAndSet(0);
                    }
                    long credited = read + padding;
                    receiveWindow.addAndGet(credited);
                    pendingWindowUpdate += credited;
                    if(pendingWindowUpdate >= initialWindowSize/2) {
                        connection.lock();
                        try {
                            WindowUpdateFrame frame = new WindowUpdateFrame(streamId, (int)pendingWindowUpdate);
                            frame.writeTo(connection.outputStream);
                            // flush: the peer may have exhausted its stream window
                            // and be blocked waiting for exactly this update -
                            // without the flush the frame sits in the connection
                            // buffer until the 1s idle ping releases it, which
                            // throttles a single-stream upload to ~window/second
                            connection.outputStream.flush();
                            logger.log(Level.TRACE, () -> "sent stream window update, receive window "+receiveWindow.get()+" on stream "+streamId);
                        } finally {
                            connection.unlock();
                        }
                        pendingWindowUpdate = 0;
                    }
                }
            }
        }
    }
}
