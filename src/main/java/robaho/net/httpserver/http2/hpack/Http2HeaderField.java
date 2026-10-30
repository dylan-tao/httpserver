package robaho.net.httpserver.http2.hpack;

public class Http2HeaderField {

  public String name;

  public String value;

  public String normalizedName;

  public Http2HeaderField() {}

  public Http2HeaderField(String name, String value) {
    this.name = name;
    this.value = value;
    this.normalizedName = normalize(name);
  }

  public String getName() { return name; }

  public void setName(String name) {
    this.name = name;
    this.normalizedName = normalize(name);
  }

  public void setName(String name, String normalizedName) {
    this.name = name;
    this.normalizedName = normalizedName;
  }

  public String getValue() { return value; }

  public void setValue(String value) { this.value = value; }

  @Override
  public String toString() {
    return name + ": " + value;
  }

  public boolean isPseudoHeader() { return name.startsWith(":"); }

  /**
   * first-char case caches shared by the decode path (normalize, upper)
   * and the encode path (lowercase): header names are a small repeating
   * set, and the two-String conversion allocated 3 objects per field.
   * bounded - on overflow the concat fallback applies (names are attacker
   * controllable, so the caches must not grow unbounded)
   */
  private static final java.util.concurrent.ConcurrentHashMap<String, String> UPPER_CACHE =
      new java.util.concurrent.ConcurrentHashMap<>();

  private static final java.util.concurrent.ConcurrentHashMap<String, String> LOWER_CACHE =
      new java.util.concurrent.ConcurrentHashMap<>();

  private static final int CASE_CACHE_MAX = 256;

  public static String normalize(String value) {
    return cachedCase(value, UPPER_CACHE, true);
  }

  /**
   * lowercase the first character: the HPACK encoder must emit lowercase
   * header names (RFC 7540 8.1.2)
   */
  static String lowercase(String value) {
    return cachedCase(value, LOWER_CACHE, false);
  }

  private static String cachedCase(String value, java.util.concurrent.ConcurrentHashMap<String, String> cache, boolean upper) {
    if (value == null || value.isEmpty()) {
      return value;
    }
    String cached = cache.get(value);
    if (cached != null) {
      return cached;
    }
    char c0 = upper ? Character.toUpperCase(value.charAt(0)) : Character.toLowerCase(value.charAt(0));
    String converted = c0 + value.substring(1);
    if (cache.size() < CASE_CACHE_MAX) {
      cache.putIfAbsent(value, converted);
    }
    return converted;
  }

}
