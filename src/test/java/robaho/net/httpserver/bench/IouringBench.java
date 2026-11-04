package robaho.net.httpserver.bench;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

import robaho.net.httpserver.iouring.IoUringRing;
import robaho.net.httpserver.iouring.IoUringSockets;

/**
 * The syscall-floor A/B for the 4.0.0 io_uring backend, run INSIDE the
 * docker/io_uring image (see src/test/bench/iouring-ab.sh). Both arms serve
 * the identical canned 128B response with one response per read - the
 * PlainNioServer shape whose macOS floor measured 36k rps - so the only
 * variable is the IO mechanism:
 * classic: java.net.ServerSocket + one platform thread per connection,
 * blocking read/write. this is the 2.x/3.0.0 IO economics.
 * uring: FFM sockets (socket/bind/listen/accept4 via libc, raw fds), a
 * blocking acceptor thread, and ONE carrier thread driving every
 * connection through the shared IoUringRing: post READ -> reap ->
 * post WRITE -> reap -> ... the completion-driven shape the 4.0.0
 * transport will use.
 * wrk runs in the same netns (also inside the container), so the comparison
 * is pure Linux loopback with identical client conditions.
 */
public final class IouringBench {

  /** identical on both arms - parity with the macOS floor baseline. */
  private static final byte[] RESP = ("HTTP/1.1 200 OK\r\n" + "Content-Type: text/plain\r\n" + "Content-Length: 13\r\n"
      + "Server: iouring-bench\r\n" + "\r\n" + "hello, world!").getBytes(java.nio.charset.StandardCharsets.US_ASCII);

  public static void main(String[] args) throws Exception {
    String mode = args.length > 0 ? args[0] : "classic";
    int port = args.length > 1 ? Integer.parseInt(args[1]) : 18080;
    // uring-vt only: shard count (power of two; the container has 8 CPUs)
    int shards = args.length > 2 ? Integer.parseInt(args[2]) : 4;
    switch (mode) {
      case "classic" -> classic(port);
      case "uring" -> uring(port);
      case "uring-vt" -> uringVt(port, shards, false);
      case "uring-vt-sp" -> uringVt(port, shards, true);
      default -> {
        System.err.println("usage: IouringBench <classic|uring|uring-vt|uring-vt-sp> [port] [shards]");
        System.exit(2);
      }
    }
  }

  // ---- uring-vt arm: the library's future shape -----------------------------
  // one VT per connection + blocking streams over sharded rings (the
  // IoUringSockets layer). same concurrency model as the classic arm: the
  // ONLY difference is the IO mechanism — the honest apples-to-apples for
  // the HttpConnection integration. the -sp variant runs SQPOLL shards:
  // submission costs zero syscalls (kernel sq threads).

  private static void uringVt(int port, int shards, boolean sqpoll) throws Exception {
    IoUringSockets sockets = sqpoll ? IoUringSockets.createSqpoll(shards, 60_000) : IoUringSockets.create(shards);
    if (sockets == null) {
      System.err.println("uring-vt: io_uring unavailable; refusing to fake the A/B");
      System.exit(3);
    }
    int listenFd = sockets.listen(port, 8192);
    System.out.println("uring-vt: listening on 127.0.0.1:" + port + " shards=" + shards + (sqpoll ? " sqpoll" : ""));
    while (true) {
      int fd = sockets.accept(listenFd);
      Thread.ofVirtual().name("vt-conn-" + fd).start(() -> serveUringVt(sockets, fd));
    }
  }

  private static void serveUringVt(IoUringSockets sockets, int fd) {
    var buf = new byte[16384];
    try (var in = sockets.inputStream(fd); var out = sockets.outputStream(fd)) {
      while (true) {
        int n = in.read(buf);
        if (n < 0) {
          return;
        }
        out.write(RESP);
        out.flush();
      }
    } catch (Exception ignored) {
      // connection noise: close via the implicit try-with-resources
    }
  }

  // ---- classic arm ----------------------------------------------------------

  private static void classic(int port) throws Exception {
    var server = new ServerSocket();
    server.bind(new InetSocketAddress("127.0.0.1", port), 8192);
    System.out.println("classic: listening on 127.0.0.1:" + port);
    while (true) {
      Socket s = server.accept();
      Thread.ofPlatform().name("conn-" + s.getPort()).daemon(true).start(() -> serveClassic(s));
    }
  }

