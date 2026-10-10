package robaho.net.httpserver.extras;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import robaho.net.httpserver.NoSyncBufferedInputStream;
import robaho.net.httpserver.NoSyncBufferedOutputStream;

/**
 * parse multipart form data
 */
public class MultipartFormParser {
    static final Logger logger = Logger.getLogger("robaho.net.httpserver.MultipartFormParser");

    // maximum size in bytes of a non-file form field - protection against memory exhaustion
    private static final int MAX_FIELD_SIZE = Integer.getInteger("robaho.net.httpserver.max_multipart_field_bytes", 1024 * 1024); // 0 == no limit

    /**
     * a multipart part.
     *
     * either data or file will be non-null, but not both.
     *
     * @param contentType if non-null, the content type of the data
     * @param filename if non-null, the form provided filename
     * @param file if non-null, points to the uploaded file data (the name may
     * differ from filename). The storage directory's contents are deleted
     * best-effort at JVM exit.
     * @param data if non-null, contains the part data as a String.
     */
    public record Part(String contentType, String filename, String data, File file) {

    }

    /** parser-created files pending best-effort exit cleanup. bounded: the
     * registry must not grow without limit (the File.deleteOnExit() leak this
     * replaces grew ~100-200B per upload forever); beyond the cap the oldest
     * entries are dropped and their files simply survive. only files the
     * PARSER created are tracked - a shared storage directory's other
     * contents are never touched */
    private static final java.util.concurrent.ConcurrentLinkedQueue<Path> CREATED_FILES = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static final java.util.concurrent.atomic.AtomicInteger trackedFileCount = new java.util.concurrent.atomic.AtomicInteger();
    private static final int MAX_TRACKED_FILES = 10_000;
    private static final java.util.concurrent.atomic.AtomicBoolean CLEANUP_HOOK_REGISTERED = new java.util.concurrent.atomic.AtomicBoolean();

