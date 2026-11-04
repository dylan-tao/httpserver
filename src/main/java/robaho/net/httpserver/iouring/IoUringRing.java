package robaho.net.httpserver.iouring;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.function.Consumer;

/**
 * A shared io_uring instance — one ring for the whole JVM, amortizing ring
 * setup across every connection. This is the raw transport brick of the 4.0.0
 * FFM backend: it binds only libc (syscall/mmap/munmap/close/
 * __errno_location), the exact zero-JNI shape the docker/io_uring probe
 * proved on this machine, then submits READ/WRITE SQEs and reaps CQEs
 * through mapped ring memory.
 * Every UAPI constant below is transcribed from
 * include/uapi/linux/io_uring.h (torvalds v6.1); line anchors in comments.
 * Ring geometry (head/tail/mask/array/cqes offsets) is read from
 * io_uring_params at setup — nothing about the layout is hardcoded. Syscall
 * numbers 425/426/427 are asm-generic, identical on x86-64 and aarch64.
 * Public API:
 * <pre>
 * IoUringRing ring = IoUringRing.shared(); // null when unavailable
 * ring.prepareRead(fd, addr, len, userData); // reserve + write an SQE
 * ring.submit(); // one enter for the batch
 * Completion c = ring.pollCompletion(); // non-null when one is ready
 * </pre>
 * Threading model: any thread may submit (mutex-guarded SQ tail) and any
 * thread may poll the CQ (mutex-guarded CQ head) — the ring is designed for
 * shared use. The kernel-side ordering rules (SQE + its array entry fully
 * written before the tail publish; CQE fully read before the head publish)
 * are enforced with release/acquire fences, exactly as in the probe.
 * IORING_SETUP_SQPOLL and SINGLE_ISSUER are intentionally NOT requested: an
 * in-kernel submitter would consume SQEs from under a Java-side batch, and
 * the pure-ring mode keeps everything explicit and measurable.
 */
public final class IoUringRing implements AutoCloseable {

  // ---- opcodes (io_uring.h:168 enum io_uring_op, counted) ------------------

  static final byte OP_NOP = 0; // verified live by the probe

  static final byte OP_READV = 1;

  static final byte OP_WRITEV = 2;

  static final byte OP_READ_FIXED = 4;

  static final byte OP_WRITE_FIXED = 5;

  static final byte OP_ACCEPT = 13;

  static final byte OP_CLOSE = 19;

  static final byte OP_READ = 22; // IORING_OP_READ

  static final byte OP_WRITE = 23; // IORING_OP_WRITE

  static final byte OP_SEND = 26;

  static final byte OP_RECV = 27;

  static final byte OP_SHUTDOWN = 34;

  // ---- setup/enter/feature flags (io_uring.h:409,412,434-442) --------------

  /** IORING_ENTER_GETEVENTS (1U << 0): wait for min_complete CQEs. */
  static final int ENTER_GETEVENTS = 1;

  /** IORING_ENTER_EXT_ARG (1U << 3): 5th syscall arg is getevents_arg. */
  static final int ENTER_EXT_ARG = 8;

  /** IORING_FEAT_EXT_ARG (1U << 8): kernel takes the EXT_ARG form. */
  static final int FEAT_EXT_ARG = 1 << 8;

  /** IORING_FEAT_NODROP (1U << 1). */
  static final int FEAT_NODROP = 1 << 1;

  /** IORING_FEAT_SINGLE_MMAP (1U << 0): sq+sqes share one mapping. NOT assumed. */
  static final int FEAT_SINGLE_MMAP = 1;

  // ---- struct io_uring_sqe (io_uring.h:22, 64B) ----------------------------

  static final long SQE_OPCODE = 0, SQE_FLAGS = 1, SQE_IOPRIO = 2, SQE_FD = 4, SQE_OFF = 8, SQE_ADDR = 16, SQE_LEN = 24,
      SQE_RW_FLAGS = 28, SQE_USER_DATA = 32;

  static final int SQE_SIZE = 64;

  // ---- struct io_uring_cqe (io_uring.h:328, 16B) ---------------------------

  static final long CQE_USER_DATA = 0, CQE_RES = 8, CQE_FLAGS = 12;

  static final int CQE_SIZE = 16;

  // ---- struct io_uring_params (io_uring.h:418, 120B) -----------------------

  private static final long P_SQ_ENTRIES = 0, P_CQ_ENTRIES = 4, P_FEATURES = 20, P_SQ_OFF = 40, P_CQ_OFF = 80;

