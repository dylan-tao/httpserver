package robaho.net.httpserver.http2.frame;

import robaho.net.httpserver.http2.Http2ErrorCode;
import robaho.net.httpserver.http2.Http2Exception;
import robaho.net.httpserver.http2.BinaryCodec;
import robaho.net.httpserver.http2.frame.FrameFlag.FlagSet;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

public class PingFrame extends BaseFrame {
    public final byte[] body;

	public PingFrame(FrameHeader header, byte[] body) {
		super(header);
        this.body = body;
	}
    public PingFrame() {
        super(new FrameHeader(8,FrameType.PING,FrameFlag.NONE,0));
        body = new byte[8];
    }
    public PingFrame(PingFrame toBeAcked) {
        super(new FrameHeader(toBeAcked.body.length,FrameType.PING,FlagSet.of(FrameFlag.ACK),0));
        body = toBeAcked.body;
    }

    @Override
    public void writeTo(OutputStream os) throws IOException {
        getHeader().writeTo(os);
        // a PING frame declares an 8 byte payload: omitting it would corrupt
        // the frame stream (the header claims 8 bytes that never follow)
        os.write(body);
    }

    public static BaseFrame parse(byte[] body, FrameHeader frameHeader) throws Http2Exception {
        if (frameHeader.streamIdentifier() != 0) {
            throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
        }
        if(body.length!=8) {
            throw new Http2Exception(Http2ErrorCode.FRAME_SIZE_ERROR);
        }
        return new PingFrame(frameHeader, body);
    }
    public byte[] encode() {
        return BinaryCodec.concat(List.of(getHeader().encode(),body));
    }
}
