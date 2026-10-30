package robaho.net.httpserver.http2.frame;

import java.io.IOException;
import java.io.OutputStream;

public class PushPromiseFrame extends BaseFrame {

  public PushPromiseFrame(FrameHeader header) {
    super(header);
  }

  @Override
  /** dead code, kept to satisfy the abstract base: server push is not implemented; this parse-only stub drops the promised-stream payload */
  public void writeTo(OutputStream os) throws IOException {
    getHeader().writeTo(os);
  }

  static BaseFrame parse(byte[] body, FrameHeader frameHeader) {
    return new PushPromiseFrame(frameHeader);
  }

}
