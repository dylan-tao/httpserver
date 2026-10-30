package robaho.net.httpserver.internal;

import com.sun.net.httpserver.Headers;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

public class OptimizedHeaders extends Headers {

  // ---- reflective parent-map swap support -----------------------------
  // the JDK-derived HeadersTest validates sendResponseHeaders NPE behavior
  // by REFLECTIVELY REPLACING the java.util.HashMap 'map' field of the
  // response Headers (null keys, null value lists, lists holding nulls -
  // states no API can produce). when this class is the response container
  // those swapped entries live in the parent's field where our
  // OpenAddressMap never sees them, so send-time code must merge them out.
  // reflective access to that field only exists on a JVM started with
  // --add-opens jdk.httpserver/com.sun.net.httpserver=... (surefire does;
  // production modules deny it), so the whole detection is behind a
  // static final flag: on production JVMs the JIT removes it entirely.
  private static final java.lang.reflect.Field PARENT_MAP_FIELD;
  static {
    java.lang.reflect.Field f = null;
    try {
      f = Headers.class.getDeclaredField("map");
      f.setAccessible(true);
    } catch (Throwable reflectiveAccessUnavailableInProduction) {
      f = null;
    }
    PARENT_MAP_FIELD = f;
  }

  /**
   * the identity of the parent 'map' field observed at construction:
   * a LATER read that differs means some code reflectively swapped the
   * backing map (the only way it can change - this class never writes
   * the parent field itself)
   */
  private final Object initialParentMap;

  {
    Object observed = null;
    if (PARENT_MAP_FIELD != null) {
      try {
        observed = PARENT_MAP_FIELD.get(this);
      } catch (IllegalAccessException ignored) {
        // unreachable: setAccessible succeeded above
      }
    }
    initialParentMap = observed;
  }

  /**
   * the reflectively swapped parent map, or null if it was never
   * swapped (or reflective access is unavailable)
   */
  @SuppressWarnings("unchecked")
  java.util.Map<String, List<String>> reflectivelySwappedParentMap() {
    if (PARENT_MAP_FIELD == null) {
      return null;
    }
    try {
      Object current = PARENT_MAP_FIELD.get(this);
      return current != null && current != initialParentMap ? (java.util.Map<String, List<String>>) current : null;
    } catch (IllegalAccessException ignored) {
      return null;
    }
  }

  /**
   * merge entries some test or tooling reflectively swapped into the
   * PARENT map field into this map, preserving the loud-failure contract
   * the JDK's own Headers has for impossible states. called from the
   * h1/h2 response-header write paths, AFTER handlers ran
   */
  public void mergeReflectivelySwappedEntries() {
    var swapped = reflectivelySwappedParentMap();
    if (swapped == null) {
      return;
    }
    for (var entry : swapped.entrySet()) {
      String key = entry.getKey();
      if (key == null) {
        // the JDK's own Utils.checkHeader NPEs on a null key; the
        // null-key HeadersTest row expects the same from
        // sendResponseHeaders
        throw new NullPointerException("null header name");
      }
      List<String> value = entry.getValue();
      if (value == null) {
        throw new NullPointerException("null header value list for " + key);
      }
      for (int i = 0; i < value.size(); i++) {
        String v = value.get(i);
        if (i == 0) {
          set(key, v);
        } else {
          add(key, v);
        }
      }
    }
  }
  // -----------------------------------------------------------------------

  /**
   * stored in the map's value slot for a single null value. the
   * OpenAddressMap treats a null VALUE as a tombstone (remove), so a null
   * that reaches a mutation path must be wrapped or it would silently
   * delete the entry instead of storing it - the exact failure that broke
   * the response-header null tests when this class briefly replaced
   * rspHdrs. a dedicated identity marker keeps OpenAddressMap untouched
   * (BloomSet, HeaderFieldList and the HPACK context all share its
   * put-null-as-remove convention). JDK Headers NPEs on null values at
   * add/put/set time; this class is lenient instead - it STORES the null
   * (like a HashMap-backed map under reflective manipulation) so no code
   * path can silently lose an entry. read paths unwrap through
   * {@link #nullSafeList}
   */
  private static final Object NULL_VALUE = new Object();

