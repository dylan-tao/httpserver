package robaho.net.httpserver.http2;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Minimal h2c (cleartext, prior knowledge) HTTP/2 test client speaking raw
 * frames over a socket. The JDK HttpClient cannot do prior-knowledge h2c, so
 * the compliance tests drive the protocol by hand.
 *
 * Only what the tests need is implemented: HPACK literal-without-indexing
 * request encoding, HEADERS / CONTINUATION / DATA / RST_STREAM writes, and a
 * frame reader that acknowledges SETTINGS and PING while collecting per-stream
 * responses. Frames that arrive for a stream other than the one being awaited
 * are buffered and replayed for the next await, so interleaved streams work.
 */
class H2Client implements AutoCloseable {
    static final int TYPE_DATA = 0x00;
    static final int TYPE_HEADERS = 0x01;
    static final int TYPE_RST_STREAM = 0x03;
    static final int TYPE_SETTINGS = 0x04;
    static final int TYPE_PING = 0x06;
    static final int TYPE_GOAWAY = 0x07;
    static final int TYPE_WINDOW_UPDATE = 0x08;
    static final int TYPE_CONTINUATION = 0x09;

    static final int FLAG_END_STREAM = 0x01;
    static final int FLAG_ACK = 0x01;
    static final int FLAG_END_HEADERS = 0x04;
    static final int FLAG_PADDED = 0x08;

    /** error codes used by the tests (RFC 7540 7) */
    static final int REFUSED_STREAM = 7;
    static final int CANCEL = 8;

    private static final String PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n";

    private final Socket socket;
    private final OutputStream out;
    private final DataInputStream in;
    private final Deque<Frame> pending = new ArrayDeque<>();
    /** set when a GOAWAY frame has been seen on this connection */
    boolean goawayReceived;
    int goawayErrorCode = -1;

    private int nextStreamId = 1;

    H2Client(int port) throws IOException {
        socket = new Socket("127.0.0.1", port);
        socket.setSoTimeout(10_000);
        socket.setTcpNoDelay(true);
        out = socket.getOutputStream();
        in = new DataInputStream(socket.getInputStream());
        out.write(PREFACE.getBytes(StandardCharsets.ISO_8859_1));
        writeFrame(TYPE_SETTINGS, 0, 0, new byte[0]);
        // consume frames until the server's SETTINGS arrives, then ack it
        while (true) {
            Frame f = readFrame();
            if (f.type == TYPE_SETTINGS && (f.flags & FLAG_ACK) == 0) {
                writeFrame(TYPE_SETTINGS, FLAG_ACK, 0, new byte[0]);
                break;
            }
            if (f.type == TYPE_GOAWAY) {
                recordGoaway(f);
                break;
            }
        }
    }

    /** a received frame */
    record Frame(int type, int flags, int streamId, byte[] payload) {
        boolean has(int flag) {
            return (flags & flag) != 0;
        }
    }

    /** the collected outcome for one stream */
    static final class Response {
        final int status;            // decoded :status, -1 when absent/undecoded
        final byte[] data;           // concatenated DATA payloads
        final Integer rstErrorCode;  // RST_STREAM error code for this stream, if any
        final boolean goaway;        // the connection saw a GOAWAY

        Response(int status, byte[] data, Integer rstErrorCode, boolean goaway) {
            this.status = status;
            this.data = data;
            this.rstErrorCode = rstErrorCode;
            this.goaway = goaway;
        }
    }

    Response awaitResponse(int streamId) throws IOException {
        return awaitResponse(streamId, 15_000);
    }

