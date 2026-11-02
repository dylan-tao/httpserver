#!/bin/bash
# final6.sh <label> <classes-dir> [rounds] — N-round full scenario sweep.
# env: BENCH_PORT (default 18080), JAVA_HOME. requires wrk on PATH.
# usage: src/test/bench/final6.sh v30 target/classes 6
DIR="$(cd "$(dirname "$0")" && pwd)"
PORT="${BENCH_PORT:-18080}"
J="$JAVA_HOME/bin/java"; [ -x "$J" ] || J=java
CP="$(cd "$DIR/../../.." && pwd)/target/classes:$(cd "$DIR/../../.." && pwd)/target/test-classes"
LABEL=$1; CL=$2; ROUNDS=${3:-6}
OUT="$DIR/final6_$LABEL.log"
: > $OUT
pkill -f "BenchServer $PORT" 2>/dev/null; sleep 1
nohup $J -Drobaho.net.httpserver.http2OverNonSSL=true -Drobaho.net.httpserver.maxRequestBodySize=1048576 \
  -cp "$DIR/classes:$CL" robaho.net.httpserver.bench.BenchServer $PORT > "$DIR/server_f6_$LABEL.log" 2>&1 &
SPID=$!
sleep 3
# JIT warm
wrk -t2 -c8 -d10s http://127.0.0.1:$PORT/hello > /dev/null 2>&1
$J -cp $CP robaho.net.httpserver.http2.H2Bench $PORT seq 3000 > /dev/null 2>&1
for r in $(seq 1 $ROUNDS); do
  echo "=== ROUND $r ===" >> $OUT
  echo "hello: $(wrk -t4 -c64 -d30s http://127.0.0.1:$PORT/hello 2>&1 | grep 'Requests/sec' | grep -oE '[0-9.]+')" >> $OUT
  echo "bulk64k: $(wrk -t2 -c4 -d20s http://127.0.0.1:$PORT/bulk64k 2>&1 | grep 'Requests/sec' | grep -oE '[0-9.]+')" >> $OUT
  echo "bulkx16: $(wrk -t2 -c4 -d20s http://127.0.0.1:$PORT/bulkx16 2>&1 | grep 'Requests/sec' | grep -oE '[0-9.]+')" >> $OUT
  echo "fileMBps: $(wrk -t2 -c4 -d15s http://127.0.0.1:$PORT/file 2>&1 | grep 'Transfer/sec' | grep -oE '[0-9.]+')" >> $OUT
  echo "sendfileGBps: $(wrk -t2 -c4 -d15s http://127.0.0.1:$PORT/sendfile 2>&1 | grep 'Transfer/sec' | grep -oE '[0-9.]+' | head -1)" >> $OUT
  echo "upload: $($J -cp $CP robaho.net.httpserver.http2.H2Bench $PORT upload256k 50 2>&1 | tail -1 | grep -oE 'rps=[0-9]+' | cut -d= -f2)" >> $OUT
  echo "download: $($J -cp $CP robaho.net.httpserver.http2.H2Bench $PORT download1m 10 2>&1 | tail -1 | grep -oE 'rps=[0-9]+' | cut -d= -f2)" >> $OUT
  echo "seq: $($J -cp $CP robaho.net.httpserver.http2.H2Bench $PORT seq 5000 2>&1 | tail -1 | grep -oE 'rps=[0-9]+' | cut -d= -f2)" >> $OUT
  echo "mux8: $($J -cp $CP robaho.net.httpserver.http2.H2Bench $PORT mux8 5000 2>&1 | tail -1 | grep -oE 'rps=[0-9]+' | cut -d= -f2)" >> $OUT
done
kill $SPID 2>/dev/null
pkill -f "BenchServer $PORT" 2>/dev/null
echo "FINAL6 $LABEL DONE" >> $OUT