  private static final long PARAMS_SIZE = 120;

  // io_sqring_offsets (io_uring.h:368): head@0 tail@4 mask@8 entries@12
  // flags@16 dropped@20 array@24
  private static final long SQO_HEAD = 0, SQO_TAIL = 4, SQO_MASK = 8, SQO_ARRAY = 24;

  // io_cqring_offsets (io_uring.h:387): head@0 tail@4 mask@8 entries@12
  // overflow@16 cqes@20 flags@24
  private static final long CQO_HEAD = 0, CQO_TAIL = 4, CQO_MASK = 8, CQO_CQES = 20;

  // ---- struct io_uring_getevents_arg (io_uring.h:634, 24B) -----------------

  // sigmask u64@0, sigmask_sz u32@8, pad u32@12, ts u64@16 (POINTER to a
  // __kernel_timespec sec@0/nsec@8)
  private static final long GEA_SIGMASK = 0, GEA_SIGMASK_SZ = 8, GEA_TS = 16;

  private static final long GEA_SIZE = 24;

  private static final long TS_SEC = 0, TS_NSEC = 8;

  // ---- syscalls / libc -----------------------------------------------------

  private static final long SYS_IO_URING_SETUP = 425;

  private static final long SYS_IO_URING_ENTER = 426;

  private static final long SYS_IO_URING_REGISTER = 427;

  private static final long IORING_OFF_SQ_RING = 0L;

  private static final long IORING_OFF_CQ_RING = 0x08000000L;

  private static final long IORING_OFF_SQES = 0x10000000L;

  private static final int PROT_READ_WRITE = 0x3;

  private static final int MAP_SHARED = 0x01;

  private static final Linker L = Linker.nativeLinker();

  private static final ValueLayout.OfInt U32 = ValueLayout.JAVA_INT.withOrder(ByteOrder.nativeOrder());

  private static final ValueLayout.OfLong U64 = ValueLayout.JAVA_LONG.withOrder(ByteOrder.nativeOrder());

  private static final MethodHandle SYSCALL, MMAP, MUNMAP, CLOSE, ERRNO_LOC;

  static {
    MethodHandle s = null, m = null, mu = null, c = null, e = null;
    try {
      SymbolLookup libc = L.defaultLookup();
      // variadic libc syscall(): fixed-arity integer downcall; register
      // passing is identical on both target LP64 ABIs
      s = L.downcallHandle(libc.find("syscall").orElseThrow(), FunctionDescriptor.of(U64, U64, U64, U64, U64, U64, U64, U64));
      m = L.downcallHandle(libc.find("mmap").orElseThrow(), FunctionDescriptor.of(U64, U64, U64, U32, U32, U32, U64));
      mu = L.downcallHandle(libc.find("munmap").orElseThrow(), FunctionDescriptor.of(U32, U64, U64));
      c = L.downcallHandle(libc.find("close").orElseThrow(), FunctionDescriptor.of(U32, U32));
      e = L.downcallHandle(libc.find("__errno_location").orElseThrow(), FunctionDescriptor.of(ValueLayout.ADDRESS));
    } catch (Throwable t) {
      throw new ExceptionInInitializerError(t);
    }
    SYSCALL = s;
    MMAP = m;
    MUNMAP = mu;
    CLOSE = c;
    ERRNO_LOC = e;
  }

  // ---- instance state --------------------------------------------------------

  private final Arena arena;

  private final int ringFd;

  private final MemorySegment sqRing, cqRing, sqes;

  private final int sqMask, cqMask, sqEntries, cqEntries;

  private final long sqHeadOff, sqTailOff, sqArrayOff;

  private final long cqHeadOff, cqTailOff, cqCqesOff;

  private final boolean extArg;

  private volatile boolean closed;

  /** guards SQ reservation + tail publish (submission side). */
  private final Object submitLock = new Object();

  /** guards CQ head advance (reaping side). */
  private final Object reapLock = new Object();

  /** a reaped completion. res is the syscall's return: >= 0 bytes moved, or -errno. */
  public record Completion(long userData, int res) {

    public boolean failed() {
      return res < 0;
    }

    public int errno() {
      return -res;
    }

  }

  // ---- lifecycle ------------------------------------------------------------

  private static volatile IoUringRing sharedRing;

  private static volatile boolean sharedProbed;

