#!/bin/bash
# The library-level io_uring A/B — the whole 4.0.0 seam question: the SAME
# BenchServer binary, full h1 stack (parse/route/Exchange/buffers), classic
# channel path (JDK Read/Write poller machinery in the baseline cost) vs
# -Diouring=true (FFM listener + ring blocking emulation).
#
#   docker run --rm --security-opt seccomp=unconfined \
#     -v "$PWD":/workspace/httpserver \
#     -v /tmp/bench/temurin25-aarch64.tar.gz:/jdk-tarball.tar.gz:ro \
#     httpserver-iouring bash src/test/bench/iouring-lib-ab.sh
#
# each arm: wrk t4/c64 15s x3 on /hello, server CPU sampled after.
set -euo pipefail
cd /workspace/httpserver

ensure-jdk.sh > /dev/null 2>&1 || ensure-jdk.sh

CP="target/test-classes:target/classes"
PORT=${BENCH_PORT:-18080}

run_arm() { # $1 = label, $2 = extra java flags ("" or -D...)
  local label=$1 flags=$2
  java $flags -Drobaho.net.httpserver.http2OverNonSSL=true \
    -Drobaho.net.httpserver.maxRequestBodySize=1048576 \
    --enable-native-access=ALL-UNNAMED -cp "$CP" \
    robaho.net.httpserver.bench.BenchServer "$PORT" > "/tmp/lib-$label.log" 2>&1 &
  local pid=$!
  sleep 2
  if ! grep -q "listening\|started\|BenchServer" "/tmp/lib-$label.log"; then
    echo "ARM-FAIL $label:"; tail -8 "/tmp/lib-$label.log"; kill $pid 2>/dev/null || true; exit 1
  fi
  # sanity: one request must answer before benchmarking
  if ! curl -sf "http://127.0.0.1:$PORT/hello" > /dev/null; then
    echo "SANITY-FAIL $label (curl /hello):"; tail -8 "/tmp/lib-$label.log"; kill $pid 2>/dev/null || true; exit 1
  fi
  local r1 r2 r3 cpu
  r1=$(wrk -t4 -c64 -d15s "http://127.0.0.1:$PORT/hello" 2>&1 | awk '/Requests\/sec/{print $2}')
  r2=$(wrk -t4 -c64 -d15s "http://127.0.0.1:$PORT/hello" 2>&1 | awk '/Requests\/sec/{print $2}')
  r3=$(wrk -t4 -c64 -d15s "http://127.0.0.1:$PORT/hello" 2>&1 | awk '/Requests\/sec/{print $2}')
  cpu=$(ps -p $pid -o %cpu= | tail -1)
  echo "RESULT $label $r1 $r2 $r3 cpu=$cpu"
  kill $pid 2>/dev/null || true
  sleep 1
}

echo "== classic arm (channel path + JDK pollers) =="
run_arm classic ""
echo
echo "== uring arm (FFM listener + ring emulation) =="
run_arm uring "-Drobaho.net.httpserver.iouring=true"
echo
echo "A/B complete."