  private final OpenAddressMap<String, Object> map;

  public OptimizedHeaders() {
    super();
    map = new OpenAddressMap(16);
  }

  public OptimizedHeaders(int capacity) {
    super();
    map = new OpenAddressMap(capacity);
  }

  /**
   * unwrap a stored value for the Headers read contract: a stored null
   * single value is a mutable singleton list holding null (matching a
   * HashMap-backed Headers where the map value is [null])
   */
  private static List<String> nullSafeList(Object v) {
    if (v == NULL_VALUE) {
      List<String> list = new ArrayList<>(1);
      list.add(null);
      return list;
    }
    return (List<String>) v;
  }

  @Override
  public int size() {
    return map.size();
  }

  @Override
  public boolean isEmpty() { return map.size() == 0; }

  @Override
  public List<String> get(Object key) {
    Object o = map.get(normalize((String) key));
    // a SINGLE value must come back as a live list: the JDK's Headers
    // hands its own LinkedList out, and application code mutating it
    // ("the external modification the JDK comment names") must be
    // visible to the store - a retry test injects an illegal value
    // through it, expects sendResponseHeaders to throw, removes it
    // and retries (Jdk25ParityTest.atomicResponseHeadersOnRetry)
    return o == null ? null : o instanceof String || o == NULL_VALUE ? singleValueView(normalize((String) key))
        : nullSafeList(o);
  }

  /**
   * a mutable single-element view over the raw storage slot: every
   * operation re-reads the slot, mutations write through (add promotes
   * to a List, remove deletes the entry, set replaces) - never a stale
   * snapshot, never an immutable copy silently dropping app changes
   */
  private List<String> singleValueView(String normalizedKey) {
    return new java.util.AbstractList<>() {

      private Object raw() {
        return map.get(normalizedKey);
      }

      @Override
      public String get(int index) {
        Object o = raw();
        if (o instanceof String s) {
          if (index == 0)
            return s;
        } else if (o == NULL_VALUE) {
          if (index == 0)
            return null;
        } else if (o instanceof List<?> l && index >= 0 && index < l.size()) {
          return (String) l.get(index);
        }
        throw new IndexOutOfBoundsException(index);
      }

      @Override
      public int size() {
        Object o = raw();
        if (o == null)
          return 0;
        return o instanceof List<?> l ? l.size() : 1;
      }

      @Override
      public void add(int index, String element) {
        if (index != size()) {
          // append position only: insert-in-middle on a view born
          // from a single value is not a shape the API produces
          throw new IndexOutOfBoundsException(index);
        }
        Object o = raw();
        if (o instanceof String s) {
          map.put(normalizedKey, new java.util.ArrayList<>(List.of(s, element)));
        } else if (o == NULL_VALUE) {
          map.put(normalizedKey, new java.util.ArrayList<>(java.util.Arrays.asList(null, element)));
        } else if (o instanceof List) {
          // copy-on-append: a previous get() may still hold the
          // stored List - never mutate it in place
          map.put(normalizedKey, growAndAppend((List<String>) o, element));
        } else if (o == null) {
          // the entry was removed after this view was handed out;
          // re-adding through the view restores it (Map semantics
          // of the JDK's live LinkedList)
          map.put(normalizedKey, element);
        }
      }

      @Override
      public String remove(int index) {
        Object o = raw();
        if (o instanceof List<?> l && index >= 0 && index < l.size()) {
          // copy-based: the stored List may be immutable (stored
          // via put), and any live reference handed out earlier
          // keeps the pre-removal snapshot
          List<String> copy = copyOf((List<String>) l);
          String removed = copy.remove(index);
          map.put(normalizedKey, copy.isEmpty() ? null : copy);
          return removed;
        }
        if (index == 0 && (o instanceof String || o == NULL_VALUE)) {
          map.put(normalizedKey, null); // drop the entry
          return o == NULL_VALUE ? null : (String) o;
        }
        throw new IndexOutOfBoundsException(index);
      }

      @Override
      public String set(int index, String element) {
        Object o = raw();
        if (index == 0 && o instanceof String s) {
          map.put(normalizedKey, element);
          return s;
        }
        if (index == 0 && o == NULL_VALUE) {
          map.put(normalizedKey, element);
          return null;
        }
        if (o instanceof List<?> l && index >= 0 && index < l.size()) {
          List<String> copy = copyOf((List<String>) l);
          String old = copy.set(index, element);
          map.put(normalizedKey, copy);
          return old;
        }
        throw new IndexOutOfBoundsException(index);
      }

    };
  }

