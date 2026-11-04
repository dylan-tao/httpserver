package robaho.net.httpserver.iouring;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.ByteOrder;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * The blocking-IO emulation over sharded rings — the layer HttpConnection
 * will consume. One virtual thread per connection keeps the library's
 * programming model; each blocking read/write posts an SQE on its shard's
 * ring and parks until that shard's carrier reaps the completion and
 * unparks it.
 * Sharding: {@code shards} rings (a power of two), each with its own
 * carrier thread and its own submission mutex; a connection maps to a shard
 * by {@code fd & (shards-1)} so the in-flight ops of N connections spread
 * across N independent SQ/critical sections instead of serializing on one.
 * The first shard reuses {@link IoUringRing#shared()} so a single-shard
 * instance costs nothing extra.
 * Waiters: one Waiter per stream (per connection, per direction), registered
 * once under a stable id (stream ordinal {@code << 1} | kind) and reused for
 * every op on that stream — blocking semantics allow at most one in-flight
 * op per direction, so no generation bookkeeping; the res slot is reset
 * BEFORE the next SQE is published (the previous op's CQE was consumed
 * before its blockingOp returned, so no stale completion can arrive).
 * FFM socket plumbing (socket/bind/listen/accept4/setsockopt) lives here too
 * so the whole 4.0.0 native surface is one package.
 */
public final class IoUringSockets {

  private static final Linker L = Linker.nativeLinker();

  private static final ValueLayout.OfInt U32 = ValueLayout.JAVA_INT.withOrder(ByteOrder.nativeOrder());

  private static final MethodHandle SOCKET, BIND, LISTEN, ACCEPT4, SETSOCKOPT, CLOSE;

  static {
    try {
      SymbolLookup libc = L.defaultLookup();
      SOCKET = L.downcallHandle(libc.find("socket").orElseThrow(), FunctionDescriptor.of(U32, U32, U32, U32));
      BIND = L.downcallHandle(libc.find("bind").orElseThrow(), FunctionDescriptor.of(U32, U32, ValueLayout.ADDRESS, U32));
      LISTEN = L.downcallHandle(libc.find("listen").orElseThrow(), FunctionDescriptor.of(U32, U32, U32));
      ACCEPT4 = L.downcallHandle(libc.find("accept4").orElseThrow(),
          FunctionDescriptor.of(U32, U32, ValueLayout.ADDRESS, ValueLayout.ADDRESS, U32));
      SETSOCKOPT = L.downcallHandle(libc.find("setsockopt").orElseThrow(),
          FunctionDescriptor.of(U32, U32, U32, U32, ValueLayout.ADDRESS, U32));
      CLOSE = L.downcallHandle(libc.find("close").orElseThrow(), FunctionDescriptor.of(U32, U32));
    } catch (Throwable t) {
      throw new ExceptionInInitializerError(t);
    }
  }

  private static final int AF_INET = 2, SOCK_STREAM = 1, SOL_SOCKET = 1, SO_REUSEADDR = 2;

  private static final int IPPROTO_TCP = 6, TCP_NODELAY = 1, SOCK_CLOEXEC = 0x80000;

  private static final byte KIND_READ = 0, KIND_WRITE = 1;

  private static final int SHARD_ENTRIES = 4096;

  private final IoUringRing[] rings;

  private final int ringMask;

  private final Arena arena;

  /** waiters by stream id (ordinal << 1 | kind); one entry per stream. */
  private final ConcurrentHashMap<Long, Waiter> waiters = new ConcurrentHashMap<>();

  private final AtomicLong streamIds = new AtomicLong();

  private volatile boolean stopped;

  /** a parked blocking op: the carrier fills res (volatile) and unparks. */
  private static final class Waiter {

    final Thread parked;

    volatile int res = Integer.MIN_VALUE; // filled by the carrier before unpark

    Waiter(Thread parked) {
      this.parked = parked;
    }

  }

  /** closes a connection fd exactly once across both streams. */
  private static final class FdGuard {

    final int fd;

    private final AtomicBoolean closed = new AtomicBoolean();

    FdGuard(int fd) {
      this.fd = fd;
    }

    void close() {
      if (closed.compareAndSet(false, true)) {
        closeFd(fd);
      }
    }

  }

  /**
   * Creates the sharded transport, or null when io_uring is unavailable
   * (non-Linux, seccomp-blocked container, pre-5.1 kernel) — callers fall
   * back to the channel path. Shards must be a power of two.
   */
  public static IoUringSockets create(int shards) {
    if (shards <= 0 || Integer.bitCount(shards) != 1) {
      throw new IllegalArgumentException("shards must be a power of two: " + shards);
    }
    IoUringRing shared = IoUringRing.shared();
    if (shared == null) {
      return null;
    }
    IoUringRing[] rs = new IoUringRing[shards];
    rs[0] = shared; // shard 0 rides the process-wide ring
    try {
      for (int i = 1; i < shards; i++) {
        rs[i] = IoUringRing.create(SHARD_ENTRIES);
      }
    } catch (IOException unavailable) {
      for (int i = 1; i < shards; i++) {
        if (rs[i] != null) {
          rs[i].close();
        }
      }
      return null;
    }
    return new IoUringSockets(rs);
  }

  private IoUringSockets(IoUringRing[] rings) {
    this.rings = rings;
    this.ringMask = rings.length - 1;
    this.arena = Arena.ofShared();
    for (int i = 0; i < rings.length; i++) {
      final int idx = i;
      Thread.ofPlatform().name("iouring-carrier-" + i).daemon(true).start(() -> carrierLoop(rings[idx]));
    }
  }

  /**
   * Creates the sharded transport with SQPOLL shards: submission costs zero
   * syscalls (the kernel's sq threads consume published SQEs; see
   * {@link IoUringRing#createSqpoll(int, int)}). Returns null when refused
   * (no CAP_SYS_ADMIN, no io_uring).
   */
  public static IoUringSockets createSqpoll(int shards, int idleMs) {
    if (shards <= 0 || Integer.bitCount(shards) != 1) {
      throw new IllegalArgumentException("shards must be a power of two: " + shards);
    }
    IoUringRing[] rs = new IoUringRing[shards];
    try {
      for (int i = 0; i < shards; i++) {
        rs[i] = IoUringRing.createSqpoll(SHARD_ENTRIES, idleMs);
      }
    } catch (IOException refused) {
      for (IoUringRing r : rs) {
        if (r != null) {
          r.close();
        }
      }
      return null;
    }
    return new IoUringSockets(rs);
  }

  /** creates the FFM listener; returns the listen fd. */
  public int listen(int port, int backlog) throws IOException {
    try (Arena a = Arena.ofConfined()) {
      int fd = (int) SOCKET.invoke(AF_INET, SOCK_STREAM, 0);
      if (fd < 0) {
        throw new IOException("socket() failed");
      }
      MemorySegment one = a.allocate(4);
      one.set(U32, 0, 1);
      SETSOCKOPT.invoke(fd, SOL_SOCKET, SO_REUSEADDR, one, 4);
      // sockaddr_in: family u16 native, port u16 + addr u32 network order
      MemorySegment sin = a.allocate(16);
      sin.set(ValueLayout.JAVA_SHORT, 0, (short) AF_INET);
      sin.set(ValueLayout.JAVA_SHORT.withOrder(ByteOrder.BIG_ENDIAN), 2, (short) port);
      sin.set(ValueLayout.JAVA_INT.withOrder(ByteOrder.BIG_ENDIAN), 4, 0x7f000001);
      if ((int) BIND.invoke(fd, sin, 16) < 0 || (int) LISTEN.invoke(fd, backlog) < 0) {
        CLOSE.invoke(fd);
        throw new IOException("bind/listen failed on port " + port);
      }
      return fd;
    } catch (IOException | RuntimeException pass) {
      throw pass;
    } catch (Throwable invoke) {
      throw new IOException("FFM listener setup failed", invoke);
    }
  }

  /** blocking accept (plain accept4; the listener is one socket). */
  public int accept(int listenFd) throws IOException {
    while (!stopped) {
      try {
        int fd = (int) ACCEPT4.invoke(listenFd, MemorySegment.NULL, MemorySegment.NULL, SOCK_CLOEXEC);
        if (fd >= 0) {
          try (Arena a = Arena.ofConfined()) {
            MemorySegment one = a.allocate(4);
            one.set(U32, 0, 1);
            SETSOCKOPT.invoke(fd, IPPROTO_TCP, TCP_NODELAY, one, 4);
          }
          return fd;
        }
        // EINTR/ECONNABORTED class: retry after a beat
        LockSupport.parkNanos(1_000_000);
      } catch (IOException | RuntimeException pass) {
        throw pass;
      } catch (Throwable invoke) {
        throw new IOException("accept4 failed", invoke);
      }
    }
    throw new IOException("stopped");
  }

  /**
   * Posts the op on the fd's shard and parks until that shard's carrier
   * completes it. res is reset BEFORE the publish (see the class doc for
   * why no stale completion can race the reset).
   */
  private int blockingOp(Waiter w, long id, byte kind, int fd, long addr, int len) throws IOException {
    if (stopped) {
      throw new IOException("stopped");
    }
    IoUringRing ring = rings[fd & ringMask];
    w.res = Integer.MIN_VALUE;
    if (kind == KIND_READ) {
      ring.prepareRead(fd, addr, len, id);
    } else {
      ring.prepareWrite(fd, addr, len, id);
    }
    ring.submit(); // publish; this enter also carries any other shard ops
    while (w.res == Integer.MIN_VALUE) {
      LockSupport.park();
    }
    return w.res;
  }

  /** the per-shard dispatcher: bounded-wait enter, drain, complete, repeat. */
  private void carrierLoop(IoUringRing ring) {
    while (!stopped) {
      try {
        ring.submit(1, 20_000_000L); // wait for >=1 completion, up to 20ms
      } catch (IOException dying) {
        return; // ring failed: nothing more this carrier can do
      }
      IoUringRing.Completion c;
      while ((c = ring.pollCompletion()) != null) {
        Waiter w = waiters.get(c.userData());
        if (w != null) {
          w.res = c.res();
          LockSupport.unpark(w.parked);
        }
      }
    }
  }

  /** stops the carriers. ring close stays with the owner (shared ring: nobody). */
  public void stop() {
    stopped = true;
  }

  // ---- connection streams ---------------------------------------------------

  /** connection id: ordinal doubled, +1 marks the write stream. */
  private long newStreamId(boolean write) {
    long id = streamIds.incrementAndGet() << 1;
    return write ? id + 1 : id;
  }

  /** blocking InputStream over an accepted fd (16KB native read buffer). */
  public InputStream inputStream(int fd) {
    return new RingInputStream(fd);
  }

  /** blocking OutputStream over an accepted fd. */
  public OutputStream outputStream(int fd) {
    return new RingOutputStream(fd);
  }

  private final class RingInputStream extends InputStream {

    private final int fd;

    private final FdGuard guard;

    private final Waiter waiter;

    private final long id;

    private final MemorySegment buf; // 16KB native read target

    private int pos, limit;

    private boolean eof;

    RingInputStream(int fd) {
      this.fd = fd;
      this.guard = new FdGuard(fd);
      this.waiter = new Waiter(Thread.currentThread());
      this.id = newStreamId(false);
      this.buf = arena.allocate(16384, 8); // 8B-aligned for the kernel
      waiters.put(id, waiter);
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      if (eof) {
        return -1;
      }
      if (pos < limit) {
        int n = Math.min(len, limit - pos);
        MemorySegment.ofArray(b).asSlice(off, n).copyFrom(buf.asSlice(pos, n));
        pos += n;
        return n;
      }
      int r = blockingOp(waiter, id, KIND_READ, fd, buf.address(), 16384);
      if (r <= 0) {
        if (r == 0) {
          eof = true;
          return -1;
        }
        throw new IOException("ring read failed: errno " + (-r));
      }
      pos = 0;
      limit = r;
      int n = Math.min(len, r);
      MemorySegment.ofArray(b).asSlice(off, n).copyFrom(buf.asSlice(0, n));
      pos = n;
      return n;
    }

    @Override
    public int read() throws IOException {
      byte[] one = new byte[1];
      int n = read(one, 0, 1);
      if (n <= 0) {
        return -1;
      }
      return one[0] & 0xFF;
    }

    @Override
    public void close() {
      waiters.remove(id);
      guard.close();
    }

  }

  private final class RingOutputStream extends OutputStream {

    private final int fd;

    private final FdGuard guard;

    private final Waiter waiter;

    private final long id;

    private final MemorySegment buf; // 16KB native write staging

    RingOutputStream(int fd) {
      this.fd = fd;
      this.guard = new FdGuard(fd);
      this.waiter = new Waiter(Thread.currentThread());
      this.id = newStreamId(true);
      this.buf = arena.allocate(16384, 8);
      waiters.put(id, waiter);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      int written = 0;
      while (written < len) {
        int chunk = Math.min(len - written, 16384);
        buf.asSlice(0, chunk).copyFrom(MemorySegment.ofArray(b).asSlice(off + written, chunk));
        int r = blockingOp(waiter, id, KIND_WRITE, fd, buf.address(), chunk);
        if (r < 0) {
          throw new IOException("ring write failed: errno " + (-r));
        }
        if (r == 0) {
          // zero-progress on a socket write: park briefly and retry instead
          // of spinning (same backoff shape as the writev path)
          LockSupport.parkNanos(100_000);
        }
        written += r;
      }
    }

    @Override
    public void write(int b) throws IOException {
      write(new byte[] {(byte) b}, 0, 1);
    }

    @Override
    public void flush() {
      // writes are posted immediately; nothing buffered to force out
    }

    @Override
    public void close() {
      waiters.remove(id);
      guard.close();
    }

  }

  private static void closeFd(int fd) {
    try {
      CLOSE.invoke(fd);
    } catch (Throwable ignored) {
      // fd already dead
    }
  }

}
