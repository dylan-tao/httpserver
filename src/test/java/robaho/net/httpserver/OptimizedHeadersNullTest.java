package robaho.net.httpserver;

import com.sun.net.httpserver.Headers;
import org.testng.annotations.Test;
import robaho.net.httpserver.internal.OptimizedHeaders;

import java.util.Collections;
import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/**
 * null-value semantics of OptimizedHeaders: a null that reaches a mutation
 * path must be STORED (observable through every read path), never silently
 * dropped. the OpenAddressMap backing treats a null value as a removal
 * tombstone, so the class wraps nulls in a dedicated marker - before that,
 * {@code add("Foo", null)} deleted the entry instead of storing [null], and
 * {@code add("Foo", "x") + add("Foo", null)} threw NPE inside List.of.
 *
 * the JDK's own Headers rejects null values at add/put/set time (NPE);
 * this class is deliberately lenient - it stores them, matching what a
 * HashMap-backed map does when nulls enter through map-level manipulation.
 */
public class OptimizedHeadersNullTest {

    private static OptimizedHeaders h() {
        return new OptimizedHeaders(8);
    }

    @Test
    public void addSingleNullIsStored() {
        var h = h();
        h.add("Foo", null);
        assertTrue(h.containsKey("Foo"), "add(k,null) must not delete the entry");
        assertEquals(h.size(), 1);
        assertNull(h.getFirst("Foo"));
        var values = h.get("Foo");
        assertEquals(values.size(), 1);
        assertNull(values.get(0));
    }

    @Test
    public void addNullThenValue() {
        var h = h();
        h.add("Foo", null);
        h.add("Foo", "x");
        assertEquals(h.size(), 1);
        assertNull(h.getFirst("Foo"));
        assertEquals(h.get("Foo"), java.util.Arrays.asList(null, "x"));
    }

    @Test
    public void addValueThenNull() {
        var h = h();
        h.add("Foo", "x");
        h.add("Foo", null);
        assertEquals(h.size(), 1);
        assertEquals(h.getFirst("Foo"), "x");
        assertEquals(h.get("Foo"), java.util.Arrays.asList("x", null));
    }

    @Test
    public void addNullTwice() {
        var h = h();
        h.add("Foo", null);
        h.add("Foo", null);
        assertEquals(h.size(), 1);
        assertEquals(h.get("Foo"), java.util.Arrays.asList(null, (String) null));
    }

    @Test
    public void setNullReplaces() {
        var h = h();
        h.add("Foo", "x");
        h.set("Foo", null);
        assertEquals(h.size(), 1);
        assertNull(h.getFirst("Foo"));
    }

    @Test
    public void removeStillRemoves() {
        var h = h();
        h.add("Foo", "x");
        h.add("Bar", null);
        h.remove("Foo");
        assertFalse(h.containsKey("Foo"));
        // the null-valued entry is a live entry: remove must see it too
        h.remove("Bar");
        assertFalse(h.containsKey("Bar"));
        assertTrue(h.isEmpty());
    }

    @Test
    public void entrySetAndForEachExposeNull() {
        var h = h();
        h.add("Foo", null);
        var it = h.entrySet().iterator();
        assertTrue(it.hasNext());
        var e = it.next();
        assertEquals(e.getKey(), "Foo");
        assertEquals(e.getValue(), Collections.singletonList((String) null));
        // the unwrapped list is a mutable copy (the Headers entrySet
        // contract): mutating it must not throw, and - as with single
        // String values, which unwrap the same way - writes do not
        // propagate back into the map
        e.getValue().add("y");
        assertEquals(h.get("Foo"), Collections.singletonList((String) null));

        var seen = new java.util.ArrayList<List<String>>();
        h.forEach((k, v) -> seen.add(v));
        assertEquals(seen, java.util.List.of(Collections.singletonList((String) null)));
    }

    @Test
    public void forEachRawPassesTheMarkerNotASilentDrop() {
        var h = h();
        h.add("Foo", null);
        h.add("Bar", "b");
        var raw = new java.util.ArrayList<Object>();
        h.forEachRaw((k, v) -> raw.add(v));
        assertEquals(raw.size(), 2);
        // Bar's single value arrives as its raw String; Foo's null arrives
        // as the marker object (neither a String nor a List) - the HPACK
        // encoder's consumer skips marker values explicitly
        assertTrue(raw.contains("b"));
        assertTrue(raw.stream().allMatch(v -> v instanceof String || !(v instanceof java.util.List)));
    }

    @Test
    public void putNullListStoresIt() {
        var h = h();
        List<String> previous = h.put("Foo", null);
        assertNull(previous);
        assertTrue(h.containsKey("Foo"));
        var values = h.get("Foo");
        assertEquals(values.size(), 1);
        assertNull(values.get(0));
    }

    @Test
    public void copyIntoCopiesNonNullAndFailsLoudlyOnNull() {
        var h = h();
        h.add("Bar", "b");
        Headers target = new Headers();
        h.copyInto(target);
        assertEquals(target.size(), 1);
        assertEquals(target.getFirst("Bar"), "b");

        // a stored null entry is copied as [null], and the JDK Headers'
        // own put validation rejects it with NPE - loud, never a silent
        // drop. the request-header parse path never produces nulls, so
        // this only fires for callers that stored nulls directly
        h.add("Foo", null);
        assertThrowsNPE(() -> h.copyInto(new Headers()));
    }

    @Test
    public void nullKeyStillNPEsLikeTheJDK() {
        var h = h();
        assertThrowsNPE(() -> h.getFirst(null));
        assertThrowsNPE(() -> h.get(null));
        assertThrowsNPE(() -> h.containsKey(null));
        assertThrowsNPE(() -> h.remove(null));
        assertThrowsNPE(() -> h.add(null, "x"));
        assertThrowsNPE(() -> h.set(null, "x"));
    }

    private static void assertThrowsNPE(Runnable r) {
        try {
            r.run();
            throw new AssertionError("expected NullPointerException");
        } catch (NullPointerException expected) {
        }
    }
}
