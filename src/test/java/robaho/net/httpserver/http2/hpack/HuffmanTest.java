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

    /** the encoder must reproduce the RFC 7541 appendix C vectors bit-exactly
     *  (they are canonical codes from the same table) */
    @Test
    public void testEncodeMatchesRfcVectors() {
        Assert.assertEquals(toHex(Huffman.encode("www.example.com".getBytes(java.nio.charset.StandardCharsets.UTF_8))), "f1e3c2e5f23a6ba0ab90f4ff");
        Assert.assertEquals(toHex(Huffman.encode("no-cache".getBytes(java.nio.charset.StandardCharsets.UTF_8))), "a8eb10649cbf");
        Assert.assertEquals(toHex(Huffman.encode("custom-key".getBytes(java.nio.charset.StandardCharsets.UTF_8))), "25a849e95ba97d7f");
        Assert.assertEquals(toHex(Huffman.encode("custom-value".getBytes(java.nio.charset.StandardCharsets.UTF_8))), "25a849e95bb8e8b4bf");
    }

    /** encode -> decode round trip over representative payloads */
    @Test
    public void testEncodeDecodeRoundTrip() throws Exception {
        String[] samples = {
            "",
            "a",
            "www.example.com",
            "0123456789".repeat(20),
            "值中文多字节".repeat(10),
            "mixed ASCII \u00e9\u00fc and CJK \u4e2d\u6587",
            new String(java.util.Arrays.copyOf(new byte[512], 512), java.nio.charset.StandardCharsets.ISO_8859_1),
        };
        for (String s : samples) {
            byte[] raw = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] encoded = Huffman.encode(raw);
            Assert.assertEquals(Huffman.decode(encoded), s, "round trip failed for: " + s);
            Assert.assertEquals(Huffman.encodedLength(raw), encoded.length);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
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
