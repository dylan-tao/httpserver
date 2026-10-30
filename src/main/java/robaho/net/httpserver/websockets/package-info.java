/**
 * WebSocket support: {@link robaho.net.httpserver.websockets.WebSocketHandler} registers a WebSocket endpoint,
 * {@link robaho.net.httpserver.websockets.WebSocket} is the server-side session, and
 * {@link robaho.net.httpserver.websockets.BufferedWebSocket} adds a bounded send queue with backpressure for producers
 * that must not block.
 */
package robaho.net.httpserver.websockets;
