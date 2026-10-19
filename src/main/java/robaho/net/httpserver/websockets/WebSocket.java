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

import java.io.IOException;
import java.io.EOFException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.CharacterCodingException;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import com.sun.net.httpserver.HttpExchange;

import robaho.net.httpserver.ServerConfig;

import static java.lang.System.Logger.Level.*;

public abstract class WebSocket {

    protected final System.Logger logger = System.getLogger("robaho.net.httpserver.websockets");

    private final InputStream in;
    private final OutputStream out;

    private OpCode continuousOpCode = null;

    private final List<WebSocketFrame> continuousFrames = new LinkedList<WebSocketFrame>();

    private volatile State state = State.UNCONNECTED;

    /** close info recorded when the closing handshake begins, so the read
     * loop exit can surface the real code instead of a generic 1011 */
    private volatile CloseCode pendingCloseCode;
    private volatile String pendingCloseReason;
    private volatile boolean pendingCloseByRemote;
    /** set whenever close info has been recorded (locally or from a remote
     * close frame): an application-defined 3xxx/4xxx close code has no
     * CloseCode mapping (null) and must still not be reported as 1011 */
    private volatile boolean pendingCloseRecorded;

    /** serializes frame writes AND the close transition: a user-thread send
     * must not interleave with the read thread's close (garbled tail frames),
     * and only one thread may transition to CLOSED and invoke onClose. a
     * ReentrantLock, NOT a synchronized monitor: close does blocking IO while
     * holding it, which would pin a virtual thread's carrier (JEP 444) */
    private final Lock lock = new ReentrantLock();

    private final URI uri;

    protected WebSocket(HttpExchange exchange) {
        this.uri = exchange.getRequestURI();

        logger.log(INFO, "connecting websocket {0}", uri);

        this.state = State.CONNECTING;
        this.in = exchange.getRequestBody();
        this.out = exchange.getResponseBody();
    }

    public boolean isOpen() {
        return state == State.OPEN;
    }

    protected void onOpen() throws WebSocketException {
    }

    protected abstract void onClose(CloseCode code, String reason, boolean initiatedByRemote);

    protected abstract void onMessage(WebSocketFrame message) throws WebSocketException;

    protected abstract void onPong(WebSocketFrame pong) throws WebSocketException;

    protected void onException(IOException exception) {
        if(state!=State.CLOSING && state!=State.CLOSED) {
            logger.log(TRACE, "exception on websocket", exception);
        }
    }

    protected void onFrameReceived(WebSocketFrame frame) {
        logger.log(TRACE, "frame received: {0}", frame);
    }

    /**
     * Debug method. <b>Do not Override unless for debug purposes!</b><br>
     * This method is called before actually sending the frame.
     * 
     * @param frame
     *              The sent WebSocket Frame.
     */
    protected void onFrameSent(WebSocketFrame frame) {
        logger.log(TRACE, "frame sent: {0}", frame);
    }

    public void close(CloseCode code, String reason, boolean initiatedByRemote) throws IOException {
        logger.log(INFO, "closing websocket {0}", uri);
        boolean completeClose;
        lock.lock();
        try {
            if (this.state == State.CLOSED) {
                // already fully closed: overwriting the state to CLOSING
                // below would let the subsequent doClose re-fire onClose
                return;
            }
            State oldState = this.state;
            this.state = State.CLOSING;
            if (oldState == State.OPEN) {
                // record the close info: after the closing handshake completes
                // (or the peer disappears) the read loop exits and must surface
                // this code, not a generic internal error
                pendingCloseCode = code;
                pendingCloseReason = reason;
                pendingCloseByRemote = initiatedByRemote;
                pendingCloseRecorded = true;
                sendFrame(new CloseFrame(code, reason));
                completeClose = false;
            } else {
                completeClose = true;
            }
        } finally {
            lock.unlock();
        }
        if (completeClose) {
            // outside the lock: BufferedWebSocket.doClose waits for the drain
            // task, whose sendFrames flushes under this lock - waiting while
            // holding it deadlocks for the full future.get timeout and then
            // cancels the drain, losing every queued frame. doClose takes the
            // lock itself, so the once-only CLOSED transition stays atomic
            doClose(code, reason, initiatedByRemote);
        }
    }

    /** only one thread may transition to CLOSED and invoke onClose (the lock
     * is reentrant from close()) */
    void doClose(CloseCode code, String reason, boolean initiatedByRemote) {
        lock.lock();
        try {
            if (this.state == State.CLOSED) {
                return;
            }
            if (this.in != null) {
                try {
                    this.in.close();
                } catch (IOException expected) {
                }
            }
            if (this.out != null) {
                try {
                    this.out.close();
                } catch (IOException expected) {
                }
            }
            this.state = State.CLOSED;
            onClose(code, reason, initiatedByRemote);
        } finally {
            lock.unlock();
        }
    }

    // --------------------------------IO--------------------------------------

    private void handleCloseFrame(WebSocketFrame frame) throws IOException {
        CloseCode code = CloseCode.NormalClosure;
        String reason = "";
        if (frame instanceof CloseFrame) {
            code = ((CloseFrame) frame).getCloseCode();
            reason = ((CloseFrame) frame).getCloseReason();
        }
        logger.log(TRACE, "handleCloseFrame: {0}, code={1}, reason={2}, state {3}", uri, code, reason, this.state);
        if (this.state == State.CLOSING) {
            // Answer for my requested close
            doClose(code, reason, false);
        } else {
            close(code, reason, true);
        }
    }

