package robaho.net.httpserver.http2.hpack;

import robaho.net.httpserver.http2.Http2ErrorCode;
import robaho.net.httpserver.http2.Http2Exception;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Arrays;

/**
 * HPACK huffman codec (RFC 7541 appendix B).
 * The codes form a canonical prefix code, so decoding uses per-length "first code / count / first index" tables: after
 * consuming each bit, an O(1) range check decides whether the accumulated bits are a code of that length. No per-bit
 * allocation is performed.
 * Encoding uses the per-symbol code/length arrays parsed from the same resource: bits are accumulated and emitted
 * byte-wise, with EOS-prefix (ones) padding on the final partial byte as the RFC requires.
 */
public class Huffman {

  private static final int MAX_CODE_LENGTH = 30;

  private static final int EOS = 256;

  /** per-symbol code values (index = symbol 0..255) for encoding */
  private static final long[] CODES = new long[256];

  /** per-symbol code lengths (index = symbol 0..255) for encoding */
  private static final int[] LENGTHS = new int[256];

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
      Arrays.sort(order, (a, b) -> lengths[a] != lengths[b] ? lengths[a] - lengths[b] : Long.compare(codes[a], codes[b]));

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
    try (InputStream is = classloader.getResourceAsStream("huffman_codes_rfc7541.txt"); BufferedReader br =
        new BufferedReader(new InputStreamReader(is, java.nio.charset.StandardCharsets.US_ASCII))) {
      String line;
      while ((line = br.readLine()) != null) {
        // lines look like " 'w' (119) |1111000 78 [ 7]" or "EOS (256) |... [30]".
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
        if (value < 256) {
          CODES[value] = c;
          LENGTHS[value] = code.length();
        }
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

  public static String decode(byte[] value) throws Http2Exception {
    return decode(value, 0, value.length);
  }

  /**
   * byte-driven decode table: RFC 7541's shortest code is 5 bits, so one input byte completes at most ONE symbol -
   * every (state, byte) pair resolves to an optional symbol plus the next state in a single flat lookup. the state
   * space is the internal nodes of the canonical code tree (a full binary tree has internal = leaves - 1 = 256), so
   * the whole table is two flat arrays of 256x256 entries (~190KB, built once). this replaces the per-bit
   * shift/range-check loop that measured ~8ns/byte with a single array access per byte
   */
  private static final short[] DECODE_SYM;

  /**
   * second symbol completed by the same (state, byte) entry, or -1. walking one byte from a NON-ROOT state can
   * complete TWO symbols: the first code finishes early (the state already held its prefix bits), the walk returns to
   * the root, and up to 7 remaining bits can complete one more 5-bit code. a third is impossible: after the second
   * code at most 2 bits remain. short, not byte: second codes are always <= 7-bit codes (ASCII today), but a byte
   * would silently truncate any symbol >= 128 into the negative sentinel space
   */
  private static final short[] DECODE_SYM2;

  private static final byte[] DECODE_NEXT;

  /**
   * states reachable by walking 1..7 one-bits from the root: the only legal padding positions (the EOS code is 30
   * one-bits, so any shorter all-ones prefix is an internal node)
   */
  private static final boolean[] PADDING_OK;

  /** sentinel in DECODE_SYM: the walk hit the EOS symbol */
  private static final short EOS_ERR = -2;

  /**
   * sentinel in DECODE_SYM: the walk fell into a tree hole - the RFC 7541 code set is NOT a complete tree (Kraft sum
   * < 1: some bit sequences are no code's prefix), and the bitwise reference rejects those as bits > MAX_CODE_LENGTH
   */
  private static final short INVALID_ERR = -3;

  static {
    // build the canonical code tree: child arrays indexed by node id,
    // internal nodes carry the next node id, leaves carry -(symbol+1)
    int[] child0 = new int[512];
    int[] child1 = new int[512];
    java.util.Arrays.fill(child0, -1);
    java.util.Arrays.fill(child1, -1);
    int nodeCount = 1; // node 0 = root
    for (int sym = 0; sym < 257; sym++) {
      long code = sym == 256 ? (1L << 30) - 1 : CODES[sym];
      int len = sym == 256 ? 30 : LENGTHS[sym];
      int cur = 0;
      for (int bit = len - 1; bit >= 0; bit--) {
        int[] child = ((code >>> bit) & 1) == 0 ? child0 : child1;
        if (bit == 0) {
          if (child[cur] != -1) {
            throw new IllegalStateException("huffman prefix collision");
          }
          // leaf marker -(sym+2): -(0+1) would be -1, the SAME
          // value as the unallocated sentinel and symbol 0's leaf
          // would be indistinguishable from a tree hole
          child[cur] = -(sym + 2);
        } else {
          if (child[cur] == -1) {
            if (nodeCount == child0.length) {
              child0 = java.util.Arrays.copyOf(child0, child0.length * 2);
              child1 = java.util.Arrays.copyOf(child1, child1.length * 2);
            }
            child[cur] = nodeCount;
            child0[nodeCount] = -1;
            child1[nodeCount] = -1;
            nodeCount++;
          }
          cur = child[cur];
        }
      }
    }
    // a full binary tree has internal = leaves - 1 = 256 nodes, so the
    // state space fits the fixed tables exactly
    if (nodeCount > 256) {
      throw new IllegalStateException("huffman tree has " + nodeCount + " states");
    }
    DECODE_SYM = new short[256 * 256];
    DECODE_SYM2 = new short[256 * 256];
    DECODE_NEXT = new byte[256 * 256];
    boolean[] padding = new boolean[256];
    padding[0] = true; // fully consumed: zero padding bits
    buildTables(child0, child1, nodeCount, padding);
    PADDING_OK = padding;
  }

  /**
   * walk every (state, byte) combination: from the state's node, follow the byte's 8 bits; the FIRST completed leaf
   * (if any, at most one since the shortest code is 5 bits) is the emitted symbol and the walk continues from the
   * root for the remaining bits; the final node is the next state
   */
  private static void buildTables(int[] child0, int[] child1, int nodeCount, boolean[] padding) {
    for (int state = 0; state < nodeCount; state++) {
      for (int b = 0; b < 256; b++) {
        int cur = state;
        short sym1 = -1;
        short sym2 = -1;
        for (int k = 7; k >= 0; k--) {
          int bit = (b >>> k) & 1;
          int[] child = bit == 0 ? child0 : child1;
          int next = child[cur];
          if (next == -1) {
            // a tree hole: this bit sequence is no code's prefix
            // (the RFC 7541 code set is incomplete by design).
            // a hole prefix always contains a 0 bit, so it can
            // never be legal end-of-stream padding - reject the
            // whole entry
            if (sym1 == -1) {
              sym1 = INVALID_ERR;
            } else {
              sym2 = INVALID_ERR;
            }
            cur = 0;
            break;
          }
          if (next <= -2) {
            int symbol = -next - 2;
            if (symbol == 256) {
              if (sym1 == -1) {
                sym1 = EOS_ERR;
              } else {
                sym2 = EOS_ERR;
              }
            } else if (sym1 == -1) {
              sym1 = (short) symbol;
            } else {
              sym2 = (short) symbol;
            }
            cur = 0; // continue from the root
          } else {
            cur = next;
          }
        }
        DECODE_SYM[state * 256 + b] = sym1;
        DECODE_SYM2[state * 256 + b] = sym2;
        DECODE_NEXT[state * 256 + b] = (byte) cur;
      }
    }
    // padding positions: walk 1..7 one-bits from the root
    int cur = 0;
    for (int depth = 1; depth <= 7; depth++) {
      int next = child1[cur];
      if (next <= 0) {
        break; // a leaf within 7 one-bits: not a legal padding state
      }
      cur = next;
      padding[cur] = true;
    }
  }

  /**
   * decode a region of the header block in place: the caller has already validated the bounds, so the previous
   * copyOfRange per name/value was a duplicate allocation
   */
  public static String decode(byte[] value, int offset, int length) throws Http2Exception {
    // RFC 7541 5.2: the decoded symbol sequence is the UTF-8 encoding of
    // the header value - collect raw bytes, then convert once (decoding
    // symbol-by-symbol into a String would corrupt multi byte sequences).
    // the shortest code is 5 bits, so the decoded output can be LARGER
    // than the input in bytes (e.g. 96 input bits -> 15+ symbols)
    byte[] decoded = new byte[length * 8 / 5 + 2];
    int pos = 0;
    int state = 0;
    for (int i = offset; i < offset + length; i++) {
      int idx = (state << 8) | (value[i] & 0xFF);
      int sym = DECODE_SYM[idx];
      if (sym >= 0) {
        decoded[pos++] = (byte) sym;
        // the same byte may complete a SECOND code (the state held
        // the first code's prefix bits): at most one more fits
        int sym2 = DECODE_SYM2[idx];
        if (sym2 >= 0) {
          decoded[pos++] = (byte) sym2;
        } else if (sym2 == EOS_ERR) {
          throw new Http2Exception(Http2ErrorCode.COMPRESSION_ERROR, "decoded data contains EOS");
        } else if (sym2 == INVALID_ERR) {
          throw new Http2Exception(Http2ErrorCode.COMPRESSION_ERROR, "invalid huffman code");
        }
      } else if (sym == EOS_ERR) {
        throw new Http2Exception(Http2ErrorCode.COMPRESSION_ERROR, "decoded data contains EOS");
      } else if (sym == INVALID_ERR) {
        throw new Http2Exception(Http2ErrorCode.COMPRESSION_ERROR, "invalid huffman code");
      }
      state = DECODE_NEXT[idx] & 0xFF;
    }
    // remaining bits must be all-ones padding of fewer than 8 bits: the
    // state itself records how deep into the all-ones path we are
    if (!PADDING_OK[state]) {
      throw new Http2Exception(Http2ErrorCode.COMPRESSION_ERROR, "decoded data has incorrect padding");
    }

    return new String(decoded, 0, pos, java.nio.charset.StandardCharsets.UTF_8);
  }

  /**
   * reference per-bit decoder kept for differential testing against the table-driven path (HuffmanDecodeTest)
   */
  static String decodeBitwise(byte[] value, int offset, int length) throws Http2Exception {
    Tables t = TABLES;
    byte[] decoded = new byte[length * 8 / 5 + 2];
    int pos = 0;
    long acc = 0;
    int bits = 0;
    for (int i = offset; i < offset + length; i++) {
      int b = value[i] & 0xFF;
      for (int j = 7; j >= 0; j--) {
        acc = (acc << 1) | ((b >> j) & 1);
        bits++;
        if (bits > MAX_CODE_LENGTH) {
          throw new Http2Exception(Http2ErrorCode.COMPRESSION_ERROR, "invalid huffman code");
        }
        long base = t.firstCode[bits];
        if (base != -1) {
          long codeOffset = acc - base;
          if (codeOffset >= 0 && codeOffset < t.count[bits]) {
            int symbol = t.symbols[t.firstIndex[bits] + (int) codeOffset];
            if (symbol == EOS) {
              throw new Http2Exception(Http2ErrorCode.COMPRESSION_ERROR, "decoded data contains EOS");
            }
            decoded[pos++] = (byte) symbol;
            acc = 0;
            bits = 0;
          }
        }
      }
    }
    if (bits > 7 || (bits > 0 && acc != (1L << bits) - 1)) {
      throw new Http2Exception(Http2ErrorCode.COMPRESSION_ERROR, "decoded data has incorrect padding");
    }
    return new String(decoded, 0, pos, java.nio.charset.StandardCharsets.UTF_8);
  }

  /**
   * the huffman-encoded byte length of value, without encoding it (used for the shorter-of-literal choice in HPACK
   * string literals)
   */
  public static int encodedLength(byte[] value) {
    long bits = 0;
    for (byte b : value) {
      bits += LENGTHS[b & 0xFF];
    }
    return (int) ((bits + 7) >>> 3);
  }

  /** huffman-encode value into a fresh array */
  public static byte[] encode(byte[] value) {
    byte[] out = new byte[encodedLength(value)];
    encodeInto(value, out, 0);
    return out;
  }

  /**
   * huffman-encode value directly into out at pos (zero allocation), returning the new position. the final partial
   * byte is padded with the EOS prefix (ones) as RFC 7541 5.2 requires
   */
  public static int encodeInto(byte[] value, byte[] out, int pos) {
    long acc = 0;
    int nbits = 0;
    for (byte b : value) {
      int s = b & 0xFF;
      acc = (acc << LENGTHS[s]) | CODES[s];
      nbits += LENGTHS[s];
      while (nbits >= 8) {
        out[pos++] = (byte) (acc >>> (nbits - 8));
        nbits -= 8;
      }
      // keep the accumulator bounded: at most 7 bits remain here, so
      // the next symbol's shift stays far below 64 bits
      acc &= (1L << nbits) - 1;
    }
    if (nbits > 0) {
      out[pos++] = (byte) ((acc << (8 - nbits)) | ((1 << (8 - nbits)) - 1));
    }
    return pos;
  }

}
