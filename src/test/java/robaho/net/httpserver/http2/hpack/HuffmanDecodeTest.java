package robaho.net.httpserver.http2.hpack;

import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.testng.Assert.assertEquals;

/** differential tests for the table-driven Huffman decoder against the
 * reference per-bit implementation, plus encode/decode round-trips */
public class HuffmanDecodeTest {

    @Test
    public void testRoundTripAllLengths() throws Exception {
        for (int len = 0; len <= 64; len++) {
            byte[] raw = new byte[len];
            new Random(len).nextBytes(raw);
            byte[] enc = Huffman.encode(raw);
            assertEquals(Huffman.decode(enc, 0, enc.length),
                new String(raw, StandardCharsets.UTF_8), "len=" + len);
        }
    }

    @Test
    public void testRoundTripEverySymbol() throws Exception {
        // every symbol value 0..255, in one stream and singly
        byte[] all = new byte[256];
        for (int i = 0; i < 256; i++) {
            all[i] = (byte) i;
        }
        byte[] enc = Huffman.encode(all);
        assertEquals(Huffman.decode(enc, 0, enc.length),
            new String(all, StandardCharsets.UTF_8));
        for (int sym = 0; sym < 256; sym++) {
            byte[] one = { (byte) sym };
            byte[] e = Huffman.encode(one);
            assertEquals(Huffman.decode(e, 0, e.length),
                new String(one, StandardCharsets.UTF_8), "sym=" + sym);
        }
    }

    @Test
    public void testTableMatchesBitwiseReference() throws Exception {
        // random PLAIN bytes -> encode -> both decoders must agree; the
        // encoded stream is always legal, so no error-path divergence
        Random r = new Random(1234);
        for (int trial = 0; trial < 2000; trial++) {
            int len = r.nextInt(64);
            byte[] raw = new byte[len];
            r.nextBytes(raw);
            byte[] enc = Huffman.encode(raw);
            String fast = Huffman.decode(enc, 0, enc.length);
            String ref = Huffman.decodeBitwise(enc, 0, enc.length);
            assertEquals(fast, ref, "trial=" + trial + " len=" + len);
        }
    }

    @Test
    public void testOffsetRegionsMatch() throws Exception {
        // decode(offset,length) slices: encode two values, decode each
        // region with both decoders
        byte[] a = Huffman.encode("www.example.com".getBytes(StandardCharsets.UTF_8));
        byte[] b = Huffman.encode("no-cache".getBytes(StandardCharsets.UTF_8));
        byte[] both = new byte[a.length + b.length];
        System.arraycopy(a, 0, both, 0, a.length);
        System.arraycopy(b, 0, both, a.length, b.length);
        assertEquals(Huffman.decode(both, 0, a.length), "www.example.com");
        assertEquals(Huffman.decode(both, a.length, b.length), "no-cache");
        assertEquals(Huffman.decodeBitwise(both, 0, a.length), "www.example.com");
        assertEquals(Huffman.decodeBitwise(both, a.length, b.length), "no-cache");
    }

    @Test(expectedExceptions = robaho.net.httpserver.http2.Http2Exception.class)
    public void testRejectsZeroPaddingLongerThan7Bits() throws Exception {
        // a full byte of one-bits mid-stream must eventually be rejected:
        // appending garbage (random bits) after a legal stream breaks the
        // padding rule or hits an invalid code
        byte[] enc = Huffman.encode("x".getBytes(StandardCharsets.UTF_8));
        byte[] bad = new byte[enc.length + 1];
        System.arraycopy(enc, 0, bad, 0, enc.length);
        bad[bad.length - 1] = (byte) 0xA5; // non-padding tail
        try {
            Huffman.decode(bad, 0, bad.length);
        } catch (robaho.net.httpserver.http2.Http2Exception e) {
            // the bitwise reference must reject the same input
            Huffman.decodeBitwise(bad, 0, bad.length);
            throw e;
        }
    }

    @Test(expectedExceptions = robaho.net.httpserver.http2.Http2Exception.class)
    public void testBothDecodersRejectEOS() throws Exception {
        // 30 one-bits = EOS; a stream of four 0xFF bytes plus 0xFF tail hits
        // it (32 bits of ones > 30)
        byte[] eosish = { (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF };
        try {
            Huffman.decode(eosish, 0, 4);
            throw new AssertionError("expected rejection");
        } catch (robaho.net.httpserver.http2.Http2Exception e) {
            Huffman.decodeBitwise(eosish, 0, 4);
            throw e;
        }
    }
}
