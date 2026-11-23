package robaho.net.httpserver.http2.hpack;

import com.sun.net.httpserver.Headers;
import robaho.net.httpserver.http2.Http2ErrorCode;
import robaho.net.httpserver.http2.Http2Exception;
import robaho.net.httpserver.http2.frame.FrameFlag;
import robaho.net.httpserver.http2.frame.FrameFlag.FlagSet;
import robaho.net.httpserver.http2.frame.FrameHeader;
import robaho.net.httpserver.http2.frame.FrameType;
import robaho.net.httpserver.internal.OpenAddressMap;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class HPackContext {

  /**
   * interning table for literal header NAME strings: client libraries
   * that do not index (e.g. simple writers) resend the same names
   * every request, and each decode materialized a fresh String -
   * JFR showed 28MB/30k requests here. the map is bounded: novel names
   * (scanners, fuzzers) cannot grow it beyond cap entries, common names
   * hit the resident copy and the transient String dies young
   */
  private static final int NAME_INTERN_CAP = 256;

  private static final java.util.concurrent.ConcurrentHashMap<String, String> NAME_INTERN =
      new java.util.concurrent.ConcurrentHashMap<>(256);

  private static String intern(String s) {
    // single-put: absent -> put and use the canonical; present -> use it
    String canonical = NAME_INTERN.putIfAbsent(s, s);
    if (canonical != null) {
      return canonical;
    }
    if (NAME_INTERN.size() > NAME_INTERN_CAP && NAME_INTERN.remove(s, s)) {
      // over cap and we were the last writer: drop ourselves, fall back
      // to the transient string. a benign race keeps an extra entry in
      // flight; the bound is approximate by design
      return s;
    }
    return s;
  }

  /**
   * deque: addFirst (newest, lowest index) and evict-pollLast (oldest)
   * are both O(1), unlike an ArrayList where add(0,..) shifts the table
   */
  private final ArrayDeque<Http2HeaderField> dynamicTable = new ArrayDeque<>(64);

  /** current dynamic table size in bytes (RFC 7541 section 4.1) */
  private int dynamicTableSize;

  private int maxDynamicTableSize = 4096;

  public HPackContext() {}

  public Http2HeaderField getHeaderField(int index) {
    if (index > 0 && index <= 61) {
      return RFC7541StaticTable.getHeaderField(index);
    } else {
      // walk the deque: index 62 is the most recently added entry
      int i = index - 62;
      for (var field : dynamicTable) {
        if (i-- == 0) {
          return field;
        }
      }
      return null;
    }
  }

  public void addHeaderField(Http2HeaderField field) {
    dynamicTable.addFirst(field);
    dynamicTableSize += fieldSize(field);
    evict();
  }

  /** the size of a field entry, in bytes, as defined by RFC 7541 section 4.1 */
  private static int fieldSize(Http2HeaderField field) {
    return field.name.length() + (field.value == null ? 0 : field.value.length()) + 32;
  }

  /** evict entries from the end of the table until it fits the configured maximum */
  private void evict() {
    while (dynamicTableSize > maxDynamicTableSize && !dynamicTable.isEmpty()) {
      var removed = dynamicTable.pollLast();
      dynamicTableSize -= fieldSize(removed);
    }
  }

  public List<Http2HeaderField> decodeFieldSegments(byte[] buffer) throws Http2Exception {
    List<Http2HeaderField> headers = new ArrayList<>(8);
    decodeInto(buffer, headers);
    return headers;
  }

  /**
   * decode straight into the caller's list (cleared first): the h2 read
   * path's lists are reader-thread-confined, so decoding appends into the
   * recycled HeaderFieldList directly instead of allocating an
   * intermediate list that addAll then copies field by field
   */
  public void decodeInto(byte[] buffer, List<Http2HeaderField> headers) throws Http2Exception {
    headers.clear();
    int index = 0;

    try {

      while (index < buffer.length) {
        Http2HeaderField headerField = new Http2HeaderField();
        if ((buffer[index] & 0x80) != 0) {
          index = decodeIndexedHeaderField(buffer, index, headerField);
        } else if ((buffer[index] & 0x40) != 0) {
          // Literal Header Field with Incremental Indexing
          index = decodeFieldWithIncrementalIndexing(buffer, index, headerField);
        } else if ((buffer[index] & 0xF0) == 0) {
          // Literal Header Field without Indexing
          index = decodeLiteralFieldWithoutIndexing(buffer, index, headerField);
        } else if ((buffer[index] & 0xF0) == 0x10) {
          // Literal Header Field never Indexed
          // never-indexed and without-indexing decode identically: the 4-bit
          // prefix distinction was consumed by decodeFieldSegments
          index = decodeLiteralFieldWithoutIndexing(buffer, index, headerField);
        } else if ((buffer[index] & 0xE0) == 0x20) {
          if (!headers.isEmpty()) {
            throw new Http2Exception(Http2ErrorCode.COMPRESSION_ERROR,
                "Dynamic table size update must occur at beginning of block");
          }
          index = decodeDynamicTableSizeUpdate(buffer, index);
          continue;
        } else {
          throw new Http2Exception(Http2ErrorCode.COMPRESSION_ERROR, "Invalid header field representation " + buffer[index]);
        }
        headers.add(headerField);
      }
    } catch (Http2Exception e) {
      throw e;
    } catch (Exception e) {
      throw new Http2Exception(Http2ErrorCode.COMPRESSION_ERROR, e);
    }
  }

  private int decodeIndexedHeaderField(byte[] buffer, int index, Http2HeaderField headerField) throws Http2Exception {
    long packed = decodeUnsignedInteger(buffer, index, 7);
    int headerIndex = packedValue(packed);
    index = packedIndex(packed);

    Http2HeaderField field = getHeaderField(headerIndex);
    if (field == null) {
      throw new Http2Exception(Http2ErrorCode.COMPRESSION_ERROR,
          "Invalid header index " + headerIndex + ", dynamic: " + dynamicTable);
    }

    headerField.setName(field.name, field.normalizedName);
    headerField.setValue(field.value);

    return index;
  }

  private int decodeFieldWithIncrementalIndexing(byte[] buffer, int index, Http2HeaderField headerField) throws Http2Exception {
    long packed = decodeUnsignedInteger(buffer, index, 6);
    int headerIndex = packedValue(packed);
    index = packedIndex(packed);

    index = decodeFieldName(buffer, index, headerIndex, headerField);
    index = decodeFieldValue(buffer, index, headerField);

    addHeaderField(headerField);

    return index;
  }

  private int decodeLiteralFieldWithoutIndexing(byte[] buffer, int index, Http2HeaderField headerField) throws Http2Exception {
    long packed = decodeUnsignedInteger(buffer, index, 4);
    int headerIndex = packedValue(packed);
    index = packedIndex(packed);

    index = decodeFieldName(buffer, index, headerIndex, headerField);
    index = decodeFieldValue(buffer, index, headerField);
    return index;
  }

  private int decodeDynamicTableSizeUpdate(byte[] buffer, int index) throws Http2Exception {
    long packed = decodeUnsignedInteger(buffer, index, 5);
    int size = packedValue(packed);
    index = packedIndex(packed);

    if (size > 4096) { // Assuming 4096 is the maximum size for the dynamic table
      throw new Http2Exception(Http2ErrorCode.COMPRESSION_ERROR, "Dynamic table size update too large: " + size);
    }

    maxDynamicTableSize = size;
    evict();

    return index;
  }

  private int decodeFieldName(byte[] buffer, int index, int headerIndex, Http2HeaderField headerField) throws Http2Exception {
    if (headerIndex == 0) {
      boolean huffmanCode = (buffer[index] & 0x80) != 0;
      long packed = decodeUnsignedInteger(buffer, index, 7);
      index = packedIndex(packed);
      int length = packedValue(packed);
      if (index + length > buffer.length) {
        throw new Http2Exception(Http2ErrorCode.COMPRESSION_ERROR, "header field name length exceeds header block");
      }
      // decode IN PLACE: the bounds are validated above, so the
      // copyOfRange allocated a duplicate of every name for nothing
      String value;
      if (huffmanCode) {
        value = intern(Huffman.decode(buffer, index, length));
      } else {
        value = intern(new String(buffer, index, length, java.nio.charset.StandardCharsets.UTF_8));
      }
      index += length;
      // zero-allocation lowercase check: toLowerCase() copied the whole
      // name on every literal header field
      for (int i = 0; i < value.length(); i++) {
        char c = value.charAt(i);
        if (c >= 'A' && c <= 'Z') {
          throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, "header field name is not lowercase " + value);
        }
      }
      headerField.setName(value);
    } else {
      var field = getHeaderField(headerIndex);
      if (field == null) {
        throw new Http2Exception(Http2ErrorCode.COMPRESSION_ERROR, "Invalid header index " + headerIndex);
      }
      headerField.setName(field.name, field.normalizedName);
    }
    return index;
  }

  private int decodeFieldValue(byte[] buffer, int index, Http2HeaderField headerField) throws Http2Exception {
    // Check if Huffman coding is used
    boolean huffmanCode = (buffer[index] & 0x80) != 0;
    long packed = decodeUnsignedInteger(buffer, index, 7);
    index = packedIndex(packed);
    int length = packedValue(packed);
    // a length beyond the end of the block is rejected before any decode
    if (index + length > buffer.length) {
      throw new Http2Exception(Http2ErrorCode.COMPRESSION_ERROR, "header field value length exceeds header block");
    }

    // decode IN PLACE (bounds validated above): the copyOfRange allocated
    // a duplicate of every value for nothing
    String value;
    if (huffmanCode) {
      value = Huffman.decode(buffer, index, length);
    } else {
      // explicit UTF-8: header values are UTF-8 (RFC 7540 8.1.2); the
      // default charset must not be assumed
      value = new String(buffer, index, length, java.nio.charset.StandardCharsets.UTF_8);
    }
    headerField.setValue(value);

    return index + length;
  }

  /**
   * decode an HPACK varint, returning (value << 32) | nextIndex packed in a
   * long: the previous record-per-varint allocated 1-3 objects per header
   * field (field index, name length, value length)
   */
  private static long decodeUnsignedInteger(byte[] buffer, int index, int prefixBits) throws Http2Exception {
    int value = buffer[index] & ((1 << prefixBits) - 1);
    index++;
    if (value < ((1 << prefixBits) - 1)) {
      return ((long) value << 32) | index;
    }

    // accumulate in a long with an explicit bound: an unbounded int
    // accumulator wraps on >=6 continuation bytes (at m=28 the shift
    // sets the sign bit, at m>=35 the shift count silently wraps mod
    // 32). a wrapped NEGATIVE value used to pass the dynamic-table
    // size-update check (> 4096) and silently wipe the table, permanently
    // desyncing the connection's HPACK state from the peer's. RFC 7541
    // 5.1: overlong or out-of-range integer encodings MUST be treated
    // as decoding errors
    long acc = value;
    int m = 0;
    int b;
    do {
      b = buffer[index] & 0xFF;
      acc += (b & 0x7FL) << m;
      // reject BOTH overflow directions AND overlong encodings: at
      // m=63 the long sign bit wraps acc negative (passes a plain
      // > MAX_VALUE check), and from m=70 the shift count wraps mod 64
      // landing bytes in wrong positions. a 31-bit value needs at most
      // 5 continuation bytes; more is either overlong or overflowing
      if (acc > Integer.MAX_VALUE || acc < 0 || m >= 35) {
        throw new Http2Exception(Http2ErrorCode.COMPRESSION_ERROR, "integer encoding overflows 31 bits or is overlong");
      }
      m += 7;
      index++;
    } while ((b & 0x80) != 0);

    return (acc << 32) | index;
  }

  private static int packedValue(long packed) {
    return (int) (packed >>> 32);
  }

  private static int packedIndex(long packed) {
    return (int) packed;
  }

  /**
   * encode a response header block into an exact-size HPACK fragment,
   * ready to be streamed as HEADERS (+CONTINUATION) frames split at the
   * PEER's advertised SETTINGS_MAX_FRAME_SIZE (RFC 7540 4.3/6.5.2:
   * outbound frames must obey the receiver's limit) - the caller passes
   * {@code connection.remoteMaxFrameSize()} to
   * {@link EncodedHeaderBlock#writeFrames}.
   * encoding runs OUTSIDE the connection lock: the caller takes the lock
   * only for the writes - the encoding (string building, HPACK) is
   * stream-local, and keeping it out of the critical section lets
   * concurrent streams on a connection scale with cores.
   * the old path built a ByteArrayOutputStream, copied it out with
   * toByteArray(), then copied each fragment into a fresh byte[9+len]
   * frame array: this block is encoded positionally at its final size,
   * and each frame is written as the 9-byte header through the caller's
   * scratch plus a direct slice of this block - no block-level copy, no
   * per-frame array allocations
   * <p>TODO (post-3.0, bandwidth feature - NOT throughput): the encoder
   * emits every VALUE as a literal (static-table names indexed, huffman
   * chosen per RFC 7541 5.2 shorter-of); it never INDEXES into a dynamic
   * table, so repeated name-value pairs (server, content-type, cookies)
   * are re-encoded per response. adding an encoder-side dynamic table
   * with reverse indices (see "Exploring HTTP/2 Header Compression",
   * CFI'17, the nghttp2/h2o authors - https://www.mew.org/~kazu/doc/paper/hpack-2017.pdf)
   * would cut outbound header bytes roughly 2-3x (their measured
   * compression ratios: static+huffman ~0.66, +dynamic table ~0.31) but
   * measured rps on loopback benchmarks stays within noise - the wins
   * are egress bytes on WAN/traffic-billed deployments only. the cost:
   * HPACK state must advance in frame order, forcing encoding back
   * under the connection lock (serializing concurrent streams), plus
   * per-connection reverse indices, peer SETTINGS_HEADER_TABLE_SIZE
   * negotiation, and never-indexed handling for set-cookie. the decoder
   * side (dynamic table + huffman decode) is already complete.
   */
  public static EncodedHeaderBlock encodeHeaderBlock(Headers headers) throws IOException {
    return encodeHeaderBlock(headers, null);
  }

  /**
   * encode the response headers into the given reusable block when it is
   * free (borrow CAS), otherwise into a fresh one. the CALLER must
   * release the block after writeFrames consumed it - the returned
   * flag tells whether the scratch was used. connection-level reuse:
   * the pooled block retains the peak capacity this connection encoded,
   * so steady-state responses (same shape) stop allocating entirely
   */
  public static EncodedHeaderBlock encodeHeaderBlock(Headers headers, EncodedHeaderBlock scratch) throws IOException {
    // ':status' is required and the only allowed outbound pseudo header.
    // checked BEFORE borrowing the scratch (audit W11/M5): a throw after a
    // successful tryBorrow() never released it, permanently locking the
    // connection's scratch - every later response on that connection
    // allocated a fresh block for its remaining lifetime
    String status = headers.getFirst(":status");
    if (status == null) {
      throw new IOException("response is missing the required :status pseudo header");
    }
    // sized from the header count: the old fixed 256 started ~2 growth
    // copies behind for a typical response (average block ~800 bytes)
    EncodedHeaderBlock fields =
        scratch != null && scratch.tryBorrow() ? scratch : new EncodedHeaderBlock(Math.max(256, headers.size() * 64 + 64));
    // release on ANY failure from here on (re-audit W11 residual): the
    // callers' release() sits in a finally that an encode-phase throw
    // never reaches - an exception after a successful borrow left the
    // scratch permanently borrowed (every later response on the
    // connection allocated fresh)
    try {
      encodeInto(fields, status, headers);
    } catch (Throwable t) {
      fields.release();
      throw t;
    }
    return fields;
  }

  /** the append loop shared by the failure-safe wrapper (see above) */
  private static void encodeInto(EncodedHeaderBlock fields, String status, Headers headers) throws IOException {
    fields.reset();
    fields.appendField(":status", status);

    // raw iteration when possible: forEach() wraps every single-valued
    // header in a mutable ArrayList (its contract), which is pure
    // allocation on the encoder's read-only path
    if (headers instanceof robaho.net.httpserver.internal.OptimizedHeaders optimized) {
      optimized.forEachRaw((name, v) -> {
        if (name.charAt(0) == ':') {
          return;
        }
        String lower = Http2HeaderField.lowercase(name);
        if (v instanceof String single) {
          fields.appendField(lower, single);
        } else if (v instanceof List<?> values) {
          for (String value : (List<String>) values) {
            fields.appendField(lower, value);
          }
        }
        // v == OptimizedHeaders' NULL_VALUE marker (a single null
        // header value): skipped - a null cannot be encoded, and
        // failing loudly here would tear down the whole connection
        // over one malformed header. the h1 write path reports null
        // response header values as an error instead; h2 encoders
        // only ever see them via direct map manipulation
      });
    } else {
      headers.forEach((name, values) -> {
        if (name.charAt(0) == ':') {
          return;
        }
        String lower = Http2HeaderField.lowercase(name);
        for (String value : values) {
          fields.appendField(lower, value);
        }
      });
    }
  }

  /**
   * an encoded HPACK response block with direct frame streaming. grown
   * geometrically while encoding (a typical response fits the initial
   * size); {@link #writeFrames} emits HEADERS/CONTINUATION frames as the
   * 9-byte frame header through the caller's scratch plus direct block
   * slices, replacing the old toByteArray() copy and the per-frame
   * byte[9+len] allocations + arraycopies
   */
  public static final class EncodedHeaderBlock {

    private byte[] buf;

    private int len;

    /**
     * FREE/BORROWED latch for connection-level reuse: encoding runs
     * OUTSIDE the connection lock (concurrent streams race), so
     * borrowing is a CAS - a busy block means the caller allocates its
     * own and the pooled one returns after the next write. the reuse
     * needs no serialization of concurrent encoders
     */
    private final java.util.concurrent.atomic.AtomicBoolean borrowed = new java.util.concurrent.atomic.AtomicBoolean();

    public EncodedHeaderBlock(int capacity) {
      buf = new byte[capacity];
    }

    /** try to claim this block for reuse; false when already in flight */
    public boolean tryBorrow() {
      return borrowed.compareAndSet(false, true);
    }

    /** release after writeFrames consumed the block; same thread that borrowed */
    public void release() {
      reset();
      if (buf.length > RETAIN_CAPACITY_LIMIT) {
        // a 1MB response head must not pin 1MB per idle connection for the
        // connection's remaining lifetime (audit W11/M5: 200 such
        // connections = 200MB). one oversized response pays one re-grow;
        // steady-state shapes (a few KB) stay under the limit untouched
        buf = new byte[512];
      }
      borrowed.set(false);
    }

    /**
     * released blocks above this capacity shrink back to the initial size:
     * typical response heads are under 8KB; anything above is an outlier
     * whose capacity must not be retained
     */
    private static final int RETAIN_CAPACITY_LIMIT = 64 * 1024;

    /**
     * drop the length, keep the capacity: called by encodeHeaderBlock on
     * a freshly borrowed (or fresh) block before appending, and by
     * release() after consumption
     */
    void reset() {
      len = 0;
    }

    private void ensure(int extra) {
      if (len + extra > buf.length) {
        buf = java.util.Arrays.copyOf(buf, Math.max(buf.length * 2, len + extra));
      }
    }

    private void append(byte[] encoded) {
      ensure(encoded.length);
      System.arraycopy(encoded, 0, buf, len, encoded.length);
      len += encoded.length;
    }

    /**
     * scratch for stringEncodedSize to hand its huffman length to
     * encodeStringInto: an instance field (not a fresh int[1] per field)
     * because appendField runs per response header under the connection
     * lock on the single writer thread
     */
    private final int[] huffLenScratch = new int[1];

    /**
     * append one encoded header field, written positionally: the old
     * encodeHeader() allocated an exact-size byte[] per field and the
     * buffer absorbed it with another copy. the value's UTF-8 bytes
     * remain one allocation (String.getBytes is the only portable
     * encoder); the huffman length is computed over them exactly once
     * and threaded through the sizing and encoding passes
     */
    void appendField(String name, String value) {
      if (":status".equals(name)) {
        byte[] result = RFC7541StaticTable.STATUSES.get(value);
        if (result != null) {
          append(result);
          return;
        }
      }
      var index = RFC7541StaticTable.getIndex(name);
      byte[] valueBytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
      if (index != null) {
        ensure(indexedFieldEncodedSize(index, 4) + stringEncodedSize(valueBytes, huffLenScratch));
        int valueHuffLen = huffLenScratch[0];
        int pos = encodeIndexedFieldInto(buf, len, index, 4);
        len = encodeStringInto(buf, pos, valueBytes, valueHuffLen);
      } else {
        byte[] nameBytes = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int nameSize = stringEncodedSize(nameBytes, huffLenScratch);
        int nameHuffLen = huffLenScratch[0];
        int valueSize = stringEncodedSize(valueBytes, huffLenScratch);
        int valueHuffLen = huffLenScratch[0];
        ensure(1 + nameSize + valueSize);
        buf[len] = 0x00; // Literal Header Field without Indexing
        int pos = encodeStringInto(buf, len + 1, nameBytes, nameHuffLen);
        len = encodeStringInto(buf, pos, valueBytes, valueHuffLen);
      }
    }

    /**
     * stream this block as complete HEADERS/CONTINUATION frames into
     * the connection output stream: per frame the 9-byte header goes
     * through the caller's scratch and the fragment is a direct slice.
     * the frame-split and flag logic is byte-identical to the old
     * encodeHeaderBlockFrames. returns the frame count for stats.
     * MUST run under the connection lock
     */
    public int writeFrames(java.io.OutputStream os, byte[] frameHeaderScratch, int streamId, boolean endStream,
        int maxFrameSize) throws IOException {
      int off = 0;
      int frames = 0;
      while (true) {
        int fragLen = Math.min(len - off, maxFrameSize);
        boolean last = off + fragLen == len;
        FlagSet flags;
        if (off == 0) {
          flags = last ? (endStream ? END_OF_HEADERS_AND_STREAM : END_OF_HEADERS)
              : (endStream ? FlagSet.of(FrameFlag.END_STREAM) : FrameFlag.NONE);
        } else {
          flags = last ? END_OF_HEADERS : FrameFlag.NONE;
        }
        FrameHeader.encodeInto(frameHeaderScratch, fragLen, off == 0 ? FrameType.HEADERS : FrameType.CONTINUATION, flags,
            streamId);
        os.write(frameHeaderScratch, 0, FrameHeader.LENGTH);
        os.write(buf, off, fragLen);
        frames++;
        off += fragLen;
        if (last) {
          return frames;
        }
      }
    }

  }

  private static final FlagSet END_OF_HEADERS = FlagSet.of(FrameFlag.END_HEADERS);

  private static final FlagSet END_OF_HEADERS_AND_STREAM = FlagSet.of(FrameFlag.END_HEADERS, FrameFlag.END_STREAM);

  /**
   * exact encoded size of a string literal: huffman when shorter (RFC 7541
   * 5.2 "SHOULD"), literal otherwise - varint length prefix + payload.
   * also returns the huffman length via the encodedLengths out-param so
   * the caller can pass it to encodeStringInto, which must make the SAME
   * huffman-vs-literal decision without re-walking the code table (the
   * old shape computed Huffman.encodedLength twice per field)
   */
  private static int stringEncodedSize(byte[] value, int[] encodedLengths) {
    int huffLen = Huffman.encodedLength(value);
    encodedLengths[0] = huffLen;
    if (huffLen < value.length) {
      return stringLengthPrefixSize(huffLen) + huffLen;
    }
    return stringLengthPrefixSize(value.length) + value.length;
  }

  /** size of the 7-bit prefix varint length prefix for a payload of len bytes */
  private static int stringLengthPrefixSize(int len) {
    if (len < 127) {
      return 1;
    }
    int remainder = len - 127;
    int varintBytes = 1;
    while (remainder >= 128) {
      varintBytes++;
      remainder >>>= 7;
    }
    return 1 + varintBytes;
  }

  /**
   * write a string literal into the target buffer at pos: huffman-coded
   * when shorter (flag bit set), raw otherwise. the huffLen argument is
   * the length the sizing pass (stringEncodedSize) already computed -
   * the two MUST agree, so it is passed instead of recomputed. returns
   * the new position
   */
  private static int encodeStringInto(byte[] out, int pos, byte[] value, int huffLen) {
    if (huffLen < value.length) {
      pos = encodeLengthPrefix(out, pos, huffLen, true);
      return Huffman.encodeInto(value, out, pos);
    }
    pos = encodeLengthPrefix(out, pos, value.length, false);
    System.arraycopy(value, 0, out, pos, value.length);
    return pos + value.length;
  }

  /**
   * write the 7-bit prefix varint string length (huffman flag bit per the
   * argument) into the target buffer at pos
   */
  private static int encodeLengthPrefix(byte[] out, int pos, int len, boolean huffman) {
    int flag = huffman ? 0x80 : 0x00;
    if (len < 127) {
      out[pos++] = (byte) (flag | len);
    } else {
      out[pos++] = (byte) (flag | 127);
      int remainder = len - 127;
      while (remainder >= 128) {
        out[pos++] = (byte) ((remainder & 0x7F) | 0x80);
        remainder >>>= 7;
      }
      out[pos++] = (byte) remainder;
    }
    return pos;
  }

  /** exact encoded size of an indexed field with the given prefix bits */
  private static int indexedFieldEncodedSize(int index, int prefixBits) {
    int mask = (1 << prefixBits) - 1;
    if (index < mask) {
      return 1;
    }
    index -= mask;
    int size = 2;
    while (index >= 128) {
      size++;
      index >>>= 7;
    }
    return size;
  }

  /**
   * write an indexed field (prefix bits + varint index) into the target
   * buffer at pos, returning the new position
   */
  private static int encodeIndexedFieldInto(byte[] out, int pos, int index, int prefixBits) {
    int mask = (1 << prefixBits) - 1;
    if (index < mask) {
      out[pos++] = (byte) index;
      return pos;
    }
    out[pos++] = (byte) mask;
    index -= mask;
    while (index >= 128) {
      out[pos++] = (byte) ((index & 0x7F) | 0x80);
      index >>>= 7;
    }
    out[pos++] = (byte) index;
    return pos;
  }

}

