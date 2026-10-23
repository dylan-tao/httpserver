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

import java.nio.charset.CharacterCodingException;
import java.util.Arrays;

/**
 * A Close control frame (RFC 6455 5.5.1): the payload carries a 2-byte
 * close code and an optional UTF-8 reason, truncated to the 125-byte
 * control frame cap without splitting a UTF-8 sequence.
 */
public class CloseFrame extends WebSocketFrame {

    private static byte[] generatePayload(CloseCode code, String closeReason) throws CharacterCodingException {
        if (code != null) {
            byte[] reasonBytes = text2Binary(closeReason);
            // a control frame payload is limited to 125 bytes (2 code + 123 reason)
            reasonBytes = truncateToValidUtf8(reasonBytes, 123);
            byte[] payload = new byte[reasonBytes.length + 2];
            payload[0] = (byte) (code.getValue() >> 8 & 0xFF);
            payload[1] = (byte) (code.getValue() & 0xFF);
            System.arraycopy(reasonBytes, 0, payload, 2, reasonBytes.length);
            return payload;
        } else {
            return new byte[0];
        }
    }

    /** truncate to at most limit bytes without cutting inside a multi byte UTF-8 sequence */
    private static byte[] truncateToValidUtf8(byte[] bytes, int limit) {
        if (bytes.length <= limit) {
            return bytes;
        }
        byte[] truncated = Arrays.copyOf(bytes, limit);
        // strip trailing continuation bytes (10xxxxxx) of a cut sequence
        while (truncated.length > 0 && (truncated[truncated.length - 1] & 0xC0) == 0x80) {
            truncated = Arrays.copyOf(truncated, truncated.length - 1);
        }
        // a leading byte (11xxxxxx) left without its continuation bytes is also invalid
        if (truncated.length > 0 && (truncated[truncated.length - 1] & 0x80) != 0) {
            truncated = Arrays.copyOf(truncated, truncated.length - 1);
        }
        return truncated;
    }

    private CloseCode _closeCode;

    private String _closeReason;

    /** build a Close frame to send: code and reason are encoded into the
     * payload, the reason truncated to 123 bytes at a UTF-8 boundary */
    public CloseFrame(CloseCode code, String closeReason) throws CharacterCodingException {
        super(OpCode.Close, true, generatePayload(code, closeReason));
    }

    /** parse a received Close frame: extracts the close code and reason */
    public CloseFrame(WebSocketFrame wrap) throws CharacterCodingException {
        super(wrap);
        assert wrap.getOpCode() == OpCode.Close;
        if (wrap.getBinaryPayload().length >= 2) {
            int codeValue = (wrap.getBinaryPayload()[0] & 0xFF) << 8 | wrap.getBinaryPayload()[1] & 0xFF;
            this._closeCode = CloseCode.find(codeValue);
            this._closeReason = binary2Text(getBinaryPayload(), 2, getBinaryPayload().length - 2);
        }
    }

    /** the received close code, or null when the payload carried none */
    public CloseCode getCloseCode() {
        return this._closeCode;
    }


    /** the received close reason, or null */
    public String getCloseReason() {
        return this._closeReason;
    }
}
