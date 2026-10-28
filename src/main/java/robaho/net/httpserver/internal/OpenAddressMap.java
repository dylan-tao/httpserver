package robaho.net.httpserver.internal;

import java.util.Arrays;
import java.util.function.BiConsumer;

/**
 * an open addressing hash map backed by flat parallel arrays (HPPC-style):
 * no per-entry object, the keys and values live in two separate arrays so
 * value consumers ({@link #forEach}, the header copy and HPACK encode
 * paths) scan a contiguous values region. NOT thread safe - intended for
 * single threaded or externally synchronized use.
 *
 * slot states: {@code keys[i] == null} - empty; {@code keys[i] != null &&
 * values[i] == null} - tombstone, the put-null-as-remove convention
 * shared by OptimizedHeaders, HeaderFieldList and the HPACK context (the
 * map itself never allocates a marker object for a stored null).
 */
public class OpenAddressMap<K,V> {

    private int capacity;
    private int mask;
    private int size;
    private int used;
    private Object[] keys;
    private Object[] values;

    private static int hash(int hash) {
        // spread the high bits into the low bits: String.hashCode keeps most
        // information in the high bits, while the table mask only looks at
        // the low bits - without this, clustered keys degrade to linear scans
        return hash ^ (hash >>> 16);
    }

    public OpenAddressMap(int capacity) {
        // round up to next power of 2
        capacity--;
        capacity |= capacity >> 1;
        capacity |= capacity >> 2;
        capacity |= capacity >> 4;
        capacity |= capacity >> 8;
        capacity |= capacity >> 16;
        capacity++;

        this.capacity = capacity;
        this.mask = capacity - 1;
        this.keys = new Object[capacity];
        this.values = new Object[capacity];
    }

    public V put(K key, V value) {
        if(used>=capacity/2) {
            resize();
        }

        int index = hash(key.hashCode()) & mask;
        int start = index;
        int sentinel = -1;
        Object k;
        while ((k = keys[index]) != null) {
            if (k.equals(key)) {
                Object oldValue = values[index];
                values[index] = value;
                if (value == null && oldValue != null) {
                    size--;
                } else if (value != null && oldValue == null) {
                    // reviving a tombstone (remove-then-re-add): the entry
                    // is live again, so the count must go back up - without
                    // this, remove+re-add left size() and isEmpty() lying
                    // (size 0 while containsKey is true)
                    size++;
                }
                return (V)oldValue;
            } else if (values[index] == null) {
                sentinel = index;
            }
            index = (index + 1) & mask;
            if (index == start) {
                resize();
                index = hash(key.hashCode()) & mask;
                start = index;
                // the table was replaced, so a tombstone index from the old
                // table must not be reused (it could hold a live entry now)
                sentinel = -1;
            }
        }
        if (value == null) {
            // removing a key that is not present creates no entry
            return null;
        }
        int slot = sentinel==-1 ? index : sentinel;
        keys[slot] = key;
        values[slot] = value;
        size++;
        if(sentinel==-1) used++;
        return null;
    }

    private void resize() {
        // rebuild in place into doubled arrays, dropping tombstones; the
        // live count is below half of the OLD capacity, hence a quarter of
        // the new one, so the re-puts cannot trigger another resize
        Object[] oldKeys = keys;
        Object[] oldValues = values;
        int newCapacity = capacity << 1;
        keys = new Object[newCapacity];
        values = new Object[newCapacity];
        capacity = newCapacity;
        mask = newCapacity - 1;
        size = 0;
        used = 0;
        for (int i = 0; i < oldKeys.length; i++) {
            if (oldKeys[i] != null && oldValues[i] != null) {
                put((K) oldKeys[i], (V) oldValues[i]);
            }
        }
    }

    public V get(K key) {
        int index = hash(key.hashCode()) & mask;
        int start = index;
        Object k;
        while ((k = keys[index]) != null) {
            if (k.equals(key)) {
                return (V)values[index];
            }
            index = (index + 1) & mask;
            if(index==start) {
                break;
            }
        }
        return null;
    }

    public int size() {
        return size;
    }

    public void clear() {
        // both arrays: a stale non-null key would occupy probe slots and
        // corrupt the empty-slot termination logic
        Arrays.fill(keys, null);
        Arrays.fill(values, null);
        size=0;
        used=0;
    }

    public void forEach(BiConsumer<K,V> action) {
        for (int i = 0; i < keys.length; i++) {
            if (keys[i] != null && values[i] != null) {
                action.accept((K)keys[i],(V)values[i]);
            }
        }
    }
    public Iterable<K> keys() {
        return KeyIterator::new;
    }

    /** iterate live (non tombstone) entries without building an intermediate collection */
    java.util.Iterator<java.util.Map.Entry<K,V>> entryIterator() {
        return new java.util.Iterator<>() {
            private int index = 0;

            @Override
            public boolean hasNext() {
                while (index < keys.length) {
                    if (keys[index] != null && values[index] != null) {
                        return true;
                    }
                    index++;
                }
                return false;
            }

            @Override
            public java.util.Map.Entry<K,V> next() {
                // honor the Iterator contract: next() without a preceding
                // hasNext() (or after exhaustion) must throw
                // NoSuchElementException - the old blind read returned
                // tombstones as null-valued entries or threw NPE/AIOOBE
                if (!hasNext()) {
                    throw new java.util.NoSuchElementException();
                }
                int i = index++;
                return new java.util.AbstractMap.SimpleEntry<>((K) keys[i], (V) values[i]);
            }
        };
    }

    private class KeyIterator implements java.util.Iterator<K> {
        private int index = 0;

        @Override
        public boolean hasNext() {
            while (index < keys.length) {
                if (keys[index] != null && values[index] != null) {
                    return true;
                }
                index++;
            }
            return false;
        }

        @Override
        public K next() {
            if (!hasNext()) {
                throw new java.util.NoSuchElementException();
            }
            return (K) keys[index++];
        }
    }
}
