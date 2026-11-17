package robaho.net.httpserver.http2;

/**
 * HTTP/2 error codes (RFC 7540 7) carried in RST_STREAM and GOAWAY frames.
 */
public enum Http2ErrorCode {

  NO_ERROR(0x0),
  PROTOCOL_ERROR(0x1),
  INTERNAL_ERROR(0x2),
  FLOW_CONTROL_ERROR(0x3),
  SETTINGS_TIMEOUT(0x4),
  STREAM_CLOSED(0x5),
  FRAME_SIZE_ERROR(0x6),
  REFUSED_STREAM(0x7),
  CANCEL(0x8),
  COMPRESSION_ERROR(0x9),
  CONNECT_ERROR(0xa),
  ENHANCE_YOUR_CALM(0xb),
  INADEQUATE_SECURITY(0xc),
  HTTP_1_1_REQUIRED(0xd);

  int value;

  Http2ErrorCode(int value) {
    this.value = value;
  }

  public int getValue() { return value; }

  /** lookup by wire value; index 0 is the NO_ERROR fallback slot */
  private static final Http2ErrorCode[] BY_VALUE = buildLookup();

  private static Http2ErrorCode[] buildLookup() {
    // codes0x0..0xd: a 14-slot array indexed by value; slot 0 doubles
    // as the unknown-value fallback (NO_ERROR), matching the old scan's
    // default. values() clones an array per call - the old loop paid
    // that clone on every GOAWAY parse
    Http2ErrorCode[] table = new Http2ErrorCode[14];
    for (Http2ErrorCode e : values()) {
      table[e.value] = e;
    }
    table[0] = NO_ERROR;
    return table;
  }

  /**
   * Returns the constant for {@code value}. Unknown wire values fall back
   * to {@link #NO_ERROR}; callers that must distinguish an unknown code
   * from a genuine NO_ERROR should re-check the result's value
   * (see GoAwayFrame.parse).
   */
  public static Http2ErrorCode getEnum(int value) {
    if (value < 0 || value >= BY_VALUE.length) {
      return NO_ERROR;
    }
    return BY_VALUE[value];
  }

}
