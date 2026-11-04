/*
 * FFM io_uring environment probe - the 4.0.0 landing/verification gate.
 *
 * Single-file source launch (JEP 330): no package, no classpath. Run INSIDE
 * the docker/io_uring image (or any Linux with JDK 22+):
 *
 *   java --enable-native-access=ALL-UNNAMED IouringProbe.java
 *
 * It answers the three questions the io_uring transport depends on, in
 * order, and exits on the first failure:
 *
 *   stage 1: are the io_uring_* syscalls reachable? (kernel >= 5.1 AND not
 *            seccomp-blocked. docker's DEFAULT seccomp profile denies them
 *            with EPERM - run the container with
 *            --security-opt seccomp=unconfined)
 *   stage 2: can the SQ/CQ rings + SQE array be mmap'd from pure FFM?
 *   stage 3: does a submitted IORING_OP_NOP complete end-to-end through the
 *            ring (submit -> enter -> reap a CQE with matching user_data)?
 *
 * Binding style is exactly what the 4.0.0 backend will use: ONLY libc's
 * syscall(2)/mmap/munmap/close via Linker.defaultLookup() - zero JNI, zero
 * liburing, raw syscalls. io_uring_setup/enter/register are 425/426/427 in
 * the asm-generic table, identical on x86-64 and aarch64; every struct below
 * is u32/u64 with natural alignment, so the layouts are also identical.
 *
 * struct layouts encoded here (kernel UAPI, stable since 5.1):
 *   io_uring_params (120B): sq_entries@0, cq_entries@4, flags@8,
 *     sq_thread_cpu@12, sq_thread_idle@16, features@20, wq_id@24,
 *     resv[3]@28, sq_off@40 (40B), cq_off@80 (40B)
 *   io_sqring_offsets: head+0, tail+4, ring_mask+8, ring_entries+12,
 *     flags+16, dropped+20, array+24, resv1+28, resv2+32
 *   io_cqring_offsets: head+0, tail+4, ring_mask+8, ring_entries+12,
 *     overflow+16, cqes+20, flags+24, resv1+28, resv2+32
 *   io_uring_sqe (64B): opcode u8@0, flags u8@1, ioprio u16@2, fd s32@4,
 *     off u64@8, addr u64@16, len u32@24, union u32@28, user_data u64@32
 *   io_uring_cqe (16B): user_data u64@0, res s32@8, flags u32@12
 *
 * Exit codes: 0 PROBE-OK | 3 non-Linux host | 4 setup refused |
 * 5 mmap failed | 6 enter failed | 7 no CQE | 8 wrong CQE
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
import java.nio.file.Files;
import java.nio.file.Path;

public class IouringProbe {

  // generic syscall numbers (asm-generic/unistd.h), same on x86-64 + aarch64
  static final long SYS_IO_URING_SETUP = 425;
  static final long SYS_IO_URING_ENTER = 426;

  // io_uring mmap offsets into the ring fd
  static final long IORING_OFF_SQ_RING = 0L;
  static final long IORING_OFF_CQ_RING = 0x08000000L;
  static final long IORING_OFF_SQES = 0x10000000L;

  static final int IORING_OP_NOP = 0; // 0 is NOP; 11 is TIMEOUT_REMOVE (the
  // -EINVAL trap: opcode numbers are NOT sequential feature additions)

  static final int PROT_READ_WRITE = 0x3; // PROT_READ|PROT_WRITE
  static final int MAP_SHARED_POPULATE = 0x8001; // MAP_SHARED|MAP_POPULATE

  static final long NOP_USER_DATA = 0x1122334455667788L;

  // offsets of the sq_off/cq_off structs inside io_uring_params
  static final long P_SQ_ENTRIES = 0;
  static final long P_CQ_ENTRIES = 4;
  static final long P_FEATURES = 20;
  static final long P_SQ_OFF = 40;
  static final long P_CQ_OFF = 80;
  static final long PARAMS_SIZE = 120;
  // struct io_sqring_offsets field positions
  static final long SQ_HEAD = 0, SQ_TAIL = 4, SQ_MASK = 8, SQ_ARRAY = 24;
  // struct io_cqring_offsets field positions
  static final long CQ_HEAD = 0, CQ_TAIL = 4, CQ_MASK = 8, CQ_CQES = 20;
  // struct io_uring_sqe fields (64B stride)
  static final long SQE_OPCODE = 0, SQE_FD = 4, SQE_USER_DATA = 32;
  static final int SQE_SIZE = 64;
  // struct io_uring_cqe fields (16B stride)
  static final long CQE_USER_DATA = 0, CQE_RES = 8;
  static final int CQE_SIZE = 16;

  static final Linker L = Linker.nativeLinker();
  // native byte order for every u32/u64 field
  static final ValueLayout.OfInt U32 = ValueLayout.JAVA_INT.withOrder(ByteOrder.nativeOrder());
  static final ValueLayout.OfLong U64 = ValueLayout.JAVA_LONG.withOrder(ByteOrder.nativeOrder());

  static final MethodHandle SYSCALL; // long syscall(long nr, a1..a6)
  static final MethodHandle MMAP; // long mmap(long addr, long len, int prot, int flags, int fd, long off)
  static final MethodHandle MUNMAP; // int munmap(long addr, long len)
  static final MethodHandle CLOSE; // int close(int fd)
  static final MethodHandle ERRNO_LOC; // int *__errno_location(void)

  static {
    try {
      SymbolLookup libc = L.defaultLookup();
      // fixed-arity downcall of a variadic libc function: integer-only args
      // pass in registers identically on both LP64 ABIs we target. (on
      // x86-64 glibc's variadic prologue may spill xmm registers if rax
      // happens to be nonzero - harmless, integer regs are read anyway)
      SYSCALL = L.downcallHandle(libc.find("syscall").orElseThrow(),
          FunctionDescriptor.of(U64, U64, U64, U64, U64, U64, U64, U64));
      MMAP = L.downcallHandle(libc.find("mmap").orElseThrow(),
          FunctionDescriptor.of(U64, U64, U64, U32, U32, U32, U64));
      MUNMAP = L.downcallHandle(libc.find("munmap").orElseThrow(),
          FunctionDescriptor.of(U32, U64, U64));
      CLOSE = L.downcallHandle(libc.find("close").orElseThrow(),
          FunctionDescriptor.of(U32, U32));
      ERRNO_LOC = L.downcallHandle(libc.find("__errno_location").orElseThrow(),
          FunctionDescriptor.of(ValueLayout.ADDRESS));
    } catch (Throwable t) {
      throw new ExceptionInInitializerError(t);
    }
  }

  public static void main(String[] args) throws Throwable {
    String os = System.getProperty("os.name");
    if (!os.toLowerCase().contains("linux")) {
      System.out.println("SKIP: " + os + " has no io_uring; run inside docker/io_uring");
      System.exit(3);
    }
    System.out.println("kernel: " + Files.readString(Path.of("/proc/sys/kernel/osrelease")).trim());

    try (Arena arena = Arena.ofConfined()) {
      // stage 1: setup
      MemorySegment params = arena.allocate(PARAMS_SIZE);
      params.fill((byte) 0);
      long fd = (long) SYSCALL.invoke(SYS_IO_URING_SETUP, 4L, params.address(), 0L, 0L, 0L, 0L);
      if (fd < 0) {
        int errno = errno();
        System.out.println("FAIL stage1 io_uring_setup: errno=" + errno + " " + errnoDiag(errno));
        System.exit(4);
      }
      int sqEntries = params.get(U32, P_SQ_ENTRIES);
      int cqEntries = params.get(U32, P_CQ_ENTRIES);
      System.out.println("stage1 OK: fd=" + fd + " sq_entries=" + sqEntries + " cq_entries=" + cqEntries
          + " features=0x" + Integer.toHexString(params.get(U32, P_FEATURES)));

      // stage 2: mmap the rings. all field positions come from params -
      // nothing about the ring layout is hardcoded.
      long sqArrBase = u32(params, P_SQ_OFF + SQ_ARRAY);
      long sqMaskOff = u32(params, P_SQ_OFF + SQ_MASK);
      long sqTailOff = u32(params, P_SQ_OFF + SQ_TAIL);
      long cqHeadOff = u32(params, P_CQ_OFF + CQ_HEAD);
      long cqTailOff = u32(params, P_CQ_OFF + CQ_TAIL);
      long cqMaskOff = u32(params, P_CQ_OFF + CQ_MASK);
      long cqCqesBase = u32(params, P_CQ_OFF + CQ_CQES);

      long sqLen = sqArrBase + sqEntries * 4L;
      long cqLen = cqCqesBase + cqEntries * (long) CQE_SIZE;
      long sqesLen = sqEntries * (long) SQE_SIZE;
      long sqAddr = (long) MMAP.invoke(0L, sqLen, PROT_READ_WRITE, MAP_SHARED_POPULATE, (int) fd, IORING_OFF_SQ_RING);
      long cqAddr = (long) MMAP.invoke(0L, cqLen, PROT_READ_WRITE, MAP_SHARED_POPULATE, (int) fd, IORING_OFF_CQ_RING);
      long sqesAddr = (long) MMAP.invoke(0L, sqesLen, PROT_READ_WRITE, MAP_SHARED_POPULATE, (int) fd, IORING_OFF_SQES);
      if (sqAddr == -1 || cqAddr == -1 || sqesAddr == -1) {
        System.out.println("FAIL stage2 mmap: errno=" + errno() + " " + errnoDiag(errno()));
        MUNMAP.invoke(sqAddr, sqLen);
        MUNMAP.invoke(cqAddr, cqLen);
        MUNMAP.invoke(sqesAddr, sqesLen);
        CLOSE.invoke((int) fd);
        System.exit(5);
      }
      System.out.println("stage2 OK: rings mapped sq=" + sqLen + "B cq=" + cqLen + "B sqes=" + sqesLen + "B");
      // zero-length native segments from the mmap return values, widened to
      // their real sizes. reinterpret(Arena) is the FFM way to attach a
      // lifetime to a raw kernel mapping; cleanup below is manual munmap
      // because the mapping is owned by us, not the arena.
      MemorySegment sqRing = MemorySegment.ofAddress(sqAddr).reinterpret(sqLen, arena, null);
      MemorySegment cqRing = MemorySegment.ofAddress(cqAddr).reinterpret(cqLen, arena, null);
      MemorySegment sqes = MemorySegment.ofAddress(sqesAddr).reinterpret(sqesLen, arena, null);

      // stage 3: submit one NOP, reap one CQE
      int sqMask = sqRing.get(U32, sqMaskOff);
      int cqMask = cqRing.get(U32, cqMaskOff);
      int tail = sqRing.get(U32, sqTailOff);
      int idx = tail & sqMask;
      MemorySegment sqe = sqes.asSlice((long) idx * SQE_SIZE, SQE_SIZE);
      sqe.fill((byte) 0);
      sqe.set(ValueLayout.JAVA_BYTE, SQE_OPCODE, (byte) IORING_OP_NOP);
      sqe.set(ValueLayout.JAVA_INT, SQE_FD, -1);
      sqe.set(U64, SQE_USER_DATA, NOP_USER_DATA);
      sqRing.set(U32, sqArrBase + (long) idx * 4L, idx);
      VarHandle.fullFence(); // SQE + array entry visible before tail publish
      sqRing.set(U32, sqTailOff, tail + 1);
      System.out.println("stage3: NOP sqe[" + idx + "] posted, tail " + tail + "->" + (tail + 1));

      long entered = (long) SYSCALL.invoke(SYS_IO_URING_ENTER, fd, 1L, 1L, 0L, 0L, 0L);
      if (entered < 0) {
        System.out.println("FAIL stage3 io_uring_enter: errno=" + errno() + " " + errnoDiag(errno()));
        teardown(sqAddr, sqLen, cqAddr, cqLen, sqesAddr, sqesLen, fd);
        System.exit(6);
      }
      System.out.println("stage3: enter submitted=" + entered + " (waited for 1 completion)");

      int cqHead = cqRing.get(U32, cqHeadOff);
      int cqTail = cqRing.get(U32, cqTailOff);
      if (cqHead == cqTail) {
        System.out.println("FAIL stage3: no CQE reaped (head==tail)");
        teardown(sqAddr, sqLen, cqAddr, cqLen, sqesAddr, sqesLen, fd);
        System.exit(7);
      }
      MemorySegment cqe = cqRing.asSlice(cqCqesBase + (long) (cqHead & cqMask) * CQE_SIZE, CQE_SIZE);
      long userData = cqe.get(U64, CQE_USER_DATA);
      int res = cqe.get(ValueLayout.JAVA_INT, CQE_RES);
      VarHandle.fullFence(); // CQE consumed before head publish
      cqRing.set(U32, cqHeadOff, cqHead + 1);
      teardown(sqAddr, sqLen, cqAddr, cqLen, sqesAddr, sqesLen, fd);

      if (userData == NOP_USER_DATA && res == 0) {
        System.out.println("PROBE-OK: io_uring full round trip (setup -> mmap -> NOP submit -> CQE reaped -> teardown)");
        System.exit(0);
      }
      System.out.println("FAIL stage3: wrong CQE user_data=0x" + Long.toHexString(userData) + " res=" + res);
      System.exit(8);
    }
  }

  static int u32(MemorySegment params, long offset) {
    return params.get(U32, offset);
  }

  static int errno() throws Throwable {
    // __errno_location() returns int*; reinterpret to 4 bytes so the int
    // read passes bounds (ofAddress alone yields a zero-length segment)
    MemorySegment errnoPtr = ((MemorySegment) ERRNO_LOC.invoke()).reinterpret(4, Arena.global(), null);
    return errnoPtr.get(ValueLayout.JAVA_INT, 0);
  }

  static void teardown(long sqAddr, long sqLen, long cqAddr, long cqLen, long sqesAddr, long sqesLen, long fd)
      throws Throwable {
    MUNMAP.invoke(sqAddr, sqLen);
    MUNMAP.invoke(cqAddr, cqLen);
    MUNMAP.invoke(sqesAddr, sqesLen);
    CLOSE.invoke((int) fd);
    System.out.println("teardown: 3 munmaps + close(" + fd + ")");
  }

  static String errnoDiag(int errno) {
    return switch (errno) {
      case 1 -> "EPERM: seccomp/container profile denies io_uring (docker default; rerun with --security-opt seccomp=unconfined)";
      case 12 -> "ENOMEM";
      case 22 -> "EINVAL: kernel < 5.1 or bad setup flags";
      case 38 -> "ENOSYS: kernel has no io_uring";
      default -> "unexpected";
    };
  }
}