  /**
   * The process-wide ring, or null when io_uring is unavailable (non-Linux,
   * kernel &lt; 5.1, or a seccomp-blocked container). Callers fall back to
   * the channel path on null — this is the seam that keeps macOS unaffected.
   * The refusal is remembered after one probe (sharedProbed) so the accept
   * path does not syscall per connection.
   */
  public static IoUringRing shared() {
    if (sharedRing != null) {
      return sharedRing;
    }
    if (sharedProbed) {
      return null;
    }
    synchronized (IoUringRing.class) {
      if (sharedRing != null) {
        return sharedRing;
      }
      if (sharedProbed) {
        return null;
      }
      if (!System.getProperty("os.name", "").toLowerCase().contains("linux")) {
        sharedProbed = true;
        return null; // macOS never gains io_uring; no point retrying
      }
      try {
        sharedRing = new IoUringRing(256);
      } catch (IOException unavailable) {
        // EPERM (docker default seccomp) or pre-5.1 kernel: stay on the
        // channel path permanently
        sharedProbed = true;
        return null;
      }
      return sharedRing;
    }
  }

  private IoUringRing(int entriesWanted) throws IOException {
    Arena a = null;
    int fd = -1;
    MemorySegment sq = null, cq = null, sqeArr = null;
    long sqLen = 0, cqLen = 0, sqesLen = 0;
    try {
      a = Arena.ofShared();
      MemorySegment params = a.allocate(PARAMS_SIZE);
      params.fill((byte) 0);
      long r = (long) SYSCALL.invoke(SYS_IO_URING_SETUP, (long) entriesWanted, params.address(), 0L, 0L, 0L, 0L);
      if (r < 0) {
        int errno = errno();
        throw new IOException("io_uring_setup failed: errno " + errno
            + (errno == 1 ? " (seccomp denies io_uring; needs --security-opt seccomp=unconfined)" : ""));
      }
      fd = (int) r;
      extArg = (params.get(U32, P_FEATURES) & FEAT_EXT_ARG) != 0;

      sqEntries = params.get(U32, P_SQ_ENTRIES);
      cqEntries = params.get(U32, P_CQ_ENTRIES);

      // ring geometry from params — offsets are VALUES inside the structs
      long sqArrayRel = u32(params, P_SQ_OFF + SQO_ARRAY);
      long cqCqesRel = u32(params, P_CQ_OFF + CQO_CQES);
      sqLen = sqArrayRel + sqEntries * 4L;
      cqLen = cqCqesRel + cqEntries * (long) CQE_SIZE;
      sqesLen = sqEntries * (long) SQE_SIZE;

      long sqAddr = (long) MMAP.invoke(0L, sqLen, PROT_READ_WRITE, MAP_SHARED, fd, IORING_OFF_SQ_RING);
      long cqAddr = (long) MMAP.invoke(0L, cqLen, PROT_READ_WRITE, MAP_SHARED, fd, IORING_OFF_CQ_RING);
      long sqesAddr = (long) MMAP.invoke(0L, sqesLen, PROT_READ_WRITE, MAP_SHARED, fd, IORING_OFF_SQES);
      if (sqAddr == -1 || cqAddr == -1 || sqesAddr == -1) {
        throw new IOException("ring mmap failed: errno " + errno());
      }
      sq = MemorySegment.ofAddress(sqAddr).reinterpret(sqLen, a, null);
      cq = MemorySegment.ofAddress(cqAddr).reinterpret(cqLen, a, null);
      sqeArr = MemorySegment.ofAddress(sqesAddr).reinterpret(sqesLen, a, null);

      sqRing = sq;
      cqRing = cq;
      sqes = sqeArr;
      arena = a;
      ringFd = fd;
      sqMask = sqRing.get(U32, u32(params, P_SQ_OFF + SQO_MASK));
      cqMask = cqRing.get(U32, u32(params, P_CQ_OFF + CQO_MASK));
      sqHeadOff = u32(params, P_SQ_OFF + SQO_HEAD);
      sqTailOff = u32(params, P_SQ_OFF + SQO_TAIL);
      sqArrayOff = sqArrayRel;
      cqHeadOff = u32(params, P_CQ_OFF + CQO_HEAD);
      cqTailOff = u32(params, P_CQ_OFF + CQO_TAIL);
      cqCqesOff = cqCqesRel;
    } catch (IOException | RuntimeException e) {
      teardown(a, sq, cq, sqeArr, sqLen, cqLen, sqesLen, fd);
      throw e;
    } catch (Throwable t) {
      teardown(a, sq, cq, sqeArr, sqLen, cqLen, sqesLen, fd);
      throw new IOException("io_uring setup failed", t);
    }
  }

