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

import com.sun.net.httpserver.HttpExchange;
import robaho.net.httpserver.ServerConfig;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.CharacterCodingException;
import java.util.LinkedList;
import java.util.Timer;
import java.util.TimerTask;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.INFO;
import static java.lang.System.Logger.Level.TRACE;

public abstract class WebSocket {

    protected final System.Logger logger = System.getLogger("robaho.net.httpserver.websockets");

    private final InputStream in;
    private final OutputStream out;

    private OpCode continuousOpCode = null;

    private final List<WebSocketFrame> continuousFrames = new LinkedList<WebSocketFrame>();

    /** running total of the current fragmented message's payload bytes:
     * maintained incrementally as fragments arrive (the old checkMessageSize
     * re-summed the whole fragment list per fragment - O(n^2) per message) */
    private long continuousBytes;

    /** package-private: BufferedWebSocket's send guard reads it */
    volatile WebSocketLifecycle state = WebSocketLifecycle.UNCONNECTED;

    /** shared daemon timer for the close-handshake watchdog (RFC 6455 8.1) */
    private static final java.util.Timer CLOSE_WATCHDOG = new java.util.Timer("websocket-close-watchdog", true);

    /** how long to wait for the peer's close echo before forcing the
     * connection down */
    private static final long CLOSE_HANDSHAKE_TIMEOUT_MILLIS = 10_000;

    /** watchdog retry period when the lock is contended by a stalled write */
    private static final long WATCHDOG_RETRY_MILLIS = 1_000;

    /** null or a code that may legally be SENT in a Close frame: 1005/1006/
     * 1015 and the unassigned 1016-2999 MUST NOT appear on the wire (RFC
     * 6455 7.4.1) - they are answered codeless instead */
    static CloseCode sanitizeCloseCode(CloseCode code) {
        if (code == null) {
            return null;
        }
        int v = code.getValue();
        if (v == 1005 || v == 1006 || v == 1015 || (v >= 1016 && v < 3000)) {
            return null;
        }
        return code;
    }

    /** close info recorded when the closing handshake begins, so the read
     * loop exit can surface the real code instead of a generic 1011.
     * recorded = true whenever info was recorded (locally or from a remote
     * close frame): an application-defined 3xxx/4xxx close code has no
     * CloseCode mapping (code = null) and must still not be reported as 1011 */
    private record CloseInfo(CloseCode code, String reason, boolean initiatedByRemote, boolean recorded) {}
    private volatile CloseInfo pendingClose;

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

