package robaho.net.httpserver.http2.frame;

import robaho.net.httpserver.http2.HTTP2ErrorCode;
import robaho.net.httpserver.http2.HTTP2Exception;
import robaho.net.httpserver.http2.BinaryCodec;

import java.io.IOException;
import java.io.OutputStream;

public class GoAwayFrame extends BaseFrame {
    public final HTTP2ErrorCode errorCode;
    public final int lastSeenStream;

	public GoAwayFrame(FrameHeader header,HTTP2ErrorCode errorCode,int lastSeenStream) {
		super(header);
        this.errorCode = errorCode;
        this.lastSeenStream = lastSeenStream;
	}

	public GoAwayFrame(HTTP2ErrorCode errorCode,int lastSeenStream) {
		this(new FrameHeader(8,FrameType.GOAWAY,FrameFlag.NONE,0),errorCode,lastSeenStream);
	}

    @Override
    public void writeTo(OutputStream os) throws IOException {
        getHeader().writeTo(os);
        BinaryCodec.writeInt(os, lastSeenStream);
        BinaryCodec.writeInt(os, errorCode.getValue());
    }

    public static BaseFrame parse(byte[] body, FrameHeader frameHeader) throws HTTP2Exception {
        if(frameHeader.streamIdentifier()!=0) {
            throw new HTTP2Exception(HTTP2ErrorCode.PROTOCOL_ERROR);
        }
        if(body.length<8) {
            throw new HTTP2Exception(HTTP2ErrorCode.FRAME_SIZE_ERROR);
        }
        try {
            var lastSeenStream = BinaryCodec.readInt(body, 0, 4);
            var errorCode = BinaryCodec.readInt(body, 4, 4);
            var code = HTTP2ErrorCode.getEnum(errorCode);
            if (code.getValue() != errorCode) {
                // unknown error code: RFC 7540 7 allows treating it as
                // INTERNAL_ERROR. the getEnum fallback returns NO_ERROR, which
                // would turn a future or hostile error code into a graceful
                // drain instead of a connection teardown
                code = HTTP2ErrorCode.INTERNAL_ERROR;
            }
            return new GoAwayFrame(frameHeader, code, lastSeenStream);
        } catch (Exception e) {
            throw new HTTP2Exception(HTTP2ErrorCode.PROTOCOL_ERROR);
        }
    }
}
