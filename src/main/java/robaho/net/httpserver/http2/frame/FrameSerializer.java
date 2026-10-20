package robaho.net.httpserver.http2.frame;

import java.io.InputStream;

import robaho.net.httpserver.ServerConfig;
import robaho.net.httpserver.http2.HTTP2Connection;
import robaho.net.httpserver.http2.HTTP2ErrorCode;
import robaho.net.httpserver.http2.HTTP2Exception;

public class FrameSerializer {

	/**
	 * Deserialize one frame from the stream. {@code headerScratch} is a
	 * caller-owned reusable 9-byte buffer: the reader thread calls this per
	 * frame, and the per-frame allocation was pure churn. the FrameHeader
	 * record itself is NOT reusable - every frame object retains its header.
	 */
	public static BaseFrame deserialize(InputStream inputStream, byte[] headerScratch) throws HTTP2Exception, java.io.IOException {

		BaseFrame baseFrame;

        HTTP2Connection.readFully(inputStream, headerScratch);
		FrameHeader frameHeader = FrameHeader.parse(headerScratch);

        if(frameHeader.length() > ServerConfig.http2MaxFrameSize()) {
            throw new HTTP2Exception(HTTP2ErrorCode.FRAME_SIZE_ERROR);
        }

        byte[] body = new byte[frameHeader.length()];
        HTTP2Connection.readFully(inputStream, body);

		switch (frameHeader.type()) {
		case HEADERS:
			baseFrame = HeadersFrame.parse(body, frameHeader);
			break;
		case CONTINUATION:
			baseFrame = ContinuationFrame.parse(body,frameHeader);
			break;
		case DATA:
			baseFrame = DataFrame.parse(body,frameHeader);
			break;
		case GOAWAY:
			baseFrame = GoawayFrame.parse(body,frameHeader);
			break;
		case PING:
			baseFrame = PingFrame.parse(body,frameHeader);
			break;
		case PRIORITY:
			baseFrame = PriorityFrame.parse(body,frameHeader);
			break;
		case PUSH_PROMISE:
			baseFrame = PushPromiseFrame.parse(body,frameHeader);
			break;
		case RST_STREAM:
			baseFrame = ResetStreamFrame.parse(body,frameHeader);
			break;
		case SETTINGS:
			baseFrame = SettingsFrame.parse(body, frameHeader);
			break;
		case WINDOW_UPDATE:
			baseFrame = WindowUpdateFrame.parse(body, frameHeader);
			break;
		default:
            baseFrame = NotImplementedFrame.parse(body,frameHeader);
			break;
		}

		return baseFrame;
	}
}
