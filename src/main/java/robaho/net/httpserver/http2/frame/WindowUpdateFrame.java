package robaho.net.httpserver.http2.frame;

import robaho.net.httpserver.http2.Http2ErrorCode;
import robaho.net.httpserver.http2.Http2Exception;
import robaho.net.httpserver.http2.internal.BinaryCodec;

import java.io.IOException;
import java.io.OutputStream;

public class WindowUpdateFrame extends BaseFrame {

  private int windowSizeIncrement;

  public WindowUpdateFrame(FrameHeader header) {
    super(header);
  }

  public WindowUpdateFrame(int streamId, int increment) {
    super(new FrameHeader(4, FrameType.WINDOW_UPDATE, FrameFlag.NONE, streamId));
    windowSizeIncrement = increment;
  }

  public int getWindowSizeIncrement() { return windowSizeIncrement; }

  public static WindowUpdateFrame parse(byte[] frameBody, FrameHeader header) throws Http2Exception {

    if (frameBody.length != 4) {
      throw new Http2Exception(Http2ErrorCode.FRAME_SIZE_ERROR);
    }

    WindowUpdateFrame frame = new WindowUpdateFrame(header);
    try {
      frame.windowSizeIncrement = BinaryCodec.readInt(frameBody, 0);
    } catch (Exception e) {
      throw new Http2Exception(Http2ErrorCode.INTERNAL_ERROR);
    }
    // the payload is [reserved bit][31-bit increment]: RFC 7540 4.1 says
    // the reserved bit MUST be ignored when receiving - mask it off rather
    // than erroring. a zero INCREMENT (after masking) is still a
    // PROTOCOL_ERROR per RFC 7540 6.9
    frame.windowSizeIncrement &= 0x7FFFFFFF;
    if (frame.windowSizeIncrement == 0) {
      throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, "window size increment == 0");
    }
    return frame;
  }

  @Override
  public void writeTo(OutputStream os) throws IOException {
    getHeader().writeTo(os);
    BinaryCodec.writeInt(os, windowSizeIncrement);
  }

  @Override
  public byte[] encode() {
    byte[] buffer = new byte[9 + 4];
    getHeader().encode(buffer);
    BinaryCodec.writeInt(buffer, 9, windowSizeIncrement, 4);
    return buffer;
  }

}
