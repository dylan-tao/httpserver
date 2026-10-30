package robaho.net.httpserver.http2.frame;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

public abstract class BaseFrame {

  private final FrameHeader header;

  public BaseFrame(FrameHeader header) {
    this.header = header;
  }

  public FrameHeader getHeader() { return header; }

  public abstract void writeTo(OutputStream os) throws IOException;

  public byte[] encode() {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    try {
      writeTo(bos);
    } catch (IOException ignore) {}
    // note: parse-only frames' writeTo stubs SILENTLY drop payload
    // (only HeadersFrame.writeTo and DataFrame.encode throw
    // UnsupportedOperationException tombstones) - so encode() on a
    // parsed sibling returns a header-only array without error.
    // acceptable because nothing serializes a parsed frame: every
    // outbound frame is constructed directly at its send site
    return bos.toByteArray();
  }

}
