package robaho.net.httpserver.internal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.GatheringByteChannel;

/**
 * writev target for the h1 response path: delegates gathering writes to the
 * socket channel and stamps the connection's last-activity clock, so the
 * idle cleaner sees writev traffic exactly like buffered writes (Http2Stream
 * has its own activity plumbing and never goes through this class)
 */
public final class GatheringByteChannelSlice implements GatheringByteChannel {

  private final java.nio.channels.WritableByteChannel channel;

  private final Runnable activityStamp;

  private GatheringByteChannelSlice(java.nio.channels.WritableByteChannel channel, Runnable activityStamp) {
    this.channel = channel;
    this.activityStamp = activityStamp;
  }

  /** wrap a socket channel; activityStamp runs after every successful write */
  public static GatheringByteChannel of(java.nio.channels.WritableByteChannel channel, Runnable activityStamp) {
    return new GatheringByteChannelSlice(channel, activityStamp);
  }

  public static GatheringByteChannel of(java.nio.channels.WritableByteChannel channel) {
    return new GatheringByteChannelSlice(channel, null);
  }

  @Override
  public int write(ByteBuffer src) throws IOException {
    int n = channel.write(src);
    if (n > 0 && activityStamp != null) {
      activityStamp.run();
    }
    return n;
  }

  @Override
  public long write(ByteBuffer[] srcs, int offset, int length) throws IOException {
    // SocketChannel (unlike its abstract Gather base contract) accepts
    // GatheringByteChannel's spread form via this overload
    if (channel instanceof GatheringByteChannel gathering) {
      long n = gathering.write(srcs, offset, length);
      if (n > 0 && activityStamp != null) {
        activityStamp.run();
      }
      return n;
    }
    long total = 0;
    for (int i = 0; i < length; i++) {
      while (srcs[offset + i].hasRemaining()) {
        long n = channel.write(srcs[offset + i]);
        if (n <= 0) {
          return total == 0 ? n : total;
        }
        total += n;
      }
    }
    if (total > 0 && activityStamp != null) {
      activityStamp.run();
    }
    return total;
  }

  @Override
  public long write(ByteBuffer[] srcs) throws IOException {
    return write(srcs, 0, srcs.length);
  }

  @Override
  public boolean isOpen() { return channel.isOpen(); }

  @Override
  public void close() throws IOException {
    // NOT the resource owner: the owned streams close the socket. closing
    // here would race the exchange lifecycle
  }

}
