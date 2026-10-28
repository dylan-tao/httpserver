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
- Test suite grown to 214 tests, including: h2-over-TLS ALPN (negotiation,
  fallback, multiplexing), h1 chunked trailer coverage, four-path request
  body cap assertions, full-size DATA frame pool round-trip, and the
  pre-existing h2 compliance/regression suites.

### Changed
- **`ProxyHandler.authorizeConnect` now DENIES CONNECT by default** (403);
  1.x allowed every target. Override `authorizeConnect` to restore
  per-target policy.
- url-encoded form parsing enforces a **10MB default body cap**
  (`max_form_bytes`, configurable); query-string keys are percent-decoded
  before grouping; malformed escapes throw the declared
  `UnsupportedEncodingException` instead of an unchecked IAE.
- test_mains (the standalone regression programs) are **compile-only** in
  the Maven build - main's gradle pipeline executed them at publish time;
  run individual mains with
  `java -cp target/classes:target/test-classes <ClassName>`.
- **Build moved from Gradle to Maven**; target is now JDK 25
  (`--release 25`). ScopedValue (JEP 506) and compact object headers
  (JEP 450, exercised in the test JVM) are available.
- 18 classes renamed for intent-revealing names (ContentEncoding -> ContentTypeCharset, Attributes -> AttributeKeys, Code -> HttpStatus, ActivityTimer -> CachedClock, ...) and dead public API removed
  (see MIGRATION.md for the complete mapping).
- API surface narrowed: buffer/data-structure internals moved to the
  non-exported `internal` packages, the HTTP/2 engine moved to
  `http2.internal` (the exported http2 package keeps only
  Http2Stats/Http2ErrorCode/Http2Exception), and the implementation
  classes HttpConnection/Http2ExchangeImpl/HttpServerImpl/HttpsServerImpl
  became package-private. every exported package now carries a
  package-info declaring what is API.
- javadoc completed for the exported API surface (ServerConfig getters
  document their -D property, default and semantics); doclint tightened
  to missing,syntax,reference to keep it that way.
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
- The h2 keepalive ping task is scheduled lazily (attached on the first
  h2 connection, detached on the last disconnect) and its timer thread is
  created only on first h2 use: idle servers stop waking once a second to
  ping an empty registry (~6ms/min measured idle CPU).
- Request headers are materialized lazily (LazyRequestHeaders): the
  per-request copy into a JDK Headers (HashMap table + node + ArrayList
  per header, ~28% of the benchmark allocation profile) is skipped for
  handlers that never read request headers; a view handed out but
  unread is force-copied when the parse table is cleared for the next
  request, so a reference retained past response completion still sees
  its own request's headers. Views never handed to the handler (never
  exposed via getRequestHeaders) cannot be retained, so even the
  clear-time snapshot is skipped for them.
- h1 response headers (rspHdrs) are an OptimizedHeaders too: the
  per-response HashMap table/Node/LinkedList allocations of the JDK
  Headers are gone (h2 already used OptimizedHeaders). the JDK-derived
  HeadersTest contract is preserved - it reflectively swaps the parent
  Headers' map field with impossible states (null keys, [null] values),
  which only --add-opens test JVMs permit; optimized send paths merge
  any swapped entries out (behind a static final reflection check that
  production JIT compiles to nothing) and reproduce the JDK's loud
  NPE/validation failures. get() on a single-valued header returns a
  live view (mutating it writes through) matching the JDK's mutable
  LinkedList contract. Measured 10-header requests: 3.63KB -> 2.07KB
  allocated per request (-43% across the header-path series), 33.6k
  rps with p99 7.99ms.
- The request parser feeds the header table through addNormalized: the
  parser builds keys in normalized shape while validating TCHAR, so the
  redundant per-header normalize scan is skipped (strictly less work;
  +0.3% rps measured, within run noise).
- Response header names are pre-encoded per connection: a bounded
  (64-entry) name->bytes table turns writeHeaderLine's per-char
  writeAscii loop into one arraycopy for every name a keep-alive
  connection re-emits (same family as the status-line/digit/Date
  caches). byte-identical output (writeAscii is a pure function);
  benefit scales with response header count (~0.3-0.5% at realistic
  6-8 header responses; h2 needs nothing - HPACK already interns names).
- Multi-valued response headers (Set-Cookie et al.) are written with
  indexed loops instead of enhanced-for: the validation, counting and
  write passes each used to allocate an Iterator per multi-valued
  header per response. (the ArrayList$Itr entries in the earlier
  profiles turned out to be the JFR sampler's own periodic-event
  allocations, not the server's - robaho's write path now shows zero
  iterator allocations under a multi-value-header load.)
- README gained a measured deployment-recipes section: compact object
  headers, G1PeriodicGCInterval idle-uncommit (validated live: periodic
  collection fires ~30s into idle, committed shrinks to the -Xms floor),
  generational ZGC (throughput parity -0.6%, zero measurable pauses),
  AOT cache cold-start recipe (~150ms -> ~111ms, -25%), and the
  platform-thread executor option for h1-heavy low-connection workloads
  (measured -32% server CPU at equal throughput).
- OpenAddressMap is backed by flat parallel key/value arrays (HPPC
  style): no per-entry object at all - the four consumers
  (OptimizedHeaders, the per-connection parse table, HeaderFieldList,
  BloomSet) dropped every OpenAddressMap Entry allocation, and value
  consumers scan a contiguous values region. Tombstone semantics are
  unchanged. Measured combined with the lazy view: 10-header requests
  3.63KB -> 3.34KB allocated per request, and the gain grows with
  header count.
- CachedClock has no background timer anymore: the Date header snapshot
  is refreshed on demand from the response path (one format per second at
  most under load, zero while idle), and the per-second staleness of the
  old 750ms tick (~1.75s worst case) improves to ~1s.
- The connection-cleaner task is scheduled lazily too (attached on the
  first accepted connection, detached on the last close, timer thread
  created only on first use): a fully idle server now has zero
  self-initiated periodic wakeups. Idle/timeout bookkeeping
  (lastActivityTime, drainingAt) reads System.currentTimeMillis()
  directly - it must advance while nothing is being served, and comparing
  against a demand-driven cache would misjudge connection idle age.
- Measured vs JDK 25 HttpServer: CPU -23%, sequential +11%, pipelined
  5×, header parsing 2-3×; idle memory parity.

## [1.x]
See the git history for the 1.x maintenance line.