class RFC7541StaticTable {

  private static final Http2HeaderField[] STATIC_HEADER_TABLE = new Http2HeaderField[62];

  static final Map<String, byte[]> STATUSES = Map.of("200", indexedField(8), "204", indexedField(9), "206", indexedField(10),
      "304", indexedField(11), "400", indexedField(12), "404", indexedField(13), "500", indexedField(14));

  private static byte[] indexedField(int index) {
    byte[] buffer = new byte[1];
    buffer[0] = (byte) (0x80 | index);
    return buffer;
  }

  static {
    STATIC_HEADER_TABLE[1] = new Http2HeaderField(":authority", null);
    STATIC_HEADER_TABLE[2] = new Http2HeaderField(":method", "GET");
    STATIC_HEADER_TABLE[3] = new Http2HeaderField(":method", "POST");
    STATIC_HEADER_TABLE[4] = new Http2HeaderField(":path", "/");
    STATIC_HEADER_TABLE[5] = new Http2HeaderField(":path", "/index.html");
    STATIC_HEADER_TABLE[6] = new Http2HeaderField(":scheme", "http");
    STATIC_HEADER_TABLE[7] = new Http2HeaderField(":scheme", "https");
    STATIC_HEADER_TABLE[8] = new Http2HeaderField(":status", "200");
    STATIC_HEADER_TABLE[9] = new Http2HeaderField(":status", "204");
    STATIC_HEADER_TABLE[10] = new Http2HeaderField(":status", "206");
    STATIC_HEADER_TABLE[11] = new Http2HeaderField(":status", "304");
    STATIC_HEADER_TABLE[12] = new Http2HeaderField(":status", "400");
    STATIC_HEADER_TABLE[13] = new Http2HeaderField(":status", "404");
    STATIC_HEADER_TABLE[14] = new Http2HeaderField(":status", "500");
    STATIC_HEADER_TABLE[15] = new Http2HeaderField("accept-charset", null);
    STATIC_HEADER_TABLE[16] = new Http2HeaderField("accept-encoding", "gzip, deflate");
    STATIC_HEADER_TABLE[17] = new Http2HeaderField("accept-language", null);
    STATIC_HEADER_TABLE[18] = new Http2HeaderField("accept-ranges", null);
    STATIC_HEADER_TABLE[19] = new Http2HeaderField("accept", null);
    STATIC_HEADER_TABLE[20] = new Http2HeaderField("access-control-allow-origin", null);
    STATIC_HEADER_TABLE[21] = new Http2HeaderField("age", null);
    STATIC_HEADER_TABLE[22] = new Http2HeaderField("allow", null);
    STATIC_HEADER_TABLE[23] = new Http2HeaderField("authorization", null);
    STATIC_HEADER_TABLE[24] = new Http2HeaderField("cache-control", null);
    STATIC_HEADER_TABLE[25] = new Http2HeaderField("content-disposition", null);
    STATIC_HEADER_TABLE[26] = new Http2HeaderField("content-encoding", null);
    STATIC_HEADER_TABLE[27] = new Http2HeaderField("content-language", null);
    STATIC_HEADER_TABLE[28] = new Http2HeaderField("content-length", null);
    STATIC_HEADER_TABLE[29] = new Http2HeaderField("content-location", null);
    STATIC_HEADER_TABLE[30] = new Http2HeaderField("content-range", null);
    STATIC_HEADER_TABLE[31] = new Http2HeaderField("content-type", null);
    STATIC_HEADER_TABLE[32] = new Http2HeaderField("cookie", null);
    STATIC_HEADER_TABLE[33] = new Http2HeaderField("date", null);
    STATIC_HEADER_TABLE[34] = new Http2HeaderField("etag", null);
    STATIC_HEADER_TABLE[35] = new Http2HeaderField("expect", null);
    STATIC_HEADER_TABLE[36] = new Http2HeaderField("expires", null);
    STATIC_HEADER_TABLE[37] = new Http2HeaderField("from", null);
    STATIC_HEADER_TABLE[38] = new Http2HeaderField("host", null);
    STATIC_HEADER_TABLE[39] = new Http2HeaderField("if-match", null);
    STATIC_HEADER_TABLE[40] = new Http2HeaderField("if-modified-since", null);
    STATIC_HEADER_TABLE[41] = new Http2HeaderField("if-none-match", null);
    STATIC_HEADER_TABLE[42] = new Http2HeaderField("if-range", null);
    STATIC_HEADER_TABLE[43] = new Http2HeaderField("if-unmodified-since", null);
    STATIC_HEADER_TABLE[44] = new Http2HeaderField("last-modified", null);
    STATIC_HEADER_TABLE[45] = new Http2HeaderField("link", null);
    STATIC_HEADER_TABLE[46] = new Http2HeaderField("location", null);
    STATIC_HEADER_TABLE[47] = new Http2HeaderField("max-forwards", null);
    STATIC_HEADER_TABLE[48] = new Http2HeaderField("proxy-authenticate", null);
    STATIC_HEADER_TABLE[49] = new Http2HeaderField("proxy-authorization", null);
    STATIC_HEADER_TABLE[50] = new Http2HeaderField("range", null);
    STATIC_HEADER_TABLE[51] = new Http2HeaderField("referer", null);
    STATIC_HEADER_TABLE[52] = new Http2HeaderField("refresh", null);
    STATIC_HEADER_TABLE[53] = new Http2HeaderField("retry-after", null);
    STATIC_HEADER_TABLE[54] = new Http2HeaderField("server", null);
    STATIC_HEADER_TABLE[55] = new Http2HeaderField("set-cookie", null);
    STATIC_HEADER_TABLE[56] = new Http2HeaderField("strict-transport-security", null);
    STATIC_HEADER_TABLE[57] = new Http2HeaderField("transfer-encoding", null);
    STATIC_HEADER_TABLE[58] = new Http2HeaderField("user-agent", null);
    STATIC_HEADER_TABLE[59] = new Http2HeaderField("vary", null);
    STATIC_HEADER_TABLE[60] = new Http2HeaderField("via", null);
    STATIC_HEADER_TABLE[61] = new Http2HeaderField("www-authenticate", null);
  }

  private static final OpenAddressMap<String, Integer> STATIC_HEADER_NAME_TO_INDEX = new OpenAddressMap<>(256);
  static {
    // index by loop position: indexOf() inside the stream made this O(n^2)
    for (int i = 1; i < STATIC_HEADER_TABLE.length; i++) {
      var field = STATIC_HEADER_TABLE[i];
      if (field != null) {
        STATIC_HEADER_NAME_TO_INDEX.put(field.name, i);
      }
    }
  }

  public static Integer getIndex(String name) {
    return STATIC_HEADER_NAME_TO_INDEX.get(name);
  }

  public static Http2HeaderField getHeaderField(int index) {
    if (index < 1 || index >= STATIC_HEADER_TABLE.length) {
      return null;
    }
    return STATIC_HEADER_TABLE[index];
  }

}
