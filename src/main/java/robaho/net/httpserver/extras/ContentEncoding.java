package robaho.net.httpserver.extras;

import com.sun.net.httpserver.Headers;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * extracts the charset parameter from the Content-Type header. despite the
 * class name (kept for API compatibility), this has nothing to do with
 * Content-Encoding - that header describes the response compression (e.g.
 * gzip) and never carries a charset.
 */
public class ContentEncoding {

    private static final String DEFAULT_CHARSET = StandardCharsets.ISO_8859_1.name();

    /**
     * @return the charset parameter of the Content-type header, or the default.
     *         (the charset is a Content-Type parameter - Content-Encoding
     *         describes the compression, e.g. gzip, and never carries a charset)
     */
    public static String encoding(Headers headers) {
        List<String> values = headers.get("Content-type");
        if (values == null) {
            return DEFAULT_CHARSET;
        }
        for (String value : values) {
            for (String part : value.split(";")) {
                String parameter = part.trim();
                if (parameter.regionMatches(true, 0, "charset=", 0, "charset=".length())) {
                    String charset = parameter.substring("charset=".length()).trim();
                    if (!charset.isEmpty()) {
                        return charset;
                    }
                }
            }
        }
        return DEFAULT_CHARSET;
    }
}
