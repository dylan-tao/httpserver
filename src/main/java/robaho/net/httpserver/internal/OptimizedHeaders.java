package robaho.net.httpserver.internal;
import robaho.net.httpserver.internal.OpenAddressMap;


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
    /** stored in the map's value slot for a single null value. the
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
     * {@link #nullSafeList} */
    private static final Object NULL_VALUE = new Object();

    private final OpenAddressMap<String,Object> map;
    public OptimizedHeaders() {
        super();
        map = new OpenAddressMap(16);
    }
    public OptimizedHeaders(int capacity) {
        super();
        map = new OpenAddressMap(capacity);
    }

    /** unwrap a stored value for the Headers read contract: a stored null
     * single value is a mutable singleton list holding null (matching a
     * HashMap-backed Headers where the map value is [null]) */
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
    public boolean isEmpty() {
        return map.size() == 0;
    }

    @Override
    public List<String> get(Object key) {
        Object o = map.get(normalize((String)key));
        return o == null ? null : o instanceof String s ? List.of(s) : nullSafeList(o);
    }

    @Override
    public List<String> put(String key, List<String> value) {
        Object o = map.put(normalize(key), value == null ? NULL_VALUE : value);
        return o == null ? null : o instanceof String s ? List.of(s) : nullSafeList(o);
    }

    @Override
    public List<String> remove(Object key) {
        Object o = map.put(normalize((String)key),null);
        return o == null ? null : o instanceof String s ? List.of(s) : nullSafeList(o);
    }

    @Override
    public String getFirst(String key) {
        Object o = map.get(normalize(key));
        return o == null ? null : o instanceof String s ? s : o == NULL_VALUE ? null : ((List<String>)o).getFirst();
    }

    /**
     * Normalize the key by converting to following form.
     * First {@code char} upper case, rest lower case.
     * key is presumed to be {@code ASCII}.
     */
    private static String normalize(String key) {
        int len = key.length();
        if(len==0) return key;

        int i=0;

        for(;i<len;i++) {
            char c = key.charAt(i);
            if (c == '\r' || c == '\n')
                throw new IllegalArgumentException("illegal character in key");
            if(i==0) {
                if (c >= 'a' && c <= 'z') {
                    break;
                }
            } else {
                if (c >= 'A' && c <= 'Z') {
                    break;
                }
            }
        }
        if(i==len) return key;
        // the fix path never re-checks the tail for CR/LF (the scan loop
        // broke at the first fix point): a lowercase-first name carrying
        // "\r\n..." passed where the JDK Headers throws
        String fixed = Character.toUpperCase(key.charAt(0))+key.substring(1).toLowerCase();
        for (int j = 0; j < fixed.length(); j++) {
            char c = fixed.charAt(j);
            if (c == '\r' || c == '\n') {
                throw new IllegalArgumentException("illegal character in key");
            }
        }
        return fixed;
    }

    @Override
    public void add(String key, String value) {
        var normalized = normalize(key);
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
            if (value == null) {
                ((List<String>) o).add(null);
            } else {
                ((List<String>) o).add(value);
            }
        }
    }

    @Override
    public void clear() {
        map.clear();
    }

    @Override
    public void set(String key, String value) {
        map.put(normalize(key), value == null ? NULL_VALUE : value);
    }

    @Override
    public boolean containsKey(Object key) {
        return map.get(normalize((String)key)) != null;
    }

    @Override
    public boolean containsValue(Object value) {
        return entrySet().stream().anyMatch(e -> e.getValue().contains((String)value));
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
        forEach((k,v) -> result.add(v));
        return result;
   }

    /** cached lazy view over the live entries; avoids rebuilding a full
     * HashSet of copied entries on every entrySet() call */
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
    public void forEach(BiConsumer<? super String,? super List<String>> action) {
        // the lists must be mutable: consumers of entrySet()/forEach() (e.g.
        // Headers.putAll followed by add()) modify the returned lists
        map.forEach((k,v) -> action.accept(k, (v instanceof String s) ? new ArrayList<>(List.of(s)) : nullSafeList(v)));
    }

    /** iteration for READ-ONLY consumers: passes the raw stored value (a
     * String for single-valued headers, the NULL_VALUE marker for a single
     * null, a List otherwise) without the mutable-list wrapper allocations
     * forEach() must make. used by the HPACK response encoder */
    public void forEachRaw(java.util.function.BiConsumer<String, Object> action) {
        map.forEach((k, v) -> action.accept(k, v));
    }

    /** copy entries into a JDK Headers without the entrySet()/forEach()
     * wrapper allocations: putAll() iterated wrappers (SimpleEntry +
     * ArrayList + List.of per single-valued header), ~5-6 allocations per
     * header on every request. the copy still yields MUTABLE lists (the
     * HttpHandler contract), so single values wrap exactly once. a stored
     * single null is NOT silently dropped: it is copied as [null], and the
     * JDK Headers' own put validation then rejects it loudly (NPE) - the
     * parse paths never produce nulls, so this only fires for callers that
     * stored nulls through direct manipulation */
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
