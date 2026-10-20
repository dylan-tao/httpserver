package robaho.net.httpserver.http2;

public class HTTP2Exception extends Exception {
	
	private final HTTP2ErrorCode errorCodee;

	public HTTP2Exception(HTTP2ErrorCode errorCodee) {
		this(errorCodee,"");
	}

	public HTTP2Exception(HTTP2ErrorCode errorCode, String message) {
		super(message);
        errorCodee = errorCode;
	}
    
	public HTTP2Exception(HTTP2ErrorCode errorCode, Throwable cause) {
		super(cause);
        errorCodee = errorCode;
	}
	
	public HTTP2Exception(String message,Exception cause) {
        super(message,cause);
		errorCodee = HTTP2ErrorCode.INTERNAL_ERROR;
	}
	public HTTP2ErrorCode getErrorCode()
	{
		return errorCodee;
	}
	
}
