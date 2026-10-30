package robaho.net.httpserver.http2.frame;

import robaho.net.httpserver.ServerConfig;
import robaho.net.httpserver.http2.Http2ErrorCode;
import robaho.net.httpserver.http2.Http2Exception;
import robaho.net.httpserver.http2.internal.Http2Connection;

import java.io.InputStream;

public class FrameParser {

  /**
   * Deserialize one frame from the stream. {@code headerScratch} is a
   * caller-owned reusable 9-byte buffer: the reader thread calls this per
   * frame, and the per-frame allocation was pure churn. the FrameHeader
   * record itself is NOT reusable - every frame object retains its header.
   */
  public static BaseFrame deserialize(InputStream inputStream, byte[] headerScratch)
      throws Http2Exception, java.io.IOException {

    BaseFrame baseFrame;

    Http2Connection.readFully(inputStream, headerScratch);
    FrameHeader frameHeader = FrameHeader.parse(headerScratch);

    if (frameHeader.length() > ServerConfig.http2MaxFrameSize()) {
      throw new Http2Exception(Http2ErrorCode.FRAME_SIZE_ERROR);
    }

    // DATA is the only frame type whose body is consumed and released via
    // the pool: other bodies may be retained by the header block assembly,
    // so they get a plain allocation
    byte[] body =
        frameHeader.type() == FrameType.DATA ? DataFrameBodyPool.borrow(frameHeader.length()) : new byte[frameHeader.length()];
    Http2Connection.readFully(inputStream, body);

    switch (frameHeader.type()) {
      case HEADERS:
        baseFrame = HeadersFrame.parse(body, frameHeader);
        break;
      case CONTINUATION:
        baseFrame = ContinuationFrame.parse(body, frameHeader);
        break;
      case DATA:
        baseFrame = DataFrame.parse(body, frameHeader);
        break;
      case GOAWAY:
        baseFrame = GoAwayFrame.parse(body, frameHeader);
        break;
      case PING:
        baseFrame = PingFrame.parse(body, frameHeader);
        break;
      case PRIORITY:
        baseFrame = PriorityFrame.parse(body, frameHeader);
        break;
      case PUSH_PROMISE:
        baseFrame = PushPromiseFrame.parse(body, frameHeader);
        break;
      case RST_STREAM:
        baseFrame = ResetStreamFrame.parse(body, frameHeader);
        break;
      case SETTINGS:
        baseFrame = SettingsFrame.parse(body, frameHeader);
        break;
      case WINDOW_UPDATE:
        baseFrame = WindowUpdateFrame.parse(body, frameHeader);
        break;
      default:
        baseFrame = UnknownFrame.parse(body, frameHeader);
        break;
    }

    return baseFrame;
  }

}
