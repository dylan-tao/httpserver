#!/bin/bash
# regressN.sh <rounds> — boundary regression against the live BenchServer.
# env: BENCH_PORT (default 18080), JAVA_HOME (JDK 25). requires python3, curl.
# starts nothing: bring the server up first (see README.md in this dir).
# if java.io.tmpdir holds a stale BenchServer payload from older harness
# versions, delete it: the checksum matches the CURRENT generator
# (160 x 64KB of 'x', SHA-256 462a12a8...).
set -o pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
PORT="${BENCH_PORT:-18080}"
J="$JAVA_HOME/bin/java"; [ -x "$J" ] || J=java
CP="$(cd "$DIR/../../.." && pwd)/target/classes:$(cd "$DIR/../../.." && pwd)/target/test-classes"
LOG="$DIR/regressN.log"
EXPECT="462a12a876c0364e4f1f3d12ed33dcae125f1198010ff78d8f4c3f4de0412d49"
N=${1:-20}
: > $LOG
overall=0
for i in $(seq 1 $N); do
  rfail=0
  if ! BENCH_PORT=$PORT python3 -I "$DIR/regress_boundary.py" $i >> $LOG 2>&1; then rfail=1; fi
  ckfail=/tmp/regress_ck_$i
  rm -f $ckfail
  for j in 1 2 3 4 5 6 7 8 9 10; do
    ( s=$(curl -s -m 30 "http://127.0.0.1:$PORT/sendfile" | shasum -a 256 | cut -d " " -f1)
      [ "$s" != "$EXPECT" ] && echo "R$i CHECKSUM-FAIL" >> $LOG && touch $ckfail ) &
  done
  wait
  [ -f $ckfail ] && rfail=1
  rm -f $ckfail
  if ! $J -cp $CP robaho.net.httpserver.http2.H2Bench $PORT upload256k 10 2>&1 | tail -1 | grep -q 'RESULT' ; then
    echo "R$i UPLOAD-FAIL" >> $LOG; rfail=1
  else
    $J -cp $CP robaho.net.httpserver.http2.H2Bench $PORT upload256k 10 2>&1 | tail -1 >> $LOG
  fi
  if ! $J -cp $CP robaho.net.httpserver.http2.H2Bench $PORT seq 500 2>&1 | tail -1 | grep -q 'RESULT'; then
    echo "R$i SEQ-FAIL" >> $LOG; rfail=1
  else
    $J -cp $CP robaho.net.httpserver.http2.H2Bench $PORT seq 500 2>&1 | tail -1 >> $LOG
  fi
  code=$(curl -s -m 5 -o /dev/null -w "%{http_code}" "http://127.0.0.1:$PORT/hello")
  [ "$code" != "200" ] && { echo "R$i HEALTH-FAIL code=$code" >> $LOG; rfail=1; }
  if [ $rfail -eq 0 ]; then echo "ROUND $i PASS" >> $LOG; else echo "ROUND $i FAIL" >> $LOG; overall=1; fi
done
echo "REGRESSION_DONE overall=$overall" >> $LOG
exit $overall
