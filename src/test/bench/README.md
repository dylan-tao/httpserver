# bench/ — load-test & boundary regression harness

The tooling used throughout the 2.0.0→3.0.0 performance campaign: every
number in the commit messages was produced (and re-producible) with these.

## components

| file | what it does |
|---|---|
| `../java/robaho/net/httpserver/bench/BenchServer.java` | the load-test server: h1+h2c on one port, endpoints `/hello` (128B), `/echo` (upload contract `ok <n>`), `/large64k` `/large1m` (fixed bodies), `/file` (10MB classic streaming), `/sendfile` (library zero-copy path via `extras.Sendfile`), `/bulk64k` `/bulkx16` (single-call big writes — the writev shape), `/drain` (handler never reads the body), `/ws` (websocket echo) |
| `../java/robaho/net/httpserver/bench/WsBench.java` | websocket echo client with byte-exact verification per frame (caught the double-masking bug), flow-control compliant |
| `../java/robaho/net/httpserver/bench/ChurnBench.java` | multi-threaded connection churn (connect→GET→close) — accept/TIME_WAIT ceiling analysis |
| `../java/robaho/net/httpserver/bench/DrainBench.java` | unread-body drain path load |
| `regress_boundary.py` | 46-case boundary suite (the repo's 38-case `src/test/python/edge_boundary_suite.py` plus the sendfile-specific cases: HEAD, keep-alive reuse, mixed zero-copy/classic, h2 fallback). single-round runner: `BENCH_PORT=18080 python3 regress_boundary.py <round-no>` (round no is the sampling seed) |
| `regressN.sh` | N-round boundary regression: per round, 5 sendfile cases + 4 sampled boundary cases + 10 parallel checksum-verified downloads + h2 upload/seq + health gate. exits nonzero on any failure |
| `final6.sh` | N-round full scenario sweep for A/B comparisons (h1 hello/bulk/file/sendfile + h2 upload/download/seq/mux8), mean±stdev from the N rounds |

## usage

```bash
mvn -q compile test-compile

# 1. start the bench server (any port)
J=$JAVA_HOME/bin/java
$J -Drobaho.net.httpserver.http2OverNonSSL=true \
   -Drobaho.net.httpserver.maxRequestBodySize=1048576 \
   -cp target/test-classes:target/classes robaho.net.httpserver.bench.BenchServer 18080

# 2. boundary regression (20/60 rounds)
src/test/bench/regressN.sh 60

# 3. websocket echo verification (any frame size)
$J -cp target/test-classes:target/classes robaho.net.httpserver.bench.WsBench 18080 65536 2000

# 4. throughput A/B: sweep against two builds (e.g. a git worktree at the base)
src/test/bench/final6.sh v30 target/classes 6
```

The regression's `/sendfile` checksum expects the deterministic 10MB payload
the BenchServer generates (SHA-256 `462a12a8…`, freshly generated into `java.io.tmpdir` on first start — delete a stale `benchserver-payload10m.bin` there if it predates this harness); a mismatch is reported as
`CHECKSUM-FAIL` per round.

Dependencies: `python3`, `curl`, `wrk` on PATH; `JAVA_HOME` set (JDK 25).
Nothing here runs under `mvn test` — the harness drives a LIVE server, which
surefire cannot host; run it per the commands above.
