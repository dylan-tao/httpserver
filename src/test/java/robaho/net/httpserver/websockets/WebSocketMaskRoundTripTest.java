package robaho.net.httpserver.websockets;

import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Random;

/**
 * masked-frame round trips across every alignment boundary of the 8-byte
 * SWAR unmask and the single-block frame writer: lengths 0..64 sweep all
 * (len % 8, len % 4) residue combinations, the larger sizes cross the
 * 125/126 short-length boundary, the 64KiB extended boundary, and exercise
 * the SWAR main loop plus its tail
 */
public class WebSocketMaskRoundTripTest {

  @Test
  public void testMaskedRoundTripAllAlignmentLengths() throws Exception {
    Random rnd = new Random(20261031L);
    byte[] key = new byte[4];
    rnd.nextBytes(key);

    for (int len = 0; len <= 64; len++) {
      roundTrip(key, len, rnd, "sweep " + len);
    }
    for (int len : new int[] {125, 126, 127, 128, 1000, 65535, 65536, 65537, 100_000}) {
      roundTrip(key, len, rnd, "boundary " + len);
    }
  }

  @Test
  public void testMaskedRoundTripVariedKeys() throws Exception {
    // the 8-byte broadcast must track the key's period exactly: keys
    // with repeated bytes (0, -1, 0x55 patterns) would hide a
    // broadcast-assembly bug that random keys can miss
    Random rnd = new Random(7L);
    for (byte[] key : new byte[][] {{0, 0, 0, 0}, {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF},
        {0x55, (byte) 0xAA, 0x55, (byte) 0xAA}, {1, 2, 3, 4}}) {
      roundTrip(key, 1031, rnd, "key " + java.util.Arrays.toString(key));
    }
  }

  private static void roundTrip(byte[] key, int len, Random rnd, String desc) throws Exception {
    byte[] payload = new byte[len];
    rnd.nextBytes(payload);

    WebSocketFrame out = new WebSocketFrame(OpCode.Binary, true, payload);
    out.setMaskingKey(key);
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    out.write(bos);

    WebSocketFrame in = WebSocketFrame.read(new ByteArrayInputStream(bos.toByteArray()));
    Assert.assertEquals(in.getBinaryPayload(), payload, "payload must survive mask round trip: " + desc);
    Assert.assertEquals(in.getOpCode(), OpCode.Binary, "opcode: " + desc);
    Assert.assertTrue(in.isFin(), "fin: " + desc);
    Assert.assertEquals(bos.size(), wireLength(len), "wire size: " + desc);
  }

  private static int wireLength(int payloadLength) {
    int len = 1 /* opcode */ + 4 /* mask key */ + payloadLength;
    if (payloadLength <= 125) {
      return len + 1;
    }
    if (payloadLength <= 0xFFFF) {
      return len + 3;
    }
    return len + 9;
  }

}
