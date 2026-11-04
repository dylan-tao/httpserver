/*
 * FFM io_uring multishot-recv probe - the roadmap brick for eliminating
 * per-read SQE posting. Single-file source launch (JEP 330), run inside
 * the docker/io_uring image:
 *
 *   java --enable-native-access=ALL-UNNAMED MultishotProbe.java
 *
 * It validates the three kernel mechanics the multishot read path needs,
 * in order, exiting on the first failure:
 *
 *   stage 1: IORING_OP_PROVIDE_BUFFERS (opcode 31) - hand a contiguous
 *            4x2KB region to the kernel as buffer group 1; the CQE res
 *            is the number of buffers accepted
 *   stage 2: ONE IORING_OP_RECV SQE armed multishot: sqe flags
 *            IOSQE_BUFFER_SELECT (1U << 4, io_uring.h:107/125), ioprio
 *            IORING_RECV_MULTISHOT (1U << 1, io_uring.h:301), len 0,
 *            buf_index = the bgid. no buffer address - the kernel picks
 *   stage 3: three writes on the socket pair's peer produce THREE CQEs
 *            from that ONE SQE - each with IORING_CQE_F_BUFFER (flags
 *            bit 0) and the buffer id in the flags' upper 16 bits
 *            (IORING_CQE_BUFFER_SHIFT = 16), plus IORING_CQE_F_MORE
 *            (flags bit 1) while the multishot stays armed. the payload
 *            is read from the returned buffer id's slot and verified
 *            byte-exact
 *
 * That is the shape the 4.0.0 read path wants: one SQE per connection
 * instead of one per blocking read, with the kernel parking data into a
 * buffer pool we hand it. The transport integration (buffer return,
 * waiter model for pre-arrived completions) is the follow-on work.
 *
 * Binding style: ONLY libc syscall(2)/mmap/munmap/close/socketpair/write
 * via Linker.defaultLookup() - same zero-JNI shape as IouringProbe.
 * Exit codes: 0 PROBE-OK | 3 non-Linux | 4 setup/provide failed |
 * 5 arm failed | 6 no completions | 7 wrong completions.
 */
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

public class MultishotProbe {

  static final long SYS_IO_URING_SETUP = 425;
  static final long SYS_IO_URING_ENTER = 426;

  static final long IORING_OFF_SQ_RING = 0L;
  static final long IORING_OFF_CQ_RING = 0x08000000L;
  static final long IORING_OFF_SQES = 0x10000000L;

  // opcodes (io_uring.h:168 enum, counted): RECV=27, PROVIDE_BUFFERS=31
  static final byte OP_RECV = 27;
  static final byte OP_PROVIDE_BUFFERS = 31;

  // sqe flags: IOSQE_BUFFER_SELECT = 1U << IOSQE_BUFFER_SELECT_BIT. counting
  // the header's bit enum (FIXED_FILE=0,IO_DRAIN=1,IO_LINK=2,IO_HARDLINK=3,
  // ASYNC=4,BUFFER_SELECT=5): bit 5, NOT 4 - setting bit 4 = IOSQE_ASYNC
  // left the multishot without buffer-select and prep returned -EINVAL
  static final byte SQE_BUFFER_SELECT = 1 << 5;
  // ioprio bits: IORING_RECV_MULTISHOT (io_uring.h:301)
  static final short RECV_MULTISHOT = 1 << 1;
  // cqe flags: IORING_CQE_F_BUFFER bit0, F_MORE bit1, buffer id at shift 16
  static final int CQE_F_BUFFER = 1;
  static final int CQE_F_MORE = 2;
  static final int CQE_BUFFER_SHIFT = 16;

  static final int PROT_READ_WRITE = 0x3;
  static final int MAP_SHARED = 0x01;

  static final int BGID = 1;
  static final int BUF_COUNT = 4;
  static final int BUF_SIZE = 2048;

  static final long MULTISHOT_USER_DATA = 0xA11CE0001L;

