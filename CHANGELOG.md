# Changelog

All notable changes to this project are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/).

## [2.0.0] - unreleased

A JDK 25-focused major release: virtual-thread hardening, an HTTP/2
overhaul (8 protocol defects fixed), large allocation/GC reductions, and
an API cleanup (see MIGRATION.md for the 1.x → 2.0 class renames).

### Added
- HTTP/2 over TLS via ALPN negotiation (`robaho.net.httpserver.http2OverSSL`),
  and h2c prior-knowledge support (`http2OverNonSSL`).
- Opt-in request body cap `maxRequestBodySize`, enforced on four paths
  (h1/h2 × declared/incremental) with per-path semantics (413 response,
  connection kill, or RST ENHANCE_YOUR_CALM).
- Request validation hardening: header name TCHAR validation, obs-fold
  handling, request-line/header-line caps, and a total header-block budget
  (`maxReqHeaderSize`).
- Configurable h2 keepalive ping interval (`http2PingIntervalMillis`,
  `<= 0` disables).
- `/__debug` now returns the connection/stream dump as the response body.
- CI (GitHub Actions, JDK 25), multi-stage Dockerfile (JDK 25), Maven
  enforcer (requireJavaVersion 25), THIRD-PARTY-NOTICES.
- Test suite grown to 189 tests, including: h2-over-TLS ALPN (negotiation,
  fallback, multiplexing), h1 chunked trailer coverage, four-path request
  body cap assertions, full-size DATA frame pool round-trip, and the
  pre-existing h2 compliance/regression suites.

### Changed
- **Build moved from Gradle to Maven**; target is now JDK 25
  (`--release 25`). ScopedValue (JEP 506) and compact object headers
  (JEP 450, exercised in the test JVM) are available.
- 17 classes renamed for intent-revealing names, dead public API removed
  (see MIGRATION.md for the complete mapping).
- h2 responses now carry a `Date` header (h1/h2 parity, RFC 9110 6.5.1).
- `jdk.httpserver.maxConnections` is the connection-limit property (JDK
  compatible name); `clockTick` default is 10000ms.
- Pipelined HTTP/1.1 responses are coalesced: the flush is deferred until
  the request input drains (5× pipelined throughput, with a pre-read
  flush hook as the safety net).
- Protocol-specific TCP_NODELAY: h1 on by default, h2 off by default
  (`nodelay` / `http2Nodelay`).

### Fixed
- 8 HTTP/2 protocol defects: stream-window flow-control deadlock,
  RST_STREAM escalating to connection teardown, connection-window
  overflow hot spin, HPACK dynamic-table desync on rejected streams,
  CONTINUATION/trailer races, window-credit loss on padded uploads,
  response write split at close(), and the connection wedge when a
  trailer block completes after stream close.
- Request smuggling vectors: the exception path no longer skips the
  request-body EOF guard; `InputStream.skip` goes through HTTP framing.
- WebSocket: double-close deadlock, bounded send queue (frame count AND
  byte cap), quiet close on RST.
- h2 upload flow control: window replenishment happens before a
  whole-frame client can stall (dead-zone fix).

### Performance
- Per-request allocation reduced 18.9KB → 2.7KB; GC pauses per million
  requests 150 → 32 (below JDK 25's own HttpServer at 51).
- Full-size DATA frame bodies are pooled (`DataFrameBodyPool`): h2 upload
  `byte[]` allocation -85%.
- Idle connections shrink their buffers between requests (input, output,
  response-head scratch, h2 stream pool): ~16.8KB saved per idle
  connection vs 1.x.
- Status-line cache without boxing, single-byte write scratch, reusable
  header-assembly scratch, bulk line reader for the header parser.
- Measured vs JDK 25 HttpServer: CPU -23%, sequential +11%, pipelined
  5×, header parsing 2-3×; idle memory parity.

## [1.x]
See the git history for the 1.x maintenance line.
