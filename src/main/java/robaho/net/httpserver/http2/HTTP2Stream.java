package robaho.net.httpserver.http2;

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

import javax.net.ssl.SSLSession;

import com.sun.net.httpserver.Headers;

import robaho.net.httpserver.NoSyncBufferedOutputStream;
import robaho.net.httpserver.OptimizedHeaders;
import robaho.net.httpserver.ServerConfig;
import robaho.net.httpserver.http2.hpack.HPackContext;
import robaho.net.httpserver.http2.frame.BaseFrame;
import robaho.net.httpserver.http2.frame.DataFrame;
import robaho.net.httpserver.http2.frame.FrameFlag;
import robaho.net.httpserver.http2.frame.FrameFlag.FlagSet;
import robaho.net.httpserver.http2.frame.FrameHeader;
import robaho.net.httpserver.http2.frame.FrameType;
import robaho.net.httpserver.http2.frame.ResetStreamFrame;
import robaho.net.httpserver.http2.frame.SettingIdentifier;
import robaho.net.httpserver.http2.frame.WindowUpdateFrame;

public class HTTP2Stream {

    private final int streamId;

    // needs to be accessible for connection to adjust based on SettingsFrame
    final AtomicLong sendWindow = new AtomicLong(65535);
    private final AtomicLong receiveWindow = new AtomicLong(65535);
    private final int initialWindowSize;

    private final HTTP2Connection connection;
    private final Logger logger;
    private final OutputStream outputStream;
    private final DataIn dataIn;
    private final HTTP2Connection.StreamHandler handler;
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
    private volatile AtomicBoolean handlingRequest = new AtomicBoolean(false);
    /** the thread currently blocked writing (flow control); woken when a
     * WINDOW_UPDATE arrives so it does not have to spin on a 1ms poll */
    private volatile Thread writerThread;

    private long dataInSize = 0;

    public HTTP2Stream(int streamId, HTTP2Connection connection, Headers requestHeaders, HTTP2Connection.StreamHandler handler) throws IOException {
        this.streamId = streamId;
        this.connection = connection;
        this.logger = connection.logger;
        this.requestHeaders = requestHeaders;
        this.handler = handler;
        this.dataIn = new DataIn();
        // the stream level buffer bounds the DATA frame size: use 16KB (the
        // default max frame size) so small writes coalesce into full frames
        // instead of 1KB frames with per frame header overhead
        this.outputStream = new NoSyncBufferedOutputStream(new Http2OutputStream(streamId), 16384);
        var setting = connection.getRemoteSettings().get(SettingIdentifier.SETTINGS_INITIAL_WINDOW_SIZE);
        if(setting!=null) {
            sendWindow.set((int)(setting.value));
        }
        setting = connection.getLocalSettings().get(SettingIdentifier.SETTINGS_INITIAL_WINDOW_SIZE);
        if(setting!=null) {
            receiveWindow.set((int)(setting.value));
            initialWindowSize = (int)setting.value;
        } else {
            initialWindowSize = 65535;
        }
        if(logger.isLoggable(Level.TRACE)) {
            logger.log(Level.TRACE,() -> "new stream, send window size "+sendWindow.get()+", receive window size "+receiveWindow.get()+" on stream "+streamId);
        }
    }

    public OutputStream getOutputStream() {
        return outputStream;
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
        logger.log(Level.INFO,connection.toString()+", stream "+streamId+" data in size "+dataInSize+" expected "+expectedSize());
        logger.log(Level.INFO,""+Arrays.toString(thread.getStackTrace()));
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

        if(connection.http2Streams.put(streamId,null)==null) {
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
        }
    }

    /** a trailer HEADERS block was received on this open stream: its contents
     * are discarded by the connection; if it carried END_STREAM the stream
     * is half closed */
    void receiveTrailer(boolean endStream) {
        if (endStream) {
            halfClosed = true;
            dataIn.wakeupReader();
        }
    }

