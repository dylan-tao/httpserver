#!/bin/bash
# The h2-scenario library A/B: the claim field for the 4.0.0 ring transport.
# multiplexing concentrates many small concurrent reads/writes onto ONE
# connection — the shape where batched ring submission should beat per-op
# poller wakeups. same BenchServer binary, classic channel path vs
# -Diouring=true (ring emulation), the repo's own flow-control-compliant
# H2Bench client (mux8/seq/upload/download from final6.sh).
#
#   docker run --rm --security-opt seccomp=unconfined \
#     -v "$PWD":/workspace/httpserver \
#     -v /tmp/bench/temurin25-aarch64.tar.gz:/jdk-tarball.tar.gz:ro \
#     httpserver-iouring bash src/test/bench/iouring-lib-h2-ab.sh
set -euo pipefail
cd /workspace/httpserver

ensure-jdk.sh > /dev/null 2>&1 || ensure-jdk.sh

CP="target/test-classes:target/classes"
PORT=${BENCH_PORT:-18080}

run_arm() { # $1 = label, $2 = flags
  local label=$1 flags=$2
  java $flags -Drobaho.net.httpserver.http2OverNonSSL=true \
    -Drobaho.net.httpserver.maxRequestBodySize=1048576 \
    --enable-native-access=ALL-UNNAMED -cp "$CP" \
    robaho.net.httpserver.bench.BenchServer "$PORT" > "/tmp/h2-$label.log" 2>&1 &
  local pid=$!
  sleep 2
  # functional gate first: h1 AND h2c must both answer, else the arm is invalid
  if ! curl -sf "http://127.0.0.1:$PORT/hello" > /dev/null; then
    echo "SANITY-FAIL $label (h1)"; tail -6 "/tmp/h2-$label.log"; kill $pid 2>/dev/null || true; exit 1
  fi
  if ! curl -sf --http2-prior-knowledge "http://127.0.0.1:$PORT/hello" > /dev/null; then
    echo "SANITY-FAIL $label (h2c)"; tail -6 "/tmp/h2-$label.log"; kill $pid 2>/dev/null || true; exit 1
  fi
  echo "-- $label arm up (h1 + h2c sane) --"
  for round in 1 2 3; do
    m=$(java -Drobaho.net.httpserver.http2OverNonSSL=true -cp "$CP" \
      robaho.net.httpserver.http2.H2Bench "$PORT" mux8 5000 2>&1 | tail -1 | grep -oE 'rps=[0-9]+' | cut -d= -f2)
    s=$(java -Drobaho.net.httpserver.http2OverNonSSL=true -cp "$CP" \
      robaho.net.httpserver.http2.H2Bench "$PORT" seq 5000 2>&1 | tail -1 | grep -oE 'rps=[0-9]+' | cut -d= -f2)
    u=$(java -Drobaho.net.httpserver.http2OverNonSSL=true -cp "$CP" \
      robaho.net.httpserver.http2.H2Bench "$PORT" upload256k 50 2>&1 | tail -1 | grep -oE 'rps=[0-9]+' | cut -d= -f2)
    d=$(java -Drobaho.net.httpserver.http2OverNonSSL=true -cp "$CP" \
      robaho.net.httpserver.http2.H2Bench "$PORT" download1m 10 2>&1 | tail -1 | grep -oE 'rps=[0-9]+' | cut -d= -f2)
    echo "RESULT $label round=$round mux8=$m seq=$s upload=$u download=$d"
  done
  cpu=$(ps -p $pid -o %cpu= | tail -1)
  echo "RESULT $label cpu=$cpu (cumulative over the scenario rounds)"
  kill $pid 2>/dev/null || true
  sleep 1
}

echo "== classic arm (channel + JDK pollers) =="
run_arm classic ""
echo
echo "== uring arm (ring emulation) =="
run_arm uring "-Drobaho.net.httpserver.iouring=true"
echo
echo "h2 A/B complete."
