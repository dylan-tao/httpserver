package robaho.net.httpserver.websockets;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import robaho.net.httpserver.HttpStatus;

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
      WebSocketHandshake.sendResponseHeaders(exchange, HttpStatus.HTTP_BAD_REQUEST, "websocket handshake must be a GET");
      return;
    }

    if (!isWebsocketRequested(headers)) {
      WebSocketHandshake.sendResponseHeaders(exchange, HttpStatus.HTTP_BAD_REQUEST, "not a websocket upgrade request");
      return;
    }

    if (!WebSocketHandshake.HEADER_WEBSOCKET_VERSION_VALUE
        .equalsIgnoreCase(headers.getFirst(WebSocketHandshake.HEADER_WEBSOCKET_VERSION))) {
      WebSocketHandshake.sendResponseHeaders(exchange, HttpStatus.HTTP_BAD_REQUEST,
          "Invalid Websocket-Version " + headers.getFirst(WebSocketHandshake.HEADER_WEBSOCKET_VERSION));
      return;
    }

    if (!headers.containsKey(WebSocketHandshake.HEADER_WEBSOCKET_KEY)) {
      WebSocketHandshake.sendResponseHeaders(exchange, HttpStatus.HTTP_BAD_REQUEST, "Missing Websocket-Key");
      return;
    }
    // RFC 6455 4.2.1: the key MUST be base64 of 16 random bytes (24
    // chars). the old code accepted any non-empty value, so a
    // non-conformant client got a 101 computed over garbage
    String wsKey = headers.getFirst(WebSocketHandshake.HEADER_WEBSOCKET_KEY);
    if (wsKey == null || wsKey.length() != 24 || !wsKey.matches("[A-Za-z0-9+/]{22}==")) {
      WebSocketHandshake.sendResponseHeaders(exchange, HttpStatus.HTTP_BAD_REQUEST, "Invalid Websocket-Key");
      return;
    }

    try {
      exchange.getResponseHeaders().add(WebSocketHandshake.HEADER_WEBSOCKET_ACCEPT,
          WebSocketHandshake.makeAcceptKey(headers.getFirst(WebSocketHandshake.HEADER_WEBSOCKET_KEY)));
    } catch (NoSuchAlgorithmException e) {
      WebSocketHandshake.sendResponseHeaders(exchange, HttpStatus.HTTP_INTERNAL_ERROR,
          "The SHA-1 Algorithm required for websockets is not available on the server.");
      return;
    }

    // only echo a sub protocol this handler actually supports; echoing an
    // unsupported one would make the client believe it was negotiated
    String requestedProtocol = headers.getFirst(WebSocketHandshake.HEADER_WEBSOCKET_PROTOCOL);
    if (requestedProtocol != null) {
      for (String candidate : requestedProtocol.split(",")) {
        String accepted = supportsProtocol(candidate.trim());
        if (accepted != null) {
          exchange.getResponseHeaders().add(WebSocketHandshake.HEADER_WEBSOCKET_PROTOCOL, accepted);
          break;
        }
      }
    }

    exchange.getResponseHeaders().add(WebSocketHandshake.HEADER_UPGRADE, WebSocketHandshake.HEADER_UPGRADE_VALUE);
    exchange.getResponseHeaders().add(WebSocketHandshake.HEADER_CONNECTION, WebSocketHandshake.HEADER_CONNECTION_VALUE);

    exchange.sendResponseHeaders(HttpStatus.HTTP_SWITCHING_PROTOCOLS, 0);

    // construct the user websocket only after the 101: the raw-stream
    // handoff (getRequestBody/getResponseBody) switches to the tunnel
    // streams only once the 101 ran (ExchangeImpl sets connectionUpgraded
    // there) - constructing EARLIER would hand the websocket the framed
    // request stream instead of the raw tunnel
    WebSocket webSocket = openWebSocket(exchange);
    if (webSocket == null) {
      // a user handler rejecting the handshake with null: the 101 is
      // already on the wire, so no second status line can be sent (the
      // old late-500 attempt died on the "headers already sent"
      // IOException - audit W15). close the exchange: the raw-stream
      // teardown ends the connection right after the 101, through the
      // normal no-leak close path
      exchange.close();
      return;
    }

    // this won't return until websocket is closed
    webSocket.readWebsocket();
  }

  /**
   * override to declare support for a requested websocket sub protocol.
   * @param protocol a protocol name requested by the client (already trimmed)
   * @return the protocol name to confirm (may normalize case), or null if not supported
   */
  protected String supportsProtocol(String protocol) {
    return null;
  }

  public static boolean isWebsocketRequested(Headers headers) {
    // the Connection header is a comma separated token list: "X-UpgradeFoo"
    // must not match an "Upgrade" token
    var values = headers.get(WebSocketHandshake.HEADER_CONNECTION);
    if (values == null) {
      return false;
    }
    boolean connectionUpgrade = values.stream().flatMap(v -> java.util.Arrays.stream(v.split(","))).map(String::trim)
        .anyMatch(s -> WebSocketHandshake.HEADER_CONNECTION_VALUE.equalsIgnoreCase(s));
    if (!connectionUpgrade) {
      return false;
    }
    // check for proper upgrade type
    String upgrade = headers.getFirst(WebSocketHandshake.HEADER_UPGRADE);
    return WebSocketHandshake.HEADER_UPGRADE_VALUE.equalsIgnoreCase(upgrade);
  }

  protected abstract WebSocket openWebSocket(HttpExchange exchange);

}
