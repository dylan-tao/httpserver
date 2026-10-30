package robaho.net.httpserver.http2;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Minimal h2c (cleartext, prior knowledge) HTTP/2 test client speaking raw frames over a socket. The JDK HttpClient
 * cannot do prior-knowledge h2c, so the compliance tests drive the protocol by hand.
 * Only what the tests need is implemented: HPACK literal-without-indexing request encoding, HEADERS / CONTINUATION /
 * DATA / RST_STREAM writes, and a frame reader that acknowledges SETTINGS and PING while collecting per-stream
 * responses. Frames that arrive for a stream other than the one being awaited are buffered and replayed for the next
 * await, so interleaved streams work.
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

  /**
   * the collected outcome for one stream
   * @param status
   *          decoded :status, -1 when absent/undecoded
   * @param data
   *          concatenated DATA payloads
   * @param rstErrorCode
   *          RST_STREAM error code for this stream, if any
   * @param goaway
   *          the connection saw a GOAWAY
   */
  record Response(int status, byte[] data, Integer rstErrorCode, boolean goaway) {}

  Response awaitResponse(int streamId) throws IOException {
    return awaitResponse(streamId, 15_000);
  }

  /**
   * Read frames until the response for {@code streamId} completes (END_STREAM or RST_STREAM). SETTINGS / PING are
   * answered, WINDOW_UPDATE is skipped. Frames for other streams are held back locally and re-queued on exit -
   * putting them straight back into {@code pending} would make readFrame() re-poll them forever. The deadline is
   * wall-clock: the server's 1s idle pings keep resetting the socket read timeout, so a socket timeout alone would
   * never fire.
   */
  /**
   * answer the server's 1s idle keepalives: a non-ACK SETTINGS gets an ACK, a non-ACK PING gets a PONG with the same
   * payload
   */
  private void answerKeepalive(Frame f) throws IOException {
    if (f.has(FLAG_ACK)) {
      return;
    }
    if (f.type == TYPE_SETTINGS) {
      writeFrame(TYPE_SETTINGS, FLAG_ACK, 0, new byte[0]);
    } else if (f.type == TYPE_PING) {
      writeFrame(TYPE_PING, FLAG_ACK, 0, f.payload);
    }
  }

  /**
   * re-queue held-back frames, preserving arrival order (adding straight into pending would make readFrame() re-poll
   * them forever)
   */
  private void requeueHeldBack(List<Frame> heldBack) {
    for (int i = heldBack.size() - 1; i >= 0; i--) {
      pending.addFirst(heldBack.get(i));
    }
  }

  /**
   * 32-bit big-endian decode with a length guard: a truncated RST_STREAM/WINDOW_UPDATE payload fails with a
   * diagnosable message instead of an ArrayIndexOutOfBoundsException
   */
  private static int readInt32(byte[] b, int off) throws IOException {
    if (b.length < off + 4) {
      throw new IOException("truncated 32-bit field (len=" + b.length + ", off=" + off + ")");
    }
    return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16) | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
  }

  private static void writeInt32(byte[] b, int off, int value) {
    b[off] = (byte) (value >>> 24);
    b[off + 1] = (byte) (value >>> 16);
    b[off + 2] = (byte) (value >>> 8);
    b[off + 3] = (byte) value;
  }

  Response awaitResponse(int streamId, long timeoutMillis) throws IOException {
    long deadline = System.currentTimeMillis() + timeoutMillis;
    ByteArrayOutputStream headers = new ByteArrayOutputStream();
    ByteArrayOutputStream data = new ByteArrayOutputStream();
    List<Frame> heldBack = new ArrayList<>();
    try {
      while (true) {
        if (System.currentTimeMillis() > deadline) {
          throw new IOException(
              "timed out (wall clock) waiting for response on stream " + streamId + " (goaway=" + goawayReceived + ")");
        }
        Frame f;
        try {
          f = readFrame();
        } catch (SocketTimeoutException e) {
          throw new IOException("timed out waiting for response on stream " + streamId + " (goaway=" + goawayReceived + ")", e);
        }
        switch (f.type) {
          case TYPE_SETTINGS:
          case TYPE_PING:
            answerKeepalive(f);
            break;
          case TYPE_WINDOW_UPDATE:
            // hold back instead of discarding: a connection-level
            // WINDOW_UPDATE arriving while awaiting a response is
            // real credit for the client's send window. dropping it
            // desyncs upload probes that track the window across
            // awaitResponse calls - they then wait for an update
            // the server (correctly) never re-sends
            heldBack.add(f);
            break;
          case TYPE_GOAWAY:
            recordGoaway(f);
            return new Response(-1, data.toByteArray(), null, true);
          case TYPE_RST_STREAM:
            if (f.streamId == streamId) {
              return new Response(-1, data.toByteArray(), readInt32(f.payload, 0), goawayReceived);
            }
            heldBack.add(f);
            break;
          case TYPE_HEADERS:
            if (f.streamId == streamId) {
              headers.writeBytes(f.payload);
              if (f.has(FLAG_END_STREAM)) {
                return new Response(decodeStatus(headers.toByteArray()), data.toByteArray(), null, goawayReceived);
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
                return new Response(decodeStatus(headers.toByteArray()), data.toByteArray(), null, goawayReceived);
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
      requeueHeldBack(heldBack);
    }
  }

  /** open a new stream with a request; returns the (odd) stream id */
  int sendRequest(String method, String path, boolean endStream) throws IOException {
    int streamId = nextStreamId;
    nextStreamId += 2;
    return sendRequest(streamId, method, path, endStream);
  }

  /**
   * open a new stream with extra header pairs appended to the pseudo headers (e.g. content-length for the
   * request-body-cap tests)
   */
  int sendRequest(String method, String path, boolean endStream, String... extraNameValues) throws IOException {
    int streamId = nextStreamId;
    nextStreamId += 2;
    String[] all = new String[8 + extraNameValues.length];
    all[0] = ":method";
    all[1] = method;
    all[2] = ":scheme";
    all[3] = "http";
    all[4] = ":path";
    all[5] = path;
    all[6] = ":authority";
    all[7] = "127.0.0.1";
    System.arraycopy(extraNameValues, 0, all, 8, extraNameValues.length);
    byte[] block = encodeHeaders(all);
    writeFrame(TYPE_HEADERS, FLAG_END_HEADERS | (endStream ? FLAG_END_STREAM : 0), streamId, block);
    return streamId;
  }

  /**
   * send a request on an explicitly chosen stream id (multi-lane benchmarks: ids must be odd and strictly increasing
   * per connection)
   */
  int sendRequest(int streamId, String method, String path, boolean endStream) throws IOException {
    byte[] block = encodeHeaders(":method", method, ":scheme", "http", ":path", path, ":authority", "127.0.0.1");
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
    writeInt32(payload, 0, errorCode);
    writeFrame(TYPE_RST_STREAM, 0, streamId, payload);
  }

  /** raw frame write, for hand-built HEADERS+CONTINUATION sequences */
  void writeRawFrame(int type, int flags, int streamId, byte[] payload) throws IOException {
    writeFrame(type, flags, streamId, payload);
  }

  /**
   * Blocking read of the next WINDOW_UPDATE (stream-level or connection level), answering SETTINGS / PING keepalives
   * on the way. Returns {@code {streamId, increment}}. Used by upload probes that track both flow-control windows
   * while sending. Response frames for streams that completed early (multi-stream uploads) are held back and
   * re-queued for the later awaitResponse - discarding them would lose the response.
   */
  int[] readWindowUpdate() throws IOException {
    List<Frame> heldBack = new ArrayList<>();
    try {
      while (true) {
        Frame f = readFrame();
        switch (f.type) {
          case TYPE_SETTINGS:
          case TYPE_PING:
            answerKeepalive(f);
            break;
          case TYPE_WINDOW_UPDATE:
            return new int[] {f.streamId, readInt32(f.payload, 0)};
          case TYPE_GOAWAY:
            recordGoaway(f);
            throw new IOException("GOAWAY received: error " + goawayErrorCode);
          default:
            // response frames of streams that already completed:
            // hold them back (re-queueing straight into pending
            // would make readFrame() re-poll them forever)
            heldBack.add(f);
            break;
        }
      }
    } finally {
      requeueHeldBack(heldBack);
    }
  }

  /**
   * HPACK-encode name/value pairs as literal header fields without indexing (0x00 prefix, new name, no Huffman) - the
   * simplest valid representation.
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

  /**
   * decode just the :status field of a response header block: the indexed static-table form, or the literal form the
   * server uses for statuses outside the static table (e.g. 413)
   */
  private static int decodeStatus(byte[] block) {
    if (block == null || block.length == 0) {
      return -1;
    }
    int b = block[0] & 0xFF;
    // the literal forms the server's encoder can use for a status outside
    // the static table (e.g. 413): with incremental indexing (0x40|idx),
    // without indexing (0x0|idx), never-indexed (0x10|idx). the name
    // index is ANY static :status entry (8..14) - the encoder picks the
    // entry its name lookup finds, e.g. 14 (":status 500") for :status.
    // all carry the value as a length-prefixed string after the prefix
    int idx6 = b & 0x3F, idx4 = b & 0x0F;
    boolean literalStatus = ((b & 0xC0) == 0x40 && idx6 >= 8 && idx6 <= 14) || ((b & 0xF0) == 0x00 && idx4 >= 8 && idx4 <= 14)
        || ((b & 0xF0) == 0x10 && idx4 >= 8 && idx4 <= 14);
    if (literalStatus && block.length > 1) {
      // mask the huffman bit before reading the length: the server
      // huffman-codes any value whose coding is shorter (e.g. "103"),
      // and the old blind length read turned a 0x85 huffman length into
      // a bogus raw length - statuses like 100/103/202/301 decoded as -1
      int first = block[1] & 0xFF;
      boolean huffman = (first & 0x80) != 0;
      int valueLen = first & 0x7F;
      if (valueLen > 0 && 2 + valueLen <= block.length) {
        try {
          String value = huffman ? robaho.net.httpserver.http2.hpack.Huffman.decode(block, 2, valueLen)
              : new String(block, 2, valueLen, StandardCharsets.US_ASCII);
          return Integer.parseInt(value.trim());
        } catch (NumberFormatException | robaho.net.httpserver.http2.Http2Exception e) {
          return -1;
        }
      }
      return -1;
    }
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
      try {
        goawayErrorCode = readInt32(f.payload, 4);
      } catch (IOException e) {
        goawayErrorCode = -1;
      }
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
    // the top bit of the stream id is reserved: mask it off the 32-bit read
    int streamId = (readInt32(header, 5) & 0x7FFFFFFF);
    byte[] payload = new byte[length];
    readFully(payload);
    if (type == TYPE_DATA && payload.length > 0) {
      // return BOTH windows' credit at wire receipt: the bytes are
      // consumed from the socket regardless of which stream's
      // awaitResponse processes the frame later. deferring the credit
      // until the right await parks every server writer once the
      // connection send window is exhausted by other streams'
      // responses. credited HERE only - re-read frames from pending
      // are already credited, so there is no double return
      writeWindowUpdate(0, payload.length);
      writeWindowUpdate(streamId, payload.length);
    }
    return new Frame(type, flags, streamId, payload);
  }

  /** send a WINDOW_UPDATE frame returning flow control credit */
  void writeWindowUpdate(int streamId, int increment) throws IOException {
    byte[] payload = new byte[4];
    writeInt32(payload, 0, increment);
    writeFrame(TYPE_WINDOW_UPDATE, 0, streamId, payload);
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
