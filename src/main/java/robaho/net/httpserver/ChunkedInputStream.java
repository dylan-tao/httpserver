/*
 * Copyright (c) 2005, 2017, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package robaho.net.httpserver;

import java.io.IOException;
import java.io.InputStream;

class ChunkedInputStream extends LeftOverInputStream {
    ChunkedInputStream(ExchangeImpl t, InputStream src) {
        super(t, src);
    }

    private int remaining;

    /* true when a chunk header needs to be read */

    private boolean needToReadHeader = true;

    static final char CR = '\r';
    static final char LF = '\n';
    /*
     * Maximum chunk header size of 2KB + 2 bytes for CRLF
     */
    private static final int MAX_CHUNK_HEADER_SIZE = 2050;

    private int numeric(char[] arr, int nchars) throws IOException {
        assert arr.length >= nchars;
        // accumulate in a long and reject overflow: without this, a 15 digit
        // hex chunk length silently overflows int and produces a negative
        // remaining count (IndexOutOfBoundsException instead of a clean error)
        long len = 0;
        for (int i = 0; i < nchars; i++) {
            char c = arr[i];
            int val;
            if (c >= '0' && c <= '9') {
                val = c - '0';
            } else if (c >= 'a' && c <= 'f') {
                val = c - 'a' + 10;
            } else if (c >= 'A' && c <= 'F') {
                val = c - 'A' + 10;
            } else {
                throw new IOException("invalid chunk length");
            }
            len = len * 16 + val;
            if (len > Integer.MAX_VALUE) {
                throw new IOException("invalid chunk length (overflow)");
            }
        }
        return (int) len;
    }

    /*
     * read the chunk header line and return the chunk length
     * any chunk extensions are ignored
     */
    private int readChunkHeader() throws IOException {
        boolean gotCR = false;
        int c;
        char[] lenArr = new char[16];
        int lenSize = 0;
        boolean endOfLen = false;
        int read = 0;

        while ((c = in.read()) != -1) {
            char ch = (char) c;
            read++;
            if ((lenSize == lenArr.length - 1) ||
                    (read > MAX_CHUNK_HEADER_SIZE)) {
                throw new IOException("invalid chunk header");
            }
            if (gotCR) {
                if (ch == LF) {
                    return numeric(lenArr, lenSize);
                } else {
                    gotCR = false;
                }
                if (!endOfLen) {
                    lenArr[lenSize++] = ch;
                }
            } else {
                if (ch == CR) {
                    gotCR = true;
                } else if (ch == ';') {
                    endOfLen = true;
                } else if (!endOfLen) {
                    lenArr[lenSize++] = ch;
                }
            }
        }
        throw new IOException("end of stream reading chunk header");
    }

    protected int readImpl(byte[] b, int off, int len) throws IOException {
        if (eof) {
            return -1;
        }
        if (needToReadHeader) {
            remaining = readChunkHeader();
            if (remaining == 0) {
                eof = true;
                consumeTrailer();
                return -1;
            }
            needToReadHeader = false;
        }
        if (len > remaining) {
            len = remaining;
        }
        int n = in.read(b, off, len);
        if (n > -1) {
            remaining -= n;
        }
        if (remaining == 0) {
            needToReadHeader = true;
            consumeCRLF();
        }
        if (n < 0)
            throw new IOException("connection closed before all data received");
        return n;
    }

    private void consumeCRLF() throws IOException {
        char c;
        c = (char) in.read(); /* CR */
        if (c != CR) {
            throw new IOException("invalid chunk end");
        }
        c = (char) in.read(); /* LF */
        if (c != LF) {
            throw new IOException("invalid chunk end");
        }
    }

    /**
     * consume the line following the final zero-length chunk: either a plain
     * CRLF, or trailer field lines terminated by an empty line
     * (RFC 7230 section 4.1.2). The trailer contents are discarded.
     */
    private void consumeTrailer() throws IOException {
        int maxTrailerLines = 100;
        while (maxTrailerLines-- > 0) {
            boolean sawCR = false;
            boolean emptyLine = true;
            int c;
            while ((c = in.read()) != -1) {
                if (sawCR) {
                    if (c == LF) {
                        break; /* end of line */
                    }
                    throw new IOException("bare CR in chunk trailer");
                }
                if (c == CR) {
                    sawCR = true;
                } else if (c == LF) {
                    break; /* bare LF accepted as line terminator */
                } else {
                    emptyLine = false;
                }
            }
            if (c == -1) {
                throw new IOException("end of stream reading chunk trailer");
            }
            if (emptyLine) {
                return; /* end of trailers (covers the plain CRLF case) */
            }
        }
        throw new IOException("too many chunk trailer lines");
    }

    /**
     * returns the number of bytes available to read in the current chunk
     * which may be less than the real amount, but we'll live with that
     * limitation for the moment. It only affects potential efficiency
     * rather than correctness.
     */
    @Override
    public int available() throws IOException {
        if (eof || closed) {
            return 0;
        }
        int n = in.available();
        return n > remaining ? remaining : n;
    }
}
