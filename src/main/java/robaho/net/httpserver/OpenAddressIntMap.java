package robaho.net.httpserver;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

public class OpenAddressIntMap<T> {

    private static class Entry {
        int key;
        Object value;

        Entry(int key, Object value) {
            this.key = key;
            this.value = value;
        }
    }

    private int capacity;
    private int mask;
    private Entry[] entries;
    private int size;
    private int used;

    public OpenAddressIntMap(int capacity) {
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
        this.entries = new Entry[capacity];
    }

    /** guards all access. a ReentrantLock rather than synchronized methods:
     * the codebase bans monitor-based locking for virtual thread safety
     * (JEP 444 pinning); the sections here are short and non-blocking, so
     * this is policy compliance rather than a pinning fix */
    private final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();

    public T put(int key, T value) {
        lock.lock();
        try {
            return putLocked(key, value);
        } finally {
            lock.unlock();
        }
    }

    private T putLocked(int key, T value) {
        if(used>=capacity/2) {
            resize();
        }
        int index = key & mask;
        int start = index;
        int sentinel = -1;
        Entry entry;
        while ((entry = entries[index]) != null) {
            if (entry.key==key) {
                T oldValue = (T)entry.value;
                entry.value = value;
                if(value==null && oldValue!=null) {
                    size--;
                }
                return oldValue;
            } else if (entry.value==null) {
                sentinel = index;
            }
            index = (index + 1) & mask;
            if (index == start) {
                resize();
                index = key & mask;
                start = index;
                sentinel = -1;
            }
        }
        if(value==null) {
            return null;
        }
        entries[sentinel==-1 ? index : sentinel] = new Entry(key, value);
        size++;
        if(sentinel==-1) used++;
        return null;
    }

    private void resize() {
        OpenAddressIntMap newMap = new OpenAddressIntMap(capacity << 1);
        for (Entry entry : entries) {
            if (entry != null && entry.value != null) {
                newMap.put(entry.key, entry.value);
            }
        }
        this.entries = newMap.entries;
        this.capacity = newMap.capacity;
        this.mask = newMap.mask;
        this.size = newMap.size;
        this.used = newMap.used;
    }

    /** locked to match put/resize: concurrent readers could otherwise observe
     * a torn view of entries/mask while resize replaces them */
    public T get(int key) {
        lock.lock();
        try {
            int index = key & mask;
            int start = index;
            Entry entry;
            while ((entry = entries[index]) != null) {
                if (entry.key==key) {
                    return (T)entry.value;
                }
                index = (index + 1) & mask;
                if(index==start) {
                    break;
                }
            }
            return null;
        } finally {
            lock.unlock();
        }
    }

    public T getOrDefault(int key, T defaultValue) {
        T value = get(key);
        return value != null ? value : defaultValue;
    }

    public int size() {
        return size;
    }

    public void clear() {
        Arrays.fill(entries, null);
        size=0;
        used=0;
    }

    public Iterable<T> values() {
        lock.lock();
        try {
            List<T> result = new ArrayList<>();
            for (Entry entry : entries) {
                if (entry != null && entry.value != null) {
                    result.add((T)entry.value);
                }
            }
            return Collections.unmodifiableList(result);
        } finally {
            lock.unlock();
        }
    }

    public <T2> Set<Map.Entry<Integer, T2>> entrySet(Function<T, T2> valueMapper) {
        lock.lock();
        try {
            Set<Map.Entry<Integer, T2>> result = new HashSet<>();
            for (Entry entry : entries) {
                // Map.entry forbids null values, so tombstones must be skipped
                if (entry != null && entry.value != null) {
                    result.add(Map.entry(entry.key, valueMapper != null ? valueMapper.apply((T)entry.value) : (T2) entry.value));
                }
            }
            return Collections.unmodifiableSet(result);
        } finally {
            lock.unlock();
        }
    }
}
