# Migrating from 1.x to 2.0

2.0 is a breaking release: the JDK-compatible `com.sun.net.httpserver`
API surface is unchanged, but the robaho-specific classes were renamed
for clarity, dead API was removed, and a few default behaviors changed.
If you only use `com.sun.net.httpserver.*` (HttpServer, HttpsServer,
HttpExchange, filters, authenticators) your code compiles unchanged.

## Class renames

| 1.x | 2.0 |
|---|---|
| `robaho.net.httpserver.Code` | `robaho.net.httpserver.HttpStatus` |
| `robaho.net.httpserver.ActivityTimer` | `robaho.net.httpserver.CachedClock` |
| `robaho.net.httpserver.Attributes` | `robaho.net.httpserver.AttributeKeys` |
| `robaho.net.httpserver.UndefLengthOutputStream` | `robaho.net.httpserver.UndefinedLengthOutputStream` |
| `robaho.net.httpserver.extras.ContentEncoding` | `robaho.net.httpserver.extras.ContentTypeCharset` |
| `robaho.net.httpserver.http2.HTTP2Connection` | `robaho.net.httpserver.http2.Http2Connection` |
| `robaho.net.httpserver.http2.HTTP2Stream` | `robaho.net.httpserver.http2.Http2Stream` |
| `robaho.net.httpserver.http2.HTTP2ErrorCode` | `robaho.net.httpserver.http2.Http2ErrorCode` |
| `robaho.net.httpserver.http2.HTTP2Exception` | `robaho.net.httpserver.http2.Http2Exception` |
| `robaho.net.httpserver.http2.HTTP2Stats` | `robaho.net.httpserver.http2.Http2Stats` |
| `robaho.net.httpserver.http2.Utils` | `robaho.net.httpserver.http2.BinaryCodec` (methods renamed: `convertToBinary` → `writeInt32`, etc.) |
| `robaho.net.httpserver.http2.frame.FrameSerializer` | `robaho.net.httpserver.http2.frame.FrameParser` |
| `robaho.net.httpserver.http2.frame.GoawayFrame` | `robaho.net.httpserver.http2.frame.GoAwayFrame` |
| `robaho.net.httpserver.http2.frame.NotImplementedFrame` | `robaho.net.httpserver.http2.frame.UnknownFrame` |
| `robaho.net.httpserver.http2.hpack.HTTP2HeaderField` | `robaho.net.httpserver.http2.hpack.Http2HeaderField` |
| `robaho.net.httpserver.http2.hpack.HeaderFields` | `robaho.net.httpserver.http2.hpack.HeaderFieldList` |
| `robaho.net.httpserver.websockets.Util` | `robaho.net.httpserver.websockets.WebSocketHandshake` |
| `robaho.net.httpserver.websockets.State` | `robaho.net.httpserver.websockets.WebSocketLifecycle` |

## Removed API

| Removed | Replacement |
|---|---|
| `robaho.net.httpserver.Utils` | inline the two helpers; nothing in the public API depended on it |
| `robaho.net.httpserver.DelegatingHttpExchange` | wrap `HttpExchange` directly |
| `robaho.net.httpserver.OpenAddressIntMap` | `robaho.net.httpserver.OpenAddressMap` |
| `robaho.net.httpserver.UndefLengthInputStream` | `UndefinedLengthOutputStream` / chunked responses |

## Moved to the internal package

The buffer/data-structure implementation classes moved to
`robaho.net.httpserver.internal`, which is **not exported** by the module
(they were never documented API, but 1.x exposed them in an exported
package):

| 1.x | 2.0 |
|---|---|
| `robaho.net.httpserver.NoSyncBufferedInputStream` | `robaho.net.httpserver.internal.NoSyncBufferedInputStream` |
| `robaho.net.httpserver.NoSyncBufferedOutputStream` | `robaho.net.httpserver.internal.NoSyncBufferedOutputStream` |
| `robaho.net.httpserver.OptimizedHeaders` | `robaho.net.httpserver.internal.OptimizedHeaders` |
| `robaho.net.httpserver.OpenAddressMap` | `robaho.net.httpserver.internal.OpenAddressMap` |
| `robaho.net.httpserver.BloomSet` | `robaho.net.httpserver.internal.BloomSet` |

`HttpConnection` and `Http2ExchangeImpl` remain in
`robaho.net.httpserver` for coupling reasons; treat them as internal -
they are not covered by the compatibility promise.

## Behavior changes

- **JDK 25 required.** The build targets `--release 25`; the Maven
  enforcer fails the build early on older JDKs.
- **h2 responses now include a `Date` header** (parity with h1). Tests
  that assert an exact header set must account for it.
- **Stricter request validation.** Invalid header names (non-TCHAR),
  oversized request lines, header counts, and total header blocks are
  rejected with 400/431. Pathological obs-fold lines that 1.x accepted
  are now rejected.
- **Request body cap is opt-in.** `robaho.net.httpserver.maxRequestBodySize`
  (default unlimited). When set: a declared over-cap Content-Length gets
  413 (h1 and h2); an over-cap chunked/h2-DATA body kills the exchange
  (h1) or RSTs the stream with ENHANCE_YOUR_CALM (h2).
- **`Expect: 100-continue` is supported on h1 only.** h2 does not emit
  1xx informational HEADERS; clients that wait for a 100 over h2 will
  fall back to their own timeout.
- **Pipelined responses are coalesced** (flush deferred until the input
  drains). Handlers that relied on an immediate per-response flush still
  work - a pre-read flush hook guarantees delivery before the next
  blocking read.

## Configuration notes

- The connection limit property is **`jdk.httpserver.maxConnections`**
  (JDK-compatible name), not `robaho.net.httpserver.maxConnections`.
- `robaho.net.httpserver.clockTick` default is **10000ms** (10s), not
  100ms.
- New in 2.0: `maxReqHeaderSize`, `maxRequestBodySize`,
  `http2PingIntervalMillis`, `http2Nodelay`, `http2MaxHeaderBlockSize`,
  `maxWebSocketFrameSize`, `maxWebSocketMessageSize`,
  `maxBufferedWebSocketBytes`, `sslHandshakeTimeout`. See README.md for
  the complete table.

## Build

Gradle → Maven. `mvn package` builds, tests, sources and javadoc jars.
`mvn -Prelease deploy` publishes to Maven Central.