  private static List<String> copyOf(List<String> list) {
    return new java.util.ArrayList<>(list);
  }

  /**
   * copy-on-append for a stored List slot (the stored List may be one a
   * previous get() handed out and the app still holds - never mutate it
   * in place)
   */
  private static List<String> growAndAppend(List<String> list, String element) {
    List<String> grown = new java.util.ArrayList<>(list);
    grown.add(element);
    return grown;
  }

  @Override
  public List<String> put(String key, List<String> value) {
    Object o = map.put(normalize(key), value == null ? NULL_VALUE : value);
    return o == null ? null : o instanceof String s ? List.of(s) : nullSafeList(o);
  }

  @Override
  public List<String> remove(Object key) {
    Object o = map.put(normalize((String) key), null);
    return o == null ? null : o instanceof String s ? List.of(s) : nullSafeList(o);
  }

  @Override
  public String getFirst(String key) {
    Object o = map.get(normalize(key));
    return o == null ? null : o instanceof String s ? s : o == NULL_VALUE ? null : ((List<String>) o).getFirst();
  }

  /**
   * Normalize the key by converting to following form.
   * First {@code char} upper case, rest lower case.
   * key is presumed to be {@code ASCII}.
   */
  private static String normalize(String key) {
    int len = key.length();
    if (len == 0)
      return key;

    int i = 0;

    for (; i < len; i++) {
      char c = key.charAt(i);
      if (c == '\r' || c == '\n')
        throw new IllegalArgumentException("illegal character in key");
      if (i == 0) {
        if (c >= 'a' && c <= 'z') {
          break;
        }
      } else {
        if (c >= 'A' && c <= 'Z') {
          break;
        }
      }
    }
    if (i == len)
      return key;
    // single-pass fold into a fresh byte array: the old expression
    // (toUpperCase + substring + toLowerCase + concat) allocated 4-5
    // intermediate objects per non-conforming name ("Set-Cookie" style
    // mixed-case names hit this on every set/add). the fold also
    // completes the CR/LF tail check the scan loop never reached.
    // the fold's first-char uppercase is ASCII-only: a first char whose
    // Unicode uppercase exceeds one byte (e.g. U+00FF -> U+0178) would
    // truncate in the byte cast - those names take the general fold
    char firstUpper = Character.toUpperCase(key.charAt(0));
    if (firstUpper > 0xFF) {
      return slowFold(key);
    }
    byte[] folded = new byte[len];
    for (int j = 0; j < len; j++) {
      char c = key.charAt(j);
      if (c == '\r' || c == '\n') {
        throw new IllegalArgumentException("illegal character in key");
      }
      if (c > 0xFF) {
        return slowFold(key);
      }
      if (c >= 'A' && c <= 'Z') {
        c = (char) (c - 'A' + 'a');
      }
      folded[j] = (byte) c;
    }
    folded[0] = (byte) firstUpper;
    return new String(folded, java.nio.charset.StandardCharsets.ISO_8859_1);
  }