    public void processFrame(BaseFrame frame) throws HTTP2Exception, IOException {

        switch (frame.getHeader().getType()) {
        case HEADERS:
        case CONTINUATION:
            if(halfClosed) {
                // stream error (RFC 7540 5.1): reset the stream, not the connection
                connection.sendResetStream(HTTP2ErrorCode.STREAM_CLOSED, streamId);
                return;
            }
            halfClosed = frame.getHeader().getFlags().contains(FrameFlag.END_STREAM);
            performRequest();
            break;
        case DATA:
            DataFrame dataFrame = (DataFrame) frame;
            logger.log(Level.TRACE,()->"received data frame, length "+dataFrame.body.length+" on stream "+streamId);
            if(halfClosed) {
                connection.sendResetStream(HTTP2ErrorCode.STREAM_CLOSED, streamId);
                return;
            }
            if(!streamOpen) {
                connection.sendResetStream(HTTP2ErrorCode.PROTOCOL_ERROR, streamId);
                return;
            }
            // stream level flow control accounting happens on receipt and uses
            // the full frame payload length (including PADDED bytes, RFC 7540 6.9);
            // the window is re-opened as the handler consumes the data
            if (receiveWindow.addAndGet(-frame.getHeader().getLength()) < 0) {
                // the peer sent beyond the stream flow control window
                connection.sendResetStream(HTTP2ErrorCode.FLOW_CONTROL_ERROR, streamId);
                rstReceived = true;
                halfClosed = true;
                close();
                return;
            }
            dataIn.enqueue(dataFrame.body);
            dataInSize += dataFrame.body.length;
            if (dataFrame.getHeader().getFlags().contains(FrameFlag.END_STREAM)) {
                long expected = expectedSize();
                if(expected!=-1 && dataInSize!=expected) {
                    connection.sendResetStream(HTTP2ErrorCode.PROTOCOL_ERROR, streamId);
                    // set halfClosed before close(): the data input must not
                    // block waiting for more data on a reset stream
                    halfClosed = true;
                    close();
                    break;
                }
                halfClosed = true;
                dataIn.wakeupReader();
            }
            break;
        case PRIORITY:
            if(streamOpen) {
                throw new HTTP2Exception(HTTP2ErrorCode.PROTOCOL_ERROR);
            }
            break;
        case RST_STREAM:
            ResetStreamFrame resetFrame = (ResetStreamFrame) frame;
            logger.log(Level.DEBUG,"received reset stream "+resetFrame.errorCode+", on stream "+streamId);
            halfClosed = true;
            rstReceived = true;
            close();
            break;
        case WINDOW_UPDATE:
            int windowSizeIncrement = ((WindowUpdateFrame)frame).getWindowSizeIncrement();
            if(sendWindow.addAndGet(windowSizeIncrement)> 2147483647) {
                connection.sendResetStream(HTTP2ErrorCode.FLOW_CONTROL_ERROR, streamId);
                close();
            }
            // wake a writer blocked on the send window immediately
            LockSupport.unpark(writerThread);
            logger.log(Level.DEBUG,"received window update "+windowSizeIncrement+", new size "+sendWindow.get()+", on stream "+streamId);
            break;
        default:
            break;
        }
    }

