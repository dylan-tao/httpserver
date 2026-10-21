package robaho.net.httpserver.websockets;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

public final class WebSocketHandshake {
    private WebSocketHandshake() {
    }


    public static final String HEADER_UPGRADE = "Upgrade";

    public static final String HEADER_UPGRADE_VALUE = "websocket";

    public static final String HEADER_CONNECTION = "Connection";

    public static final String HEADER_CONNECTION_VALUE = "Upgrade";

    public static final String HEADER_WEBSOCKET_VERSION = "sec-websocket-version";

    public static final String HEADER_WEBSOCKET_VERSION_VALUE = "13";

    public static final String HEADER_WEBSOCKET_KEY = "sec-websocket-key";

    public static final String HEADER_WEBSOCKET_ACCEPT = "sec-websocket-accept";

    public static final String HEADER_WEBSOCKET_PROTOCOL = "sec-websocket-protocol";

    private final static String WEBSOCKET_KEY_MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private static String encodeBase64(byte[] buf) {
        return Base64.getEncoder().encodeToString(buf);
    }

    public static String makeAcceptKey(String key) throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        String text = key + WebSocketHandshake.WEBSOCKET_KEY_MAGIC;
        // the key and magic are ASCII; hashing the char count instead of the
        // byte count truncates the input when non-ASCII characters appear
        md.update(text.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        byte[] sha1hash = md.digest();
        return encodeBase64(sha1hash);
    }

    private static final String CONTENT_TYPE = "Content-type";

    public static void sendResponseHeaders(HttpExchange exchange, int code, String reason) throws IOException {
        var bytes = reason.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        // the content type describes the RESPONSE, not the request
        exchange.getResponseHeaders().set(CONTENT_TYPE, "text/plain");
        exchange.sendResponseHeaders(code, bytes.length);
        // closing the response stream flushes it: the handshake error must
        // actually reach the client. do NOT close the request stream here -
        // for upgrade requests it is the raw socket stream, and closing it
        // kills the socket before the response is flushed
        try (var os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