  private static void teardown(Arena a, MemorySegment sq, MemorySegment cq, MemorySegment sqeArr, long sqLen, long cqLen,
      long sqesLen, int fd) {
    try {
      if (sq != null) {
        MUNMAP.invoke(sq.address(), sqLen);
      }
      if (cq != null) {
        MUNMAP.invoke(cq.address(), cqLen);
      }
      if (sqeArr != null) {
        MUNMAP.invoke(sqeArr.address(), sqesLen);
      }
      if (fd >= 0) {
        CLOSE.invoke(fd);
      }
    } catch (Throwable ignored) {
      // teardown on a half-built ring: best effort
    }
    if (a != null) {
      a.close();
    }
  }

  @Override
  public void close() {
    closed = true;
    long sqLen = sqRing.byteSize(), cqLen = cqRing.byteSize(), sqesLen = sqes.byteSize();
    teardown(arena, sqRing, cqRing, sqes, sqLen, cqLen, sqesLen, ringFd);
  }

  // ---- submission ------------------------------------------------------------

  /**
   * Reserves the next SQE slot and writes an IORING_OP_READ SQE (socket fd,
   * current-position read: off field carries -1 via the 0 fill so the socket
   * reads at its own cursor). The SQE is NOT visible to the kernel until the
   * next {@link #submit()}. Safe to call from any thread.
   * @param addr native address of the destination buffer
   * @param userData opaque token returned in the Completion
   */
  public void prepareRead(int fd, long addr, int len, long userData) throws IOException {
    prepare(fd, OP_READ, addr, len, userData);
  }

  /** IORING_OP_WRITE variant of {@link #prepareRead}. */
  public void prepareWrite(int fd, long addr, int len, long userData) throws IOException {
    prepare(fd, OP_WRITE, addr, len, userData);
  }

  private void prepare(int fd, byte opcode, long addr, int len, long userData) throws IOException {
    if (closed) {
      throw new IOException("ring closed");
    }
    synchronized (submitLock) {
      int head = sqRing.get(U32, sqHeadOff);
      int tail = sqRing.get(U32, sqTailOff);
      if (tail - head >= sqEntries) {
        throw new IOException("SQ full (" + (tail - head) + " outstanding)");
      }
      int idx = tail & sqMask;
      MemorySegment sqe = sqes.asSlice((long) idx * SQE_SIZE, SQE_SIZE);
      sqe.fill((byte) 0);
      sqe.set(ValueLayout.JAVA_BYTE, SQE_OPCODE, opcode);
      sqe.set(ValueLayout.JAVA_INT, SQE_FD, fd);
      // off = -1 (u64 @8): read/write at the socket's own cursor, not a file
      // offset — preadv2/pwritev2 with -1 offset semantics
      sqe.set(U64, SQE_OFF, -1L);
      sqe.set(U64, SQE_ADDR, addr);
      sqe.set(U32, SQE_LEN, len);
      sqe.set(U64, SQE_USER_DATA, userData);
      sqRing.set(U32, sqArrayOff + (long) idx * 4L, idx);
      VarHandle.releaseFence(); // SQE + array entry visible before tail publish
      sqRing.set(U32, sqTailOff, tail + 1); // publish: the kernel consumes it
    }
  }

  /**
   * Enters the kernel with every SQE published since the last submit: one
   * syscall for the whole batch. Returns the number actually submitted.
   */
  public int submit() throws IOException {
    return (int) submit(0, 0L);
  }

