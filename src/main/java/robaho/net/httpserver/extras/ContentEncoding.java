package robaho.net.httpserver.extras;

import java.nio.charset.StandardCharsets;
import java.util.List;

import com.sun.net.httpserver.Headers;

public class ContentEncoding {

    private static final String defaultCharset = StandardCharsets.ISO_8859_1.name();

    /**
     * @return the charset parameter of the Content-type header, or the default.
     *         (the charset is a Content-Type parameter - Content-Encoding
     *         describes the compression, e.g. gzip, and never carries a charset)
     */
    public static String encoding(Headers headers) {
        List<String> values = headers.get("Content-type");
        if (values == null) {
            return defaultCharset;
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
        return defaultCharset;
    }
}
