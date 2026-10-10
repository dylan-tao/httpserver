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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import com.sun.net.httpserver.Headers;

/**
 */
class Request {

    static final char CR = 13;
    static final char LF = 10;

    private final InputStream is;
    private final OutputStream os;

    private final String requestLine;
    /** the protocol token of the request line, cached to avoid a substring
     * allocation on every getProtocol() call */
    private final String protocol;

    Request(InputStream rawInputStream, OutputStream rawout) throws IOException {
        is = rawInputStream;
        os = rawout;

        requestLine = readRequestLine();
        int lastSpace = requestLine.lastIndexOf(' ');
        protocol = lastSpace >= 0 ? requestLine.substring(lastSpace + 1) : "";
    }

    public InputStream inputStream() {
        return is;
    }

    public OutputStream outputStream() {
        return os;
    }

    /** the protocol token from the request line, e.g. "HTTP/1.1" */
    public String protocol() {
        return protocol;
    }

    static class PushbackStream {
        private final InputStream is;
        private int pushback = -1;
        private boolean eof=false;

        public PushbackStream(InputStream is) {
            this.is = is;
        }
        public int read() throws IOException {
            if(pushback!=-1) {
                try {
                    return pushback;
                } finally {
                    pushback=-1;
                }
            }
            if(eof) return -1;
            return is.read();
        }
        public void skipWhitespace() throws IOException {
            int c;
            for(c=read();c==' ' || c=='\t';c=read()){}
            if(c==-1) eof=true; else pushback=c;
        }
    }

    /**
     * read a line from the stream returning as a String.
     * Not used for reading headers.
     */
    private boolean readLine(BufferedBuilder lineBuf) throws IOException {
        boolean gotCR = false;
        while (true) {
            int c;

            try {
                c = is.read();
            } catch(IOException e) {
                if(lineBuf.isEmpty()) return false;
                throw e;
            }

            if (c == -1) {
                lineBuf.count=0;
                return false;
            }
            if (lineBuf.count >= ServerConfig.getMaxReqLineSize()) {
                throw new IOException("request line too long (limit " + ServerConfig.getMaxReqLineSize() + " bytes)");
            }
            if (gotCR) {
                if (c == LF) {
                    return true;
                } else {
                    gotCR = false;
                    lineBuf.append(CR);
                    lineBuf.append((char)c);
                }
            } else {
                if (c == CR) {
                    gotCR = true;
                } else if (c == LF) {
                    // a bare LF terminates the line too (RFC 9112 2.2), matching
                    // the header parser's behaviour - without this, LF-only
                    // clients never get past the request line
                    return true;
                } else {
                    lineBuf.append((char)c);
                }
            }
        }
    }

    /**
     * read the request line into the buffer
     */
    private String readRequestLine() throws IOException {
        final BufferedBuilder bb = new BufferedBuilder(64);
        while(readLine(bb)) {
            if(!bb.isEmpty()) break;
        }
        return bb.trimmed();
    }

    /** return trimmed value from StringBuilder and reset to empty */

    /**
     * @returns the request line or the empty string if not found
     */
    public String requestLine() {
        return requestLine;
    }

    Headers hdrs = null;

    private static final class BufferedBuilder {
        private byte[] buffer;
        private int capacity;
        private int count;
        BufferedBuilder(int capacity) {
            buffer = new byte[capacity];
            this.capacity=capacity;
        }
        boolean isEmpty() {
            return count==0;
        }
        void append(char c) {
            if(count==capacity) {
                capacity *= 2;
                buffer = Arrays.copyOf(buffer, capacity);
            }
            buffer[count++]=(byte)c;
        }
        public String trimmed() {
            int start=0;
            while(start<count && buffer[start]==' ') start++;
            int end=count;
            while(end>0 && buffer[end-1]==' ') end--;
            count=0;
            return new String(buffer,start,end-start,StandardCharsets.ISO_8859_1);
        }
    }

    Headers headers() throws IOException {
        if (hdrs != null) {
            return hdrs;
        }
        hdrs = new OptimizedHeaders(16);

        BufferedBuilder key = new BufferedBuilder(32);
        BufferedBuilder value = new BufferedBuilder(128);

        PushbackStream pbs = new PushbackStream(is);

        BufferedBuilder current = key;
        boolean prevCR = false;
        boolean sol = true;
        int c;
        int headerCount = 0;

        while((c=pbs.read())!=-1) {
            if(c==CR) { prevCR = true; }
            else if(c==LF) {
                // a bare LF is accepted as a line terminator (RFC 9112 2.2);
                // a preceding CR (if any) is consumed as part of the CRLF
                if(key.isEmpty() && value.isEmpty()) break;
                if(sol) {
                    if (++headerCount > ServerConfig.getMaxReqHeaders()) {
                        throw new IOException("too many request headers (limit " + ServerConfig.getMaxReqHeaders() + ")");
                    }
                    hdrs.add(key.trimmed(), value.trimmed());
                    break;
                }
                prevCR=false;
                sol=true;
            } else {
                if(prevCR) {
                    // a CR not followed by LF is a malformed header line
                    throw new IOException("bare CR in header line");
                }
                if(sol && (c==' ' || c=='\t')) {
                    pbs.skipWhitespace();
                    current=value;
                    sol=false;
                } else {
                    if(sol) {
                        if(!key.isEmpty() || !value.isEmpty()) {
                            if (++headerCount > ServerConfig.getMaxReqHeaders()) {
                                throw new IOException("too many request headers (limit " + ServerConfig.getMaxReqHeaders() + ")");
                            }
                            hdrs.add(key.trimmed(), value.trimmed());
                        }
                        current=key;
                        sol=false;
                    }
                    if(c==':' && current==key) {
                        current=value;
                        pbs.skipWhitespace();
                    } else {
                        if (current.count >= ServerConfig.getMaxReqLineSize()) {
                            throw new IOException("header line too long (limit " + ServerConfig.getMaxReqLineSize() + " bytes)");
                        }
                        if(current==key) {
                            key.append((char)(current.count==0 ? Character.toUpperCase(c) : Character.toLowerCase(c)));
                        } else {
                            current.append((char)c);
                        }
                    }
                }
            }
        }
        return hdrs;
    }
}
