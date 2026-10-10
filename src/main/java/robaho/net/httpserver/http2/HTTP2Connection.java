package robaho.net.httpserver.http2;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import com.sun.net.httpserver.Headers;

import robaho.net.httpserver.HttpConnection;
import robaho.net.httpserver.OptimizedHeaders;
import robaho.net.httpserver.ServerConfig;
import robaho.net.httpserver.http2.hpack.HPackContext;
import robaho.net.httpserver.http2.hpack.HTTP2HeaderField;
import robaho.net.httpserver.http2.hpack.HeaderFields;
import robaho.net.httpserver.http2.frame.BaseFrame;
import robaho.net.httpserver.http2.frame.ContinuationFrame;
import robaho.net.httpserver.http2.frame.DataFrame;
import robaho.net.httpserver.http2.frame.FrameFlag;
import robaho.net.httpserver.http2.frame.FrameFlag.FlagSet;
import robaho.net.httpserver.http2.frame.FrameHeader;
import robaho.net.httpserver.http2.frame.FrameSerializer;
import robaho.net.httpserver.http2.frame.FrameType;
import robaho.net.httpserver.http2.frame.GoawayFrame;
import robaho.net.httpserver.http2.frame.HeadersFrame;
import robaho.net.httpserver.http2.frame.PingFrame;
import robaho.net.httpserver.http2.frame.ResetStreamFrame;
import robaho.net.httpserver.http2.frame.SettingIdentifier;
import robaho.net.httpserver.http2.frame.SettingParameter;
import robaho.net.httpserver.http2.frame.SettingsFrame;
import robaho.net.httpserver.http2.frame.SettingsMap;
import robaho.net.httpserver.http2.frame.WindowUpdateFrame;

public class HTTP2Connection {

    static final String PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n";
    static final String PARTIAL_PREFACE = "\r\nSM\r\n\r\n";

    final private InputStream inputStream;
    final OutputStream outputStream;

    private int lastSeenStreamId = 0;

    /** stream registry. a ConcurrentHashMap (not the custom open-addressing map):
     * the reader thread looks up a stream on EVERY inbound frame, and a lock-free
     * volatile read beats even an uncontended lock acquisition. close() removes
     * the entry (the old map used null tombstones for the same guard) */
    final java.util.concurrent.ConcurrentHashMap<Integer, HTTP2Stream> http2Streams = new java.util.concurrent.ConcurrentHashMap<>(16);

    /** reusable 16KB stream output buffers: sequential streams on a connection
     * share a small pool instead of allocating 16KB per stream (at high h2 RPS
     * that allocation dominates young-gen churn). bounded so a connection with
     * many CONCURRENT streams cannot pin extra buffers beyond the cap */
    private final java.util.concurrent.ConcurrentLinkedDeque<byte[]> streamBufferPool = new java.util.concurrent.ConcurrentLinkedDeque<>();
    private final java.util.concurrent.atomic.AtomicInteger pooledBufferCount = new java.util.concurrent.atomic.AtomicInteger();
    private static final int MAX_POOLED_STREAM_BUFFERS = 4;

    byte[] pollStreamBuffer() {
        byte[] buffer = streamBufferPool.poll();
        if (buffer != null) {
            pooledBufferCount.decrementAndGet();
            return buffer;
        }
        return new byte[robaho.net.httpserver.ServerConfig.bufferSize()];
    }

    void offerStreamBuffer(byte[] buffer) {
        if (buffer != null && buffer.length > 0
                && pooledBufferCount.incrementAndGet() <= MAX_POOLED_STREAM_BUFFERS) {
            streamBufferPool.offer(buffer);
        } else if (buffer != null) {
            pooledBufferCount.decrementAndGet();
        }
    }

    private final SettingsMap remoteSettings = new SettingsMap();

    /** cached peer SETTINGS_MAX_FRAME_SIZE (default 16384), updated on SETTINGS
     * receipt: the DATA write path reads it per chunk, and a settings-map
     * lookup per write was measurable overhead */
    private volatile int remoteMaxFrameSize = 16384;
    private final SettingsMap localSettings = new SettingsMap();

