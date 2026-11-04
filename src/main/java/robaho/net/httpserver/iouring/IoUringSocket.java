package robaho.net.httpserver.iouring;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;

/**
 * The java.net.Socket facade over an io_uring connection — what
 * ServerImpl's Dispatcher hands to HttpConnection when the 4.0.0 transport
 * is enabled. HttpConnection's socket surface (audited):
 * getInputStream/getOutputStream (the buffered stream wrappers), getChannel
 * (null: the writev gather and sendfile fast paths stay dormant — the ring
 * batches writes at the SQE level), the SSLSocket instanceof checks (never
 * true — TLS rides the channel path), getLocalPort/getPort (connection id),
 * getRemoteSocketAddress (close logging and getRemoteAddress), isClosed,
 * close, setTcpNoDelay (already set at accept), setSoTimeout (only the
 * RST-drain path, which first checks available() — buffered bytes report
 * available, empty parks nothing), and the accept-loop close paths.
 * Every close path funnels into the Conn's shared FdGuard: is.close(),
 * os.close() and socket.close() each fire at most one libc close(), and
 * this class's close() unregisters BOTH stream waiters.
 */
public final class IoUringSocket extends Socket {

  private final IoUringSockets.Conn conn;

  private final int localPort;

  private final int remotePort;

  private final InetAddress remoteAddress;

  private volatile boolean closed;

  /**
   * @param accepted the peer info from {@link IoUringSockets#acceptPeer}
   * @param localPort the listener's port (for the connection id)
   */
  /**
   * @param accepted the peer info from {@link IoUringSockets#acceptPeer}
   * @param localPort the listener's port (for the connection id)
   */
  public IoUringSocket(IoUringSockets sockets, IoUringSockets.Accepted accepted, int localPort) {
    // unconnected base Socket: every method HttpConnection touches is
    // overridden below, so the parent's impl never runs
    super();
    this.conn = sockets.connection(accepted.fd());
    this.localPort = localPort;
    this.remotePort = accepted.remotePort();
    byte[] addr = accepted.remoteAddress();
    try {
      this.remoteAddress = InetAddress.getByAddress(addr);
    } catch (UnknownHostException impossible) {
      // a 4-byte address is always valid
      throw new IllegalArgumentException("bad peer address", impossible);
    }
  }

  @Override
  public InputStream getInputStream() { return conn.in(); }

  @Override
  public OutputStream getOutputStream() { return conn.out(); }

  @Override
  public java.nio.channels.SocketChannel getChannel() {
    // null keeps the writev/sendfile fast paths dormant (see class doc)
    return null;
  }

  @Override
  public int getPort() { return remotePort; }

  @Override
  public int getLocalPort() { return localPort; }

  @Override
  public InetAddress getInetAddress() { return remoteAddress; }

  @Override
  public InetSocketAddress getRemoteSocketAddress() { return new InetSocketAddress(remoteAddress, remotePort); }

  @Override
  public InetSocketAddress getLocalSocketAddress() {
    return new InetSocketAddress(InetAddress.getLoopbackAddress(), localPort);
  }

  @Override
  public boolean isConnected() { return true; }

  @Override
  public boolean isBound() { return true; }

  @Override
  public boolean isClosed() { return closed; }

  @Override
  public void close() {
    closed = true;
    // both stream waiters must leave the dispatch map; the shared FdGuard
    // makes the fd close itself fire exactly once
    try {
      conn.in().close();
    } catch (IOException ignored) {
      // close of a raw fd is best-effort
    }
    try {
      conn.out().close();
    } catch (IOException ignored) {
      // close of a raw fd is best-effort
    }
  }

  @Override
  public void setTcpNoDelay(boolean on) {
    // already set at accept time (IoUringSockets.acceptPeer)
  }

  @Override
  public void setSoTimeout(int timeout) {
    // the drain path calls this only when available() reported pending
    // bytes; reads of buffered bytes return immediately, and raw ring reads
    // beyond the buffer are not time-bounded (v1: the drain deadline still
    // applies via the surrounding wall clock)
  }

  @Override
  public String toString() {
    return "IoUringSocket[local=" + localPort + " remote=" + remoteAddress.getHostAddress() + ":" + remotePort
        + (closed ? " closed" : "") + "]";
  }

}
