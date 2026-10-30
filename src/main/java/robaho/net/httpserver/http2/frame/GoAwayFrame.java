package robaho.net.httpserver.http2.frame;

import robaho.net.httpserver.http2.Http2ErrorCode;
import robaho.net.httpserver.http2.Http2Exception;
import robaho.net.httpserver.http2.internal.BinaryCodec;

import java.io.IOException;
import java.io.OutputStream;

public class GoAwayFrame extends BaseFrame {

  public final Http2ErrorCode errorCode;

  public final int lastSeenStream;

  public GoAwayFrame(FrameHeader header, Http2ErrorCode errorCode, int lastSeenStream) {
    super(header);
    this.errorCode = errorCode;
    this.lastSeenStream = lastSeenStream;
  }

  public GoAwayFrame(Http2ErrorCode errorCode, int lastSeenStream) {
    this(new FrameHeader(8, FrameType.GOAWAY, FrameFlag.NONE, 0), errorCode, lastSeenStream);
  }

  @Override
  public void writeTo(OutputStream os) throws IOException {
    getHeader().writeTo(os);
    BinaryCodec.writeInt(os, lastSeenStream);
    BinaryCodec.writeInt(os, errorCode.getValue());
  }

  public static BaseFrame parse(byte[] body, FrameHeader frameHeader) throws Http2Exception {
    if (frameHeader.streamIdentifier() != 0) {
      throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
    }
    if (body.length < 8) {
      throw new Http2Exception(Http2ErrorCode.FRAME_SIZE_ERROR);
    }
    try {
      // mask the reserved bit (RFC 7540 6.8: it MUST be ignored):
      // unmasked, a set bit made the id negative, so the GOAWAY drain
      // guard (streamId > clientGoawayStreamId) never fired and the
      // server kept dispatching streams the peer promised not to open
      var lastSeenStream = BinaryCodec.readInt(body, 0, 4) & 0x7FFFFFFF;
      var errorCode = BinaryCodec.readInt(body, 4, 4);
      var code = Http2ErrorCode.getEnum(errorCode);
      if (code.getValue() != errorCode) {
        // unknown error code: RFC 7540 7 allows treating it as
        // INTERNAL_ERROR. the getEnum fallback returns NO_ERROR, which
        // would turn a future or hostile error code into a graceful
        // drain instead of a connection teardown
        code = Http2ErrorCode.INTERNAL_ERROR;
      }
      return new GoAwayFrame(frameHeader, code, lastSeenStream);
    } catch (Exception e) {
      throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
    }
  }

}
