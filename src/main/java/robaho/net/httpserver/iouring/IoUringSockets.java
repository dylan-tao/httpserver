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

  /**
   * a parked blocking op: the carrier fills res (volatile) and unparks.
   * The parked thread is resolved per-op at blockingOp entry, NOT at stream
   * construction — the library constructs streams on the dispatcher thread
   * and parks on the connection's VT, so a construction-time owner would
   * unpark the wrong thread (the sanity curl hang). blocking semantics keep
   * one op in flight per stream, so the owner can be reassigned between ops.
   */
  private static final class Waiter {

    volatile Thread parked;

    volatile int res = Integer.MIN_VALUE; // filled by the carrier before unpark

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
   * back to the channel path. Shards must be a power of two. Prefers
   * SQPOLL shards (zero-syscall submit; measured +68% on the blocking
   * emulation — the per-op self-submit enter storm is the wall without
   * it) and falls back to plain rings when the kernel refuses SQPOLL
   * (no CAP_SYS_ADMIN).
   */
  public static IoUringSockets create(int shards) {
    if (shards <= 0 || Integer.bitCount(shards) != 1) {
      throw new IllegalArgumentException("shards must be a power of two: " + shards);
    }
    if (IoUringRing.shared() == null) {
      return null; // no io_uring at all (probe once, cached)
    }
    IoUringRing[] rs = new IoUringRing[shards];
    // SQPOLL first: sq threads consume SQEs with zero submit syscalls. a
    // 100s idle keeps them awake through the workload; sleeping sq threads
    // are doorbelled by submit's NEED_WAKEUP check
    IoUringSockets sqpoll = createSqpoll(shards, 100_000);
    if (sqpoll != null) {
      return sqpoll;
    }
    try {
      for (int i = 0; i < shards; i++) {
        rs[i] = IoUringRing.create(SHARD_ENTRIES);
      }
    } catch (IOException unavailable) {
      for (IoUringRing r : rs) {
        if (r != null) {
          r.close();
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

  /**
   * A peer address from accept4: fd plus the decoded sockaddr_in (port and
   * IPv4 bytes, network order decoded here).
   */
  public record Accepted(int fd, int remotePort, byte[] remoteAddress) {}

  /**
   * Blocking accept with peer decoding. The caller must run on a PLATFORM
   * thread (a raw FFM downcall never yields a virtual thread's carrier).
   * On stop the listener fd closes and accept4 fails; callers treat that as
   * shutdown.
   */
  public Accepted acceptPeer(int listenFd) throws IOException {
    while (!stopped) {
      try (Arena a = Arena.ofConfined()) {
        MemorySegment peer = a.allocate(16); // sockaddr_in
        MemorySegment len = a.allocate(4);
        len.set(U32, 0, 16);
        int fd = (int) ACCEPT4.invoke(listenFd, peer, len, SOCK_CLOEXEC);
        if (fd < 0) {
          // EINTR/ECONNABORTED class: retry after a beat
          LockSupport.parkNanos(1_000_000);
          continue;
        }
        try (Arena a2 = Arena.ofConfined()) {
          MemorySegment one = a2.allocate(4);
          one.set(U32, 0, 1);
          SETSOCKOPT.invoke(fd, IPPROTO_TCP, TCP_NODELAY, one, 4);
        }
        // sockaddr_in: port u16@2 (network order), addr u32@4 (network order)
        int port = Short.toUnsignedInt(peer.get(ValueLayout.JAVA_SHORT.withOrder(ByteOrder.BIG_ENDIAN), 2));
        byte[] addr = new byte[4];
        for (int i = 0; i < 4; i++) {
          addr[i] = peer.get(ValueLayout.JAVA_BYTE, 4 + i);
        }
        return new Accepted(fd, port, addr);
      } catch (IOException | RuntimeException pass) {
        throw pass;
      } catch (Throwable invoke) {
        throw new IOException("accept4 failed", invoke);
      }
    }
    throw new IOException("stopped");
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
   * Asymmetric wait: socket WRITES complete warm — the sq thread consumes
   * the SQE within microseconds and the buffer has space — so a SHORT spin
   * avoids the park/unpark round trip. READS wait on network RTT; spinning
   * for them burned FJP workers and starved runnable VTs (measured: a
   * uniform 50us spin collapsed 272k -> 144k rps at 6.7 cores — with VTs
   * outnumbering cores, the park/unpark handoff is CHEAPER than spinning),
   * so reads park immediately. While a write spins, the thread also reaps
   * ready CQEs ("helping"): a reaped completion may complete a sibling
   * without a carrier dispatch. Reaping is safe from any thread (the reap
   * lock serializes CQ head advance).
   */
  private int blockingOp(Waiter w, long id, byte kind, int fd, long addr, int len) throws IOException {
    if (stopped) {
      throw new IOException("stopped");
    }
    IoUringRing ring = rings[fd & ringMask];
    w.parked = Thread.currentThread(); // per-op owner: see the Waiter doc
    w.res = Integer.MIN_VALUE;
    if (kind == KIND_READ) {
      ring.prepareRead(fd, addr, len, id);
    } else {
      ring.prepareWrite(fd, addr, len, id);
    }
    ring.submit(); // publish; this enter also carries any other shard ops
    long deadline = kind == KIND_WRITE ? System.nanoTime() + WRITE_SPIN_NANOS : 0L;
    while (w.res == Integer.MIN_VALUE) {
      if (deadline != 0L) {
        Thread.onSpinWait();
        IoUringRing.Completion c = ring.tryPollCompletion();
        if (c != null) {
          completeOne(c);
          if (w.res != Integer.MIN_VALUE) {
            return w.res; // spin reaped our own completion: no park happened
          }
          continue; // completed a sibling: keep helping while the window lasts
        }
        if (System.nanoTime() < deadline) {
          continue;
        }
        deadline = 0L; // spin window exhausted: fall through to the park path
      }
      LockSupport.park(); // cold completion: the carrier will wake us
    }
    return w.res;
  }

  /** one completion -> its waiter; callable from ANY thread (spin or carrier). */
  private void completeOne(IoUringRing.Completion c) {
    Waiter w = waiters.get(c.userData());
    if (w != null) {
      w.res = c.res();
      LockSupport.unpark(w.parked);
    }
  }

  /**
   * write-side spin budget, 0 by default. measured on the 4-shard SQPOLL A/B
   * (wrk t4/c64, 8-CPU container): pure park 292k rps at 546% CPU vs write-
   * spin-5us 284k at 583% — the completions are not warm enough (sq threads
   * run ~136k ops/s) and worker occupancy is the scarce resource, so the
   * park/unpark handoff wins at every spin budget. the knob stays for
   * latency-tuning on different hardware shapes.
   */
  private static final long WRITE_SPIN_NANOS =
      Math.max(0L, Long.parseLong(System.getProperty("iouring.write.spin.us", "0"))) * 1_000L;

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
        completeOne(c);
      }
    }
  }

  /** stops the carriers. ring close stays with the owner (shared ring: nobody). */
  public void stop() {
    stopped = true;
  }

  /** closes the listener fd (server stop path; wakes a parked accept4). */
  public void closeListener(int listenFd) {
    closeFd(listenFd);
  }

  // ---- connection streams ---------------------------------------------------

  /** connection id: ordinal doubled, +1 marks the write stream. */
  private long newStreamId(boolean write) {
    long id = streamIds.incrementAndGet() << 1;
    return write ? id + 1 : id;
  }

  /** blocking InputStream over an accepted fd (16KB native read buffer). */
  public InputStream inputStream(int fd) {
    return new RingInputStream(fd, new FdGuard(fd));
  }

  /** blocking OutputStream over an accepted fd. */
  public OutputStream outputStream(int fd) {
    return new RingOutputStream(fd, new FdGuard(fd));
  }

  /**
   * One connection as a stream pair over a single fd guard: both streams
   * share the FdGuard so the fd closes exactly once, whichever side closes
   * first. The HttpConnection seam consumes this.
   */
  public Conn connection(int fd) {
    FdGuard guard = new FdGuard(fd);
    return new Conn(new RingInputStream(fd, guard), new RingOutputStream(fd, guard));
  }

  /** paired blocking streams over one connection fd. */
  public record Conn(InputStream in, OutputStream out) {}

  private final class RingInputStream extends InputStream {

    private final int fd;

    private final FdGuard guard;

    private final Waiter waiter;

    private final long id;

    private final MemorySegment buf; // 16KB native read target

    private int pos, limit;

    private boolean eof;

    RingInputStream(int fd, FdGuard guard) {
      this.fd = fd;
      this.guard = guard;
      this.waiter = new Waiter();
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

    RingOutputStream(int fd, FdGuard guard) {
      this.fd = fd;
      this.guard = guard;
      this.waiter = new Waiter();
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
