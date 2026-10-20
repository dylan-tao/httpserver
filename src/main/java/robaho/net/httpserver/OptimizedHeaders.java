package robaho.net.httpserver;

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
    private final OpenAddressMap<String,Object> map;
    public OptimizedHeaders() {
        super();
        map = new OpenAddressMap(16);
    }
    public OptimizedHeaders(int capacity) {
        super();
        map = new OpenAddressMap(capacity);
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
        return o == null ? null : (o instanceof String s) ? List.of(s) : (List<String>)o;
    }

    @Override
    public List<String> put(String key, List<String> value) {
        Object o = map.put(normalize(key), value);
        return o == null ? null : (o instanceof String s) ? List.of(s) : (List<String>)o;
    }

    @Override
    public List<String> remove(Object key) {
        Object o = map.put(normalize((String)key),null);
        return o == null ? null : (o instanceof String s) ? List.of(s) : (List<String>)o;
    }

    @Override
    public String getFirst(String key) {
        Object o = map.get(normalize(key));
        return o == null ? null : (o instanceof String s) ? s : ((List<String>)o).getFirst();
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
        return Character.toUpperCase(key.charAt(0))+key.substring(1).toLowerCase();
    }

    @Override
    public void add(String key, String value) {
        var normalized = normalize(key);
        Object o = map.get(normalized);
        if (o == null) {
            map.put(normalized, value);
        } else if(o instanceof String s) {
            map.put(normalized, new ArrayList(List.of(s,value)));
        } else {
            ((List<String>)o).add(value);
        }
    }

    @Override
    public void clear() {
        map.clear();
    }

    @Override
    public void set(String key, String value) {
        map.put(normalize(key), value);
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
                                    v instanceof String s ? new ArrayList<>(List.of(s)) : (List<String>) v);
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
        map.forEach((k,v) -> action.accept(k, (v instanceof String s) ? new ArrayList<>(List.of(s)) : (List<String>)v));
    }

    /** iteration for READ-ONLY consumers: passes the raw stored value (a
     * String for single-valued headers, a List otherwise) without the
     * mutable-list wrapper allocations forEach() must make. used by the HPACK
     * response encoder */
    public void forEachRaw(java.util.function.BiConsumer<String, Object> action) {
        map.forEach((k, v) -> action.accept(k, v));
    }

    /** copy entries into a JDK Headers without the entrySet()/forEach()
     * wrapper allocations: putAll() iterated wrappers (SimpleEntry +
     * ArrayList + List.of per single-valued header), ~5-6 allocations per
     * header on every request. the copy still yields MUTABLE lists (the
     * HttpHandler contract), so single values wrap exactly once */
    public void copyInto(Headers target) {
        map.forEach((k, v) -> {
            if (v instanceof String single) {
                java.util.ArrayList<String> list = new java.util.ArrayList<>(1);
                list.add(single);
                target.put(k, list);
            } else {
                target.put(k, new java.util.ArrayList<>((java.util.List<String>) v));
            }
        });
    }
}