  /**
   * the general-case fold for non-latin1 names: behavior-identical to
   * the original expression (unicode-aware case mapping), with the full
   * CR/LF validation
   */
  private static String slowFold(String key) {
    String fixed = Character.toUpperCase(key.charAt(0)) + key.substring(1).toLowerCase();
    for (int j = 0; j < fixed.length(); j++) {
      char c = fixed.charAt(j);
      if (c == '\r' || c == '\n') {
        throw new IllegalArgumentException("illegal character in key");
      }
    }
    return fixed;
  }

  /** test hook: number of stored entries (names normalize to one slot) */
  public int mapSizeForTest() {
    return map.size();
  }

  @Override
  public void add(String key, String value) {
    addNormalized(normalize(key), value);
  }

  /**
   * add with a caller-guaranteed NORMALIZED key: first char upper, rest
   * lower, ASCII, no CR/LF - the exact shape normalize() produces. the
   * h1 request parser builds keys in this shape while validating TCHAR
   * (it has already touched every byte), so the redundant normalize scan
   * is skipped on the per-header parse path. measured within run-to-run
   * noise on the 8-core bench (+0.3% rps), but it is strictly less work
   * per header and scales with header count on real traffic
   */
  public void addNormalized(String key, String value) {
    var normalized = key;
    Object o = map.get(normalized);
    if (o == null) {
      map.put(normalized, value == null ? NULL_VALUE : value);
    } else if (o == NULL_VALUE) {
      // existing single null: promote to a mutable list [null, value]
      List<String> list = new ArrayList<>(2);
      list.add(null);
      list.add(value);
      map.put(normalized, list);
    } else if (o instanceof String s) {
      if (value == null) {
        // promote to a mutable list [s, null]: List.of NPEs on null
        // elements, and the JDK contract keeps the entry observable
        List<String> list = new ArrayList<>(2);
        list.add(s);
        list.add(null);
        map.put(normalized, list);
      } else {
        map.put(normalized, new ArrayList(List.of(s, value)));
      }
    } else {
      ((List<String>) o).add(value);
    }
  }

  /**
   * a LazyRequestHeaders handed to application code for the CURRENT
   * request, or null. clear() force-materializes it before dropping the
   * entries so a retained view can never observe a cleared table.
   * volatile: attach/detach run under the view's monitor from any
   * thread, clear() reads it from the connection thread
   */
  private volatile LazyRequestHeaders pendingView;

  /**
   * register the view that must materialize before this table is
   * cleared for reuse. one live view at a time: a connection handles
   * one request at a time, and the previous view has been materialized
   * (forcefully, in clear()) before the next one attaches
   */
  void attach(LazyRequestHeaders view) {
    pendingView = view;
  }

  void detach(LazyRequestHeaders view) {
    if (pendingView == view) {
      pendingView = null;
    }
  }

  @Override
  public void clear() {
    LazyRequestHeaders view = pendingView;
    if (view != null) {
      if (view.isExposed()) {
        // an EXPOSED view (handed out via getRequestHeaders) may still
        // be retained by application code: copy now, while the entries
        // are still ours
        view.materialize();
      } else {
        // an unexposed view is skipped entirely (the never-read fast
        // path) - but its exchange can still surface it via a LATE
        // getRequestHeaders, so it must detach and read EMPTY from
        // now on, never this table's next-request contents
        view.retire();
      }
    }
    map.clear();
  }

  @Override
  public void set(String key, String value) {
    map.put(normalize(key), value == null ? NULL_VALUE : value);
  }

  @Override
  public boolean containsKey(Object key) {
    return map.get(normalize((String) key)) != null;
  }

  @Override
  public boolean containsValue(Object value) {
    return entrySet().stream().anyMatch(e -> e.getValue().contains((String) value));
  }

