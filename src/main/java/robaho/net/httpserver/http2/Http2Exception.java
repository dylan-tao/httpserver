package robaho.net.httpserver.http2;

public class Http2Exception extends Exception {
	
	private final Http2ErrorCode errorCode;

	public Http2Exception(Http2ErrorCode errorCode) {
		this(errorCode,"");
	}

	public Http2Exception(Http2ErrorCode errorCode, String message) {
		super(message);
        this.errorCode = errorCode;
	}
    
	public Http2Exception(Http2ErrorCode errorCode, Throwable cause) {
		super(cause);
        this.errorCode = errorCode;
	}
	
	public Http2Exception(String message,Exception cause) {
        super(message,cause);
		errorCode = Http2ErrorCode.INTERNAL_ERROR;
	}
	public Http2ErrorCode getErrorCode()
	{
		return errorCode;
	}
	
}