    private void performRequest() throws IOException, HTTP2Exception {
        if(!handlingRequest.compareAndSet(false, true)) {
            throw new HTTP2Exception(HTTP2ErrorCode.PROTOCOL_ERROR,"already received headers for stream "+streamId);
        }
        connection.httpConnection.requestCount.incrementAndGet();
        connection.requestsInProgress.incrementAndGet();
        connection.stats.activeStreams.incrementAndGet();

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
                connection.lock();
                try {
                    HPackContext.writeHeaderFrame(responseHeaders, connection.outputStream, streamId, closeStream);
                    if (closeStream) {
                        streamOutputClosed = true;
                    }
                } finally {
                    connection.unlock();
                }
            } catch (IOException e) {
                // the response could not be encoded (e.g. missing :status):
                // fail this stream rather than leaving the request hanging
                connection.sendResetStream(HTTP2ErrorCode.INTERNAL_ERROR, streamId);
                throw e;
            }
        }
    }

    public InetSocketAddress getLocalAddress() {
        return connection.getLocalAddress();
    }

    public InetSocketAddress getRemoteAddress() {
        return connection.getRemoteAddress();
    }

    class Http2OutputStream extends OutputStream {
        private static final FlagSet END_STREAM = FlagSet.of(FrameFlag.END_STREAM);

        private final int streamId;
        private boolean closed;

        public Http2OutputStream(int streamId) {
            this.streamId = streamId;
        }

        /** read the peer's max frame size on every write: the peer may raise it
         * with a later SETTINGS frame, which a value fixed at construction
         * would ignore */
        private int maxFrameSize() {
            var setting = connection.getRemoteSettings().get(SettingIdentifier.SETTINGS_MAX_FRAME_SIZE);
            return setting!=null ? (int)setting.value : 16384;
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[]{(byte) b});
        }

        @Override
        public void write(byte[] b) throws IOException {
            write(b, 0, b.length);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            connection.stats.bytesSent.addAndGet(len);
            writerThread = Thread.currentThread();
            // test outside of lock so other streams can progress. the park is
            // bounded only as a safety net: a WINDOW_UPDATE (or stream close)
            // unparks the writer immediately
            while(sendWindow.get()<=0 && !connection.isClosed() && !rstReceived) {
                connection.stats.pauses.incrementAndGet();
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(200));
            }
            if (rstReceived) {
                throw new IOException("stream " + streamId + " was reset by the peer");
            }
            writeResponseHeaders(false);
            if(streamOutputClosed) {
                throw new IOException("output stream was closed during headers send");
            }
            while(len>0) {
                int _len = Math.min(Math.min(len,maxFrameSize()),(int)Math.min(connection.sendWindow.get(),sendWindow.get()));
                if(_len<=0) {
                    connection.stats.pauses.incrementAndGet();
                    connection.lock();
                    try {
                        connection.stats.flushes.incrementAndGet();
                        connection.outputStream.flush();
                    } finally {
                        connection.unlock();
                    }
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(200));
                    if(connection.isClosed()) {
                        throw new IOException("connection closed");
                    }
                    int remaining = len;
                    // logger.log(Level.TRACE,() -> "paused sending data frame, remaining "+remaining+", length "+_len+" on stream "+streamId);
                    continue;
                }
                if(connection.sendWindow.addAndGet(-_len)<0) {
                    // if we can't get the space from the connection window, need to retry
                    connection.sendWindow.addAndGet(_len);
                    continue;
                }
                connection.lock();
                try {
                    FrameHeader.writeTo(connection.outputStream, _len, FrameType.DATA, FrameFlag.NONE, streamId);
                    connection.outputStream.write(b,off,_len);
                    connection.stats.framesSent.incrementAndGet();
                    if (ServerConfig.http2DisableFlushDelay()) {
                        connection.outputStream.flush();
                        connection.stats.flushes.incrementAndGet();
                    }
                } finally {
                    connection.unlock();
                }
                // byte[] header = FrameHeader.encode(_len, FrameType.DATA, FrameFlag.NONE, streamId);
                // byte[] data = Arrays.copyOfRange(b,off, len);
                // connection.enqueue(List.of(header,data));
                off+=_len;
                len-=_len;
                sendWindow.addAndGet(-_len);
                logger.log(Level.TRACE,() -> "sent data frame, length "+_len+", new send window "+sendWindow.get()+" on stream "+streamId);
            }
        }
        @Override
        public void flush() throws IOException {
            // an explicit flush must push data out of the connection level
            // buffer; otherwise small events (e.g. SSE) can stay buffered
            // indefinitely when no other traffic fills the buffer
            connection.lock();
            try {
                connection.outputStream.flush();
                connection.stats.flushes.incrementAndGet();
            } finally {
                connection.unlock();
            }
        }
        @Override
        public synchronized void close() throws IOException {
            // synchronized: the handler thread and the connection reader (on
            // RST_STREAM) can race here, and the non-volatile 'closed' check
            // alone let both through, double-decrementing requestsInProgress
            if(closed) return;
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
                        FrameHeader.writeTo(connection.outputStream, 0, FrameType.DATA, END_STREAM, streamId);
                        connection.stats.framesSent.incrementAndGet();
                    }
                    if(lastRequest) {
                        connection.outputStream.flush();
                        connection.stats.flushes.incrementAndGet();
                    }
                } finally {
                    connection.unlock();
                }
                dataIn.close();
            } finally {
                connection.stats.activeStreams.decrementAndGet();
                closed=true;
                HTTP2Stream.this.close();
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
                reader = Thread.currentThread();
                for(;len>0;) {
                    byte[] data;
                    while((data=queue.peek())==null) {
                        if(read>0) {
                            return read;
                        }
                        if(closed || halfClosed) return -1;
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
                        queue.poll();
                        offset=0;
                    }
                }
                return read;
            } finally {
                if(read>0) {
                    // the stream window is debited on frame receipt (processFrame);
                    // re-open it as the handler consumes the queued data
                    receiveWindow.addAndGet(read);
                    pendingWindowUpdate += read;
                    if(pendingWindowUpdate >= initialWindowSize/2) {
                        connection.lock();
                        try {
                            WindowUpdateFrame frame = new WindowUpdateFrame(streamId, (int)pendingWindowUpdate);
                            frame.writeTo(connection.outputStream);
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