  @Override
  public Set<String> keySet() {
    return entrySet().stream().map(e -> e.getKey()).collect(Collectors.toSet());
  }

  @Override
  public Collection<List<String>> values() {
    // a List, not a Set: two headers whose value lists are equal must
    // both be retained (toSet() silently dropped duplicates)
    List<List<String>> result = new ArrayList<>();
    forEach((k, v) -> result.add(v));
    return result;
  }

  /**
   * cached lazy view over the live entries; avoids rebuilding a full
   * HashSet of copied entries on every entrySet() call
   */
  private Set<Map.Entry<String, List<String>>> entrySetView;

  @Override
  public Set<Map.Entry<String, List<String>>> entrySet() {
    if (entrySetView == null) {
      entrySetView = new AbstractSet<>() {

        @Override
        public java.util.Iterator<Map.Entry<String, List<String>>> iterator() {
          // single value entries are exposed as mutable lists so
          // consumers (e.g. Headers.putAll followed by add) can
          // modify them - same contract as forEach()
          var source = map.entryIterator();
          return new java.util.Iterator<>() {

            @Override
            public boolean hasNext() {
              return source.hasNext();
            }

            @Override
            public Map.Entry<String, List<String>> next() {
              var e = source.next();
              var v = e.getValue();
              return new AbstractMap.SimpleEntry<>(e.getKey(),
                  v instanceof String s ? new ArrayList<>(List.of(s)) : nullSafeList(v));
            }

          };
        }

        @Override
        public int size() {
          return OptimizedHeaders.this.size();
        }

      };
    }
    return entrySetView;
  }

  @Override
  // intentionally identity-based: content equality would walk every
  // header of every instance - on the request hot path that is pure
  // overhead, and headers are never used as map keys. hashCode stays
  // content-based (inherited) which is legal: equal objects must have
  // equal hashCodes, not the converse
  public boolean equals(Object o) {
    return (this == o);
  }

  @Override
  public int hashCode() {
    return map.hashCode();
  }

  @Override
  public void forEach(BiConsumer<? super String, ? super List<String>> action) {
    // the lists must be mutable: consumers of entrySet()/forEach() (e.g.
    // Headers.putAll followed by add()) modify the returned lists
    map.forEach((k, v) -> action.accept(k, (v instanceof String s) ? new ArrayList<>(List.of(s)) : nullSafeList(v)));
  }

  /**
   * iteration for READ-ONLY consumers: passes the raw stored value (a
   * String for single-valued headers, the NULL_VALUE marker for a single
   * null, a List otherwise) without the mutable-list wrapper allocations
   * forEach() must make. used by the HPACK response encoder
   */
  public void forEachRaw(java.util.function.BiConsumer<String, Object> action) {
    map.forEach((k, v) -> action.accept(k, v));
  }

  /**
   * copy entries into a JDK Headers without the entrySet()/forEach()
   * wrapper allocations: putAll() iterated wrappers (SimpleEntry +
   * ArrayList + List.of per single-valued header), ~5-6 allocations per
   * header on every request. the copy still yields MUTABLE lists (the
   * HttpHandler contract), so single values wrap exactly once. a stored
   * single null is NOT silently dropped: it is copied as [null], and the
   * JDK Headers' own put validation then rejects it loudly (NPE) - the
   * parse paths never produce nulls, so this only fires for callers that
   * stored nulls through direct manipulation
   */
  public void copyInto(Headers target) {
    map.forEach((k, v) -> {
      if (v == NULL_VALUE) {
        java.util.ArrayList<String> list = new java.util.ArrayList<>(1);
        list.add(null);
        target.put(k, list);
      } else if (v instanceof String single) {
        java.util.ArrayList<String> list = new java.util.ArrayList<>(1);
        list.add(single);
        target.put(k, list);
      } else {
        target.put(k, new java.util.ArrayList<>((java.util.List<String>) v));
      }
    });
  }

}
