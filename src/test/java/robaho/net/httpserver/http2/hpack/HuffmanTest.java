package robaho.net.httpserver.http2.hpack;

import org.testng.Assert;
import org.testng.annotations.Test;

import robaho.net.httpserver.http2.HTTP2Exception;

/**
 * Huffman decoding against the RFC 7541 appendix vectors, plus the two
 * regressions found while rewriting the decoder (todo.md P3-43 and the
 * P2-40 note): decoded output longer than the input, and invalid padding.
 */
public class HuffmanTest {

    @Test
    public void testRfc7541Vectors() throws Exception {
        // appendix C.4.1 - C.4.4
        Assert.assertEquals(Huffman.decode(hex("f1e3c2e5f23a6ba0ab90f4ff")), "www.example.com");
        Assert.assertEquals(Huffman.decode(hex("a8eb10649cbf")), "no-cache");
        Assert.assertEquals(Huffman.decode(hex("25a849e95ba97d7f")), "custom-key");
        Assert.assertEquals(Huffman.decode(hex("25a849e95bb8e8b4bf")), "custom-value");
    }

    @Test
    public void testDecodedOutputLongerThanInput() throws Exception {
        // 5 zero bytes = 40 bits = 8 five-bit '0' symbols: the decoded output
        // (8 bytes) is longer than the input (5 bytes), which once overflowed
        // a buffer sized to "output <= input"
        Assert.assertEquals(Huffman.decode(new byte[5]), "00000000");
    }

    @Test(expectedExceptions = HTTP2Exception.class)
    public void testInvalidPaddingRejected() throws Exception {
        // 0x00 = '0' (5 bits) + 3 zero bits: padding must be the EOS prefix
        // (all ones, fewer than 8 bits) per RFC 7541 5.2
        Huffman.decode(new byte[] { 0x00 });
    }

    @Test(expectedExceptions = HTTP2Exception.class)
    public void testEosSymbolRejected() throws Exception {
        // 0xFF... is the prefix of the 30+ bit EOS symbol: decoding it must
        // fail rather than produce data
        Huffman.decode(new byte[] { (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF });
    }

    private static byte[] hex(String s) {
        int len = s.length() / 2;
        byte[] out = new byte[len];
        for (int i = 0; i < len; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }
}
