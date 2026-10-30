package robaho.net.httpserver.http2.frame;

import robaho.net.httpserver.http2.Http2ErrorCode;
import robaho.net.httpserver.http2.Http2Exception;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;

public class DataFrame extends BaseFrame {

  public final byte[] body;

  public DataFrame(FrameHeader header, byte[] body) {
    super(header);
    this.body = body;
  }

  @Override
  public void writeTo(OutputStream outputStream) throws IOException {
    outputStream.write(body);
  }

  public static BaseFrame parse(byte[] body, FrameHeader frameHeader) throws Http2Exception {
    if (frameHeader.flags().contains(FrameFlag.PADDED)) {
      if (body.length == 0) {
        throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, "PADDED DATA frame with empty body");
      }
      int padLength = body[0] & 0xFF;
      // the frame layout is [Pad Length][data][padding] (RFC 7540 6.1):
      // total padding = the pad length octet itself + padLength octets
      if (padLength + 1 > body.length) {
        throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, "padding exceeds frame size");
      }
      // the data starts AFTER the pad length octet and ends BEFORE the
      // padding: getting either boundary wrong silently corrupts the
      // request body (the pad octet delivered as data / last byte lost)
      return new DataFrame(frameHeader, Arrays.copyOfRange(body, 1, body.length - padLength));
    }
    return new DataFrame(frameHeader, body);
  }

  public byte[] encode() {
    throw new UnsupportedOperationException();
  }

}
