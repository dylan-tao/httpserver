package robaho.net.httpserver.http2.frame;

import robaho.net.httpserver.http2.Http2ErrorCode;
import robaho.net.httpserver.http2.Http2Exception;
import robaho.net.httpserver.http2.internal.BinaryCodec;

import java.io.IOException;
import java.io.OutputStream;

public class PriorityFrame extends BaseFrame {

  private int streamDependency;

  public PriorityFrame(FrameHeader header) {
    super(header);
  }

  /** dead code, kept to satisfy the abstract base: priority is never emitted by this server; a revival must serialize the full 5-byte payload this stub drops */
  @Override
  public void writeTo(OutputStream os) throws IOException {
    getHeader().writeTo(os);
  }

  static BaseFrame parse(byte[] body, FrameHeader frameHeader) throws Http2Exception, java.io.IOException {
    var frame = new PriorityFrame(frameHeader);
    if (body.length != 5) {
      throw new Http2Exception(Http2ErrorCode.FRAME_SIZE_ERROR);
    }
    var tmp = BinaryCodec.readInt(body, 0, 4);
    // the exclusive bit (0x80000000) and the weight byte below are consumed
    // per RFC 7540 6.3 but nothing downstream uses them
    frame.streamDependency = tmp & 0x7FFFFFFF;
    if (frame.streamDependency == frameHeader.streamIdentifier()) {
      throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
    }
    return frame;
  }

}
