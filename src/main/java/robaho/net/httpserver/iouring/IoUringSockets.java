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
import java.lang.invoke.VarHandle;
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

  private static final ValueLayout.OfLong U64 = ValueLayout.JAVA_LONG.withOrder(ByteOrder.nativeOrder());

  private static final MethodHandle SOCKET, BIND, LISTEN, ACCEPT4, SETSOCKOPT, CLOSE, FCNTL, WRITE, READ, ERRNO_LOC;

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
      // fcntl is variadic; for F_GETFL/F_SETFL the third arg is an int — the
      // fixed 3-int arity is ABI-identical for this form (same trick as syscall)
      FCNTL = L.downcallHandle(libc.find("fcntl").orElseThrow(), FunctionDescriptor.of(U32, U32, U32, U32));
      WRITE = L.downcallHandle(libc.find("write").orElseThrow(), FunctionDescriptor.of(U64, U32, ValueLayout.ADDRESS, U64));
      READ = L.downcallHandle(libc.find("read").orElseThrow(), FunctionDescriptor.of(U64, U32, ValueLayout.ADDRESS, U64));
      ERRNO_LOC = L.downcallHandle(libc.find("__errno_location").orElseThrow(), FunctionDescriptor.of(ValueLayout.ADDRESS));
      CLOSE = L.downcallHandle(libc.find("close").orElseThrow(), FunctionDescriptor.of(U32, U32));
    } catch (Throwable t) {
      throw new ExceptionInInitializerError(t);
    }
  }

  private static final int AF_INET = 2, SOCK_STREAM = 1, SOL_SOCKET = 1, SO_REUSEADDR = 2;

  private static final int IPPROTO_TCP = 6, TCP_NODELAY = 1, SOCK_CLOEXEC = 0x80000;

  // fcntl commands / flags (linux)
  private static final int F_GETFL = 3, F_SETFL = 4, O_NONBLOCK = 2048; // 0o4000

  private static final int EAGAIN = 11, EINTR = 4;

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
    // are doorbelled by submit's NEED_WAKEUP check. the preference is
    // switchable: with the hybrid direct write/read carrying the
    // per-request traffic the SQE rate is ~1/read, and plain rings cost
    // ~1 enter syscall per read — while SQPOLL burns a spinning kernel
    // thread per shard (~1 core each) that competes with the workload
    // for CPU on small boxes
    if (SQPOLL_PREFERRED) {
      IoUringSockets sqpoll = createSqpoll(shards, 100_000);
      if (sqpoll != null) {
        return sqpoll;
      }
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
    this.pools = new BufferPool[rings.length];
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
        if (DIRECT) {
          setNonblocking(fd); // only with a direct path to absorb EAGAIN; a
          // blocking fd keeps the ring's OP_READ semaphore-free blocking
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
          if (DIRECT) {
            setNonblocking(fd); // only with a direct path to absorb EAGAIN; a
            // blocking fd keeps the ring's OP_READ semaphore-free blocking
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
    // record-then-recheck, full form: poll the CQ ONCE before the first
    // park. a warm completion (inline-completed read/write, the common
    // case for small ops) is often already sitting in the CQ at this
    // point — the pre-park poll catches it without ANY park or carrier
    // dispatch (the non-sqpoll cold-start hang traced to exactly this
    // window: the opener's inline CQE completed before the carrier's
    // 20ms GETEVENTS armed, and nothing else on the box reaped it).
    // the zero-budget lesson stays: the WRITE spin is still opt-in, but
    // this ONE pre-park poll is part of the readiness protocol, not a
    // spin budget (it runs once regardless of kind)
    long deadline = kind == KIND_WRITE && WRITE_SPIN_NANOS > 0 ? System.nanoTime() + WRITE_SPIN_NANOS : 0L;
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
      } else {
        IoUringRing.Completion c = ring.tryPollCompletion();
        if (c != null) {
          completeOne(c);
          if (w.res != Integer.MIN_VALUE) {
            return w.res; // warm completion: zero parks, zero dispatches
          }
          // a sibling's: loop once more, then park for ours
        }
      }
      LockSupport.park(); // cold completion: the carrier will wake us
    }
    return w.res;
  }

  /**
   * one completion -> its target: a per-op waiter (write path), a multishot
   * channel (read path), or nothing (buffer-pool provides carry userData 0;
   * stale ids after a close are dropped by design). callable from ANY thread.
   */
  private void completeOne(IoUringRing.Completion c) {
    Waiter w = waiters.get(c.userData());
    if (w != null) {
      w.res = c.res();
      LockSupport.unpark(w.parked);
      return;
    }
    MultishotChannel channel = multishots.get(c.userData());
    if (channel != null) {
      channel.onCompletion(c.res(), c.flags());
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

  /**
   * O_NONBLOCK on a connected fd (F_GETFL then F_SETFL — the flag is not
   * settable via accept4, whose SOCK_NONBLOCK would also hit the listener
   * semantics we keep blocking). the hybrid write/read paths need it:
   * they attempt the fast syscall FIRST and fall to the ring on EAGAIN.
   */
  private static void setNonblocking(int fd) throws IOException {
    try {
      int flags = (int) FCNTL.invoke(fd, F_GETFL, 0);
      FCNTL.invoke(fd, F_SETFL, flags | O_NONBLOCK);
    } catch (IOException | RuntimeException pass) {
      throw pass;
    } catch (Throwable invoke) {
      throw new IOException("fcntl(O_NONBLOCK) failed", invoke);
    }
  }

  /** libc errno (POSIX convention: -1 return + errno set). */
  private static int errno() {
    try {
      MemorySegment p = ((MemorySegment) ERRNO_LOC.invoke()).reinterpret(4, Arena.global(), null);
      return p.get(ValueLayout.JAVA_INT, 0);
    } catch (Throwable t) {
      return -1;
    }
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
   * first. The HttpConnection seam consumes this. The read side rides a
   * multishot channel (ONE kernel recv per connection, served from a
   * provided-buffer pool) when arming succeeds; any arm failure degrades
   * to the per-read blocking stream — identical semantics, only the
   * per-read SQE post returns.
   */
  public Conn connection(int fd) {
    FdGuard guard = new FdGuard(fd);
    InputStream in = new RingInputStream(fd, guard);
    if (MULTISHOT) {
      try {
        MultishotChannel channel = armMultishot(fd);
        if (channel != null) {
          in = channel.new Stream(guard);
        }
      } catch (IOException armFailed) {
        // arm refused (SQ full, ring dying): per-read fallback above stays
      }
    }
    return new Conn(in, new RingOutputStream(fd, guard));
  }

  // ---- multishot read path ---------------------------------------------------

  /** multishot channels by stream id: the carrier's second dispatch table. */
  private final ConcurrentHashMap<Long, MultishotChannel> multishots = new ConcurrentHashMap<>();

  /** per-shard buffer pools, created lazily on the first connection. */
  private final BufferPool[] pools;

  /**
   * multishot read path knob: OFF by default. v1 measured WORSE on every
   * workload in the same-container differential (h1 -25%, seq -30%, mux8
   * -54%, upload -100% vs the per-read stream): the per-delivery cost
   * (synchronized pending queue + ArrayDeque entry + leftover bookkeeping
   * + lazy recycle batches) exceeds the plain 16KB-read cost it replaced
   * at these message sizes. the mechanism is correct (8MB uploads
   * byte-verified) and stays armed for a v2 that elides the queue — zero
   * when the copy happens directly on the carrier or the delivery lands
   * in a reader-owned slot. opt back in with
   * {@code -Drobaho.net.httpserver.iouring.multishot=true}.
   */
  private static final boolean MULTISHOT =
      Boolean.parseBoolean(System.getProperty("robaho.net.httpserver.iouring.multishot", "false"));

  /** hybrid direct-write/read switch (A/B knob, default on). */
  private static final boolean DIRECT =
      Boolean.parseBoolean(System.getProperty("robaho.net.httpserver.iouring.direct", "true"));

  /** SQPOLL ring preference switch (A/B knob, default on). */
  private static final boolean SQPOLL_PREFERRED =
      Boolean.parseBoolean(System.getProperty("robaho.net.httpserver.iouring.sqpoll", "true"));

  /**
   * buffer-pool sizing: 64 x 16KB = 1MB per shard. 16KB matters: the v1
   * 4KB pool QUADRUPLED the completion rate of the per-read path (a 16KB
   * read became four deliveries) and that alone drove most of the
   * measured regression. one buffer per delivery at 16KB matches the
   * per-read completion rate exactly.
   */
  private static final int POOL_BUFFERS = 64;

  private static final int POOL_BUF_SIZE = 16384;

  /** pool group ids are u16: a global counter keeps pools collision-free. */
  private static final java.util.concurrent.atomic.AtomicInteger BGIDS = new java.util.concurrent.atomic.AtomicInteger();

  /**
   * Arms one multishot RECV for the connection. Registration into the
   * dispatch map happens BEFORE the submit — a completion can land between
   * a later registration and would otherwise be dropped (the probe's
   * early-reap lesson). null when this shard cannot host a pool.
   */
  private MultishotChannel armMultishot(int fd) throws IOException {
    int shard = fd & ringMask;
    BufferPool pool = pools[shard];
    if (pool == null) {
      pool = createPool(rings[shard]);
      pools[shard] = pool;
      if (pool == null) {
        return null; // this shard stays on the per-read path
      }
    }
    MultishotChannel channel = new MultishotChannel(fd, pool);
    multishots.put(channel.id, channel);
    IoUringRing ring = rings[shard];
    ring.prepareMultishotRecv(fd, pool.bgid(), channel.id);
    ring.submit();
    return channel;
  }

  private BufferPool createPool(IoUringRing ring) {
    try {
      return new BufferPool(ring, BGIDS.incrementAndGet());
    } catch (IOException failed) {
      return null;
    }
  }

  /**
   * The multishot read side of one connection (v2, the queue-elision
   * shape): ONE RECV SQE armed once delivers unboundedly. The carrier
   * writes (bufferId, len) pairs into a LOCK-FREE single-producer/
   * single-consumer delivery ring and unparks the reader; the reader
   * consumes slots, copies out of the pool slice, and hands the buffer
   * back IMMEDIATELY (one provide SQE — no drained backlog, no batching
   * bookkeeping). A reader-owned partial (curBid/curOff/curLen) holds
   * leftover bytes of an unconsumed delivery, so nothing is ever
   * re-queued.
   * v1 (synchronized ArrayDeque + 4KB pool + batch-4 recycle) measured
   * h1 -25% / seq -30% / mux8 -54% vs the per-read path: the 4KB pool
   * QUADRUPLED the completion rate (every 16KB read turned into four
   * deliveries), and each delivery paid a monitor + an allocation. v2
   * fixes all three: 16KB buffers (completion rate matches the per-read
   * shape), the SPSC ring costs one volatile store per delivery with
   * zero allocation, and recycle is a bare SQE post.
   * Ring capacity equals the pool depth: the kernel can never deliver
   * more buffers to one channel than the pool holds, so the producer
   * never blocks on a full ring — the SPSC pair needs no overflow path.
   */
  final class MultishotChannel {

    private final int fd;

    private final long id;

    private final BufferPool pool;

    /** delivery ring: packed (bufferId &lt;&lt; 32 | len); producers append under ringLock, the reader consumes. */
    private final long[] ringSlots = new long[POOL_BUFFERS];

    private static final int RING_MASK = POOL_BUFFERS - 1;

    /** carrier-owned write cursor; volatile: the reader's empty check sees it. */
    private volatile int tail;

    /** reader-owned read cursor; plain: only this reader mutates it. */
    private int head;

    /** reader-owned partial delivery: the slot's leftover bytes. */
    private int curBid = -1;

    private int curOff;

    private int curLen;

    /** the parked reader; recorded before every park (record-then-recheck). */
    private volatile Thread reader;

    private volatile boolean eof;

    private volatile int error;

    private volatile boolean armed = true;

    private MultishotChannel(int fd, BufferPool pool) {
      this.fd = fd;
      this.pool = pool;
      this.id = newStreamId(false);
    }

    /** the carrier's dispatch entry — one completion of the multishot SQE. */
    void onCompletion(int res, int flags) {
      if (res > 0) {
        if ((flags & IoUringRing.CQE_F_BUFFER) != 0) {
          deliver((flags >>> IoUringRing.CQE_BUFFER_SHIFT) & 0xFFFF, res);
        }
        // res > 0 without F_BUFFER carries no data (no buffer selected)
      } else if (res == 0) {
        eof = true; // orderly EOF: ring slots still deliver
      } else if (res == -105 /* ENOBUFS: pool starvation, recoverable */) {
        armed = false; // data stays queued in the socket; rearm picks it up
      } else if (error == 0) {
        error = -res; // -errno: ECONNRESET and friends end the channel
      }
      if ((flags & IoUringRing.CQE_F_MORE) == 0) {
        // the kernel ended the multishot: EOF, error, or starvation
        armed = false;
      }
      Thread r = reader;
      if (r != null) {
        LockSupport.unpark(r);
      }
    }

    int read(byte[] b, int off, int len) throws IOException {
      // the reader-owned partial first: no requeue, no ring traffic
      if (curLen > 0) {
        int n = Math.min(len, curLen);
        MemorySegment.ofArray(b).asSlice(off, n).copyFrom(pool.region().asSlice((long) curBid * pool.bufSize() + curOff, n));
        curOff += n;
        curLen -= n;
        if (curLen == 0) {
          recycleNow(curBid);
          curBid = -1;
        }
        return n;
      }
      long packed = take();
      if (packed == EOF_PACKED) {
        return -1;
      }
      int bid = (int) (packed >>> 32);
      int have = (int) packed;
      int n = Math.min(len, have);
      MemorySegment.ofArray(b).asSlice(off, n).copyFrom(pool.region().asSlice((long) bid * pool.bufSize(), n));
      if (n == have) {
        recycleNow(bid);
      } else {
        curBid = bid;
        curOff = n;
        curLen = have - n;
      }
      return n;
    }

    private static final long EOF_PACKED = -1L;

    /**
     * Appends one delivery. The slot write is synchronized: the ring is
     * SPSC by design (carrier produces, reader consumes), but the write
     * threads' opt-in spin-helping also reaches here as a RARE second
     * producer (a reaped CQE cannot be pushed back into the kernel ring).
     * Uncontended lock cost is ~20ns per delivery — the alternative, CAS
     * slot claiming with a separate publish index, only moves the same
     * ordering problem around.
     */
    private void deliver(int bid, int res) {
      synchronized (ringLock) {
        // capacity == pool depth: no overflow check is needed (see doc)
        int t = tail;
        ringSlots[t & RING_MASK] = ((long) bid << 32) | (res & 0xFFFFFFFFL);
        VarHandle.releaseFence(); // slot written before the tail publish
        tail = t + 1;
      }
    }

    /** guards the delivery-ring append (see deliver). */
    private final Object ringLock = new Object();

    /** parks until a delivery, EOF or an error is visible; re-arms on disarm. */
    private long take() throws IOException {
      reader = Thread.currentThread(); // record-then-recheck
      while (true) {
        if (head != tail) {
          long packed = ringSlots[head & RING_MASK];
          head++; // plain: only the reader mutates head
          return packed;
        }
        if (error != 0) {
          throw new IOException("multishot read failed: errno " + error);
        }
        if (eof) {
          return EOF_PACKED;
        }
        if (!armed) {
          rearm(); // starvation disarm: submit carries the recycle provides
          continue;
        }
        LockSupport.park();
      }
    }

    /** hands the buffer back NOW: one provide SQE, zero bookkeeping. */
    private void recycleNow(int bid) {
      if (!stopped) {
        try {
          pool.provide(1, bid);
        } catch (IOException io) {
          // the shard is dying: the next read surfaces it via error
        }
      }
    }

    /** reader-side re-arm after a kernel-side disarm (pool starvation). */
    private void rearm() throws IOException {
      IoUringRing ring = rings[fd & ringMask];
      ring.submit(); // carry any provides the pool needs back
      ring.prepareMultishotRecv(fd, pool.bgid(), id);
      ring.submit();
      armed = true;
    }

    /** the blocking-InputStream facade over this channel. */
    final class Stream extends InputStream {

      private final FdGuard guard;

      Stream(FdGuard guard) {
        this.guard = guard;
      }

      @Override
      public int read(byte[] b, int off, int len) throws IOException {
        if (len == 0) {
          return 0;
        }
        return MultishotChannel.this.read(b, off, len);
      }

      @Override
      public int read() throws IOException {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n == -1 ? -1 : one[0] & 0xFF;
      }

      @Override
      public int available() {
        // the TRUE delivered byte count: NoSyncBufferedInputStream.read1
        // loops while available() > 0, so an over-report (full bufSize per
        // queued slot instead of the packed len) makes it issue raw reads
        // past the real data — the reader then parks on an empty ring and
        // a flow-controlled upload deadlocks (client waits for credit the
        // parked handler would issue). slots between head and the read of
        // tail are immutable once written, so this sum is stable
        int total = curLen;
        int t = tail;
        for (int i = head; i < t; i++) {
          total += (int) ringSlots[i & RING_MASK];
        }
        return Math.min(total, Integer.MAX_VALUE);
      }

      @Override
      public void close() {
        multishots.remove(id);
        eof = true;
        armed = false;
        if (curLen > 0 && curBid >= 0) {
          recycleNow(curBid); // do not strand the partial's buffer
        }
        guard.close();
      }

    }

  }

  /** per-shard contiguous buffer pool backing the multishot channels. */
  final class BufferPool {

    private final IoUringRing ring;

    private final MemorySegment region;

    private final int bufSize;

    private final int bgid;

    BufferPool(IoUringRing ring, int bgid) throws IOException {
      this.ring = ring;
      this.bgid = bgid;
      this.bufSize = POOL_BUF_SIZE;
      this.region = arena.allocate((long) POOL_BUFFERS * bufSize, 8);
      // userData 0: the provide's own completion routes to no waiter and
      // no channel (completeOne skips unknown ids by design)
      ring.prepareProvideBuffers(region.address(), bufSize, POOL_BUFFERS, 0, bgid, 0L);
      ring.submit();
    }

    int bgid() {
      return bgid;
    }

    int bufSize() {
      return bufSize;
    }

    MemorySegment region() {
      return region;
    }

    void provide(int n, int bid) throws IOException {
      long base = region.address() + (long) bid * bufSize;
      ring.prepareProvideBuffers(base, bufSize, n, bid, bgid, 0L);
      ring.submit();
    }

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
      // hybrid direct read: queued socket data returns inline — zero park,
      // zero carrier round trip (the classic path's pipelined-request win).
      // EAGAIN (nothing queued) falls to the ring SQE below. ordering is
      // single-op-in-flight per stream (the same blocking semantics this
      // class already carries), so the direct read and any SQE never race;
      // the MULTISHOT path cannot do this (a standing recv would race the
      // direct read and reorder the stream — see MultishotChannel's doc)
      long direct = 0;
      int err = 0;
      if (DIRECT) {
        try {
          direct = (long) READ.invoke(fd, buf, 16384L);
        } catch (Throwable invoke) {
          throw new IOException("direct read failed", invoke);
        }
      }
      if (direct == 0) {
        eof = true;
        return -1;
      }
      if (direct < 0) {
        err = errno();
        if (err != EAGAIN && err != EINTR) {
          throw new IOException("direct read failed: errno " + err);
        }
      }
      if (direct > 0) {
        pos = 0;
        limit = (int) direct;
        int n = Math.min(len, (int) direct);
        MemorySegment.ofArray(b).asSlice(off, n).copyFrom(buf.asSlice(0, n));
        pos = n;
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

    /**
     * Hybrid write: the classic path completes small writes INLINE (the
     * socket accepts them synchronously — no park, no syscall round trip
     * through the carrier); the ring path paid that async tax on EVERY
     * write, which is where most of the uring h1/mux regression lived.
     * So: stage into the native buffer, attempt a DIRECT write() on the
     * nonblocking fd, and only the remainder (partial or EAGAIN) rides the
     * ring SQE. ordering is safe by construction — a write is at most one
     * in flight per stream, so the direct write and the fallback SQE never
     * interleave with anything else on this fd.
     */
    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      int written = 0;
      while (written < len) {
        int chunk = Math.min(len - written, 16384);
        buf.asSlice(0, chunk).copyFrom(MemorySegment.ofArray(b).asSlice(off + written, chunk));
        long direct;
        int err = 0;
        if (DIRECT) {
          try {
            // ADDRESS parameters take the SEGMENT, not its raw address —
            // passing buf.address() (a long) throws WrongMethodTypeException
            direct = (long) WRITE.invoke(fd, buf, (long) chunk);
          } catch (Throwable invoke) {
            throw new IOException("direct write failed", invoke);
          }
        } else {
          direct = 0;
        }
        if (direct < 0) {
          err = errno();
          if (err != EAGAIN && err != EINTR) {
            throw new IOException("direct write failed: errno " + err);
          }
          direct = 0;
        }
        if (direct > 0) {
          written += (int) direct;
        }
        int rest = chunk - (int) direct;
        if (rest > 0) {
          // partial or would-block: the remainder rides the ring (the
          // bytes sit at buf+direct.. — the SQE addresses them directly)
          int r = blockingOp(waiter, id, KIND_WRITE, fd, buf.asSlice(direct, rest).address(), rest);
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
