# io_uring verification image — the 4.0.0 FFM transport's Linux environment.
#
# Built ON macOS (Docker Desktop VM: linux/arm64, kernel 6.12.76-linuxkit —
# full io_uring support) from the already-local arm64v8/debian:bookworm-slim.
# No docker-Hub JDK image is needed (Hub pulls stall on this network); the
# Temurin 25 aarch64 tarball downloads from github's CDN and is bind-mounted
# at run time, expanded once into /opt/jdk by ensure-jdk.sh.
#
# build (context is this directory):
#   docker build -t httpserver-iouring docker/io_uring/
#
# run the probe (the io_uring syscalls are EPERM-blocked under docker's
# DEFAULT seccomp profile — the flag below is REQUIRED, not optional):
#   docker run --rm --security-opt seccomp=unconfined \
#     -v "$PWD":/workspace/httpserver \
#     -v /tmp/bench/temurin25-aarch64.tar.gz:/jdk-tarball.tar.gz:ro \
#     httpserver-iouring
FROM arm64v8/debian:bookworm-slim

# verification toolchain: wrk (the campaign's load tool), h2load (h2 load),
# curl (boundary scripts), procps (ps/top for busy/idle CPU), and the
# tarball expander's needs. one layer, lists cleaned.
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      wrk nghttp2-client curl ca-certificates procps tar gzip \
 && rm -rf /var/lib/apt/lists/*

COPY ensure-jdk.sh /usr/local/bin/ensure-jdk.sh
RUN chmod +x /usr/local/bin/ensure-jdk.sh

ENV LANG=C.UTF-8
# the FFM transport is the point of this image: enable native access for
# every JVM started inside it instead of remembering per-command flags.
# (side effect: each java run prints one "Picked up JAVA_TOOL_OPTIONS" line)
ENV JAVA_TOOL_OPTIONS="--enable-native-access=ALL-UNNAMED"

# expected mounts: the repo (probe + built classes) at /workspace/httpserver,
# the JDK tarball at /jdk-tarball.tar.gz. see README.md.
WORKDIR /workspace/httpserver
CMD ["/bin/bash", "-c", "ensure-jdk.sh && java docker/io_uring/IouringProbe.java"]