  // struct io_uring_params (120B) — offsets as in IouringProbe
  static final long P_SQ_ENTRIES = 0, P_CQ_ENTRIES = 4, P_SQ_OFF = 40, P_CQ_OFF = 80;
  static final long PARAMS_SIZE = 120;
  // io_sqring_offsets: head@0 tail@4 ring_mask@8 array@24
  static final long SQO_HEAD = 0, SQO_TAIL = 4, SQO_MASK = 8, SQO_ARRAY = 24;
  // io_cqring_offsets: head@0 tail@4 ring_mask@8 cqes@20
  static final long CQO_HEAD = 0, CQO_TAIL = 4, CQO_MASK = 8, CQO_CQES = 20;
  // struct io_uring_sqe (64B)
  static final long SQE_OPCODE = 0, SQE_FLAGS = 1, SQE_IOPRIO = 2, SQE_FD = 4,
      SQE_OFF = 8, SQE_ADDR = 16, SQE_LEN = 24, SQE_USER_DATA = 32, SQE_BUF_INDEX = 40;
  static final int SQE_SIZE = 64;
  // struct io_uring_cqe (16B): user_data u64@0, res s32@8, flags u32@12
  static final long CQE_RES = 8, CQE_FLAGS = 12;
  static final int CQE_SIZE = 16;

  static final Linker L = Linker.nativeLinker();
  static final ValueLayout.OfInt U32 = ValueLayout.JAVA_INT.withOrder(ByteOrder.nativeOrder());
  static final ValueLayout.OfLong U64 = ValueLayout.JAVA_LONG.withOrder(ByteOrder.nativeOrder());

  static final MethodHandle SYSCALL, MMAP, MUNMAP, CLOSE, SOCKETPAIR, WRITE;

  static {
    try {
      SymbolLookup libc = L.defaultLookup();
      SYSCALL = L.downcallHandle(libc.find("syscall").orElseThrow(),
          FunctionDescriptor.of(U64, U64, U64, U64, U64, U64, U64, U64));
      MMAP = L.downcallHandle(libc.find("mmap").orElseThrow(),
          FunctionDescriptor.of(U64, U64, U64, U32, U32, U32, U64));
      MUNMAP = L.downcallHandle(libc.find("munmap").orElseThrow(),
          FunctionDescriptor.of(U32, U64, U64));
      CLOSE = L.downcallHandle(libc.find("close").orElseThrow(),
          FunctionDescriptor.of(U32, U32));
      SOCKETPAIR = L.downcallHandle(libc.find("socketpair").orElseThrow(),
          FunctionDescriptor.of(U32, U32, U32, U32, ValueLayout.ADDRESS));
      WRITE = L.downcallHandle(libc.find("write").orElseThrow(),
          FunctionDescriptor.of(U64, U32, ValueLayout.ADDRESS, U64));
    } catch (Throwable t) {
      throw new ExceptionInInitializerError(t);
    }
  }

  public static void main(String[] args) throws Throwable {
    if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("linux")) {
      System.out.println("SKIP: " + System.getProperty("os.name") + " has no io_uring");
      System.exit(3);
    }

