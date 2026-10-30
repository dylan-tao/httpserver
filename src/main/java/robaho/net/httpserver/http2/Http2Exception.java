package robaho.net.httpserver.http2;

/**
 * An HTTP/2 protocol error carrying the error code (RFC 7540 7) that a
 * RST_STREAM or GOAWAY should be sent with.
 */
public class Http2Exception extends Exception {

  private static final long serialVersionUID = 1L;

  private final Http2ErrorCode errorCode;

  public Http2Exception(Http2ErrorCode errorCode) {
    this(errorCode, "");
  }

  public Http2Exception(Http2ErrorCode errorCode, String message) {
    super(message);
    this.errorCode = errorCode;
  }

  public Http2Exception(Http2ErrorCode errorCode, Throwable cause) {
    super(cause);
    this.errorCode = errorCode;
  }

  /**
   * generic failure, signaled to the peer as
   * {@link Http2ErrorCode#INTERNAL_ERROR}
   */
  public Http2Exception(String message, Throwable cause) {
    super(message, cause);
    errorCode = Http2ErrorCode.INTERNAL_ERROR;
  }

  /** the protocol error code to signal to the peer */
  public Http2ErrorCode getErrorCode() { return errorCode; }

}
