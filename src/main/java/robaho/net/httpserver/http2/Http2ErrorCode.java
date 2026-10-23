package robaho.net.httpserver.http2;

/**
 * HTTP/2 error codes (RFC 7540 7) carried in RST_STREAM and GOAWAY frames.
 */
public enum Http2ErrorCode {
	
	NO_ERROR (0x0),
	PROTOCOL_ERROR (0x1),
	INTERNAL_ERROR (0x2),
	FLOW_CONTROL_ERROR (0x3),
	SETTINGS_TIMEOUT (0x4),
	STREAM_CLOSED (0x5),
	FRAME_SIZE_ERROR (0x6),
	REFUSED_STREAM (0x7),
	CANCEL (0x8),
	COMPRESSION_ERROR (0x9),
	CONNECT_ERROR (0xa),
	ENHANCE_YOUR_CALM (0xb),
	INADEQUATE_SECURITY (0xc),
	HTTP_1_1_REQUIRED (0xd);
	
	int value;

	Http2ErrorCode(int value) {
		this.value = value;
	}

	public int getValue() {
		return value;
	}

	public static Http2ErrorCode getEnum(int value) {
		Http2ErrorCode result = Http2ErrorCode.NO_ERROR;

		for (Http2ErrorCode e : Http2ErrorCode.values()) {
			if (e.getValue() == value)
				result = e;
		}
		return result;
	}

}