    private final StreamHandler handler;

    final HttpConnection httpConnection;

    final Logger logger;
    final HPackContext hpack = new HPackContext();

    final AtomicLong sendWindow = new AtomicLong(65535);
    final AtomicInteger receiveWindow = new AtomicInteger(65535);
    final AtomicInteger requestsInProgress = new AtomicInteger();

    final HTTP2Stats stats;

    private final int connectionWindowSize;

    private int maxConcurrentStreams = -1;
    private int highNumberStreams = 0;

    private final Lock lock = new ReentrantLock();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * Constructor to instantiate HTTP2Connection object
     *
     * @param input HTTP2Client passes the ExBufferedInputStream
     * @param output
     */
    public HTTP2Connection(HttpConnection httpConnection, HTTP2Stats stats, InputStream input, OutputStream output, StreamHandler handler) {
        this.httpConnection = httpConnection;
        this.inputStream = input;
        this.outputStream = output;
        this.handler = handler;
        this.stats = stats;
        this.logger = System.getLogger("robaho.net.httpserver.http2");

        connectionWindowSize = ServerConfig.http2ConnectionWindowSize();

        localSettings.set(new SettingParameter(SettingIdentifier.SETTINGS_MAX_FRAME_SIZE, ServerConfig.http2MaxFrameSize()));
        localSettings.set(new SettingParameter(SettingIdentifier.SETTINGS_INITIAL_WINDOW_SIZE, ServerConfig.http2InitialWindowSize()));

        // enforce the stream limit from the start: previously it was only
        // assigned when the client's SETTINGS ack arrived, so a client that
        // never acked could open unlimited streams
        maxConcurrentStreams = ServerConfig.http2MaxConcurrentStreams();
        if (maxConcurrentStreams != -1) {
            localSettings.set(new SettingParameter(SettingIdentifier.SETTINGS_MAX_CONCURRENT_STREAMS, maxConcurrentStreams));
        }
        logger.log(Level.DEBUG, "opened http2 connection " + httpConnection + ", max concurrent streams " + maxConcurrentStreams);
    }

    public void debug() {
        logger.log(Level.INFO, this +" receive window "+receiveWindow.get()+" send window "+sendWindow.get()+" in progress "+requestsInProgress.get());
        for(var stream : http2Streams.values()) {
            stream.debug();
        }
    }

    void lock() {
        lock.lock();
    }

    void unlock() {
        lock.unlock();
    }

    @Override
    public String toString() {
        return "{" + httpConnection + ", streams=" + http2Streams.size() + ", high " + highNumberStreams + "}";
    }

    public void close() {
        if(closed.compareAndSet(false,true)) {
            for (HTTP2Stream stream : http2Streams.values()) {
                stream.close();
            }
        }
    }

    /** the peer's advertised max frame size, cached for the DATA write path */
    int remoteMaxFrameSize() {
        return remoteMaxFrameSize;
    }

    public SettingsMap getRemoteSettings() {
        return remoteSettings;
    }

    public SettingsMap getLocalSettings() {
        return localSettings;
    }

    public void writeFrame(byte[] frame) throws IOException {
        writeFrame(List.of(frame));
    }

    /**
     * writes a frame that consists of multiple byte arrays. the method is
     * designed for low-volume frames where the overhead of creating a new byte
     * array is minimal. Otherwise, the data/frame should be written directly to
     * the output stream, e.g. see HTTP2Stream.Http2OutputStream
     */
    public void writeFrame(List<byte[]> partials) throws IOException {
        lock();
        try {
            logger.log(Level.TRACE, () -> "sending frame " + FrameHeader.debug(partials.get(0)));
            for (var frame : partials) {
                outputStream.write(frame);
            }
            outputStream.flush();
            if (HTTP2Stats.ENABLED) stats.flushes.incrementAndGet();
        } finally {
            unlock();
        }
    }

