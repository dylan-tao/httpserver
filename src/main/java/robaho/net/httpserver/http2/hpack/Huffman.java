package robaho.net.httpserver.http2.hpack;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Arrays;

import robaho.net.httpserver.http2.HTTP2ErrorCode;
import robaho.net.httpserver.http2.HTTP2Exception;

/**
 * HPACK huffman decoding (RFC 7541 appendix B).
 *
 * The codes form a canonical prefix code, so decoding uses per-length
 * "first code / count / first index" tables: after consuming each bit, an
 * O(1) range check decides whether the accumulated bits are a code of that
 * length. No per-bit allocation is performed.
 */
public class Huffman {

    private static final int MAX_CODE_LENGTH = 30;
    private static final int EOS = 256;

    private static final class Tables {
        /** first code value of each length (index = length), or -1 if none */
        final long[] firstCode;
        /** number of codes of each length */
        final int[] count;
        /** index into symbols[] of the first code of each length */
        final int[] firstIndex;
        /** symbols (0..255, 256=EOS) ordered by (length, code) */
        final int[] symbols;

        Tables(long[] codes, int[] lengths) {
            int n = codes.length;
            Integer[] order = new Integer[n];
            for (int i = 0; i < n; i++) {
                order[i] = i;
            }
            // canonical order: by length, then by code value
            Arrays.sort(order, (a, b) -> lengths[a] != lengths[b]
                    ? lengths[a] - lengths[b]
                    : Long.compare(codes[a], codes[b]));

            int maxLen = MAX_CODE_LENGTH;
            firstCode = new long[maxLen + 1];
            count = new int[maxLen + 1];
            firstIndex = new int[maxLen + 1];
            Arrays.fill(firstCode, -1);

            symbols = new int[n];
            for (int pos = 0; pos < n; pos++) {
                int i = order[pos];
                int len = lengths[i];
                symbols[pos] = i;
                if (firstCode[len] == -1) {
                    firstCode[len] = codes[i];
                    firstIndex[len] = pos;
                }
                count[len]++;
            }
        }
    }

    private static final Tables TABLES = loadTables();

    private static Tables loadTables() {
        long[] codes = new long[257];
        int[] lengths = new int[257];
        int value = 0;

        ClassLoader classloader = Thread.currentThread().getContextClassLoader();
        if (classloader == null) {
            classloader = Huffman.class.getClassLoader();
        }
        try (InputStream is = classloader.getResourceAsStream("huffman_codes_rfc7541.txt");
             BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
            String line;
            while ((line = br.readLine()) != null) {
                // lines look like "  'w' (119)  |1111000   78  [ 7]" or "EOS (256)  |...  [30]".
                // locate the code group after the symbol index parenthesis - the
                // symbol itself may contain a '|' character (symbol 124). Each
                // group has only a LEADING '|' so the code ends at the next space.
                int closeParen = line.indexOf(')');
                if (closeParen == -1) {
                    continue; // not a code line
                }
                int start = line.indexOf('|', closeParen);
                if (start == -1) {
                    continue; // not a code line
                }
                int end = line.indexOf(' ', start);
                if (end == -1) {
                    end = line.length();
                }
                String code = line.substring(start, end).replace("|", "");
                if (code.isEmpty()) {
                    continue;
                }
                if (value >= codes.length) {
                    throw new IllegalStateException("too many huffman codes in resource file");
                }
                if (code.length() > MAX_CODE_LENGTH) {
                    throw new IllegalStateException("huffman code longer than " + MAX_CODE_LENGTH + " bits");
                }
                long c = 0;
                for (int i = 0; i < code.length(); i++) {
                    char ch = code.charAt(i);
                    if (ch != '0' && ch != '1') {
                        throw new IllegalStateException("invalid huffman code '" + code + "'");
                    }
                    c = (c << 1) | (ch - '0');
                }
                codes[value] = c;
                lengths[value] = code.length();
                value++;
            }
        } catch (IOException e) {
            throw new IllegalStateException("unable to load huffman code table", e);
        }
        if (value != codes.length) {
            throw new IllegalStateException("expected " + codes.length + " huffman codes, found " + value);
        }
        return new Tables(codes, lengths);
    }

    public static String decode(byte[] value) throws HTTP2Exception {
        Tables t = TABLES;
        // RFC 7541 5.2: the decoded symbol sequence is the UTF-8 encoding of
        // the header value - collect raw bytes, then convert once (decoding
        // symbol-by-symbol into a String would corrupt multi byte sequences).
        // the shortest code is 5 bits, so the decoded output can be LARGER
        // than the input in bytes (e.g. 96 input bits -> 15+ symbols)
        byte[] decoded = new byte[value.length * 8 / 5 + 2];
        int pos = 0;

        long acc = 0;
        int bits = 0;

        for (int i = 0; i < value.length; i++) {
            int b = value[i] & 0xFF;
            for (int j = 7; j >= 0; j--) {
                acc = (acc << 1) | ((b >> j) & 1);
                bits++;

                if (bits > MAX_CODE_LENGTH) {
                    throw new HTTP2Exception(HTTP2ErrorCode.COMPRESSION_ERROR, "invalid huffman code");
                }

                long base = t.firstCode[bits];
                if (base != -1) {
                    long offset = acc - base;
                    if (offset >= 0 && offset < t.count[bits]) {
                        int symbol = t.symbols[t.firstIndex[bits] + (int) offset];
                        if (symbol == EOS) {
                            throw new HTTP2Exception(HTTP2ErrorCode.COMPRESSION_ERROR, "decoded data contains EOS");
                        }
                        decoded[pos++] = (byte) symbol;
                        acc = 0;
                        bits = 0;
                    }
                }
            }
        }

        // any remaining bits must be padding: fewer than 8 bits, all ones
        if (bits > 7 || (bits > 0 && acc != (1L << bits) - 1)) {
            throw new HTTP2Exception(HTTP2ErrorCode.COMPRESSION_ERROR, "decoded data has incorrect padding");
        }

        return new String(decoded, 0, pos, java.nio.charset.StandardCharsets.UTF_8);
    }
}
