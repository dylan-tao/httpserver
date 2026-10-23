package robaho.net.httpserver.extras;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;

public class QueryParameters extends LinkedHashMap<String, List<String>> {

    /**
     * @param encoding a valid java character set name
     * @param query the query string
     * @return an insertion-ordered map preserving the parameter order
     * @throws UnsupportedEncodingException if the encoding or query is not
     * valid
     */
    public static QueryParameters decode(String encoding, String query) throws UnsupportedEncodingException {
        if (query == null || "".equals(query)) {
            return new QueryParameters();
        }
        QueryParameters qp = new QueryParameters();
        for (var entry : query.split("&")) {
            String[] parts = entry.split("=", 2);
            if (parts.length == 0 || parts[0].length() == 0) {
                continue;
            }
            // decode the key as well as the value, so "%61=1" and "a=1" group together
            String key = URLDecoder.decode(parts[0], encoding);
            qp.computeIfAbsent(key, k -> new LinkedList<>()).add(parts.length == 1 ? "" : URLDecoder.decode(parts[1], encoding));
        }
        return qp;
    }

    /** the first value of a query parameter, or null when absent */
    public String getFirst(String key) {
        List<String> values = get(key);
        return values == null || values.size() == 0 ? null : values.get(0);
    }
}
