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
import java.util.concurrent.locks.LockSupport;

/**
 * The blocking-IO emulation over the shared ring — the layer HttpConnection
 * will consume. One virtual thread per connection keeps the library's
 * programming model; each blocking read/write posts an SQE and parks until
 * the carrier reaps its completion and unparks it. The completion
 * dispatcher is a single shared carrier thread: it enters the kernel with a
 * bounded wait, drains every ready CQE, completes the parked waiters, and
 * loops — never holding Java locks across the enter.
 * FFM socket plumbing (socket/bind/listen/accept4/setsockopt) lives here too
 * so the whole 4.0.0 native surface is one package. Lifecycle: stop() stops
 * the carrier; connection fds are closed by their streams' close().
 * userData encoding: ordinal in the high 60 bits, wait kind in the low 4.
 * Ordinals are unique for the process lifetime (an AtomicLong), so a stale
 * completion for a closed waiter simply finds no slot — no generation
 * bookkeeping needed.
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

  private final IoUringRing ring;

  private final Arena arena;

  /** waiter slots by ordinal; unique per process, removed on completion. */
  private final ConcurrentHashMap<Long, Waiter> waiters = new ConcurrentHashMap<>();

  private final java.util.concurrent.atomic.AtomicLong ordinals = new java.util.concurrent.atomic.AtomicLong();

  private volatile boolean stopped;

  /** a parked blocking op: the carrier fills res (release) and unparks. */
  private static final class Waiter {

    final Thread parked;

    volatile int res = Integer.MIN_VALUE; // filled by the carrier before unpark

    Waiter(Thread parked) {
      this.parked = parked;
    }

  }

  public IoUringSockets(IoUringRing ring) {
    this.ring = ring;
    this.arena = Arena.ofShared();
    Thread.ofPlatform().name("iouring-carrier").daemon(true).start(this::carrierLoop);
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

  /** posts the op and parks until the carrier completes it. */
  private int blockingOp(byte kind, int fd, long addr, int len) throws IOException {
    if (stopped) {
      throw new IOException("stopped");
    }
    long ordinal = ordinals.incrementAndGet();
    Waiter w = new Waiter(Thread.currentThread());
    waiters.put(ordinal, w);
    try {
      if (kind == KIND_READ) {
        ring.prepareRead(fd, addr, len, (ordinal << 4) | kind);
      } else {
        ring.prepareWrite(fd, addr, len, (ordinal << 4) | kind);
      }
      ring.submit(); // publish; the carrier's enter drives it to completion
      int spins = 0;
      while (w.res == Integer.MIN_VALUE) {
        LockSupport.park();
        if (++spins > 1_000_000) {
          throw new IOException("carrier lost the completion (ordinal " + ordinal + ")");
        }
      }
      return w.res;
    } finally {
      waiters.remove(ordinal);
    }
  }

  /** the dispatcher: bounded-wait enter, drain, complete, repeat. */
  private void carrierLoop() {
    while (!stopped) {
      try {
        ring.submit(1, 20_000_000L); // wait for >=1 completion, up to 20ms
      } catch (IOException dying) {
        return; // ring failed: nothing more this carrier can do
      }
      IoUringRing.Completion c;
      while ((c = ring.pollCompletion()) != null) {
        Waiter w = waiters.get(c.userData() >>> 4);
        if (w != null) {
          w.res = c.res(); // volatile write...
          LockSupport.unpark(w.parked); // ...then the unpark pairs the park
        }
      }
    }
  }

  public void stop() {
    stopped = true;
  }

  // ---- connection streams ---------------------------------------------------

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

    private final MemorySegment buf; // 16KB native read target

    private int pos, limit;

    private boolean eof;

    RingInputStream(int fd) {
      this.fd = fd;
      this.buf = arena.allocate(16384, 8); // 8B-aligned for the kernel
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
      int r = blockingOp(KIND_READ, fd, buf.address(), 16384);
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
      closeFd(fd);
    }

  }

  private final class RingOutputStream extends OutputStream {

    private final int fd;

    private final MemorySegment buf; // 16KB native write staging

    RingOutputStream(int fd) {
      this.fd = fd;
      this.buf = arena.allocate(16384, 8);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      int written = 0;
      while (written < len) {
        int chunk = Math.min(len - written, 16384);
        buf.asSlice(0, chunk).copyFrom(MemorySegment.ofArray(b).asSlice(off + written, chunk));
        int r = blockingOp(KIND_WRITE, fd, buf.address(), chunk);
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
      closeFd(fd);
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