    /**
     * Read frames until the response for {@code streamId} completes (END_STREAM
     * or RST_STREAM). SETTINGS / PING are answered, WINDOW_UPDATE is skipped.
     * Frames for other streams are held back locally and re-queued on exit -
     * putting them straight back into {@code pending} would make readFrame()
     * re-poll them forever. The deadline is wall-clock: the server's 1s idle
     * pings keep resetting the socket read timeout, so a socket timeout alone
     * would never fire.
     */
    Response awaitResponse(int streamId, long timeoutMillis) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        ByteArrayOutputStream headers = new ByteArrayOutputStream();
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        List<Frame> heldBack = new ArrayList<>();
        try {
            while (true) {
                if (System.currentTimeMillis() > deadline) {
                    throw new IOException("timed out (wall clock) waiting for response on stream " + streamId
                        + " (goaway=" + goawayReceived + ")");
                }
                Frame f;
                try {
                    f = readFrame();
                } catch (SocketTimeoutException e) {
                    throw new IOException("timed out waiting for response on stream " + streamId
                        + " (goaway=" + goawayReceived + ")", e);
                }
                switch (f.type) {
                    case TYPE_SETTINGS:
                        if (!f.has(FLAG_ACK)) {
                            writeFrame(TYPE_SETTINGS, FLAG_ACK, 0, new byte[0]);
                        }
                        break;
                    case TYPE_PING:
                        if (!f.has(FLAG_ACK)) {
                            writeFrame(TYPE_PING, FLAG_ACK, 0, f.payload);
                        }
                        break;
                    case TYPE_WINDOW_UPDATE:
                        break;
                    case TYPE_GOAWAY:
                        recordGoaway(f);
                        return new Response(-1, data.toByteArray(), null, true);
                    case TYPE_RST_STREAM:
                        if (f.streamId == streamId) {
                            int code = ((f.payload[0] & 0xFF) << 24) | ((f.payload[1] & 0xFF) << 16)
                                | ((f.payload[2] & 0xFF) << 8) | (f.payload[3] & 0xFF);
                            return new Response(-1, data.toByteArray(), code, goawayReceived);
                        }
                        heldBack.add(f);
                        break;
                    case TYPE_HEADERS:
                        if (f.streamId == streamId) {
                            headers.writeBytes(f.payload);
                            if (f.has(FLAG_END_STREAM)) {
                                return new Response(decodeStatus(headers.toByteArray()),
                                    data.toByteArray(), null, goawayReceived);
                            }
                        } else {
                            heldBack.add(f);
                        }
                        break;
                    case TYPE_CONTINUATION:
                        if (f.streamId == streamId) {
                            headers.writeBytes(f.payload);
                        } else {
                            heldBack.add(f);
                        }
                        break;
                    case TYPE_DATA:
                        if (f.streamId == streamId) {
                            data.writeBytes(f.payload);
                            if (f.has(FLAG_END_STREAM)) {
                                return new Response(decodeStatus(headers.toByteArray()),
                                    data.toByteArray(), null, goawayReceived);
                            }
                        } else {
                            heldBack.add(f);
                        }
                        break;
                    default:
                        break;
                }
            }
        } finally {
            // re-queue other streams' frames, preserving arrival order
            for (int i = heldBack.size() - 1; i >= 0; i--) {
                pending.addFirst(heldBack.get(i));
            }
        }
    }

    /** open a new stream with a request; returns the (odd) stream id */
    int sendRequest(String method, String path, boolean endStream) throws IOException {
        int streamId = nextStreamId;
        nextStreamId += 2;
        byte[] block = encodeHeaders(
            ":method", method,
            ":scheme", "http",
            ":path", path,
            ":authority", "127.0.0.1");
        writeFrame(TYPE_HEADERS, FLAG_END_HEADERS | (endStream ? FLAG_END_STREAM : 0), streamId, block);
        return streamId;
    }

    void sendData(int streamId, byte[] payload, boolean endStream) throws IOException {
        writeFrame(TYPE_DATA, endStream ? FLAG_END_STREAM : 0, streamId, payload);
    }

    /** single-frame trailer HEADERS on an open stream */
    void sendTrailer(int streamId, String name, String value, boolean endStream) throws IOException {
        byte[] block = encodeHeaders(name, value);
        writeFrame(TYPE_HEADERS, FLAG_END_HEADERS | (endStream ? FLAG_END_STREAM : 0), streamId, block);
    }

    void sendRstStream(int streamId, int errorCode) throws IOException {
        byte[] payload = new byte[4];
        payload[0] = (byte) (errorCode >>> 24);
        payload[1] = (byte) (errorCode >>> 16);
        payload[2] = (byte) (errorCode >>> 8);
        payload[3] = (byte) errorCode;
        writeFrame(TYPE_RST_STREAM, 0, streamId, payload);
    }

    /** raw frame write, for hand-built HEADERS+CONTINUATION sequences */
    void writeRawFrame(int type, int flags, int streamId, byte[] payload) throws IOException {
        writeFrame(type, flags, streamId, payload);
    }

    /**
     * Blocking read of the next WINDOW_UPDATE (stream-level or connection
     * level), answering SETTINGS / PING keepalives on the way. Returns
     * {@code {streamId, increment}}. Used by upload probes that track both
     * flow-control windows while sending.
     */
    int[] readWindowUpdate() throws IOException {
        while (true) {
            Frame f = readFrame();
            switch (f.type) {
                case TYPE_SETTINGS:
                    if (!f.has(FLAG_ACK)) {
                        writeFrame(TYPE_SETTINGS, FLAG_ACK, 0, new byte[0]);
                    }
                    break;
                case TYPE_PING:
                    if (!f.has(FLAG_ACK)) {
                        writeFrame(TYPE_PING, FLAG_ACK, 0, f.payload);
                    }
                    break;
                case TYPE_WINDOW_UPDATE:
                    int inc = ((f.payload[0] & 0xFF) << 24) | ((f.payload[1] & 0xFF) << 16)
                        | ((f.payload[2] & 0xFF) << 8) | (f.payload[3] & 0xFF);
                    return new int[] { f.streamId, inc };
                case TYPE_GOAWAY:
                    recordGoaway(f);
                    throw new IOException("GOAWAY received: error " + goawayErrorCode);
                default:
                    // response frames only appear after the upload completes;
                    // anything else here is skipped
                    break;
            }
        }
    }

    /**
     * HPACK-encode name/value pairs as literal header fields without indexing
     * (0x00 prefix, new name, no Huffman) - the simplest valid representation.
     */
    static byte[] encodeHeaders(String... nameValues) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        for (int i = 0; i < nameValues.length; i += 2) {
            buf.write(0x00);
            writeStringLiteral(buf, nameValues[i].getBytes(StandardCharsets.ISO_8859_1));
            writeStringLiteral(buf, nameValues[i + 1].getBytes(StandardCharsets.ISO_8859_1));
        }
        return buf.toByteArray();
    }

    /** HPACK string literal: 7-bit prefix integer length, Huffman bit clear */
    private static void writeStringLiteral(ByteArrayOutputStream buf, byte[] bytes) {
        int len = bytes.length;
        if (len < 127) {
            buf.write(len);
        } else {
            buf.write(0x7F);
            int remainder = len - 127;
            while (remainder >= 128) {
                buf.write((remainder & 0x7F) | 0x80);
                remainder >>>= 7;
            }
            buf.write(remainder);
        }
        buf.writeBytes(bytes);
    }

    /** decode just the :status field of a response header block, if indexed */
    private static int decodeStatus(byte[] block) {
        if (block == null || block.length == 0) {
            return -1;
        }
        int b = block[0] & 0xFF;
        if ((b & 0x80) != 0) {
            int index = b & 0x7F;
            return switch (index) {
                case 8 -> 200;
                case 9 -> 204;
                case 10 -> 206;
                case 11 -> 304;
                case 12 -> 400;
                case 13 -> 404;
                case 14 -> 500;
                default -> -1;
            };
        }
        return -1;
    }

    private void recordGoaway(Frame f) {
        goawayReceived = true;
        if (f.payload.length >= 8) {
            goawayErrorCode = ((f.payload[4] & 0xFF) << 24) | ((f.payload[5] & 0xFF) << 16)
                | ((f.payload[6] & 0xFF) << 8) | (f.payload[7] & 0xFF);
        }
    }

    private Frame readFrame() throws IOException {
        Frame buffered = pending.poll();
        if (buffered != null) {
            return buffered;
        }
        byte[] header = new byte[9];
        readFully(header);
        int length = ((header[0] & 0xFF) << 16) | ((header[1] & 0xFF) << 8) | (header[2] & 0xFF);
        int type = header[3] & 0xFF;
        int flags = header[4] & 0xFF;
        int streamId = ((header[5] & 0x7F) << 24) | ((header[6] & 0xFF) << 16)
            | ((header[7] & 0xFF) << 8) | (header[8] & 0xFF);
        byte[] payload = new byte[length];
        readFully(payload);
        return new Frame(type, flags, streamId, payload);
    }

    private void writeFrame(int type, int flags, int streamId, byte[] payload) throws IOException {
        byte[] header = new byte[9];
        header[0] = (byte) (payload.length >>> 16);
        header[1] = (byte) (payload.length >>> 8);
        header[2] = (byte) payload.length;
        header[3] = (byte) type;
        header[4] = (byte) flags;
        header[5] = (byte) (streamId >>> 24);
        header[6] = (byte) (streamId >>> 16);
        header[7] = (byte) (streamId >>> 8);
        header[8] = (byte) streamId;
        out.write(header);
        out.write(payload);
        out.flush();
    }

    private void readFully(byte[] buffer) throws IOException {
        int off = 0;
        while (off < buffer.length) {
            int n = in.read(buffer, off, buffer.length - off);
            if (n == -1) {
                throw new IOException("connection closed while reading frame");
            }
            off += n;
        }
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