  private static void serveClassic(Socket s) {
    var buf = new byte[16384];
    try (s) {
      s.setTcpNoDelay(true);
      var in = s.getInputStream();
      var out = s.getOutputStream();
      while (true) {
        int n = in.read(buf);
        if (n < 0) {
          return;
        }
        out.write(RESP);
        out.flush();
      }
    } catch (Exception ignored) {
      // connection noise: close via the implicit try-with-resources
    }
  }

  // ---- uring arm --------------------------------------------------------------

  private static final Linker L = Linker.nativeLinker();

  private static final ValueLayout.OfInt U32 = ValueLayout.JAVA_INT.withOrder(ByteOrder.nativeOrder());

  private static final ValueLayout.OfLong U64 = ValueLayout.JAVA_LONG.withOrder(ByteOrder.nativeOrder());

  private static final MethodHandle SOCKET, BIND, LISTEN, ACCEPT4, SETSOCKOPT, CLOSE;

  static {
    try {
      SymbolLookup libc = L.defaultLookup();
      // all direct (non-variadic) libc symbols: safe fixed-arity downcalls
      SOCKET = L.downcallHandle(libc.find("socket").orElseThrow(),
          FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
      BIND = L.downcallHandle(libc.find("bind").orElseThrow(),
          FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
      LISTEN = L.downcallHandle(libc.find("listen").orElseThrow(),
          FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
      ACCEPT4 = L.downcallHandle(libc.find("accept4").orElseThrow(), FunctionDescriptor.of(ValueLayout.JAVA_INT,
          ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
      SETSOCKOPT = L.downcallHandle(libc.find("setsockopt").orElseThrow(), FunctionDescriptor.of(ValueLayout.JAVA_INT,
          ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
      CLOSE =
          L.downcallHandle(libc.find("close").orElseThrow(), FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
    } catch (Throwable t) {
      throw new ExceptionInInitializerError(t);
    }
  }

  // socket constants (Linux)
  private static final int AF_INET = 2, SOCK_STREAM = 1, SOL_SOCKET = 1, SO_REUSEADDR = 2;

  private static final int IPPROTO_TCP = 6, TCP_NODELAY = 1, SOCK_CLOEXEC = 0x80000;

  private static void uring(int port) throws Exception {
    IoUringRing ring = IoUringRing.shared();
    if (ring == null) {
      System.err.println("uring: IoUringRing unavailable (non-Linux or seccomp-blocked); refusing to fake the A/B");
      System.exit(3);
    }
    int listenFd;
    try {
      listenFd = listenAt(port);
    } catch (IOException | RuntimeException pass) {
      throw pass;
    } catch (Throwable invoke) {
      throw new IOException("FFM socket setup failed", invoke);
    }
    var newFds = new LinkedBlockingQueue<Integer>();
    Thread.ofPlatform().name("acceptor").daemon(true).start(() -> acceptLoop(listenFd, newFds));
    System.out.println("uring: listening on 127.0.0.1:" + port + " ring sq=" + ring.sqEntries() + " cq=" + ring.cqEntries());
    carrierLoop(ring, newFds);
  }

  private static int listenAt(int port) throws Exception {
    int fd;
    try {
      fd = (int) SOCKET.invoke(AF_INET, SOCK_STREAM, 0);
      if (fd < 0) {
        throw new IllegalStateException("socket() failed");
      }
      try (Arena a = Arena.ofConfined()) {
        MemorySegment one = a.allocate(4);
        one.set(U32, 0, 1);
        SETSOCKOPT.invoke(fd, SOL_SOCKET, SO_REUSEADDR, one, 4);
        // sockaddr_in: family u16 (native order), port u16 + addr u32 (network order)
        MemorySegment sin = a.allocate(16);
        sin.set(ValueLayout.JAVA_SHORT, 0, (short) AF_INET);
        sin.set(ValueLayout.JAVA_SHORT.withOrder(ByteOrder.BIG_ENDIAN), 2, (short) port);
        sin.set(ValueLayout.JAVA_INT.withOrder(ByteOrder.BIG_ENDIAN), 4, 0x7f000001);
        if ((int) BIND.invoke(fd, sin, 16) < 0 || (int) LISTEN.invoke(fd, 8192) < 0) {
          throw new IllegalStateException("bind/listen failed on port " + port);
        }
      }
    } catch (RuntimeException pass) {
      throw pass;
    } catch (Throwable invoke) {
      throw new IOException("FFM socket/bind/listen invocation failed", invoke);
    }
    return fd;
  }

  private static void acceptLoop(int listenFd, LinkedBlockingQueue<Integer> newFds) {
    while (true) {
      try {
        int fd = (int) ACCEPT4.invoke(listenFd, MemorySegment.NULL, MemorySegment.NULL, SOCK_CLOEXEC);
        if (fd < 0) {
          // EINTR/EAGAIN/ECONNABORTED class noise: retry after a beat
          Thread.sleep(1);
          continue;
        }
        try (Arena a = Arena.ofConfined()) {
          MemorySegment one = a.allocate(4);
          one.set(U32, 0, 1);
          SETSOCKOPT.invoke(fd, IPPROTO_TCP, TCP_NODELAY, one, 4);
        }
        newFds.offer(fd);
      } catch (InterruptedException woken) {
        return;
      } catch (Throwable t) {
        // acceptor must survive transient errors (EMFILE etc.)
      }
    }
  }

  /** userData encoding: (fd << 1) | isWritePending. */
  private static long userData(int fd, boolean writePending) {
    return ((long) fd << 1) | (writePending ? 1L : 0L);
  }

  private static void carrierLoop(IoUringRing ring, LinkedBlockingQueue<Integer> newFds) {
    // the carrier owns this arena and the ring usage: buffers live until the
    // connection closes; wrk keeps <=64 conns open so the growth is bounded
    try (Arena arena = Arena.ofConfined()) {
      Map<Integer, MemorySegment> bufs = new HashMap<>();
      MemorySegment resp = arena.allocate(RESP.length);
      for (int i = 0; i < RESP.length; i++) {
        resp.set(ValueLayout.JAVA_BYTE, i, RESP[i]);
      }
      int connCount = 0;
      while (true) {
        // 1. register any accepted connections
        Integer fdIn;
        while ((fdIn = newFds.poll()) != null) {
          int fd = fdIn;
          bufs.put(fd, arena.allocate(16384));
          connCount++;
          try {
            ring.prepareRead(fd, bufs.get(fd).address(), 16384, userData(fd, false));
          } catch (Exception e) {
            System.err.println("uring: prepareRead(fd=" + fd + ") failed: " + e);
            dropConn(ring, bufs, fd);
          }
        }
        // 2. flush the batch + wait up to 20ms for at least one completion
        try {
          ring.submit(1, 20_000_000L);
        } catch (Exception e) {
          System.err.println("uring: enter failed: " + e);
          break;
        }
        // 3. drain every ready completion
        IoUringRing.Completion c;
        while ((c = ring.pollCompletion()) != null) {
          int fd = (int) (c.userData() >>> 1);
          boolean wasWrite = (c.userData() & 1L) != 0;
          if (c.res() == 0 && !wasWrite) {
            dropConn(ring, bufs, fd); // orderly EOF
            continue;
          }
          if (c.res() < 0) {
            dropConn(ring, bufs, fd); // errno in c.errno(): ECONNRESET/EPIPE noise
            continue;
          }
          try {
            if (wasWrite) {
              ring.prepareRead(fd, bufs.get(fd).address(), 16384, userData(fd, false));
            } else {
              // one canned response per read - the classic arm's contract
              ring.prepareWrite(fd, resp.address(), RESP.length, userData(fd, true));
            }
          } catch (Exception e) {
            System.err.println("uring: prepare failed (fd=" + fd + "): " + e);
            dropConn(ring, bufs, fd);
          }
        }
        if (connCount > 100_000) {
          break; // safety: arena confined-lifetime bound (never hit by wrk)
        }
      }
    }
  }

  private static void dropConn(IoUringRing ring, Map<Integer, MemorySegment> bufs, int fd) {
    bufs.remove(fd);
    try {
      CLOSE.invoke(fd);
    } catch (Throwable ignored) {
      // fd already dead
    }
  }

}
