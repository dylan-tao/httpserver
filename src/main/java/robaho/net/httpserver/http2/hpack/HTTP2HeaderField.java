package robaho.net.httpserver.http2.hpack;

public class HTTP2HeaderField {

	public String name;
	public String value;
    public String normalizedName;

    public HTTP2HeaderField() {
    }
    
    public HTTP2HeaderField(String name, String value) {
        this.name = name;
        this.value = value;
        this.normalizedName = normalize(name);
    }

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
        this.normalizedName = normalize(name);
	}
	public void setName(String name,String normalizedName) {
		this.name = name;
        this.normalizedName = normalizedName;
	}

	public String getValue() {
		return value;
	}

	public void setValue(String value) {
		this.value = value;
	}
    
    @Override
    public String toString() {
        return name + ": " + value;
    }

    public boolean isPseudoHeader() {
        return name.startsWith(":");
    }

    /** normalized-name cache: header names are a small repeating set, and the
     * concat allocated 3 objects per literal header field on the decode path.
     * bounded - on overflow the concat fallback applies (names are attacker
     * controllable, so the cache must not grow unbounded) */
    private static final java.util.concurrent.ConcurrentHashMap<String, String> NORMALIZE_CACHE =
        new java.util.concurrent.ConcurrentHashMap<>();
    private static final int NORMALIZE_CACHE_MAX = 256;

    public static String normalize(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        String cached = NORMALIZE_CACHE.get(value);
        if (cached != null) {
            return cached;
        }
        String normalized = Character.toUpperCase(value.charAt(0)) + value.substring(1);
        if (NORMALIZE_CACHE.size() < NORMALIZE_CACHE_MAX) {
            NORMALIZE_CACHE.putIfAbsent(value, normalized);
        }
        return normalized;
    }
}
