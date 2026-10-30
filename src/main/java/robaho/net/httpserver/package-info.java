/**
 * The HTTP server implementation: a zero-dependency, virtual-thread-per- connection replacement for the JDK's
 * {@code com.sun.net.httpserver}, including HTTP/1.1, HTTP/2 and WebSocket support.
 * <p>
 * The supported API surface of this package is:
 * <ul>
 * <li>{@link robaho.net.httpserver.ServerConfig} - all tunables, each getter documenting its {@code -D} property,
 * default and semantics</li>
 * <li>{@link robaho.net.httpserver.AuthFilter} - the authentication filter SPI used with the JDK's
 * {@code Authenticator}</li>
 * <li>{@link robaho.net.httpserver.AttributeKeys} - well-known {@code HttpExchange} attribute keys</li>
 * <li>{@link robaho.net.httpserver.HttpStatus} - HTTP status code constants</li>
 * <li>{@link robaho.net.httpserver.DefaultHttpServerProvider} - the {@code HttpServerProvider} SPI implementation</li>
 * </ul>
 * <p>
 * Every other class in this package (and everything in {@code robaho.net.httpserver.internal}) is implementation
 * detail: it may be package-private, or public only for intra-module wiring, and carries no compatibility promise
 * across releases.
 */
package robaho.net.httpserver;
