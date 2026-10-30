package robaho.net.httpserver.http2.frame;

import robaho.net.httpserver.http2.Http2ErrorCode;
import robaho.net.httpserver.http2.Http2Exception;
import robaho.net.httpserver.http2.internal.BinaryCodec;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;

/**
 * [rfc7540 Section 6.2] The HEADERS frame (type=0x1) is used to open a stream (Section 5.1), and additionally carries a
 * header block fragment. HEADERS frames can be sent on a stream in the "idle", "reserved (local)", "open", or
 * "half-closed (remote)" state.
 */
public class HeadersFrame extends BaseFrame {

  private int padLength;

  private long dependentStreamId;

  private byte[] headerBlock;

  public HeadersFrame(FrameHeader header) {
    super(header);
  }

  /**
   * An 8-bit field containing the length of the frame padding in units of octets. This field is only present if the
   * PADDED flag is set.
   */
  public int getPadLength() { return padLength; }

  public void setPadLength(int padLength) { this.padLength = padLength; }

  public void setDependentStream(long streamIdentifier) { this.dependentStreamId = streamIdentifier; }

  /**
   * A header block fragment (Section 4.3).
   */
  public byte[] getHeaderBlock() { return headerBlock; }

  public void setHeaderBlock(byte[] headerBlock) { this.headerBlock = headerBlock; }

  /**
   * @param frameBody
   *          payload of the HeadersFrame after consuming the FrameHeader
   * @param header
   *          FrameHeader
   * @return HeadersFrame object
   * @throws Http2Exception
   */
  public static HeadersFrame parse(byte[] frameBody, FrameHeader header) throws Http2Exception, java.io.IOException {

    if (frameBody == null) {
      throw new Http2Exception(Http2ErrorCode.FRAME_SIZE_ERROR);
    }

    if (header.length() != frameBody.length) {
      throw new Http2Exception(Http2ErrorCode.FRAME_SIZE_ERROR);
    }

    int paramIndex = 0;

    HeadersFrame headersFrame = new HeadersFrame(header);

    // validate the declared flag fields fit the frame BEFORE reading
    // them: PADDED needs >=1 byte, PRIORITY >=5, both >=6. the old code
    // indexed straight into frameBody, so a truncated frame escaped as
    // an uncaught ArrayIndexOutOfBoundsException that tore the
    // connection down without a GOAWAY (RFC 7540 6.2 requires
    // FRAME_SIZE_ERROR)
    boolean padded = header.flags().contains(FrameFlag.PADDED);
    boolean priority = header.flags().contains(FrameFlag.PRIORITY);
    int min = (padded ? 1 : 0) + (priority ? 5 : 0);
    if (frameBody.length < min) {
      throw new Http2Exception(Http2ErrorCode.FRAME_SIZE_ERROR);
    }

    // check for PADDED flag and if set then store padding length
    if (padded) {
      int padLength = BinaryCodec.readInt(frameBody, paramIndex, 1);
      // the padding must fit AFTER the pad-length octet and the
      // priority fields, not merely inside the whole frame
      if (padLength + min >= header.length()) {
        throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
      }
      headersFrame.setPadLength(padLength);
      paramIndex += 1;
    }

    // check for PRIORITY flag and if set then store the Exclusive bit (isExclusive) and store streamID
    if (header.flags().contains(FrameFlag.PRIORITY)) {

      var streamId = BinaryCodec.readInt(frameBody, paramIndex, 4);
      paramIndex += 4;

      headersFrame.setDependentStream(streamId & 0x7FFFFFFFL);
      if (headersFrame.dependentStreamId == header.streamIdentifier()) {
        // cannot depend on itself
        throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
      }

      // RFC 7540 6.2: weight byte is consumed; nothing downstream uses it
      paramIndex += 1;
    }

    // the common case (no PADDED, no PRIORITY) has paramIndex == 0 and no
    // padding: the frame body is freshly allocated per frame and owned
    // exclusively by this frame, so the copyOfRange duplicated the whole
    // header block for nothing
    int end = header.length() - headersFrame.getPadLength();
    if (paramIndex == 0 && end == frameBody.length) {
      headersFrame.setHeaderBlock(frameBody);
    } else {
      headersFrame.setHeaderBlock(Arrays.copyOfRange(frameBody, paramIndex, end));
    }

    return headersFrame;
  }

  /**
   * never called: outbound HEADERS go through HPackContext.encodeHeaderFrames. kept only to satisfy the abstract
   * base; a real serialization here would need the flags/priority/padding context this parse-only frame lost
   */
  @Override
  public void writeTo(OutputStream os) throws IOException {
    throw new UnsupportedOperationException("outbound HEADERS frames are encoded by HPackContext");
  }

}