    /**
     * Function to validate the PREFACE received on the input stream from the
     * remote system
     *
     * @return true if preface is valid
     * @throws IOException
     */
    public boolean hasProperPreface() throws IOException {
        String preface_match = (httpConnection.isSSL()) ? PREFACE : PARTIAL_PREFACE;
        byte[] preface = new byte[preface_match.length()];
        // a single read() may return short when the preface is segmented by
        // the transport; readFully loops until the whole preface arrived
        readFully(inputStream, preface);
        String prefaceStr = new String(preface);
        return prefaceStr.equals(preface_match);
    }

    public boolean isClosed() {
        return closed.get();
    }

    public void handle() throws Exception {
        try {
            processFrames();
        } catch (HTTP2Exception e) {
            logger.log(Level.DEBUG, "exception on http2 connection", e);
            sendGoAway(e.getErrorCode());
            throw e;
        }
    }

    private void processFrames() throws Exception {
        boolean inHeaders = false;
        int openStreamId = 0;
        int headerBlockBytes = 0;
        // END_STREAM flag of a trailer HEADERS that is still being completed by
        // CONTINUATION frames
        boolean pendingTrailerEndStream = false;
        // END_STREAM flag of a NEW stream's HEADERS that is still being
        // completed by CONTINUATION frames: CONTINUATION cannot carry the flag,
        // so it must be remembered and re-applied when the block completes
        boolean pendingEndStream = false;

        List<byte[]> headerBlockFragments = new ArrayList();

        // main HTTP2
        while (!httpConnection.isClosed()) {
            BaseFrame frame = FrameSerializer.deserialize(inputStream);
            // System.out.println("Received frame: " + frame.getHeader());

            int streamId = frame.getHeader().streamIdentifier();
            if (streamId != 0 && streamId % 2 == 0) {
                throw new HTTP2Exception(HTTP2ErrorCode.PROTOCOL_ERROR, "invalid stream id " + streamId + " on type " + frame.getHeader().type());
            }

            // rfc7540 (section 6.5): SETTINGS frames always apply to a
            // connection, never a single stream.
            switch (frame.getHeader().type()) {
                case SETTINGS:
                    if (frame.getHeader().flags().contains(FrameFlag.ACK)) {
                        // the limit is already enforced from construction; the ack
                        // needs no further action
                        continue;
                    } else {
                        updateRemoteSettings((SettingsFrame) frame);
                        sendSettingsAck();
                    }
                    continue;
                case GOAWAY:
                    GoawayFrame goaway = (GoawayFrame) frame;
                    if (goaway.errorCode == HTTP2ErrorCode.NO_ERROR) {
                        continue;
                    }
                    throw new IOException("received GOAWAY from remote " + goaway.errorCode);
                case PING:
                    if (!frame.getHeader().flags().contains(FrameFlag.ACK)) {
                        sendPingAck((PingFrame) frame);
                    }
                    continue;
                case WINDOW_UPDATE:
                    if (inHeaders && streamId != 0) {
                        // RFC 7540 6.2: only CONTINUATION may follow an incomplete
                        // header block. letting a stream-level WINDOW_UPDATE fall
                        // through to the common path would dispatch a request from
                        // the PARTIAL header block and wedge the state machine
                        throw new HTTP2Exception(HTTP2ErrorCode.PROTOCOL_ERROR, "WINDOW_UPDATE frame received during headers receive");
                    }
                    if (streamId == 0) {
                        int windowSizeIncrement = ((WindowUpdateFrame) frame).getWindowSizeIncrement();
                        sendWindow.addAndGet(windowSizeIncrement);
                        logger.log(Level.DEBUG, () -> "received connection window update " + windowSizeIncrement + ", new size " + sendWindow.get());
                        if (sendWindow.get() > 2147483647) {
                            throw new HTTP2Exception(HTTP2ErrorCode.FLOW_CONTROL_ERROR, "maximum window size exceeded");
                        }
                        continue;
                    }
                    break;
                case NOT_IMPLEMENTED:
                    if (inHeaders) {
                        throw new HTTP2Exception(HTTP2ErrorCode.PROTOCOL_ERROR, "NOT_IMPLEMENTED frame received while headers being received");
                    }
                    if (frame.getHeader().streamIdentifier() == 0) {
                        continue;
                    }
                    break;
                case DATA:
                    DataFrame dataFrame = (DataFrame) frame;
                    // flow control accounting uses the full frame payload length
                    // (including any PADDED bytes), not the stripped body (RFC 7540 6.9)
                    if (receiveWindow.addAndGet(-frame.getHeader().length()) < 0) {
                        // the peer sent beyond the connection flow control window
                        throw new HTTP2Exception(HTTP2ErrorCode.FLOW_CONTROL_ERROR, "connection flow control window exceeded");
                    }
                    if (receiveWindow.get() < connectionWindowSize/10) {
                        sendWindowUpdate();
                    }
                    if (inHeaders) {
                        throw new HTTP2Exception(HTTP2ErrorCode.PROTOCOL_ERROR, "DATA frame received while headers being received");
                    }
                    break;
                case HEADERS:
                    if (inHeaders) {
                        throw new HTTP2Exception(HTTP2ErrorCode.PROTOCOL_ERROR, "HEADERS frame received on open stream");
                    }
                    // check for an existing stream FIRST: a trailer HEADERS on an
                    // interleaved (older, still open) stream is legal and must not
                    // be rejected by the out-of-order guard below, which would
                    // kill every concurrent stream with a GOAWAY
                    var stream = http2Streams.get(streamId);
                    if (stream != null) {
                        if (stream.isOpen() && !stream.isHalfClosed()) {
                            // HEADERS following DATA on an open stream is a trailer
                            // (RFC 7540 8.1.2.3): consume it and discard the contents
                            HeadersFrame trailerFrame = (HeadersFrame) frame;
                            headerBlockBytes += trailerFrame.getHeaderBlock().length;
                            if (headerBlockBytes > ServerConfig.http2MaxHeaderBlockSize()) {
                                throw new HTTP2Exception(HTTP2ErrorCode.ENHANCE_YOUR_CALM, "header block exceeds " + ServerConfig.http2MaxHeaderBlockSize() + " bytes");
                            }
                            headerBlockFragments.add(trailerFrame.getHeaderBlock());
                            if (!trailerFrame.getHeader().flags().contains(FrameFlag.END_HEADERS)) {
                                // multi frame trailer: remember the END_STREAM flag of the
                                // HEADERS frame - it is consumed when the last CONTINUATION
                                // completes the block
                                pendingTrailerEndStream = trailerFrame.getHeader().flags().contains(FrameFlag.END_STREAM);
                                inHeaders = true;
                                openStreamId = streamId;
                                continue;
                            }
                            // decode the trailer block to keep the HPACK dynamic table in
                            // sync: a trailer field sent with incremental indexing adds an
                            // entry on the client side, and an undecoded block here desyncs
                            // every later request on this connection (wrong headers or
                            // COMPRESSION_ERROR)
                            hpack.decodeFieldSegments(Utils.combineByteArrays(headerBlockFragments));
                            headerBlockFragments.clear();
                            headerBlockBytes = 0;
                            stream.receiveTrailer(trailerFrame.getHeader().flags().contains(FrameFlag.END_STREAM));
                            continue;
                        }
                        // HEADERS on a closed or half closed stream is a stream error
                        // (RFC 7540 5.1): reset the stream, not the connection
                        sendResetStream(HTTP2ErrorCode.STREAM_CLOSED, streamId);
                        continue;
                    }
                    // from here on this must be a NEW stream
                    if (streamId < lastSeenStreamId) {
                        throw new HTTP2Exception(HTTP2ErrorCode.PROTOCOL_ERROR, "HEADERS frame received out of order");
                    }
                    HeadersFrame headersFrame = (HeadersFrame) frame;
                    headerBlockBytes += headersFrame.getHeaderBlock().length;
                    if (headerBlockBytes > ServerConfig.http2MaxHeaderBlockSize()) {
                        // CONTINUATION flood protection
                        throw new HTTP2Exception(HTTP2ErrorCode.ENHANCE_YOUR_CALM, "header block exceeds " + ServerConfig.http2MaxHeaderBlockSize() + " bytes");
                    }
                    headerBlockFragments.add(headersFrame.getHeaderBlock());
                    if (!headersFrame.getHeader().flags().contains(FrameFlag.END_HEADERS)) {
                        // remember the END_STREAM flag of the HEADERS frame: it
                        // cannot ride on the CONTINUATION frames that complete
                        // the block, and losing it leaves a body-reading
                        // handler parked forever
                        pendingEndStream = headersFrame.getHeader().flags().contains(FrameFlag.END_STREAM);
                        inHeaders = true;
                        openStreamId = streamId;
                        continue;
                    }
                    break;
                case CONTINUATION:
                    if (inHeaders && frame.getHeader().streamIdentifier() != openStreamId) {
                        throw new HTTP2Exception(HTTP2ErrorCode.PROTOCOL_ERROR, "HEADERS frame received on open stream");
                    }
                    if (!inHeaders) {
                        throw new HTTP2Exception(HTTP2ErrorCode.PROTOCOL_ERROR, "CONTINUATION frame received on closed stream");
                    }
                    ContinuationFrame continuationFrame = (ContinuationFrame) frame;
                    headerBlockBytes += continuationFrame.getHeaderBlock().length;
                    if (headerBlockBytes > ServerConfig.http2MaxHeaderBlockSize()) {
                        // CONTINUATION flood protection
                        throw new HTTP2Exception(HTTP2ErrorCode.ENHANCE_YOUR_CALM, "header block exceeds " + ServerConfig.http2MaxHeaderBlockSize() + " bytes");
                    }
                    headerBlockFragments.add(continuationFrame.getHeaderBlock());
                    if (!continuationFrame.getHeader().flags().contains(FrameFlag.END_HEADERS)) {
                        continue;
                    }
                    // header block complete: if the stream already exists this block
                    // is a multi frame trailer (RFC 7540 8.1.2.3) - discard it instead
                    // of feeding the CONTINUATION into performRequest (which would
                    // treat it as a duplicate request and kill the connection)
                    if (http2Streams.get(streamId) != null) {
                        // decode the trailer block to keep the HPACK dynamic table in
                        // sync (see the single frame trailer path above)
                        hpack.decodeFieldSegments(Utils.combineByteArrays(headerBlockFragments));
                        headerBlockFragments.clear();
                        headerBlockBytes = 0;
                        inHeaders = false;
                        http2Streams.get(streamId).receiveTrailer(pendingTrailerEndStream);
                        continue;
                    }
                    break;
                case PRIORITY:
                    if (inHeaders) {
                        throw new HTTP2Exception(HTTP2ErrorCode.PROTOCOL_ERROR, "PRIORITY frame received during headers receive");
                    }
                    if (streamId == 0) {
                        throw new HTTP2Exception(HTTP2ErrorCode.PROTOCOL_ERROR, "PRIORITY frame received on stream 0");
                    }
                    // stream priority is ignore for now
                    continue;
                case RST_STREAM:
                    ResetStreamFrame resetFrame = (ResetStreamFrame) frame;
                    // any RST_STREAM (including NO_ERROR and codes the enum
                    // does not know) must close the stream: the peer considers
                    // it cancelled
                    if (streamId == 0) {
                        throw new HTTP2Exception(HTTP2ErrorCode.PROTOCOL_ERROR, "RST_STREAM frame received on stream 0");
                    }
                    if (http2Streams.get(streamId) == null) {
                        if (streamId <= lastSeenStreamId) {
                            continue;
                        }
                        throw new HTTP2Exception(HTTP2ErrorCode.PROTOCOL_ERROR, "RST_STREAM frame received on non-existent stream");
                    }
                    break;
                default:
                    if (inHeaders) {
                        throw new HTTP2Exception(HTTP2ErrorCode.PROTOCOL_ERROR, frame.getHeader().type() + " frame received during headers receive");
                    }
                    // unknown or unsupported frame types (e.g. PUSH_PROMISE from a
                    // client, extension frames) must not reach the stream dispatch
                    // below: they would create a phantom stream from nothing
                    continue;
            }

            // we can only get here if we have a complete set of headers
            HTTP2Stream targetStream = http2Streams.get(streamId);

            if (targetStream != null) {
                // found existing stream
            } else if (lastSeenStreamId < streamId) {
                int currentSize = http2Streams.size();
                if (maxConcurrentStreams != -1 && currentSize >= maxConcurrentStreams) {
                    // stream level error: refuse this single stream, not the
                    // whole connection (RFC 7540 5.1.2)
                    sendResetStream(HTTP2ErrorCode.REFUSED_STREAM, streamId);
                    headerBlockFragments.clear();
                    headerBlockBytes = 0;
                    pendingEndStream = false;
                    // reset the header block state or the client's legitimate
                    // retry (RFC 7540 5.1.2 invites retry after REFUSED_STREAM)
                    // hits the inHeaders guard and kills ALL streams with GOAWAY
                    inHeaders = false;
                    continue;
                }
                highNumberStreams = Math.max(highNumberStreams, currentSize);
                byte[] headerBlock = Utils.combineByteArrays(headerBlockFragments);
                HeaderFields fields = new HeaderFields();
                fields.addAll(hpack.decodeFieldSegments(headerBlock));
                // streamID is not present and has to be greater than all
                // the stream IDs present
                fields.validate();
                Headers requestHeaders = new OptimizedHeaders(fields.size()*2);
                for (HTTP2HeaderField field : fields) {
                    if (field.value == null) {
                        logger.log(Level.TRACE, () -> "ignoring null header for " + field.getName());
                    } else {
                        requestHeaders.add(field.normalizedName, field.value);
                    }
                }
                headerBlockFragments.clear();
                headerBlockBytes = 0;
                inHeaders = false;
                targetStream = new HTTP2Stream(streamId, this, requestHeaders, handler);
                http2Streams.put(streamId, targetStream);
                lastSeenStreamId = streamId;
            } else {
                if (streamId <= lastSeenStreamId) {
                    // frames arriving for a stream we have already closed
                    // (e.g. DATA in flight when the stream was reset) are
                    // ignored (RFC 7540 5.1, closed state)
                    logger.log(Level.TRACE,() -> "received "+frame.getHeader().type()+" on closed stream "+streamId);
                    continue;
                }
                throw new HTTP2Exception(HTTP2ErrorCode.PROTOCOL_ERROR,  "frame "+frame.getHeader().type()+", stream "+streamId+" not in order");
            }

            targetStream.processFrame(frame);
            if (pendingEndStream && frame.getHeader().type() == FrameType.CONTINUATION) {
                // a new stream's header block just completed via CONTINUATION:
                // re-apply the END_STREAM flag recorded from the HEADERS frame
                // (processFrame saw only the CONTINUATION, which cannot carry
                // it) so the request body is at EOF for the handler
                targetStream.receiveTrailer(true);
                pendingEndStream = false;
            }
        }
    }

