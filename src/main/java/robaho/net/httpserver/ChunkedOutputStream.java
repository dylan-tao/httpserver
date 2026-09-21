/*
 * Copyright (c) 2005, 2023, Oracle and/or its affiliates. All rights reserved.
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

import java.io.*;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * a class which allows the caller to write an arbitrary
 * number of bytes to an underlying stream.
 * normal close() does not close the underlying stream
 *
 * This class is buffered.
 *
 * Each chunk is written in one go as :-
 * abcd\r\nxxxxxxxxxxxxxx\r\n
 *
 * abcd is the chunk-size, and xxx is the chunk data
 * If the length is less than 4 chars (in size) then the buffer
 * is written with an offset.
 * Final chunk is:
 * 0\r\n\r\n
 */

class ChunkedOutputStream extends FilterOutputStream {
    private boolean closed = false;
    /* max. amount of user data per chunk */
    static final int CHUNK_SIZE = 4096;
    /* allow 4 bytes for chunk-size plus 4 for CRLFs */
    static final int OFFSET = 6; /* initial <=4 bytes for len + CRLF */
    private int pos = OFFSET;
    private int count = 0;
    final private byte[] buf = new byte[CHUNK_SIZE + OFFSET + 2];
    ExchangeImpl t;

    /* header of a full size chunk, for the zero copy large write path */
    private static final byte[] FULL_CHUNK_HEADER =
            (Integer.toHexString(CHUNK_SIZE) + "\r\n").getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] CRNL_BYTES = "\r\n".getBytes(StandardCharsets.ISO_8859_1);

    ChunkedOutputStream(ExchangeImpl t, OutputStream src) {
        super(src);
        this.t = t;
    }

    public void write(int b) throws IOException {
        if (closed) {
            throw new StreamClosedException();
        }
        buf[pos++] = (byte) b;
        count++;
        if (count == CHUNK_SIZE) {
            writeChunk();
        }
        assert count < CHUNK_SIZE;
    }

    public void write(byte[] b, int off, int len) throws IOException {
        Objects.checkFromIndexSize(off, len, b.length);
        if (len == 0) {
            return;
        }
        if (closed) {
            throw new StreamClosedException();
        }
        int remain = CHUNK_SIZE - count;
        if (len > remain) {
            System.arraycopy(b, off, buf, pos, remain);
            count = CHUNK_SIZE;
            writeChunk();
            len -= remain;
            off += remain;
            while (len >= CHUNK_SIZE) {
                // full size chunks are written directly from the caller's
                // buffer, avoiding a copy through the internal buffer
                out.write(FULL_CHUNK_HEADER);
                out.write(b, off, CHUNK_SIZE);
                out.write(CRNL_BYTES);
                len -= CHUNK_SIZE;
                off += CHUNK_SIZE;
            }
        }
        if (len > 0) {
            System.arraycopy(b, off, buf, pos, len);
            count += len;
            pos += len;
        }
        if (count == CHUNK_SIZE) {
            writeChunk();
        }
    }

    /**
     * write out a chunk , and reset the pointers
     * chunk does not have to be CHUNK_SIZE bytes
     * count must == number of user bytes (<= CHUNK_SIZE)
     */
    private void writeChunk() throws IOException {
        char[] c = Integer.toHexString(count).toCharArray();
        int clen = c.length;
        int startByte = 4 - clen;
        int i;
        for (i = 0; i < clen; i++) {
            buf[startByte + i] = (byte) c[i];
        }
        buf[startByte + (i++)] = '\r';
        buf[startByte + (i++)] = '\n';
        buf[startByte + (i++) + count] = '\r';
        buf[startByte + (i++) + count] = '\n';
        out.write(buf, startByte, i + count);
        count = 0;
        pos = OFFSET;
    }

    public void close() throws IOException {
        if (closed) {
            return;
        }
        try {
            if (count > 0) {
                writeChunk();
            }
            /* write an empty chunk */
            writeChunk();
            out.flush();
            /* some clients close the connection before empty chunk is sent */
        } catch (IOException e) {
            // make the failure visible instead of silently swallowing it
            t.getServerImpl().getLogger().log(Level.DEBUG, "error writing final chunk", e);
        } finally {
            closed = true;
            LeftOverInputStream is = t.getOriginalInputStream();
            if (is != null && !is.isClosed()) {
                try {
                    is.close();
                } catch (IOException e) {
                    t.getServerImpl().getLogger().log(Level.DEBUG, "error draining request stream", e);
                }
            }
        }
    }

    public void flush() throws IOException {
        if (closed) {
            throw new StreamClosedException();
        }
        if (count > 0) {
            writeChunk();
        }
        out.flush();
    }
}
