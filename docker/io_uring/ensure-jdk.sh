#!/bin/bash
# expand the JDK tarball bind-mounted at /jdk-tarball.tar.gz into /opt/jdk
# (idempotent — skips when already expanded), expose java on PATH via
# /usr/local/bin, and print its version. the 4.0.0 io_uring image does not
# bake the 200MB JDK in: bind-mounting keeps the image at bookworm-slim +
# apt-tool size, and JDK patch bumps never require an image rebuild.
set -euo pipefail

JDK_TARBALL=/jdk-tarball.tar.gz
JDK_HOME=/opt/jdk

if [ ! -x "$JDK_HOME/bin/java" ]; then
  if [ ! -f "$JDK_TARBALL" ]; then
    echo "ensure-jdk: missing $JDK_TARBALL (bind-mount it:" \
         "-v <host-tarball>:/jdk-tarball.tar.gz:ro)" >&2
    exit 1
  fi
  echo "ensure-jdk: expanding $(basename "$JDK_TARBALL")"
  mkdir -p /opt
  # single top-level jdk-25.x.y/ directory in the tarball; symlink a stable name
  tar -xzf "$JDK_TARBALL" -C /opt
  ln -sfn /opt/jdk-25* "$JDK_HOME"
fi

JDK_HOME=$(cd "$JDK_HOME" && pwd -P)
# PATH exposure for every later shell in the container
ln -sf "$JDK_HOME/bin/java" /usr/local/bin/java
ln -sf "$JDK_HOME/bin/javac" /usr/local/bin/javac
# JAVA_TOOL_OPTIONS prints a "Picked up" banner line; the version is the next one
echo "ensure-jdk: $("$JDK_HOME/bin/java" -version 2>&1 | grep -v 'Picked up' | head -1)"
