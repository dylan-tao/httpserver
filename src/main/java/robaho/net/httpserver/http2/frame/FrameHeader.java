package robaho.net.httpserver.http2.frame;

import java.io.IOException;
import java.io.OutputStream;

import robaho.net.httpserver.http2.Utils;
import robaho.net.httpserver.http2.frame.FrameFlag.FlagSet;

/**
 * Create a frame header object
 */
public record FrameHeader(int length, FrameType type, FlagSet flags, int streamIdentifier) {

	/**
	 * 24-bit unsigned integer value that specifies length of the
	 * frame
	 */
	@Override
	public int length() {
		return length;
	}

	/**
	 * defined as an enum FrameType, it identifies the type of the
	 * frame
	 */
	@Override
	public FrameType type() {
		return type;
	}

	/**
	 * defined as an EnumSet&lt;FrameFlag&gt;, it identifies flags associated with a
	 * particular frame
	 */
	@Override
	public FlagSet flags() {
		return flags;
	}

	/**
	 * 31-bit unsigned integer uniquely identifies a frame
	 */
	@Override
	public int streamIdentifier() {
		return streamIdentifier;
	}

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
	 * Parse the 9 bytes frame header to determine length, type, flags and the stream identifier
	 *
	 * @param tmpBuffer 9 bytes frame header
	 * @return
	 * @throws Exception
	 */
	// TODO validate the frame size, frame number, and number of frames in session based on the SETTINGS frame
	public static FrameHeader Parse(byte[] tmpBuffer) throws Exception {
		FrameHeader frameHeader = null;

		FrameType type = null;
		FlagSet flag = null;
		int streamIdentifier = 0;
		int length = 0;
		int readIndex = 0;

		length = Utils.convertToInt(tmpBuffer, readIndex, 3);
		readIndex += 3;

		type = FrameType.getEnum(tmpBuffer[readIndex]);
		readIndex++;

		flag = FrameFlag.getEnumSet(tmpBuffer[readIndex], type);
		readIndex++;

		streamIdentifier = Utils.convertToInt(tmpBuffer, readIndex);
		readIndex += 4;

		streamIdentifier = streamIdentifier & 0x7FFFFFFF;

		frameHeader = new FrameHeader(length, type, flag, streamIdentifier);

		return frameHeader;
	}

	public static String debug(byte[] header) {
		try {
			var type = FrameType.getEnum(header[3]);
			return "length=" + Utils.convertToInt(header, 0, 3) + ", type " + type + ", flags " + FrameFlag.getEnumSet(header[4], type) + ", stream " + Utils.convertToInt(header, 5);
		} catch (Exception ex) {
			return "<unable to parse>";
		}
	}

	public void writeTo(OutputStream os) throws IOException {
		// build the 9 byte header in one array and write it once: writing it
		// byte by byte crosses two stream layers per byte
		byte[] header = new byte[9];
		Utils.convertToBinary(header, 0, this.length, 3);
		header[3] = (byte) (this.type().value & 0xFF);
		header[4] = flags.value();
		Utils.convertToBinary(header, 5, this.streamIdentifier, 4);
		os.write(header);
	}

	public static void writeTo(OutputStream os, int length, FrameType frameType, FlagSet flags, int streamId) throws IOException {
		byte[] header = new byte[9];
		Utils.convertToBinary(header, 0, length, 3);
		header[3] = (byte) (frameType.value & 0xFF);
		header[4] = flags.value();
		Utils.convertToBinary(header, 5, streamId, 4);
		os.write(header);
	}

	public static byte[] encode(int length, FrameType frameType, FlagSet flags, int streamId) {
		byte[] buffer = new byte[9];
		Utils.convertToBinary(buffer, 0, length, 3);
		buffer[3] = (byte) (frameType.value & 0xFF);
		buffer[4] = flags.value();
		Utils.convertToBinary(buffer, 5, streamId, 4);
		return buffer;
	}

	public byte[] encode() {
		byte[] buffer = new byte[9];
		Utils.convertToBinary(buffer, 0, length, 3);
		buffer[3] = (byte) (type.value & 0xFF);
		buffer[4] = flags.value();
		Utils.convertToBinary(buffer, 5, streamIdentifier, 4);
		return buffer;
	}

	/**
	 * encode into an existing byte array
	 */
	public byte[] encode(byte[] buffer) {
		Utils.convertToBinary(buffer, 0, length, 3);
		buffer[3] = (byte) (type.value & 0xFF);
		buffer[4] = flags.value();
		Utils.convertToBinary(buffer, 5, streamIdentifier, 4);
		return buffer;
	}
}