    public void updateRemoteSettings(SettingsFrame remoteSettingFrame) throws HTTP2Exception {
        logger.log(Level.TRACE, () -> "updating remote settings");

        for (SettingParameter parameter : remoteSettingFrame.getSettingParameters()) {
            long oldInitialWindowSize = remoteSettings.getOrDefault(SettingIdentifier.SETTINGS_INITIAL_WINDOW_SIZE, SettingParameter.DEFAULT_INITIAL_WINDOWSIZE).value;
            if (parameter.identifier == SettingIdentifier.SETTINGS_INITIAL_WINDOW_SIZE) {
                if (parameter.value > 2147483647) {
                    throw new HTTP2Exception(HTTP2ErrorCode.FLOW_CONTROL_ERROR, "Invalid value for SETTINGS_INITIAL_WINDOW_SIZE " + parameter.value);
                }
                logger.log(Level.DEBUG, () -> "received initial window size of " + parameter.value);
                for (var stream : http2Streams.values()) {
                    stream.sendWindow.addAndGet(parameter.value - oldInitialWindowSize);
                }
            }
            if (parameter.identifier == SettingIdentifier.SETTINGS_MAX_FRAME_SIZE) {
                logger.log(Level.DEBUG, () -> "received max frame size " + parameter.value);
                remoteMaxFrameSize = (int) parameter.value;
            }
            getRemoteSettings().set(parameter);
        }
    }

