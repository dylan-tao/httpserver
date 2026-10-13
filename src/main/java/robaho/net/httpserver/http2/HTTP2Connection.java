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

    /** header block assembly state - touched only by the reader thread. a
     * HEADERS block may span HEADERS + CONTINUATION frames; while it is
     * incomplete no other frame type is legal on the connection (RFC 7540 6.2) */
    private int headerBlockBytes = 0;
    private final List<byte[]> headerBlockFragments = new ArrayList<>();
    /** END_STREAM flag of a trailer HEADERS that is still being completed by
     * CONTINUATION frames */
    private boolean pendingTrailerEndStream = false;
    /** END_STREAM flag of a NEW stream's HEADERS that is still being completed
     * by CONTINUATION frames: CONTINUATION cannot carry the flag, so it must
     * be remembered and re-applied when the block completes */
    private boolean pendingEndStream = false;

    /** the peer's GOAWAY(NO_ERROR) boundary: it will not open new streams
     * beyond this id (RFC 7540 6.8). -1 = no GOAWAY received. the connection
     * keeps serving in-flight streams and refuses newer ones */
    private volatile int clientGoawayStreamId = -1;

    /** scratch for DATA frame headers on the send path: reused per frame
     * instead of allocating 9 bytes each. used ONLY while holding the
     * connection lock (both call sites in Http2OutputStream hold it) */
    final byte[] frameHeaderScratch = new byte[9];

    /** count of writer threads parked in the in-loop flow-control park (the
     * connection-window park site). lets the connection-level WINDOW_UPDATE
     * handler skip the O(registered streams) wake-all loop when no writer
     * needs it: at high throughput with per-frame client credit the
     * unconditional loop issued tens of thousands of no-op unparks per
     * second, and each spurious wake of a parked writer costs a full
     * wake/re-check/re-park cycle */
    final java.util.concurrent.atomic.AtomicInteger parkedWriters = new java.util.concurrent.atomic.AtomicInteger();

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

    /** write a single pre-encoded frame under the connection lock. designed
     * for low-volume control frames where the overhead is minimal; bulk data
     * is written directly to the output stream instead (see
     * HTTP2Stream.Http2OutputStream) */
    public void writeFrame(byte[] frame) throws IOException {
        lock();
        try {
            logger.log(Level.TRACE, () -> "sending frame " + FrameHeader.debug(frame));
            outputStream.write(frame);
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

    /** accumulate a header block fragment, enforcing the total header block
     * size limit (CONTINUATION flood protection) */
    private void addHeaderFragment(byte[] block) throws HTTP2Exception {
        headerBlockBytes += block.length;
        if (headerBlockBytes > ServerConfig.http2MaxHeaderBlockSize()) {
            throw new HTTP2Exception(HTTP2ErrorCode.ENHANCE_YOUR_CALM, "header block exceeds " + ServerConfig.http2MaxHeaderBlockSize() + " bytes");
        }
        headerBlockFragments.add(block);
    }

    /** a trailer HEADERS block is complete: decode it to keep the HPACK
     * dynamic table in sync (a trailer field sent with incremental indexing
     * adds an entry on the client side, and an undecoded block desyncs every
     * later request on this connection - wrong headers or COMPRESSION_ERROR),
     * then discard its contents. the stream is null when it was closed and
     * unregistered while the block was still in flight: the trailer is then
     * simply ignored */
    private void completeTrailer(HTTP2Stream stream, boolean endStream) throws HTTP2Exception {
        hpack.decodeFieldSegments(Utils.combineByteArrays(headerBlockFragments));
        headerBlockFragments.clear();
        headerBlockBytes = 0;
        pendingTrailerEndStream = false;
        if (stream != null) {
            stream.receiveTrailer(endStream);
        }
    }

    private void processFrames() throws Exception {
        boolean inHeaders = false;
        // true while an incomplete header block belongs to a TRAILER (not a new
        // stream's request): at completion time the registry lookup cannot
        // distinguish them - a trailer's stream may have been closed and
        // unregistered mid-block, and a new stream is not registered until the
        // dispatch below creates it
        boolean inTrailerBlock = false;
        int openStreamId = 0;

        // main HTTP2
        while (!httpConnection.isClosed()) {
            BaseFrame frame = FrameSerializer.deserialize(inputStream);

            int streamId = frame.getHeader().streamIdentifier();
            // box once per frame: every registry get/put below autoboxes the
            // id, and stream ids grow past the Integer cache within the first
            // 64 requests on a connection
            Integer boxedStreamId = streamId;
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
                        // graceful shutdown (RFC 7540 6.8): the peer will not
                        // open new streams beyond lastSeenStream. finish the
                        // in-flight streams and refuse anything newer; the
                        // connection itself stays up until the peer (or the
                        // idle cleaner) closes it. max: a later GOAWAY with a
                        // smaller id must not re-accept refused streams
                        clientGoawayStreamId = Math.max(clientGoawayStreamId, goaway.lastSeenStream);
                        logger.log(Level.DEBUG, () -> "received GOAWAY, last stream " + goaway.lastSeenStream + ", draining");
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
                        int windowSizeIncrement = ((WindowUpdateFrame)frame).getWindowSizeIncrement();
                        sendWindow.addAndGet(windowSizeIncrement);
                        if(logger.isLoggable(Level.DEBUG)) {
                            logger.log(Level.DEBUG, () -> "received connection window update " + windowSizeIncrement + ", new size " + sendWindow.get());
                        }
                        if (sendWindow.get() > 2147483647) {
                            throw new HTTP2Exception(HTTP2ErrorCode.FLOW_CONTROL_ERROR, "maximum window size exceeded");
                        }
                        // wake every stream's writer when any is parked on the
                        // CONNECTION window (exhausted by other streams'
                        // traffic): only its own stream-level WINDOW_UPDATE
                        // would otherwise unpark it - its own credit never
                        // arrives while its data cannot be sent. spurious
                        // unparks (stream window still exhausted) are
                        // harmless: the writer re-checks and re-parks. the
                        // parkedWriters gate skips the O(streams) loop when
                        // nobody is parked (writers count themselves BEFORE
                        // the re-check, so a WINDOW_UPDATE processed after
                        // the count sees it, and one processed before it is
                        // caught by the re-check - no wakeup is lost)
                        if (parkedWriters.get() > 0) {
                            for (var stream : http2Streams.values()) {
                                stream.wakeupWriter();
                            }
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
                    // replenish before the remaining window drops below what a
                    // full-size DATA frame needs: a client that only sends whole
                    // frames (frame size = our advertised SETTINGS_MAX_FRAME_SIZE,
                    // a legal implementation choice) otherwise stalls forever when
                    // the remainder falls between the old 10% threshold and its
                    // frame size - the client waits for credit, the server waits
                    // for data. the half-window floor matches the stream-level
                    // policy (DataIn flushes at initialWindowSize/2)
                    if (receiveWindow.get() < Math.max(connectionWindowSize / 2, ServerConfig.http2MaxFrameSize())) {
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
                    var stream = http2Streams.get(boxedStreamId);
                    if (stream != null) {
                        if (stream.isOpen() && !stream.isHalfClosed()) {
                            // HEADERS following DATA on an open stream is a trailer
                            // (RFC 7540 8.1.2.3): consume it and discard the contents
                            HeadersFrame trailerFrame = (HeadersFrame) frame;
                            addHeaderFragment(trailerFrame.getHeaderBlock());
                            if (!trailerFrame.getHeader().flags().contains(FrameFlag.END_HEADERS)) {
                                // multi frame trailer: remember the END_STREAM flag of the
                                // HEADERS frame - it is consumed when the last CONTINUATION
                                // completes the block
                                pendingTrailerEndStream = trailerFrame.getHeader().flags().contains(FrameFlag.END_STREAM);
                                inHeaders = true;
                                inTrailerBlock = true;
                                openStreamId = streamId;
                                continue;
                            }
                            completeTrailer(stream, trailerFrame.getHeader().flags().contains(FrameFlag.END_STREAM));
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
                    addHeaderFragment(headersFrame.getHeaderBlock());
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
                        throw new HTTP2Exception(HTTP2ErrorCode.PROTOCOL_ERROR, "CONTINUATION frame received on stream " + streamId + " while the open header block belongs to stream " + openStreamId);
                    }
                    if (!inHeaders) {
                        throw new HTTP2Exception(HTTP2ErrorCode.PROTOCOL_ERROR, "CONTINUATION frame received on closed stream");
                    }
                    ContinuationFrame continuationFrame = (ContinuationFrame) frame;
                    addHeaderFragment(continuationFrame.getHeaderBlock());
                    if (!continuationFrame.getHeader().flags().contains(FrameFlag.END_HEADERS)) {
                        continue;
                    }
                    if (inTrailerBlock) {
                        // a multi frame trailer is complete (RFC 7540 8.1.2.3):
                        // complete it even when the stream was closed and
                        // unregistered while the block was in flight - the HPACK
                        // dynamic table is connection-wide and must stay in sync,
                        // and the assembly state must reset, or inHeaders stays
                        // wedged and the next HEADERS frame kills the connection
                        // with GOAWAY (the trailer itself is then ignored)
                        inHeaders = false;
                        inTrailerBlock = false;
                        completeTrailer(http2Streams.get(boxedStreamId), pendingTrailerEndStream);
                        continue;
                    }
                    // header block of a NEW stream complete: fall through to the
                    // dispatch below, which creates the stream
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
                    if (http2Streams.get(boxedStreamId) == null) {
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
            HTTP2Stream targetStream = http2Streams.get(boxedStreamId);

            if (targetStream != null) {
                // found existing stream
            } else if (lastSeenStreamId < streamId) {
                if (clientGoawayStreamId >= 0 && streamId > clientGoawayStreamId) {
                    // the peer promised (GOAWAY) not to open streams beyond
                    // this id: refuse the stream, keep the connection alive
                    // for in-flight work (RFC 7540 6.8)
                    sendResetStream(HTTP2ErrorCode.REFUSED_STREAM, streamId);
                    headerBlockFragments.clear();
                    headerBlockBytes = 0;
                    pendingEndStream = false;
                    inHeaders = false;
                    continue;
                }
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
                http2Streams.put(boxedStreamId, targetStream);
                lastSeenStreamId = streamId;
            } else {
                if (streamId <= lastSeenStreamId) {
                    // frames arriving for a stream we have already closed
                    // (e.g. DATA in flight when the stream was reset) are
                    // ignored (RFC 7540 5.1, closed state). a HEADERS/
                    // CONTINUATION block with a REUSED stream id also lands
                    // here: reset BOTH halves of the assembly state - the
                    // completing CONTINUATION breaks out of the switch with
                    // inHeaders still set, and leaving it set makes the next
                    // stream-level frame trip a "during headers receive"
                    // error that GOAWAYs every concurrent stream
                    headerBlockFragments.clear();
                    headerBlockBytes = 0;
                    pendingEndStream = false;
                    inHeaders = false;
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
            if (parameter.identifier == SettingIdentifier.SETTINGS_INITIAL_WINDOW_SIZE) {
                if (parameter.value > 2147483647) {
                    throw new HTTP2Exception(HTTP2ErrorCode.FLOW_CONTROL_ERROR, "Invalid value for SETTINGS_INITIAL_WINDOW_SIZE " + parameter.value);
                }
                logger.log(Level.DEBUG, () -> "received initial window size of " + parameter.value);
                long oldInitialWindowSize = remoteSettings.getOrDefault(SettingIdentifier.SETTINGS_INITIAL_WINDOW_SIZE, SettingParameter.DEFAULT_INITIAL_WINDOWSIZE).value;
                long delta = parameter.value - oldInitialWindowSize;
                for (var stream : http2Streams.values()) {
                    stream.sendWindow.addAndGet(delta);
                    if (delta > 0) {
                        // wake writers parked on their stream window: the grown
                        // window may let them proceed now (the same wake gap
                        // the connection-level WINDOW_UPDATE had)
                        stream.wakeupWriter();
                    }
                }
            }
            if (parameter.identifier == SettingIdentifier.SETTINGS_MAX_FRAME_SIZE) {
                // RFC 7540 6.5.2: valid range is 16384..16777215 and an
                // out-of-range value is a connection error
                if (parameter.value < 16384 || parameter.value > 16777215) {
                    throw new HTTP2Exception(HTTP2ErrorCode.PROTOCOL_ERROR, "Invalid value for SETTINGS_MAX_FRAME_SIZE " + parameter.value);
                }
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
            if(logger.isLoggable(Level.DEBUG)) {
                logger.log(Level.DEBUG, () -> "sent connection window update, previous "+current+", now "+ receiveWindow.get());
            }
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
            // the stream ended mid-frame: an EOF condition, not a generic
            // read failure - lets callers distinguish truncation cleanly
            throw new EOFException("connection closed mid-frame (read " + offset + " of " + length + " bytes)");
        }
    }
}
