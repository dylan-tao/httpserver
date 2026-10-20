package robaho.net.httpserver.websockets;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import robaho.net.httpserver.Code;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;

public abstract class WebSocketHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        Headers headers = exchange.getRequestHeaders();

        // the handshake must be a GET request (RFC 6455 4.2.1). a null method
        // (e.g. from a stubbed exchange) is not rejected.
        String requestMethod = exchange.getRequestMethod();
        if (requestMethod != null && !"GET".equalsIgnoreCase(requestMethod)) {
            Util.sendResponseHeaders(exchange, Code.HTTP_BAD_REQUEST, "websocket handshake must be a GET");
            return;
        }

        if (!isWebsocketRequested(headers)) {
            Util.sendResponseHeaders(exchange, Code.HTTP_BAD_REQUEST, "not a websocket upgrade request");
            return;
        }

        if (!Util.HEADER_WEBSOCKET_VERSION_VALUE.equalsIgnoreCase(headers.getFirst(Util.HEADER_WEBSOCKET_VERSION))) {
            Util.sendResponseHeaders(exchange, Code.HTTP_BAD_REQUEST,
                    "Invalid Websocket-Version " + headers.getFirst(Util.HEADER_WEBSOCKET_VERSION));
            return;
        }

        if (!headers.containsKey(Util.HEADER_WEBSOCKET_KEY)) {
            Util.sendResponseHeaders(exchange, Code.HTTP_BAD_REQUEST, "Missing Websocket-Key");
            return;
        }

        try {
            exchange.getResponseHeaders().add(Util.HEADER_WEBSOCKET_ACCEPT,
                    Util.makeAcceptKey(headers.getFirst(Util.HEADER_WEBSOCKET_KEY)));
        } catch (NoSuchAlgorithmException e) {
            Util.sendResponseHeaders(exchange, Code.HTTP_INTERNAL_ERROR,
                    "The SHA-1 Algorithm required for websockets is not available on the server.");
            return;
        }

        // only echo a sub protocol this handler actually supports; echoing an
        // unsupported one would make the client believe it was negotiated
        String requestedProtocol = headers.getFirst(Util.HEADER_WEBSOCKET_PROTOCOL);
        if (requestedProtocol != null) {
            for (String candidate : requestedProtocol.split(",")) {
                String accepted = supportsProtocol(candidate.trim());
                if (accepted != null) {
                    exchange.getResponseHeaders().add(Util.HEADER_WEBSOCKET_PROTOCOL, accepted);
                    break;
                }
            }
        }

        exchange.getResponseHeaders().add(Util.HEADER_UPGRADE, Util.HEADER_UPGRADE_VALUE);
        exchange.getResponseHeaders().add(Util.HEADER_CONNECTION, Util.HEADER_CONNECTION_VALUE);

        exchange.sendResponseHeaders(Code.HTTP_SWITCHING_PROTOCOLS, 0);

        // construct the user websocket only after the 101: the handshake
        // result (accepted subprotocol etc.) is known at this point, and a
        // handshake failure above never creates a half-initialized socket
        WebSocket webSocket = openWebSocket(exchange);

        // this won't return until websocket is closed
        webSocket.readWebsocket();
    }

    /**
     * override to declare support for a requested websocket sub protocol.
     *
     * @param protocol a protocol name requested by the client (already trimmed)
     * @return the protocol name to confirm (may normalize case), or null if not supported
     */
    protected String supportsProtocol(String protocol) {
        return null;
    }

    public static boolean isWebsocketRequested(Headers headers) {
        // the Connection header is a comma separated token list: "X-UpgradeFoo"
        // must not match an "Upgrade" token
        var values = headers.get(Util.HEADER_CONNECTION);
        if (values == null) {
            return false;
        }
        boolean connectionUpgrade = values.stream()
                .flatMap(v -> java.util.Arrays.stream(v.split(",")))
                .map(String::trim)
                .anyMatch(s -> Util.HEADER_CONNECTION_VALUE.equalsIgnoreCase(s));
        if (!connectionUpgrade) {
            return false;
        }
        // check for proper upgrade type
        String upgrade = headers.getFirst(Util.HEADER_UPGRADE);
        return Util.HEADER_UPGRADE_VALUE.equalsIgnoreCase(upgrade);
    }

    protected abstract WebSocket openWebSocket(HttpExchange exchange);
}
