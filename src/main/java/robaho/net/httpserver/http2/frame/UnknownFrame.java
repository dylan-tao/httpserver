package robaho.net.httpserver.http2.frame;

import java.io.IOException;
import java.io.OutputStream;

class UnknownFrame extends BaseFrame {
    private final byte[] body;
    public UnknownFrame(FrameHeader header,byte[] body) {
        super(header);
        this.body = body;
    }

    @Override
    /** dead code, kept to satisfy the abstract base: unknown frames are ignored per RFC 7540 4.1; this stub drops the body it parsed */
    public void writeTo(OutputStream os) throws IOException {
        getHeader().writeTo(os);
    }

    static BaseFrame parse(byte[] body, FrameHeader frameHeader) {
        return new UnknownFrame(frameHeader,body);
    }
}
