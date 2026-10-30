package robaho.net.httpserver.http2.frame;

import robaho.net.httpserver.http2.Http2Exception;
import robaho.net.httpserver.http2.frame.FrameFlag.FlagSet;
import robaho.net.httpserver.http2.internal.BinaryCodec;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Create a frame header object
 *
 * @param length          24-bit unsigned integer value that specifies length of the
 *                        frame
 * @param type            defined as an enum FrameType, it identifies the type of the
 *                        frame
 * @param flags           defined as an EnumSet&lt;FrameFlag&gt;, it identifies flags associated with a
 *                        particular frame
 * @param streamIdentifier 31-bit unsigned integer uniquely identifies a frame
 */
public record FrameHeader(int length, FrameType type, FlagSet flags, int streamIdentifier) {

    /** the fixed size of an HTTP/2 frame header in bytes */
    public static final int LENGTH = 9;

	@Override
	public String toString() {
		return "FrameHeader{" +
				"length=" + length +
				", type=" + type +
				", flags=" + flags +
				", streamIdentifier=" + streamIdentifier +
				'}';
	}

	/**
	 * Parse the 9 bytes frame header to determine length, type, flags and the
	 * stream identifier. frame length validation against the advertised
	 * max frame size happens in FrameParser.deserialize; stream id
	 * ordering in Http2Connection.processFrames
	 *
	 * @param tmpBuffer 9 bytes frame header
	 * @return
	 * @throws Exception
	 */
	public static FrameHeader parse(byte[] tmpBuffer) throws Http2Exception, java.io.IOException {
		FrameHeader frameHeader = null;

		FrameType type = null;
		FlagSet flag = null;
		int streamIdentifier = 0;
		int length = 0;
		int readIndex = 0;

		length = BinaryCodec.readInt(tmpBuffer, readIndex, 3);
		readIndex += 3;

		type = FrameType.getEnum(tmpBuffer[readIndex]);
		readIndex++;

		flag = FrameFlag.getEnumSet(tmpBuffer[readIndex], type);
		readIndex++;

		streamIdentifier = BinaryCodec.readInt(tmpBuffer, readIndex);
		readIndex += 4;

		streamIdentifier = streamIdentifier & 0x7FFFFFFF;

		frameHeader = new FrameHeader(length, type, flag, streamIdentifier);

		return frameHeader;
	}

	public static String debug(byte[] header) {
		try {
			var type = FrameType.getEnum(header[3]);
			return "length=" + BinaryCodec.readInt(header, 0, 3) + ", type " + type + ", flags " + FrameFlag.getEnumSet(header[4], type) + ", stream " + BinaryCodec.readInt(header, 5);
		} catch (Exception ex) {
			return "<unable to parse>";
		}
	}

    public void writeTo(OutputStream os) throws IOException {
        writeTo(os, length, type, flags, streamIdentifier);
    }

    public static void writeTo(OutputStream os, int length, FrameType frameType, FlagSet flags, int streamId) throws IOException {
        // build the 9 byte header in one array and write it once: writing it
        // byte by byte crosses two stream layers per byte
        byte[] header = new byte[9];
        encodeInto(header, length, frameType, flags, streamId);
        os.write(header);
    }

    public byte[] encode() {
        return encode(length, type, flags, streamIdentifier);
    }

    public static byte[] encode(int length, FrameType frameType, FlagSet flags, int streamId) {
        byte[] buffer = new byte[9];
        encodeInto(buffer, length, frameType, flags, streamId);
        return buffer;
    }

    /**
     * encode into an existing byte array
     */
    public byte[] encode(byte[] buffer) {
        encodeInto(buffer, length, type, flags, streamIdentifier);
        return buffer;
    }

    /** encode a frame header into an existing buffer (at least 9 bytes):
     * avoids the 9-byte allocation of encode() when the caller already has
     * the target array (HPACK frame assembly, the locked DATA write path) */
    public static void encodeInto(byte[] buffer, int length, FrameType frameType, FlagSet flags, int streamId) {
        BinaryCodec.writeInt(buffer, 0, length, 3);
        buffer[3] = (byte) (frameType.value & 0xFF);
        buffer[4] = flags.value();
        BinaryCodec.writeInt(buffer, 5, streamId, 4);
    }
}
