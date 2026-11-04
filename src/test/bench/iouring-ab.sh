#!/bin/bash
# io_uring syscall-floor A/B — run INSIDE the httpserver-iouring container
# (unconfined), same netns, wrk from the image:
#
#   docker run --rm --security-opt seccomp=unconfined \
#     -v "$PWD":/workspace/httpserver \
#     -v /tmp/bench/temurin25-aarch64.tar.gz:/jdk-tarball.tar.gz:ro \
#     httpserver-iouring bash src/test/bench/iouring-ab.sh
#
# arms (identical canned 128B response, one response per read - the
# PlainNioServer shape whose macOS floor measured 36k rps):
#   classic: java.net blocking sockets, thread per connection
#   uring:   FFM sockets + ONE carrier thread through IoUringRing
# the uring arm exits 3 if the ring is unavailable, so a failed stage-1
# probe cannot be mistaken for a benchmark result. each arm: 3 wrk runs
# (t4/c64/15s), all three reported.
set -euo pipefail
cd /workspace/httpserver

ensure-jdk.sh # expand the bind-mounted JDK and expose java on PATH

CP="target/test-classes:target/classes"
PORT=${BENCH_PORT:-18080}
DUR=${BENCH_DUR:-15s}

run_arm() { # $1 = classic|uring
  local arm=$1
  java --enable-native-access=ALL-UNNAMED -cp "$CP" \
    robaho.net.httpserver.bench.IouringBench "$arm" "$PORT" > "/tmp/$arm.log" 2>&1 &
  local pid=$!
  sleep 2
  if ! grep -q "listening" "/tmp/$arm.log"; then
    echo "ARM-FAIL $arm:"; cat "/tmp/$arm.log"
    kill $pid 2>/dev/null || true
    exit 1
  fi
  echo "-- $arm arm up (log: /tmp/$arm.log) --"
  local r1 r2 r3
  r1=$(wrk -t4 -c64 -d"$DUR" "http://127.0.0.1:$PORT/" 2>&1 | awk '/Requests\/sec/{print $2}')
  r2=$(wrk -t4 -c64 -d"$DUR" "http://127.0.0.1:$PORT/" 2>&1 | awk '/Requests\/sec/{print $2}')
  r3=$(wrk -t4 -c64 -d"$DUR" "http://127.0.0.1:$PORT/" 2>&1 | awk '/Requests\/sec/{print $2}')
  echo "RESULT $arm $r1 $r2 $r3 (rps x3)"
  kill $pid 2>/dev/null || true
  sleep 1
}

echo "== classic arm =="
run_arm classic
echo
echo "== uring arm (includes the ring round-trip gate) =="
run_arm uring
echo
echo "A/B complete. compare RESULT lines above."
