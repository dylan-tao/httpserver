#!/bin/bash
# The h1 bulk-upload cross-check for the io_uring seam: 8 verified 8MB
# uploads per arm (curl --data-binary, server must answer "ok 8388608").
# This is the experiment that falsified the "generic read-path win"
# reading of the h2 upload256k 12-27x: classic h1 bulk upload is FAST
# (data always queued -> reads never park), so the h2 win is specific to
# the flow-control credit chain's per-quantum park/wake latency.
#
#   docker run --rm --security-opt seccomp=unconfined \
#     -v "$PWD":/workspace/httpserver \
#     -v /tmp/bench/temurin25-aarch64.tar.gz:/jdk-tarball.tar.gz:ro \
#     httpserver-iouring bash src/test/bench/iouring-h1-upload-ab.sh
set -euo pipefail
cd /workspace/httpserver
ensure-jdk.sh > /dev/null 2>&1 || ensure-jdk.sh
CP="target/test-classes:target/classes"
PORT=${BENCH_PORT:-18080}
dd if=/dev/zero of=/tmp/payload8m.bin bs=1M count=8 2>/dev/null
for arm in classic uring; do
  FLAGS=""
  [ $arm = uring ] && FLAGS="-Drobaho.net.httpserver.iouring=true"
  java $FLAGS -Drobaho.net.httpserver.http2OverNonSSL=true \
    --enable-native-access=ALL-UNNAMED -cp "$CP" \
    robaho.net.httpserver.bench.BenchServer "$PORT" > "/tmp/h1u-$arm.log" 2>&1 &
  PID=$!
  sleep 2
  curl -sf "http://127.0.0.1:$PORT/hello" > /dev/null || { echo "SANITY-FAIL $arm"; kill $PID; continue; }
  T0=$(date +%s%N)
  OK=0
  for i in 1 2 3 4 5 6 7 8; do
    R=$(curl -s --data-binary @/tmp/payload8m.bin "http://127.0.0.1:$PORT/echo")
    [ "$R" = "ok 8388608" ] && OK=$((OK+1)) || echo "VERIFY-FAIL $arm round $i: $R"
  done
  T1=$(date +%s%N)
  MS=$(( (T1 - T0) / 1000000 ))
  echo "RESULT h1upload $arm verified=$OK/8 wall=${MS}ms rate=$(( 64 * 1000 / (MS>0?MS:1) ))MBps"
  kill $PID; sleep 1
done
