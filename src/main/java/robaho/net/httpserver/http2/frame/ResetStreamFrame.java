package robaho.net.httpserver.http2.frame;

import robaho.net.httpserver.http2.Http2ErrorCode;
import robaho.net.httpserver.http2.Http2Exception;
import robaho.net.httpserver.http2.internal.BinaryCodec;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

import static robaho.net.httpserver.http2.internal.BinaryCodec.readInt;

public class ResetStreamFrame extends BaseFrame {
    final public Http2ErrorCode errorCode;

	public ResetStreamFrame(FrameHeader header,Http2ErrorCode errorCode) {
		super(header);
        this.errorCode = errorCode;
	}
	public ResetStreamFrame(Http2ErrorCode errorCode,int streamId) {
		super(new FrameHeader(4,FrameType.RST_STREAM,FrameFlag.NONE,streamId));
        this.errorCode = errorCode;
	}

    @Override
    public void writeTo(OutputStream os) throws IOException {
        getHeader().writeTo(os);
        BinaryCodec.writeInt(os,errorCode.getValue(),4);
        os.flush();
    }

    static BaseFrame parse(byte[] body, FrameHeader frameHeader) throws Http2Exception, java.io.IOException {
        if(body.length != 4) {
            throw new Http2Exception(Http2ErrorCode.FRAME_SIZE_ERROR);
        }
        return new ResetStreamFrame(frameHeader,Http2ErrorCode.getEnum(readInt(body, 0)));
    }
    public byte[] encode() {
        byte[] buffer = new byte[4];
        BinaryCodec.writeInt(buffer, 0, errorCode.getValue(),4);
        return BinaryCodec.concat(List.of(getHeader().encode(),buffer));
    }

}