    try (Arena arena = Arena.ofConfined()) {
      // ring setup - identical to IouringProbe
      MemorySegment params = arena.allocate(PARAMS_SIZE);
      params.fill((byte) 0);
      long fd = (long) SYSCALL.invoke(SYS_IO_URING_SETUP, 4L, params.address(), 0L, 0L, 0L, 0L);
      if (fd < 0) {
        System.out.println("FAIL setup: errno " + errno());
        System.exit(4);
      }
      int sqEntries = params.get(U32, P_SQ_ENTRIES);
      int cqEntries = params.get(U32, P_CQ_ENTRIES);
      long sqArrBase = u32(params, P_SQ_OFF + SQO_ARRAY);
      long sqMaskOff = u32(params, P_SQ_OFF + SQO_MASK);
      long sqTailOff = u32(params, P_SQ_OFF + SQO_TAIL);
      long cqCqesBase = u32(params, P_CQ_OFF + CQO_CQES);
      long cqHeadOff = u32(params, P_CQ_OFF + CQO_HEAD);
      long cqTailOff = u32(params, P_CQ_OFF + CQO_TAIL);
      long cqMaskOff = u32(params, P_CQ_OFF + CQO_MASK);
      long sqLen = sqArrBase + sqEntries * 4L;
      long cqLen = cqCqesBase + cqEntries * (long) CQE_SIZE;
      long sqesLen = sqEntries * (long) SQE_SIZE;
      MemorySegment sqRing = map(arena, fd, 0L, sqLen);
      MemorySegment cqRing = map(arena, fd, 0x08000000L, cqLen);
      MemorySegment sqes = map(arena, fd, 0x10000000L, sqesLen);
      int sqMask = sqRing.get(U32, sqMaskOff);
      int cqMask = cqRing.get(U32, cqMaskOff);
      System.out.println("ring OK: fd=" + fd + " sq=" + sqEntries + " cq=" + cqEntries);

      // a socket pair as the data source
      MemorySegment pair = arena.allocate(8);
      // SOCK_DGRAM: message boundaries keep the three writes as three
      // distinct recvs - on SOCK_STREAM they coalesce into ONE big CQE
      if ((int) SOCKETPAIR.invoke(1 /* AF_UNIX */, 2 /* SOCK_DGRAM */, 0, pair) != 0) {
        System.out.println("FAIL socketpair: errno " + errno());
        System.exit(4);
      }
      int readEnd = pair.get(U32, 0);
      int writeEnd = pair.get(U32, 4);

      // stage 1: provide 4x2KB contiguous buffers as group BGID
      MemorySegment pool = arena.allocate((long) BUF_COUNT * BUF_SIZE, 8);
      for (int i = 0; i < BUF_COUNT; i++) {
        pool.set(ValueLayout.JAVA_BYTE, (long) i * BUF_SIZE, (byte) 0);
      }
      // field mapping per kernel io_provide_buffers_prep (kbuf.c:319): nbufs
      // = sqe->fd (NOT a real fd - fd=-1 read as huge count => -E2BIG), bid
      // = sqe->off, bgid = the u16 buf_index/buf_group slot @40 (one union
      // slot), splice_fd_in@44 and rw_flags@28 must stay zero
      postSqe(sqRing, sqes, sqTailOff, sqArrBase, sqMask, sqe -> {
        sqe.set(ValueLayout.JAVA_BYTE, SQE_OPCODE, OP_PROVIDE_BUFFERS);
        sqe.set(ValueLayout.JAVA_INT, SQE_FD, BUF_COUNT); // nbufs
        sqe.set(U64, SQE_ADDR, pool.address());
        sqe.set(U32, SQE_LEN, BUF_SIZE);
        sqe.set(U64, SQE_OFF, 0); // starting buffer id
        sqe.set(ValueLayout.JAVA_SHORT, SQE_BUF_INDEX, (short) BGID); // bgid
        sqe.set(U64, SQE_USER_DATA, 0x50524F56L); // 'PROV'
      });
      long r1 = (long) SYSCALL.invoke(SYS_IO_URING_ENTER, fd, 1L, 1L, 0L, 0L, 0L);
      if (r1 < 0) {
        System.out.println("FAIL provide_buffers enter: errno " + errno());
        System.exit(4);
      }
      var c1 = reap(cqRing, cqHeadOff, cqTailOff, cqMask, cqCqesBase);
      // v6.1 io_add_buffers returns 0 on success (res = the COUNT was the
      // pre-5.x behavior; kbuf.c:421 'return i ? 0 : -ENOMEM')
      if (c1 == null || c1.res != 0) {
        System.out.println("FAIL provide_buffers CQE: " + (c1 == null ? "none" : "res=" + c1.res));
        System.exit(4);
      }
      System.out.println("stage1 OK: " + BUF_COUNT + " buffers provided (res=0), bgid=" + BGID);

      // stage 2: ONE multishot RECV - kernel picks buffers from the bgid
      postSqe(sqRing, sqes, sqTailOff, sqArrBase, sqMask, sqe -> {
        sqe.set(ValueLayout.JAVA_BYTE, SQE_OPCODE, OP_RECV);
        sqe.set(ValueLayout.JAVA_BYTE, SQE_FLAGS, SQE_BUFFER_SELECT);
        sqe.set(ValueLayout.JAVA_SHORT, SQE_IOPRIO, RECV_MULTISHOT);
        sqe.set(ValueLayout.JAVA_INT, SQE_FD, readEnd);
        sqe.set(U64, SQE_ADDR, 0L);
        sqe.set(U32, SQE_LEN, 0);
        sqe.set(ValueLayout.JAVA_INT, SQE_BUF_INDEX, BGID);
        sqe.set(U64, SQE_USER_DATA, MULTISHOT_USER_DATA);
      });
      // arm it (submit only; completions come when data arrives)
      long r2 = (long) SYSCALL.invoke(SYS_IO_URING_ENTER, fd, 1L, 0L, 0L, 0L, 0L);
      if (r2 < 0) {
        System.out.println("FAIL multishot arm enter: errno " + errno());
        System.exit(5);
      }
      // prep failures (v6.1 io_recvmsg_prep: multishot needs BUFFER_SELECT,
      // RECV needs len==0, off/addr2 must be 0) complete immediately - reap
      // any instant CQE so the probe cannot hang on a rejected SQE
      try {
        Thread.sleep(50);
      } catch (InterruptedException ignored) {
        // probe: no interruption expected
      }
      Cqe early = reap(cqRing, cqHeadOff, cqTailOff, cqMask, cqCqesBase);
      if (early != null && early.userData == MULTISHOT_USER_DATA && early.res < 0) {
        System.out.println("FAIL multishot prep: res=" + early.res + " (-errno)");
        System.exit(5);
      }
      if (early != null && early.userData == MULTISHOT_USER_DATA) {
        System.out.println("NOTE: multishot completed instantly (res=" + early.res + ")");
      }
      System.out.println("stage2 OK: one multishot RECV armed (no buffer address, len=0)");

      // stage 3: three writes on the peer -> three CQEs from the ONE SQE
      String[] payloads = {"mshot-aaaa", "mshot-bbbb", "mshot-cccc"};
      for (String p : payloads) {
        MemorySegment bytes = arena.allocate(p.length());
        for (int i = 0; i < p.length(); i++) {
          bytes.set(ValueLayout.JAVA_BYTE, i, (byte) p.charAt(i));
        }
        long w = (long) WRITE.invoke(writeEnd, bytes, (long) p.length());
        if (w != p.length()) {
          System.out.println("FAIL write: " + w + " errno " + errno());
          System.exit(6);
        }
      }
      // bounded wait: EXT_ARG enter with a 100ms timespec (the V1 form:
      // argsz=24 is REQUIRED - argsz=0 is EINVAL) until CQEs appear or ~2s.
      // NOTE: wait only, never reap here - the counting loop below must see
      // them (a drain loop here consumed them and starved the counter)
      long deadline = System.nanoTime() + 2_000_000_000L;
      while (System.nanoTime() < deadline && cqRing.get(U32, cqHeadOff) == cqRing.get(U32, cqTailOff)) {
        MemorySegment ts = arena.allocate(16);
        ts.set(U64, 0, 0L);
        ts.set(U64, 8, 100_000_000L);
        MemorySegment arg = arena.allocate(24);
        arg.set(U64, 0, 0L);
        arg.set(U32, 8, 0);
        arg.set(U64, 16, ts.address());
        SYSCALL.invoke(SYS_IO_URING_ENTER, fd, 0L, 1L, (long) (1 | 8), arg.address(), 24L);
      }
      boolean[] seen = new boolean[payloads.length];
      int got = 0;
      Cqe c;
      while ((c = reap(cqRing, cqHeadOff, cqTailOff, cqMask, cqCqesBase)) != null && got < payloads.length) {
        if (c.userData != MULTISHOT_USER_DATA) {
          continue; // a provide CQE reaped late - skip
        }
        got++;
        int bid = (c.flags >>> CQE_BUFFER_SHIFT) & 0xFFFF;
        boolean bufferFlag = (c.flags & CQE_F_BUFFER) != 0;
        boolean moreFlag = (c.flags & CQE_F_MORE) != 0;
        System.out.println("stage3: CQE res=" + c.res + " buffer=" + bid + " F_BUFFER=" + bufferFlag
            + " F_MORE=" + moreFlag);
        // verify payload byte-exact from the returned buffer's slot
        String gotStr = readString(pool, bid, c.res);
        int idx = -1;
        for (int i = 0; i < payloads.length; i++) {
          if (payloads[i].equals(gotStr)) {
            idx = i;
            break;
          }
        }
        if (!bufferFlag || c.res != payloads[0].length() || idx < 0) {
          System.out.println("FAIL wrong CQE payload: res=" + c.res + " str=" + gotStr);
          System.exit(7);
        }
        seen[idx] = true;
      }
      if (got < payloads.length) {
        System.out.println("FAIL only " + got + " completions from one multishot SQE");
        System.exit(6);
      }
      for (int i = 0; i < seen.length; i++) {
        if (!seen[i]) {
          System.out.println("FAIL payload " + i + " missing");
          System.exit(7);
        }
      }
      System.out.println("PROBE-OK: multishot recv - ONE SQE delivered " + got
          + " verified completions from a provided-buffer pool");
      System.exit(0);
    }
  }

  // ---- helpers (IouringProbe's proven scaffolding) ---------------------------

  record Cqe(long userData, int res, int flags) {}

  interface SqeWriter {
    void write(MemorySegment sqe);
  }

  static void postSqe(MemorySegment sqRing, MemorySegment sqes, long sqTailOff, long sqArrBase, int sqMask,
      SqeWriter writer) {
    int tail = sqRing.get(U32, sqTailOff);
    int idx = tail & sqMask;
    MemorySegment sqe = sqes.asSlice((long) idx * SQE_SIZE, SQE_SIZE);
    sqe.fill((byte) 0);
    writer.write(sqe);
    sqRing.set(U32, sqArrBase + (long) idx * 4L, idx);
    VarHandle.releaseFence();
    sqRing.set(U32, sqTailOff, tail + 1);
  }

  static Cqe reap(MemorySegment cqRing, long cqHeadOff, long cqTailOff, long cqMask, long cqCqesBase) {
    int head = cqRing.get(U32, cqHeadOff);
    int tail = cqRing.get(U32, cqTailOff);
    if (head == tail) {
      return null;
    }
    MemorySegment cqe = cqRing.asSlice(cqCqesBase + (long) (head & cqMask) * CQE_SIZE, CQE_SIZE);
    long userData = cqe.get(U64, 0);
    int res = cqe.get(U32, CQE_RES);
    int flags = cqe.get(U32, CQE_FLAGS);
    VarHandle.acquireFence();
    cqRing.set(U32, cqHeadOff, head + 1);
    return new Cqe(userData, res, flags);
  }

  static String readString(MemorySegment pool, int bid, int len) {
    StringBuilder sb = new StringBuilder(Math.max(0, len));
    for (int i = 0; i < len; i++) {
      sb.append((char) (pool.get(ValueLayout.JAVA_BYTE, (long) bid * BUF_SIZE + i) & 0xFF));
    }
    return sb.toString();
  }

  static MemorySegment map(Arena arena, long fd, long offset, long len) throws Throwable {
    long addr = (long) MMAP.invoke(0L, len, PROT_READ_WRITE, MAP_SHARED, (int) fd, offset);
    if (addr == -1) {
      throw new IllegalStateException("mmap failed: errno " + errno());
    }
    return MemorySegment.ofAddress(addr).reinterpret(len, arena, null);
  }

  static int u32(MemorySegment params, long offset) {
    return params.get(U32, offset);
  }

  static final MethodHandle ERRNO_LOC;

  static {
    MethodHandle e = null;
    try {
      e = L.downcallHandle(L.defaultLookup().find("__errno_location").orElseThrow(),
          FunctionDescriptor.of(ValueLayout.ADDRESS));
    } catch (Throwable t) {
      throw new ExceptionInInitializerError(t);
    }
    ERRNO_LOC = e;
  }

  static int errno() {
    try {
      MemorySegment p = ((MemorySegment) ERRNO_LOC.invoke()).reinterpret(4, Arena.global(), null);
      return p.get(ValueLayout.JAVA_INT, 0);
    } catch (Throwable t) {
      return -1;
    }
  }
}
