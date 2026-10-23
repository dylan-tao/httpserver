package robaho.net.httpserver.http2.frame;

import robaho.net.httpserver.http2.Http2ErrorCode;
import robaho.net.httpserver.http2.Http2Exception;
import robaho.net.httpserver.http2.internal.BinaryCodec;

import java.io.IOException;
import java.io.OutputStream;

public class PriorityFrame extends BaseFrame {
    public int streamDependency;
    public int weight;
    public boolean exclusive;

	public PriorityFrame(FrameHeader header) {
		super(header);
	}

    @Override
    public void writeTo(OutputStream os) throws IOException {
        getHeader().writeTo(os);
    }

    static BaseFrame parse(byte[] body, FrameHeader frameHeader) throws Http2Exception, java.io.IOException {
        var frame = new PriorityFrame(frameHeader);
        if(body.length != 5) {
            throw new Http2Exception(Http2ErrorCode.FRAME_SIZE_ERROR);
        }
        var tmp = BinaryCodec.readInt(body, 0, 4);
        frame.exclusive = (tmp & 0x80000000) != 0;
        frame.streamDependency = tmp & 0x7FFFFFFF;
        if(frame.streamDependency == frameHeader.streamIdentifier()) {
            throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
        }
        frame.weight = (body[4] & 0xFF) + 1;
        return frame;
    }
}
