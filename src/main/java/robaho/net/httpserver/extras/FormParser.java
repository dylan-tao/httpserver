package robaho.net.httpserver.extras;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parse url-encoded requests.
 */
public class FormParser {

    // Sets the maximum count of accepted POST params - protection against Hash collision DOS attacks
    private static final int MAX_PARAMS = Integer.getInteger("robaho.net.httpserver.max_form_params", 1000); // 0 == no limit
    // Sets the maximum accepted request body size in bytes - protection against memory exhaustion
    private static final int MAX_CONTENT_LENGTH = Integer.getInteger("robaho.net.httpserver.max_form_bytes", 10 * 1024 * 1024); // 0 == no limit

    /**
     * @param encoding the content encoding or null for the default encoding
     * @param is
     * @return a map of the provided parameters
     * @throws IOException
     */
    public static Map<String, List<String>> parse(String encoding, InputStream is) throws IOException {
        if (encoding == null) {
            encoding = Charset.defaultCharset().name();
        }
        Map<String, List<String>> params = new LinkedHashMap<>();
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int bytesRead;
        long total = 0;
        while ((bytesRead = is.read(buffer)) != -1) {
            if (bytesRead == 0) {
                continue;
            }
            total += bytesRead;
            if (MAX_CONTENT_LENGTH != 0 && total > MAX_CONTENT_LENGTH) {
                throw new IOException("form content of " + total + " bytes exceeds the limit of " + MAX_CONTENT_LENGTH + " bytes");
            }
            os.write(buffer, 0, bytesRead);
        }

        // toString(charset) decodes the internal buffer directly: the previous
        // toByteArray() + new String() made a full extra copy of the body
        String data = os.toString(encoding);
        if (data.isEmpty()) {
            //data is empty - can skip the rest
            return new HashMap<>(0);
        }

        // data is o the form:
        // a=b&b=c%12...
        // Let us parse in two phases - we wait until everything is parsed before
        // we decoded it - this makes it possible for use to look for the
        // special _charset_ param which can hold the charset the form is encoded in.
        //
        // http://www.crazysquirrel.com/computing/general/form-encoding.jspx
        // https://bugzilla.mozilla.org/show_bug.cgi?id=18643
        //
        // NB: _charset_ must always be used with accept-charset and it must have the same value
        //
        // index-based scanning instead of split(): no intermediate String[]
        // arrays and no regex machinery (split also allocates a copy of every
        // segment). trailing '&' produce empty segments that split() silently
        // drops, so trim them for identical behavior
        int end = data.length();
        while (end > 0 && data.charAt(end - 1) == '&') {
            end--;
        }

        int paramCount = 0;
        int start = 0;
        while (start < end) {
            int amp = data.indexOf('&', start);
            if (amp == -1 || amp >= end) {
                amp = end;
            }
            String keyValue = data.substring(start, amp);
            int eq = keyValue.indexOf('=');
            String key = eq == -1 ? keyValue : keyValue.substring(0, eq);
            String value = eq == -1 ? null : keyValue.substring(eq + 1);

            List<String> values = params.computeIfAbsent(key, k -> new ArrayList());
            if (value != null) {
                values.add(value);
            }
            paramCount++;
            if (MAX_PARAMS != 0 && paramCount > MAX_PARAMS) {
                throw new IllegalStateException("number of request parameters %d is higher than maximum of %d, aborting. Can be configured using 'http.maxParams'".formatted(paramCount, MAX_PARAMS));
            }
            start = amp + 1;
        }

        // Second phase - look for _charset_ param and do the encoding
        List<String> charsetParam = params.get("_charset_");
        if (charsetParam != null && !charsetParam.isEmpty()) {
            encoding = charsetParam.get(0);
        }

        // We're ready to decode the params. Keys are decoded BEFORE grouping so
        // that "a=1" and "%61=2" merge into a single entry (servlet semantics).
        Map<String, List<String>> decodedParams = new LinkedHashMap<>(params.size());
        for (var e : params.entrySet()) {
            String key = decode(e.getKey(), encoding);
            for (String value : e.getValue()) {
                String decodedValue = value == null ? null : decode(value, encoding);
                List<String> values = decodedParams.computeIfAbsent(key, k -> new ArrayList());
                if (decodedValue != null) {
                    values.add(decodedValue);
                }
            }
        }
        return decodedParams;
    }

    private static String decode(String value, String encoding) throws IOException {
        try {
            return URLDecoder.decode(value, encoding);
        } catch (IllegalArgumentException e) {
            // malformed percent encoding (e.g. "%zz"): report as a client error
            // instead of an uncaught IllegalArgumentException
            throw new IOException("invalid url-encoded form value: " + value, e);
        }
    }
}