    private static void trackForExitCleanup(File file) {
        if (CLEANUP_HOOK_REGISTERED.compareAndSet(false, true)) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                Path p;
                while ((p = CREATED_FILES.poll()) != null) {
                    try {
                        java.nio.file.Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // best effort: a locked file simply survives
                    }
                }
            }, "multipart-file-cleanup"));
        }
        if (trackedFileCount.incrementAndGet() <= MAX_TRACKED_FILES) {
            CREATED_FILES.offer(file.toPath());
        } else {
            trackedFileCount.decrementAndGet();
        }
    }

    private record PartMetadata(String contentType, String name, String filename) {

    }

    /**
     * parse a multipart input stream, write files to storage. The caller is
     * responsible to delete files when they are no longer needed.
     *
     * @return a map of key to either a String (non-file) or a File
     */
    public static Map<String, List<Part>> parse(String encoding, String content_type, InputStream is, Path storage) throws IOException {
        Charset charset = encoding == null ? StandardCharsets.ISO_8859_1 : Charset.forName(encoding);

        if (!content_type.contains("boundary=")) {
            throw new IllegalStateException("content type does not contain boundary");
        }

        String boundary = parseBoundary(content_type);

        // an unsynchronized buffered stream: the parser reads single bytes, and
        // BufferedInputStream.read() is synchronized per byte
        // the configured connection buffer size: this wrap exists to amortize
        // the LeftOver lock across single-byte reads, so it follows the same
        // sizing knob as the connection buffers
        is = new NoSyncBufferedInputStream(is, robaho.net.httpserver.ServerConfig.bufferSize());

        Map<String, List<Part>> results = new HashMap<>();

        // the CRLF is considered part of the boundary
        byte[] boundaryCheck = ("\r\n--" + boundary).getBytes(charset);

        List<String> headers = new LinkedList<>();

        logger.finer(() -> "reading multipart form data with boundary '%s'".formatted(boundary));

        // read until boundary found
        int matchCount = 2; // starting at 2 allows matching non-compliant senders. rfc says CRLF is part of
                            // boundary marker
        while (true) {
            int c = is.read();
            if (c == -1) {
                return results;
            }
            if (c == boundaryCheck[matchCount]) {
                matchCount++;
                if (matchCount == boundaryCheck.length) {
                    break;
                }
            } else {
                matchCount = 0;
                if (c == boundaryCheck[matchCount]) {
                    matchCount++;
                }
            }
        }

        // read to end of line
        String s = readLine(charset, is);
        if (s == null || "--".equals(s)) {
            return results;
        }

        while (true) {
            // read part headers until blank line
            headers.clear();

            while (true) {
                s = readLine(charset, is);
                if (s == null) {
                    return results;
                }
                if ("".equals(s)) {
                    break;
                }
                headers.add(s);
            }

            // read part data - need to detect end of part
            PartMetadata meta = parseHeaders(headers);

            Runnable addToResults;
            OutputStream os;
            // an empty filename is what browsers send for a file input that was
            // left empty: treat it as a plain field instead of crashing the
            // whole upload in sanitizeFilename
            if (meta.filename == null || meta.filename.isEmpty()) {
                var bos = new BoundedOutputStream(MAX_FIELD_SIZE);
                os = bos;
                addToResults = () -> results.computeIfAbsent(meta.name, k -> new LinkedList<Part>()).add(new Part(meta.contentType, null, bos.toString(charset), null));
            } else {
                File file = Path.of(storage.toString(), sanitizeFilename(meta.filename)).toFile();
                trackForExitCleanup(file);
                os = new NoSyncBufferedOutputStream(new FileOutputStream(file));
                addToResults = () -> results.computeIfAbsent(meta.name, k -> new LinkedList<Part>()).add(new Part(meta.contentType, meta.filename, null, file));
            }

            try (os) {
                matchCount = 0;
                while (true) {
                    int c = is.read();
                    if (c == -1) {
                        return results;
                    }
                    if (c == boundaryCheck[matchCount]) {
                        matchCount++;
                        if (matchCount == boundaryCheck.length) {
                            break;
                        }
                    } else {
                        if (matchCount > 0) {
                            os.write(boundaryCheck, 0, matchCount);
                            matchCount = 0;
                        }
                        if (c == boundaryCheck[matchCount]) {
                            matchCount++;
                        } else {
                            os.write(c);
                        }
                    }
                }
            }

            addToResults.run();

            // read to end of line
            s = readLine(charset, is);
            if ("--".equals(s)) {
                return results;
            }
        }
    }

    private static final Pattern optionPattern = Pattern.compile("\\s(?<key>.*)=\"(?<value>.*)\"");

    /**
     * extract the boundary parameter from a Content-Type header value,
     * handling quoted values and parameters following it
     * (RFC 2046 section 5.1.1)
     */
    private static String parseBoundary(String contentType) {
        int idx = contentType.indexOf("boundary=");
        if (idx < 0) {
            throw new IllegalStateException("content type does not contain boundary");
        }
        int start = idx + "boundary=".length();
        boolean quoted = start < contentType.length() && contentType.charAt(start) == '"';
        if (quoted) {
            start++;
        }
        int end = start;
        while (end < contentType.length()) {
            char c = contentType.charAt(end);
            if (quoted) {
                if (c == '"') {
                    break;
                }
            } else if (c == ';' || c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                break;
            }
            end++;
        }
        String boundary = contentType.substring(start, end);
        if (boundary.isEmpty()) {
            throw new IllegalStateException("empty boundary parameter in content type");
        }
        return boundary;
    }

    /**
     * sanitize a client provided filename: the stored file uses only the final
     * path component, so a malicious Content-Disposition cannot write outside
     * the storage directory. The original filename is still reported in
     * {@link Part#filename}.
     */
    private static String sanitizeFilename(String filename) {
        if (filename == null) {
            throw new IllegalArgumentException("null filename in content-disposition");
        }
        // normalize separators so both unix and windows style paths are handled
        String name = filename.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        if (name.isEmpty() || name.equals(".") || name.equals("..")) {
            throw new IllegalArgumentException("illegal filename in content-disposition: " + filename);
        }
        return name;
    }

    private static PartMetadata parseHeaders(List<String> headers) {
        String name = null;
        String filename = null;
        String contentType = null;
        for (var header : headers) {
            String[] parts = header.split(":", 2);
            if (parts.length < 2) {
                // malformed part header line without a colon: skip it rather
                // than failing the whole upload with an ArrayIndexOutOfBoundsException
                logger.finer(() -> "ignoring malformed multipart part header '%s'".formatted(header));
                continue;
            }
            if ("content-disposition".equalsIgnoreCase(parts[0])) {
                String[] options = parts[1].split(";");
                for (var option : options) {
                    Matcher m = optionPattern.matcher(option);
                    if (m.matches()) {
                        String key = m.group("key");
                        String value = m.group("value");
                        if ("name".equals(key)) {
                            name = value;
                        }
                        if ("filename".equals(key)) {
                            filename = value;
                        }
                    }
                }

            } else if ("content-type".equalsIgnoreCase(parts[0])) {
                contentType = parts[1].trim();
            }
        }
        return new PartMetadata(contentType, name, filename);
    }

    /** an OutputStream that fails once more than limit bytes have been written */
    private static final class BoundedOutputStream extends OutputStream {
        private final ByteArrayOutputStream delegate = new ByteArrayOutputStream();
        private final long limit;
        private long count;

        BoundedOutputStream(long limit) {
            this.limit = limit;
        }

        @Override
        public void write(int b) throws IOException {
            check(1);
            delegate.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            check(len);
            delegate.write(b, off, len);
        }

        private void check(int n) throws IOException {
            count += n;
            if (limit != 0 && count > limit) {
                throw new IOException("non-file form field exceeds the limit of " + limit + " bytes");
            }
        }

        String toString(Charset charset) {
            return delegate.toString(charset);
        }
    }

    private static String readLine(Charset charset, InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        boolean prevCR = false;
        while (true) {
            int c = is.read();
            if (c == -1) {
                if (bos.size() > 0) {
                    return bos.toString(charset);
                }
                return null;
            }
            if (c == '\r') {
                prevCR = true;
            } else if (c == '\n') {
                if (prevCR) {
                    return bos.toString(charset);
                } else {
                    bos.write(c);
                }
            } else {
                if (prevCR) {
                    bos.write('\r');
                    prevCR = false;
                }
                bos.write(c);
            }
        }
    }
}