    private void handleFrameFragment(WebSocketFrame frame) throws IOException {
        if (frame.getOpCode() != OpCode.Continuation) {
            // First
            if (this.continuousOpCode != null) {
                throw new WebSocketException(CloseCode.ProtocolError,
                        "Previous continuous frame sequence not completed.");
            }
            checkMessageSize(frame);
            this.continuousOpCode = frame.getOpCode();
            this.continuousFrames.clear();
            this.continuousFrames.add(frame);
        } else if (frame.isFin()) {
            // Last
            if (this.continuousOpCode == null) {
                throw new WebSocketException(CloseCode.ProtocolError, "Continuous frame sequence was not started.");
            }
            checkMessageSize(frame);
            this.continuousFrames.add(frame);
            WebSocketFrame message = new WebSocketFrame(this.continuousOpCode, this.continuousFrames);
            if (this.continuousOpCode == OpCode.Text) {
                // validate the WHOLE message (RFC 6455 5.6, 8.2): per-fragment
                // validation would reject a legal code point split across
                // fragments, and continuation frames were not validated at all
                try {
                    WebSocketFrame.binary2Text(message.getBinaryPayload());
                } catch (CharacterCodingException e) {
                    throw new WebSocketException(CloseCode.InvalidFramePayloadData,
                        "invalid UTF-8 in fragmented text message");
                }
            }
            onMessage(message);
            this.continuousOpCode = null;
            this.continuousFrames.clear();
        } else if (this.continuousOpCode == null) {
            // Unexpected
            throw new WebSocketException(CloseCode.ProtocolError, "Continuous frame sequence was not started.");
        } else {
            // Intermediate
            checkMessageSize(frame);
            this.continuousFrames.add(frame);
        }
    }

    /** reject a fragmented message whose total size exceeds the configured maximum */
    private void checkMessageSize(WebSocketFrame frame) throws WebSocketException {
        long total = frame.getBinaryPayload().length;
        for (WebSocketFrame f : this.continuousFrames) {
            total += f.getBinaryPayload().length;
        }
        if (total > ServerConfig.getMaxWebSocketMessageSize()) {
            throw new WebSocketException(CloseCode.MessageTooBig,
                    "aggregated message exceeds " + ServerConfig.getMaxWebSocketMessageSize() + " bytes");
        }
    }

    private void handleWebsocketFrame(WebSocketFrame frame) throws IOException {
        onFrameReceived(frame);
        if (frame.getOpCode() == OpCode.Close) {
            handleCloseFrame(frame);
        } else if (frame.getOpCode() == OpCode.Ping) {
            sendFrame(new WebSocketFrame(OpCode.Pong, true, frame.getBinaryPayload()));
        } else if (frame.getOpCode() == OpCode.Pong) {
            onPong(frame);
        } else if (!frame.isFin() || frame.getOpCode() == OpCode.Continuation) {
            handleFrameFragment(frame);
        } else if (this.continuousOpCode != null) {
            throw new WebSocketException(CloseCode.ProtocolError, "Continuous frame sequence not completed.");
        } else if (frame.getOpCode() == OpCode.Text || frame.getOpCode() == OpCode.Binary) {
            onMessage(frame);
        } else {
            throw new WebSocketException(CloseCode.ProtocolError, "Non control or continuous frame expected.");
        }
    }

    // --------------------------------Close-----------------------------------

    public void ping(byte[] payload) throws IOException {
        sendFrame(new WebSocketFrame(OpCode.Ping, true, payload));
    }

    void readWebsocket() {
        try {
            state = State.OPEN;
            logger.log(DEBUG, "websocket open {0}", uri);
            onOpen();
            while (this.state == State.OPEN) {
                WebSocketFrame frame = WebSocketFrame.read(in);
                if (!frame.isMasked()) {
                    // all client-to-server frames must be masked (RFC 6455 5.1)
                    throw new WebSocketException(CloseCode.ProtocolError, "client frames must be masked");
                }
                handleWebsocketFrame(frame);
            }
        } catch (EOFException e) {
            onException(e);
            doClose(CloseCode.AbnormalClosure, e.toString(), false);
        } catch (CharacterCodingException e) {
            onException(e);
            doClose(CloseCode.InvalidFramePayloadData, e.toString(), false);
        } catch (IOException e) {
            onException(e);
            if (e instanceof WebSocketException wse) {
                doClose(wse.getCode(), wse.getReason(), false);
            } else {
                doClose(CloseCode.AbnormalClosure, e.toString(), false);
            }
        } finally {
            if (this.state == State.CLOSING && pendingCloseRecorded) {
                // the closing handshake began (locally or remotely) but did not
                // complete - surface the recorded close code and reason. the code
                // is null for application-defined 3xxx/4xxx codes (no CloseCode
                // mapping), but the reason and initiator are the real ones
                doClose(pendingCloseCode, pendingCloseReason, pendingCloseByRemote);
            } else if (this.state != State.CLOSED) {
                doClose(CloseCode.InternalServerError, "Handler terminated without closing the connection.", false);
            }
            logger.log(TRACE, "readWebsocket() exiting {0}", uri);
        }
    }

    public void send(byte[] payload) throws IOException {
        sendFrame(new WebSocketFrame(OpCode.Binary, true, payload));
    }

    public void send(String payload) throws IOException {
        sendFrame(new WebSocketFrame(OpCode.Text, true, payload));
    }

    public void sendFrame(WebSocketFrame frame) throws IOException {
        lock.lock();
        try {
            onFrameSent(frame);
            frame.write(this.out);
        } finally {
            lock.unlock();
        }
    }
}