    public void sendSettingsAck() throws IOException {
        try {
            byte[] frame = FrameHeader.encode(0, FrameType.SETTINGS, FlagSet.of(FrameFlag.ACK), 0);
            HTTP2Connection.this.writeFrame(frame);
        } finally {
            logger.log(Level.TRACE, () -> "sent Settings Ack");
        }
    }

    public void sendMySettings() throws IOException {
        try {
            FrameHeader header = new FrameHeader(0, FrameType.SETTINGS, FrameFlag.NONE, 0);
            SettingsFrame frame = new SettingsFrame(header);
            localSettings.forEach(setting -> frame.getSettingParameters().add(setting));
            HTTP2Connection.this.writeFrame(frame.encode());
        } finally {
            logger.log(Level.TRACE, () -> "sent My Settings");
        }
    }

    public void sendWindowUpdate() throws IOException {
        int current = receiveWindow.get();
        try {
            int increment = connectionWindowSize-current;
            receiveWindow.addAndGet(increment);
            WindowUpdateFrame frame = new WindowUpdateFrame(0, increment);
            HTTP2Connection.this.writeFrame(frame.encode());
        } finally {
            logger.log(Level.DEBUG, () -> "sent connection window update, previous "+current+", now "+ receiveWindow.get());
        }
    }

