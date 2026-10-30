package robaho.net.httpserver.http2.frame;

import robaho.net.httpserver.http2.Http2Exception;

import java.io.IOException;
import java.io.OutputStream;

public class ContinuationFrame extends BaseFrame {

  private final byte[] body;

  public ContinuationFrame(FrameHeader header, byte[] body) {
    super(header);
    this.body = body;
  }

  @Override
  /**
   * dead code, kept to satisfy the abstract base: outbound header blocks are written by HPackContext.encodeHeaderFrames (CONTINUATION splitting included); this parse-only frame would drop the
   * header block
   */
  public void writeTo(OutputStream os) throws IOException {
    getHeader().writeTo(os);
  }

  public static BaseFrame parse(byte[] body, FrameHeader frameHeader) throws Http2Exception {
    return new ContinuationFrame(frameHeader, body);
  }

  public byte[] getHeaderBlock() { return body; }

}