        this.state = WebSocketLifecycle.CONNECTING;
        this.in = exchange.getRequestBody();
        this.out = exchange.getResponseBody();
    }

    /** true until the close handshake completes */
    public boolean isOpen() {
        return state == WebSocketLifecycle.OPEN;
    }

    protected void onOpen() throws WebSocketException {
    }

    protected abstract void onClose(CloseCode code, String reason, boolean initiatedByRemote);

    protected abstract void onMessage(WebSocketFrame message) throws WebSocketException;

    /** called when a pong frame arrives. rarely needed: the framework
     * answers pings automatically; override only for keepalive monitoring */
    protected void onPong(WebSocketFrame pong) throws WebSocketException {
    }

    protected void onException(IOException exception) {
        if(state!=WebSocketLifecycle.CLOSING && state!=WebSocketLifecycle.CLOSED) {
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

    /** close this websocket with a normal closure. the 3-arg variant is
     * internal: initiatedByRemote is a fact the framework tracks, not a
     * parameter for the caller to guess */
    /** initiate (or acknowledge) the close handshake: sends a Close frame
     *  with the given code and reason and closes the connection. safe to
     *  call more than once - only the first call has an effect */
    public void close(CloseCode code, String reason) throws IOException {
        close(code, reason, false);
    }

    void close(CloseCode code, String reason, boolean initiatedByRemote) throws IOException {
        logger.log(INFO, "closing websocket {0}", uri);
        boolean completeClose;
        lock.lock();
        try {
            if (this.state == WebSocketLifecycle.CLOSED) {
                // already fully closed: overwriting the state to CLOSING
                // below would let the subsequent doClose re-fire onClose
                return;
            }
            WebSocketLifecycle oldState = this.state;
            this.state = WebSocketLifecycle.CLOSING;
            if (oldState == WebSocketLifecycle.OPEN) {
                // record the close info: after the closing handshake completes
                // (or the peer disappears) the read loop exits and must surface
                // this code, not a generic internal error
                pendingClose = new CloseInfo(code, reason, initiatedByRemote, true);
                // a code that MUST NOT be sent (RFC 6455 7.4.1: 1005/1006/
                // 1015 and the unassigned 1016-2999) is answered with a
                // codeless close instead of being echoed onto the wire
                CloseCode sendable = sanitizeCloseCode(code);
                sendFrame(sendable == null ? new CloseFrame(null, null) : new CloseFrame(sendable, reason));
                completeClose = false;
                // the read loop now blocks waiting for the peer's close echo
                // with NO timeout: a peer that withholds it pinned the read
                // loop, the virtual thread and the socket forever. RFC 6455
                // 8.1: the endpoint SHOULD close the connection after a
                // timeout - schedule that here
                scheduleCloseWatchdog(CLOSE_HANDSHAKE_TIMEOUT_MILLIS);
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
    /** subclass hook invoked under the lock before a forced close (the
     * watchdog path): stop background tasks that would otherwise outlive the
     * websocket. the default is a no-op */
    void prepareForcedClose() {
    }

    /** the close-handshake watchdog (RFC 6455 8.1): force the connection
     * down when the peer withholds the close echo. a FRESH TimerTask per
     * (re)schedule: java.util.Timer requires the VIRGIN state, so
     * rescheduling the executing task always throws IllegalStateException -
     * the first version of the retry swallowed that and the watchdog stayed
     * one-shot, silently no-oping exactly when a stalled write held the
     * lock. the state check bounds the retry loop: once CLOSED, it stops */
    private void scheduleCloseWatchdog(long delayMillis) {
        CLOSE_WATCHDOG.schedule(new TimerTask() {
            @Override
            public void run() {
                // isolation for the ONE shared timer thread: (a) a user
                // onClose callback throwing anything but InterruptedException
                // PERMANENTLY kills a java.util.Timer (its mainLoop catches
                // only IE); (b) a stalled socket write can hold WebSocket.lock
                // for the TCP timeout - tryLock so one stuck connection
                // cannot queue every other websocket's watchdog behind it
                try {
                    if (state != WebSocketLifecycle.CLOSING) {
                        return;
                    }
                    if (!lock.tryLock()) {
                        scheduleCloseWatchdog(WATCHDOG_RETRY_MILLIS);
                        return;
                    }
                    try {
                        if (state == WebSocketLifecycle.CLOSING) {
                            doCloseLocked(CloseCode.AbnormalClosure, "close handshake timed out", false);
                        }
                    } finally {
                        lock.unlock();
                    }
                } catch (Throwable t) {
                    logger.log(System.Logger.Level.DEBUG, "close watchdog failed", t);
                }
            }
        }, delayMillis);
    }

    void doClose(CloseCode code, String reason, boolean initiatedByRemote) {
        lock.lock();
        try {
            doCloseLocked(code, reason, initiatedByRemote);
        } finally {
            lock.unlock();
        }
    }

    /** the lock-held body of doClose, callable with the lock already held
     * (the watchdog's tryLock path) */
    private void doCloseLocked(CloseCode code, String reason, boolean initiatedByRemote) {
        if (this.state == WebSocketLifecycle.CLOSED) {
            return;
        }
        // subclass hook (BufferedWebSocket's poison pill): the forced-close
        // path must stop the drain task too, or it stays parked in
        // queue.take() forever - one leaked virtual thread per occurrence.
        // runs UNDER the lock and must not block
        prepareForcedClose();
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
        this.state = WebSocketLifecycle.CLOSED;
        onClose(code, reason, initiatedByRemote);
    }

    // --------------------------------IO--------------------------------------

    private void handleCloseFrame(WebSocketFrame frame) throws IOException {
        CloseCode code = CloseCode.NormalClosure;
        String reason = "";
        if (frame instanceof CloseFrame) {
            code = ((CloseFrame) frame).getCloseCode();
            reason = ((CloseFrame) frame).getCloseReason();
        }
        // a received code that can never legally appear on the wire is a
        // protocol violation: answer with 1002, not an echo (RFC 6455 7.4.1)
        if (code != null && sanitizeCloseCode(code) == null) {
            code = CloseCode.ProtocolError;
            reason = "invalid close code in close frame";
        }
        // read the state under the lock: the old unlocked check could act on
        // a stale OPEN while a user thread was already CLOSING, mislabeling
        // the close initiator and letting the remote's code win
        boolean peerAnswering;
        lock.lock();
        try {
            peerAnswering = this.state == WebSocketLifecycle.CLOSING;
        } finally {
            lock.unlock();
        }
        logger.log(TRACE, "handleCloseFrame: {0}, code={1}, reason={2}, closing {3}", uri, code, reason, peerAnswering);
        if (peerAnswering) {
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
            this.continuousBytes = frame.getBinaryPayload().length;
            this.continuousFrames.add(frame);
        } else if (frame.isFin()) {
            // Last
            if (this.continuousOpCode == null) {
                throw new WebSocketException(CloseCode.ProtocolError, "Continuous frame sequence was not started.");
            }
            checkMessageSize(frame);
            this.continuousBytes += frame.getBinaryPayload().length;
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
            this.continuousBytes = 0;
        } else if (this.continuousOpCode == null) {
            // Unexpected
            throw new WebSocketException(CloseCode.ProtocolError, "Continuous frame sequence was not started.");
        } else {
            // Intermediate
            checkMessageSize(frame);
            this.continuousBytes += frame.getBinaryPayload().length;
            this.continuousFrames.add(frame);
        }
    }

    /** reject a fragmented message whose total size exceeds the configured maximum */
    private void checkMessageSize(WebSocketFrame frame) throws WebSocketException {
        if (continuousBytes + frame.getBinaryPayload().length > ServerConfig.getMaxWebSocketMessageSize()) {
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

    /** send a Ping control frame; the peer is expected to answer with a
     *  Pong carrying the same payload (delivered to onPong) */
    public void ping(byte[] payload) throws IOException {
        sendFrame(new WebSocketFrame(OpCode.Ping, true, payload));
    }

    void readWebsocket() {
        try {
            // the ONLY unlocked state write used to be right here: a user
            // thread calling close() before the read loop started completed
            // the whole close (CLOSED + onClose), and this plain write then
            // resurrected the connection to OPEN - isOpen() true on a dead
            // socket, onOpen firing after onClose. guard the transition and
            // bail out when the websocket was closed in the meantime
            lock.lock();
            try {
                if (state != WebSocketLifecycle.CONNECTING) {
                    return;
                }
                state = WebSocketLifecycle.OPEN;
            } finally {
                lock.unlock();
            }
            logger.log(DEBUG, "websocket open {0}", uri);
            onOpen();
            while (this.state == WebSocketLifecycle.OPEN) {
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
            if (this.state == WebSocketLifecycle.CLOSING && pendingClose != null && pendingClose.recorded()) {
                // the closing handshake began (locally or remotely) but did not
                // complete - surface the recorded close code and reason. the code
                // is null for application-defined 3xxx/4xxx codes (no CloseCode
                // mapping), but the reason and initiator are the real ones
                doClose(pendingClose.code(), pendingClose.reason(), pendingClose.initiatedByRemote());
            } else if (this.state != WebSocketLifecycle.CLOSED) {
                doClose(CloseCode.InternalServerError, "Handler terminated without closing the connection.", false);
            }
            logger.log(TRACE, "readWebsocket() exiting {0}", uri);
        }
    }

    /** send a complete binary message (one unfragmented frame) */
    public void send(byte[] payload) throws IOException {
        sendFrame(new WebSocketFrame(OpCode.Binary, true, payload));
    }

    /** send a complete UTF-8 text message (one unfragmented frame) */
    public void send(String payload) throws IOException {
        sendFrame(new WebSocketFrame(OpCode.Text, true, payload));
    }

    /** write one frame under the connection lock: the only thread-safe
     *  send primitive - concurrent senders are serialized here. subclasses
     *  overriding sendFrame must preserve the locking */
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
