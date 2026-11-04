# docker/io_uring — the 4.0.0 FFM io_uring landing & verification environment

The 4.0.0 branch lands an io_uring-backed transport behind the existing
blocking-IO seams (zero JNI, zero liburing — raw syscalls via
`java.lang.foreign`: libc `syscall(2)/mmap/munmap/close`). macOS has no
io_uring, so on this Mac every io_uring step runs inside this Linux
container: Docker Desktop's VM is `linux/arm64` on kernel
`6.12.76-linuxkit` — full io_uring support (5.1 baseline, multishot recv
5.19+, everything the backend needs).

## image

| piece | what |
|---|---|
| base | `arm64v8/debian:bookworm-slim` (already on the Mac) |
| JDK | Temurin 25 (aarch64) tarball, bind-mounted, expanded on first run by `ensure-jdk.sh` |
| tools | `h2load` (nghttp2-client), `curl`, `procps` — the load/verify set |
| probe | `IouringProbe.java` — pure-FFM ring round trip (see below) |

The image does not bake in the 200MB JDK tarball: it is bind-mounted at
run time, so JDK patch bumps don't require an image rebuild.

## build

```bash
# from the repo root, with the JDK tarball downloaded to /tmp/bench:
docker build -t httpserver-iouring -f docker/io_uring/Dockerfile .
```

The build context is the repo (for the probe + scripts). apt runs once
and the layer is cached.

## the seccomp rule (the one non-obvious constraint)

Docker's **default seccomp profile denies `io_uring_setup/enter/register`
with EPERM**. This is not optional knowledge: any io_uring run must add

```bash
--security-opt seccomp=unconfined
```

(the alternative is a custom profile that allowlists those three
syscalls). The probe prints a specific diagnostic when it detects
`EPERM` so a future session can't lose an hour to this again.

## probe (verifies the environment end to end)

```bash
docker run --rm \
  -v /tmp/bench/temurin25-aarch64.tar.gz:/jdk-tarball.tar.gz:ro \
  httpserver-iouring
#        ^ default profile: expected to FAIL with EPERM at stage1

docker run --rm --security-opt seccomp=unconfined \
  -v /tmp/bench/temurin25-aarch64.tar.gz:/jdk-tarball.tar.gz:ro \
  httpserver-iouring
#        ^ expected: PROBE-OK (setup -> mmap rings -> NOP submit -> CQE reaped)
```

Stages the probe checks, in order (exit codes 4/5/6/7/8 = the stage
number that failed):

1. `io_uring_setup(4)` reachable — kernel >= 5.1 AND seccomp allows it
2. SQ ring / CQ ring / SQE array mmap'd via FFM (`MemorySegment` views)
3. one `IORING_OP_NOP` submitted, one CQE reaped with matching user_data,
   then teardown (3x munmap + close)

It uses only `Linker.defaultLookup()` symbols — the exact binding shape
the 4.0.0 transport will use, so the probe doubles as the first brick of
the implementation.

## running the library inside the container

```bash
docker run --rm --security-opt seccomp=unconfined -it \
  -v /tmp/bench/temurin25-aarch64.tar.gz:/jdk-tarball.tar.gz:ro \
  -v "$PWD/target:/workspace/httpserver/target" \
  -p 18080:18080 \
  httpserver-iouring bash
# then: java -cp target/test-classes:target/classes robaho.net.httpserver.bench.BenchServer 18080
#       (host-built bytecode is portable; h2load/wrk from the host or inside)
```

The host maven build stays the build path (`mvn verify` under
`/tmp/bench/apache-maven-3.9.16` — the stock PATH maven 3.6.3 is too old
for the SpotBugs plugin 4.10.5.0, which requires Maven >= 3.8.9);
containers only run the result.

## portability note

io_uring syscall numbers (425/426/427) are the asm-generic ones —
identical on aarch64 and x86-64 — and every struct used here is u32/u64
with natural alignment, so the same probe and the future transport work
unchanged on amd64 Linux production hosts; only the JDK tarball differs
per arch.

## measured workload characterization (the landing decision)

The seam is landed as **opt-in** (`-Drobaho.net.httpserver.iouring=true`).
These are the library-level A/B numbers that decided it (same container,
same netns; `src/test/bench/iouring-lib-ab.sh` + `iouring-lib-h2-ab.sh`):

| workload | classic (channel+pollers) | uring (ring) | verdict |
|---|---|---|---|
| h1 /hello (wrk t4/c64) | ~380k rps @ 413% CPU | ~286k rps @ 566% | classic |
| h2 mux8 / seq (H2Bench) | ~18–37k / ~8.7k | ~9–18k / ~4.5k | classic (~2x) |
| h2 upload256k (H2Bench, verified) | **14 rps (3.5MB/s)** | **170–383 rps (98MB/s)** | **uring, 12–27x** |
| h1 bulk upload (curl 8x8MB) | 673MB/s | 174MB/s | classic |
| h2 download1m | ~300 rps | ~103 rps | classic (sendfile/writev dormant on the ring) |

The h2-upload win is a container-environment finding, not a generic
read-path win: on the macOS host, classic h2 upload is ALREADY fast
(621–779 rps). In the container, the flow-control credit chain pays a
JDK-poller wake (~1.4ms) per 16KB quantum on the channel path, which
adds up to 22ms per 256KB upload; the ring's carrier dispatch wakes in
~0.16ms — host-class latency. Conversely the per-op park/unpark round
trip COSTS on small-frame multiplexing and bulk streaming, and the
writev/sendfile fast paths stay dormant (getChannel() is null), which
is why download regresses.

Cost note: SQPOLL shards spin a kernel thread each (~100% CPU per shard
while awake). The benchmark shape sets a 100s sq_thread idle — for
latency-tolerant deployments shorten the idle; sleeping sq threads are
doorbelled by submit's NEED_WAKEUP check.

Bottom line: enable the transport for **h2 upload-heavy serving inside
virtualized/containerized Linux**, keep the channel path for
small-frame multiplexing and bulk streaming/download shapes.

