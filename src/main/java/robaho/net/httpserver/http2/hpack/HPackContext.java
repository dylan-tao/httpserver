package robaho.net.httpserver.http2.hpack;
import robaho.net.httpserver.internal.OpenAddressMap;
import robaho.net.httpserver.internal.OptimizedHeaders;

import com.sun.net.httpserver.Headers;
import robaho.net.httpserver.http2.Http2ErrorCode;
import robaho.net.httpserver.http2.Http2Exception;
import robaho.net.httpserver.http2.frame.FrameFlag;
import robaho.net.httpserver.http2.frame.FrameFlag.FlagSet;
import robaho.net.httpserver.http2.frame.FrameHeader;
import robaho.net.httpserver.http2.frame.FrameType;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class HPackContext {
    /** deque: addFirst (newest, lowest index) and evict-pollLast (oldest)
     * are both O(1), unlike an ArrayList where add(0,..) shifts the table */
    private final ArrayDeque<Http2HeaderField> dynamicTable = new ArrayDeque<>(64);
    /** current dynamic table size in bytes (RFC 7541 section 4.1) */
    private int dynamicTableSize;
    private int maxDynamicTableSize = 4096;

    public HPackContext() {
    }

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
                } else if((buffer[index] & 0xE0) == 0x20) {
                    if(!headers.isEmpty()) {
                        throw new Http2Exception(Http2ErrorCode.COMPRESSION_ERROR, "Dynamic table size update must occur at beginning of block");
                    }
                    index = decodeDynamicTableSizeUpdate(buffer,index);
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

        return headers;
    }

    private int decodeIndexedHeaderField(byte[] buffer, int index, Http2HeaderField headerField) throws Http2Exception {
        long packed = decodeUnsignedInteger(buffer, index, 7);
        int headerIndex = packedValue(packed);
        index = packedIndex(packed);

        Http2HeaderField field = getHeaderField(headerIndex);
        if (field == null) {
            throw new Http2Exception(Http2ErrorCode.COMPRESSION_ERROR, "Invalid header index " + headerIndex + ", dynamic: "+dynamicTable);
        }

        headerField.setName(field.name,field.normalizedName);
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
                value = Huffman.decode(buffer, index, length);
            } else {
                value = new String(buffer, index, length, java.nio.charset.StandardCharsets.UTF_8);
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
            headerField.setName(field.name,field.normalizedName);
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

    /** decode an HPACK varint, returning (value << 32) | nextIndex packed in a
     * long: the previous record-per-varint allocated 1-3 objects per header
     * field (field index, name length, value length) */
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
                throw new Http2Exception(Http2ErrorCode.COMPRESSION_ERROR,
                    "integer encoding overflows 31 bits or is overlong");
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


    private static void writeField(ByteArrayOutputStream fields, String name, String value) {
        byte[] header = encodeHeader(name, value);
        try {
            fields.write(header);
        } catch (IOException ex) {
        }
    }

    /**
     * encode a response header block into complete HEADERS (+CONTINUATION)
     * frames, splitting at the PEER's advertised SETTINGS_MAX_FRAME_SIZE
     * (RFC 7540 4.3/6.5.2: outbound frames must obey the receiver's limit).
     * callers with a connection pass {@code connection.remoteMaxFrameSize()}.
     * callers run this OUTSIDE the connection lock and take the lock only for
     * the writes: the encoding (string building, HPACK) is stream-local, and
     * keeping it out of the critical section lets concurrent streams on a
     * connection scale with cores
     */
    public static List<byte[]> encodeHeaderFrames(Headers headers, int streamId, boolean endStream, int maxFrameSize) throws IOException {
        // sized from the header count: the old fixed 256 started ~2 growth
        // copies behind for a typical response (average block ~800 bytes)
        ByteArrayOutputStream fields = new ByteArrayOutputStream(Math.max(256, headers.size() * 64 + 64));

        // ':status' is required and the only allowed outbound pseudo headers
        String status = headers.getFirst(":status");
        if (status == null) {
            throw new IOException("response is missing the required :status pseudo header");
        }
        fields.write(encodeHeader(":status", status));

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
                    writeField(fields, lower, single);
                } else {
                    for (String value : (List<String>) v) {
                        writeField(fields, lower, value);
                    }
                }
            });
        } else {
            headers.forEach((name, values) -> {
                if (name.charAt(0) == ':') {
                    return;
                }
                String lower = Http2HeaderField.lowercase(name);
                for (String value : values) {
                    writeField(fields, lower, value);
                }
            });
        }

        return encodeHeaderBlockFrames(fields.toByteArray(), streamId, endStream, maxFrameSize);
    }

    /** split an encoded header block into complete HEADERS/CONTINUATION frames
     * (each a byte[] of frame header + fragment) at the given max frame size */
    private static List<byte[]> encodeHeaderBlockFrames(byte[] block, int streamId, boolean endStream, int maxFrameSize) {
        List<byte[]> frames = new ArrayList<>(2);
        int off = 0;
        while (true) {
            int len = Math.min(block.length - off, maxFrameSize);
            boolean last = off + len == block.length;
            FlagSet flags;
            if (off == 0) {
                flags = last
                    ? (endStream ? END_OF_HEADERS_AND_STREAM : END_OF_HEADERS)
                    : (endStream ? FlagSet.of(FrameFlag.END_STREAM) : FrameFlag.NONE);
            } else {
                flags = last ? END_OF_HEADERS : FrameFlag.NONE;
            }
            byte[] frame = new byte[9 + len];
            FrameHeader.encodeInto(frame, len, off == 0 ? FrameType.HEADERS : FrameType.CONTINUATION, flags, streamId);
            System.arraycopy(block, off, frame, 9, len);
            frames.add(frame);
            off += len;
            if (last) {
                return frames;
            }
        }
    }

    private static final FlagSet END_OF_HEADERS = FlagSet.of(FrameFlag.END_HEADERS);
    private static final FlagSet END_OF_HEADERS_AND_STREAM = FlagSet.of(FrameFlag.END_HEADERS,FrameFlag.END_STREAM);

    private static byte[] encodeHeader(String name, String value) {
        if(":status".equals(name)) {
            byte[] result = RFC7541StaticTable.STATUSES.get(value);
            if(result!=null) return result;
        }
        var index = RFC7541StaticTable.getIndex(name);

        // single allocation for the whole field: the previous version built
        // 2-3 intermediate byte[] per header (encodeString growth via
        // Arrays.copyOf + combineByteArrays), ~6-8 allocations per response
        // header at high h2 RPS
        byte[] nameBytes = null;
        byte[] valueBytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int size;
        if(index!=null) {
            size = indexedFieldEncodedSize(index, 4) + stringEncodedSize(valueBytes);
        } else {
            nameBytes = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            size = 1 + stringEncodedSize(nameBytes) + stringEncodedSize(valueBytes);
        }
        byte[] out = new byte[size];
        int pos;
        if(index!=null) {
            pos = encodeIndexedFieldInto(out, 0, index, 4);
        } else {
            out[0] = 0x00; // Literal Header Field without Indexing
            pos = encodeStringInto(out, 1, nameBytes);
        }
        encodeStringInto(out, pos, valueBytes);
        return out;
    }

    /** exact encoded size of a string literal: huffman when shorter (RFC 7541
     * 5.2 "SHOULD"), literal otherwise - varint length prefix + payload */
    private static int stringEncodedSize(byte[] value) {
        int huffLen = Huffman.encodedLength(value);
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

    /** write a string literal into the target buffer at pos: huffman-coded
     * when shorter (flag bit set), raw otherwise. returns the new position */
    private static int encodeStringInto(byte[] out, int pos, byte[] value) {
        int huffLen = Huffman.encodedLength(value);
        if (huffLen < value.length) {
            pos = encodeLengthPrefix(out, pos, huffLen, true);
            return Huffman.encodeInto(value, out, pos);
        }
        pos = encodeLengthPrefix(out, pos, value.length, false);
        System.arraycopy(value, 0, out, pos, value.length);
        return pos + value.length;
    }

    /** write the 7-bit prefix varint string length (huffman flag bit per the
     * argument) into the target buffer at pos */
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

    /** write an indexed field (prefix bits + varint index) into the target
     * buffer at pos, returning the new position */
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
    static final Map<String,byte[]> STATUSES = Map.of(
        "200",indexedField(8),
        "204",indexedField(9),
        "206",indexedField(10),
        "304",indexedField(11),
        "400",indexedField(12),
        "404",indexedField(13),
        "500",indexedField(14));

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