  /**
   * Publish + enter with an optional wait: when minComplete &gt; 0 the enter
   * blocks until that many CQEs exist (GETEVENTS). When the ring supports
   * EXT_ARG (FEAT_EXT_ARG) and timeoutNs &gt; 0, the wait is bounded by a
   * __kernel_timespec through the getevents_arg struct — the kernel returns
   * -ETIME on expiry, which this method reports as 0 submitted (a bounded
   * poll, not an error).
   * @return the raw io_uring_enter return: submitted count (>= 0), or -errno
   */
  public long submit(int minComplete, long timeoutNs) throws IOException {
    if (closed) {
      throw new IOException("ring closed");
    }
    int head = sqRing.get(U32, sqHeadOff);
    int tail = sqRing.get(U32, sqTailOff);
    int toSubmit = tail - head;
    if (toSubmit == 0 && minComplete == 0) {
      return 0;
    }
    long ret;
    if (minComplete > 0 && timeoutNs > 0 && extArg) {
      try (Arena ta = Arena.ofConfined()) {
        MemorySegment ts = ta.allocate(16);
        ts.set(U64, TS_SEC, timeoutNs / 1_000_000_000L);
        ts.set(U64, TS_NSEC, timeoutNs % 1_000_000_000L);
        MemorySegment arg = ta.allocate(GEA_SIZE);
        arg.set(U64, GEA_SIGMASK, 0L);
        arg.set(U32, GEA_SIGMASK_SZ, 0);
        arg.set(U64, GEA_TS, ts.address());
        ret = (long) SYSCALL.invoke(SYS_IO_URING_ENTER, (long) ringFd, (long) toSubmit, (long) minComplete,
            (long) (ENTER_GETEVENTS | ENTER_EXT_ARG), arg.address(), GEA_SIZE);
      } catch (IOException | RuntimeException pass) {
        throw pass;
      } catch (Throwable invoke) {
        throw new IOException("io_uring_enter invocation failed", invoke);
      }
    } else {
      int flags = minComplete > 0 ? ENTER_GETEVENTS : 0;
      try {
        ret =
            (long) SYSCALL.invoke(SYS_IO_URING_ENTER, (long) ringFd, (long) toSubmit, (long) minComplete, (long) flags, 0L, 0L);
      } catch (IOException | RuntimeException pass) {
        throw pass;
      } catch (Throwable invoke) {
        throw new IOException("io_uring_enter invocation failed", invoke);
      }
    }
    if (ret < 0) {
      int errno = errno();
      // libc's syscall() wrapper is POSIX: on error it returns -1 AND sets
      // errno (unlike the raw kernel convention of returning -errno). so the
      // verdict is errno()-based; ret itself is only "it failed".
      // timeout-family outcomes are "no progress this call", not ring errors
      // (EXT_ARG expiry returns ETIME; the NUMBER differs by arch: 110 on
      // x86-64, 62 on this aarch64 linuxkit. EAGAIN covers nonblocking
      // waits, EINTR signal pokes)
      if (errno == 110 || errno == 62 /* ETIME per arch */
          || errno == 4 /* EINTR */ || errno == 11 /* EAGAIN */) {
        return 0;
      }
      throw new IOException("io_uring_enter failed: errno " + errno);
    }
    return ret;
  }

  // ---- completion ------------------------------------------------------------

  /**
   * Reaps one completion if available, null otherwise. Thread-safe: the CQE
   * is fully read before the CQ head publish (acquire/release fences).
   */
  public Completion pollCompletion() {
    synchronized (reapLock) {
      int head = cqRing.get(U32, cqHeadOff);
      int tail = cqRing.get(U32, cqTailOff);
      if (head == tail) {
        return null;
      }
      MemorySegment cqe = cqRing.asSlice(cqCqesOff + (long) (head & cqMask) * CQE_SIZE, CQE_SIZE);
      long userData = cqe.get(U64, CQE_USER_DATA);
      int res = cqe.get(U32, CQE_RES);
      VarHandle.acquireFence(); // CQE consumed before head publish
      cqRing.set(U32, cqHeadOff, head + 1);
      return new Completion(userData, res);
    }
  }

  /**
   * Reaps up to max completions into the handler; returns the count. The
   * handler runs under the reap mutex — keep it cheap or hand off.
   */
  public int drain(int max, Consumer<Completion> handler) {
    int n = 0;
    while (n < max) {
      Completion c = pollCompletion();
      if (c == null) {
        break;
      }
      handler.accept(c);
      n++;
    }
    return n;
  }

  // ---- misc ------------------------------------------------------------

  public int sqEntries() {
    return sqEntries;
  }

  public int cqEntries() {
    return cqEntries;
  }

  boolean extArg() {
    return extArg;
  }

  private static int u32(MemorySegment params, long offset) {
    return params.get(U32, offset);
  }

  private static int errno() {
    try {
      MemorySegment errnoPtr = ((MemorySegment) ERRNO_LOC.invoke()).reinterpret(4, Arena.global(), null);
      return errnoPtr.get(ValueLayout.JAVA_INT, 0);
    } catch (Throwable t) {
      return -1;
    }
  }

}
