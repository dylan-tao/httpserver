package robaho.net.httpserver.websockets;

/*
 * #%L
 * NanoHttpd-Websocket
 * %%
 * Copyright (C) 2012 - 2016 nanohttpd
 * %%
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * 3. Neither the name of the nanohttpd nor the names of its contributors
 *    may be used to endorse or promote products derived from this software without
 *    specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 * #L%
 */

import robaho.net.httpserver.ServerConfig;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * A single WebSocket frame (RFC 6455 5): opcode, FIN bit, optional masking key and payload. Frames are read from and
 * written to the raw stream; fragmented messages are reassembled via the {@link #WebSocketFrame(OpCode, List)}
 * constructor.
 */
public class WebSocketFrame {

  public static final Charset TEXT_CHARSET = StandardCharsets.UTF_8;

  /**
   * one strict decoder per thread: CharsetDecoder is stateful and not thread-safe, and allocating one per text frame
   * was pure overhead. reset() restores the decode state while keeping the REPORT actions
   */
  private static final ThreadLocal<java.nio.charset.CharsetDecoder> TEXT_DECODER =
      ThreadLocal.withInitial(() -> TEXT_CHARSET.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
          .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT));

  /**
   * decode a text payload strictly: malformed UTF-8 throws (and maps to close code 1007) instead of being silently
   * replaced with U+FFFD
   */
  public static String binary2Text(byte[] payload) throws CharacterCodingException {
    return binary2Text(payload, 0, payload.length);
  }

  public static String binary2Text(byte[] payload, int offset, int length) throws CharacterCodingException {
    // String(byte[], UTF_8) silently replaces malformed sequences with
    // U+FFFD and never throws - a strict decoder rejects invalid UTF-8
    // text payloads with 1007 (RFC 6455 8.1)
    java.nio.charset.CharsetDecoder decoder = TEXT_DECODER.get();
    decoder.reset();
    return decoder.decode(java.nio.ByteBuffer.wrap(payload, offset, length)).toString();
  }

  private static int checkedRead(int read) throws IOException {
    if (read < 0) {
      throw new EOFException();
    }
    return read;
  }

  /**
   * read and validate one frame from the stream: reserved bits, unknown opcodes, fragmented control frames,
   * non-minimal length encodings, control payload caps and the configured max frame size are all rejected here. a
   * Close frame is returned as a {@link CloseFrame}
   */
  public static WebSocketFrame read(InputStream in) throws IOException {
    byte head = (byte) checkedRead(in.read());
    boolean fin = (head & 0x80) != 0;
    OpCode opCode = OpCode.find((byte) (head & 0x0F));
    if ((head & 0x70) != 0) {
      throw new WebSocketException(CloseCode.ProtocolError,
          "The reserved bits (" + Integer.toBinaryString(head & 0x70) + ") must be 0.");
    }
    if (opCode == null) {
      throw new WebSocketException(CloseCode.ProtocolError,
          "Received frame with reserved/unknown opcode " + (head & 0x0F) + ".");
    } else if (opCode.isControlFrame() && !fin) {
      throw new WebSocketException(CloseCode.ProtocolError, "Fragmented control frame.");
    }

    WebSocketFrame frame = new WebSocketFrame(opCode, fin);
    frame.readPayloadInfo(in);
    frame.readPayload(in);
    if (frame.getOpCode() == OpCode.Close) {
      return new CloseFrame(frame);
    } else {
      return frame;
    }
  }

  /** encode a text payload as UTF-8 bytes */
  public static byte[] text2Binary(String payload) throws CharacterCodingException {
    return payload.getBytes(WebSocketFrame.TEXT_CHARSET);
  }

  private OpCode opCode;

  private boolean fin;

  private byte[] maskingKey;

  private byte[] payload;

  // --------------------------------GETTERS---------------------------------

  private transient int _payloadLength;

  private transient String _payloadString;

  private WebSocketFrame(OpCode opCode, boolean fin) {
    setOpCode(opCode);
    setFin(fin);
  }

  /**
   * a frame with a binary payload; control frame payloads above the 125-byte protocol cap are rejected up front
   */
  public WebSocketFrame(OpCode opCode, boolean fin, byte[] payload) {
    this(opCode, fin);
    if ((opCode == OpCode.Close || opCode == OpCode.Ping || opCode == OpCode.Pong) && payload != null && payload.length > 125) {
      // control frame payloads are capped at 125 bytes (RFC 6455 5.5):
      // emitting a larger one makes every compliant peer fail the
      // connection
      throw new IllegalArgumentException("control frame payload exceeds 125 bytes: " + payload.length);
    }
    setBinaryPayload(payload == null ? new byte[0] : payload);
  }

  /** a frame with a UTF-8 text payload */
  public WebSocketFrame(OpCode opCode, boolean fin, String payload) throws CharacterCodingException {
    this(opCode, fin);
    setTextPayload(payload);
  }

  /**
   * reassemble a fragmented message from its continuation frames; the aggregate size is bounded by the int payload
   * length
   */
  public WebSocketFrame(OpCode opCode, List<WebSocketFrame> fragments) throws WebSocketException {
    setOpCode(opCode);
    setFin(true);

    long _payloadLength = 0;
    for (WebSocketFrame inter : fragments) {
      _payloadLength += inter.getBinaryPayload().length;
    }
    if (_payloadLength < 0 || _payloadLength > Integer.MAX_VALUE) {
      throw new WebSocketException(CloseCode.MessageTooBig, "Max frame length has been exceeded.");
    }
    this._payloadLength = (int) _payloadLength;
    byte[] payload = new byte[this._payloadLength];
    int offset = 0;
    for (WebSocketFrame inter : fragments) {
      System.arraycopy(inter.getBinaryPayload(), 0, payload, offset, inter.getBinaryPayload().length);
      offset += inter.getBinaryPayload().length;
    }
    setBinaryPayload(payload);
  }

  public WebSocketFrame(WebSocketFrame clone) {
    setOpCode(clone.getOpCode());
    setFin(clone.isFin());
    setBinaryPayload(clone.getBinaryPayload());
    setMaskingKey(clone.getMaskingKey());
  }

  /** the (unmasked) payload bytes; null for a frame without payload */
  public byte[] getBinaryPayload() { return this.payload; }

  /** the 4-byte masking key, or null for an unmasked frame */
  public byte[] getMaskingKey() { return this.maskingKey; }

  /** this frame's opcode */
  public OpCode getOpCode() { return this.opCode; }

  // --------------------------------SERIALIZATION---------------------------

  /**
   * the payload decoded as strict UTF-8; only meaningful for text frames (other opcodes decode their bytes verbatim)
   */
  public String getTextPayload() {
    if (this._payloadString == null) {
      try {
        this._payloadString = binary2Text(getBinaryPayload());
      } catch (CharacterCodingException e) {
        throw new RuntimeException("Undetected CharacterCodingException", e);
      }
    }
    return this._payloadString;
  }

  /** true when this frame terminates a message (not a continuation) */
  public boolean isFin() { return this.fin; }

  /** true when the frame carries a 4-byte masking key */
  public boolean isMasked() {
    return this.maskingKey != null && this.maskingKey.length == 4;
  }

  private String payloadToString() {
    if (this.payload == null) {
      return "null";
    } else {
      final StringBuilder sb = new StringBuilder();
      sb.append('[').append(this.payload.length).append("b] ");
      if (getOpCode() == OpCode.Text) {
        String text = getTextPayload();
        if (text.length() > 100) {
          sb.append(text, 0, 100).append("...");
        } else {
          sb.append(text);
        }
      } else {
        sb.append("0x");
        for (int i = 0; i < Math.min(this.payload.length, 50); ++i) {
          sb.append(Integer.toHexString(this.payload[i] & 0xFF));
        }
        if (this.payload.length > 50) {
          sb.append("...");
        }
      }
      return sb.toString();
    }
  }

  private void readPayload(InputStream in) throws IOException {
    this.payload = new byte[this._payloadLength];
    int read = 0;
    while (read < this._payloadLength) {
      read += checkedRead(in.read(this.payload, read, this._payloadLength - read));
    }

    if (isMasked()) {
      for (int i = 0; i < this.payload.length; i++) {
        this.payload[i] ^= this.maskingKey[i % 4];
      }
    }

    // Test for Unicode errors - only for a COMPLETE unfragmented text
    // message: a fragment may legally end mid code point (RFC 6455 5.6,
    // 8.2), so fragmented messages are validated on aggregation instead
    if (getOpCode() == OpCode.Text && isFin()) {
      this._payloadString = binary2Text(getBinaryPayload());
    }
  }

  // --------------------------------ENCODING--------------------------------

  private void readPayloadInfo(InputStream in) throws IOException {
    byte b = (byte) checkedRead(in.read());
    boolean masked = (b & 0x80) != 0;

    this._payloadLength = (byte) (0x7F & b);
    if (this._payloadLength == 126) {
      // checkedRead must return int for this to work
      this._payloadLength = (checkedRead(in.read()) << 8 | checkedRead(in.read())) & 0xFFFF;
      if (this._payloadLength < 126) {
        throw new WebSocketException(CloseCode.ProtocolError,
            "Invalid data frame 2byte length. (not using minimal length encoding)");
      }
    } else if (this._payloadLength == 127) {
      long _payloadLength = (long) checkedRead(in.read()) << 56 | (long) checkedRead(in.read()) << 48
          | (long) checkedRead(in.read()) << 40 | (long) checkedRead(in.read()) << 32 | (long) checkedRead(in.read()) << 24
          | checkedRead(in.read()) << 16 | checkedRead(in.read()) << 8 | checkedRead(in.read());
      if (_payloadLength < 65536) {
        throw new WebSocketException(CloseCode.ProtocolError,
            "Invalid data frame 4byte length. (not using minimal length encoding)");
      }
      if (_payloadLength > Integer.MAX_VALUE) {
        throw new WebSocketException(CloseCode.MessageTooBig, "Max frame length has been exceeded.");
      }
      this._payloadLength = (int) _payloadLength;
    }

    // bound the frame size before the payload buffer is allocated
    if (this._payloadLength > ServerConfig.getMaxWebSocketFrameSize()) {
      throw new WebSocketException(CloseCode.MessageTooBig, "Frame payload of " + this._payloadLength
          + " bytes exceeds the configured maximum of " + ServerConfig.getMaxWebSocketFrameSize() + " bytes.");
    }

    if (this.opCode.isControlFrame()) {
      if (this._payloadLength > 125) {
        throw new WebSocketException(CloseCode.ProtocolError, "Control frame with payload length > 125 bytes.");
      }
      if (this.opCode == OpCode.Close && this._payloadLength == 1) {
        throw new WebSocketException(CloseCode.ProtocolError, "Received close frame with payload len 1.");
      }
    }

    if (masked) {
      this.maskingKey = new byte[4];
      int read = 0;
      while (read < this.maskingKey.length) {
        read += checkedRead(in.read(this.maskingKey, read, this.maskingKey.length - read));
      }
    }
  }

  /** replace the payload bytes (invalidates the cached text form) */
  public void setBinaryPayload(byte[] payload) {
    this.payload = payload;
    this._payloadLength = payload.length;
    this._payloadString = null;
  }

  /** set whether this frame terminates a message */
  public void setFin(boolean fin) { this.fin = fin; }

  /** set the masking key (exactly 4 bytes, or null to unmask) */
  public void setMaskingKey(byte[] maskingKey) {
    if (maskingKey != null && maskingKey.length != 4) {
      throw new IllegalArgumentException("MaskingKey " + Arrays.toString(maskingKey) + " hasn't length 4");
    }
    this.maskingKey = maskingKey;
  }

  /** change this frame's opcode */
  public void setOpCode(OpCode opcode) { this.opCode = opcode; }

  /** replace the payload with the UTF-8 encoding of the given text */
  public void setTextPayload(String payload) throws CharacterCodingException {
    this.payload = text2Binary(payload);
    this._payloadLength = payload.length();
    this._payloadString = payload;
  }

  // --------------------------------CONSTANTS-------------------------------

  @Override
  public String toString() {
    String sb = "WS[" + getOpCode() + ", " + (isFin() ? "fin" : "inter") + ", " + (isMasked() ? "masked" : "unmasked") + ", "
        + payloadToString() + ']';
    return sb;
  }

  // ------------------------------------------------------------------------

  /**
   * serialize this frame (header, length, optional mask, payload) and flush the stream
   */
  public void write(OutputStream out) throws IOException {
    byte header = 0;
    if (this.fin) {
      header |= 0x80;
    }
    header |= this.opCode.getValue() & 0x0F;
    out.write(header);

    this._payloadLength = getBinaryPayload().length;
    if (this._payloadLength <= 125) {
      out.write(isMasked() ? 0x80 | (byte) this._payloadLength : (byte) this._payloadLength);
    } else if (this._payloadLength <= 0xFFFF) {
      out.write(isMasked() ? 0xFE : 126);
      out.write(this._payloadLength >>> 8);
      out.write(this._payloadLength);
    } else {
      out.write(isMasked() ? 0xFF : 127);
      // _payloadLength is an int (max 31 bits), so the top 4 of the 8
      // length bytes are always zero
      out.write(0);
      out.write(0);
      out.write(0);
      out.write(0);
      out.write(this._payloadLength >>> 24);
      out.write(this._payloadLength >>> 16);
      out.write(this._payloadLength >>> 8);
      out.write(this._payloadLength);
    }

    if (isMasked()) {
      out.write(this.maskingKey);
      for (int i = 0; i < this._payloadLength; i++) {
        out.write(getBinaryPayload()[i] ^ this.maskingKey[i % 4]);
      }
    } else {
      out.write(getBinaryPayload());
    }
    out.flush();
  }

}