    public void sendGoAway(HTTP2ErrorCode errorCode) throws IOException {
        lock();
        try {
            GoawayFrame frame = new GoawayFrame(errorCode, lastSeenStreamId);
            frame.writeTo(outputStream);
            outputStream.flush();
        } finally {
            unlock();
        }
        logger.log(Level.TRACE, () -> "Sent GoAway " + errorCode + ", last stream " + lastSeenStreamId);
    }

    public void sendResetStream(HTTP2ErrorCode errorCode, int streamId) throws IOException {
        ResetStreamFrame frame = new ResetStreamFrame(errorCode, streamId);
        HTTP2Connection.this.writeFrame(frame.encode());
        logger.log(Level.TRACE, () -> "Sent ResetStream " + errorCode);
    }

    public void sendPing() throws IOException {
        PingFrame frame = new PingFrame();
        HTTP2Connection.this.writeFrame(frame.encode());
        if (HTTP2Stats.ENABLED) stats.pingsSent.incrementAndGet();
        logger.log(Level.TRACE, () -> "Sent Ping ");
    }

    private void sendPingAck(PingFrame ping) throws IOException {
        PingFrame frame = new PingFrame(ping);
        HTTP2Connection.this.writeFrame(frame.encode());
        logger.log(Level.TRACE, "Sent Ping Ack");
    }

    public interface StreamHandler {

        void handleStream(HTTP2Stream stream, InputStream in, OutputStream out) throws IOException;

        Executor getExecutor();
    }

    public static void readFully(InputStream inputStream, byte[] buffer) throws IOException {
        if (buffer.length == 0) {
            return;
        }

        int bytesRead = 0;
        int offset = 0;
        int length = buffer.length;
        while (bytesRead != -1 && offset < length) {
            bytesRead = inputStream.read(buffer, offset, length - offset);
            if (bytesRead != -1) {
                offset += bytesRead;
            }
        }
        if (offset == 0) {
            throw new EOFException("end of stream detected");
        }
        if (offset < length) {
            throw new IOException("failed to read the full buffer");
        }
    }
}
