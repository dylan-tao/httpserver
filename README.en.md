# robaho httpserver

A zero-dependency drop-in replacement for the JDK's `com.sun.net.httpserver`,
designed for virtual threads. Includes HTTP/1.1, HTTP/2 (ALPN + h2c
prior-knowledge) and WebSocket support.

- JDK 25+, zero runtime dependencies, virtual-thread-per-connection
- HTTP/2: ALPN over TLS, h2c prior knowledge, flow control, HPACK
- WebSocket: handler API, buffered variant with bounded queues
- extras: form/urlencoded/multipart parsing, reverse proxy

See [README.md](README.md) for the full Chinese documentation
(configuration table, benchmark results, tuning guide) and
[MIGRATION.md](MIGRATION.md) for the 1.x -> 2.0 migration.

## Quick start

```java
HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
server.createContext("/", exchange -> {
    byte[] body = "hello".getBytes();
    exchange.sendResponseHeaders(200, body.length);
    try (var os = exchange.getResponseBody()) { os.write(body); }
});
server.start();
```

Requires JDK 25. Set `-Dcom.sun.net.httpserver.HttpServerProvider=robaho.net.httpserver.DefaultHttpServerProvider`
or rely on the META-INF/services registration on the classpath.
