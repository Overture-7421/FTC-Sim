package android.util;

import java.util.Map;
import java.util.TreeMap;

/**
 * Map from {@code long} keys to objects. Android's version is a sorted array; a sorted map gives the same
 * ordered iteration by index, which is what the SDK relies on.
 */
public class LongSparseArray<E> {
    private final TreeMap<Long, E> entries = new TreeMap<>();

    public LongSparseArray() {}

    public LongSparseArray(int initialCapacity) {}

    public E get(long key) {
        return entries.get(key);
    }

    public E get(long key, E valueIfKeyNotFound) {
        E value = entries.get(key);
        return value != null || entries.containsKey(key) ? value : valueIfKeyNotFound;
    }

    public void put(long key, E value) {
        entries.put(key, value);
    }

    public void append(long key, E value) {
        put(key, value);
    }

    public void delete(long key) {
        entries.remove(key);
    }

    public void remove(long key) {
        entries.remove(key);
    }

    public void clear() {
        entries.clear();
    }

    public int size() {
        return entries.size();
    }

    public long keyAt(int index) {
        return entryAt(index).getKey();
    }

    public E valueAt(int index) {
        return entryAt(index).getValue();
    }

    public int indexOfKey(long key) {
        return entries.containsKey(key) ? entries.headMap(key).size() : -1;
    }

    public boolean containsKey(long key) {
        return entries.containsKey(key);
    }

    private Map.Entry<Long, E> entryAt(int index) {
        if (index < 0 || index >= entries.size()) {
            throw new ArrayIndexOutOfBoundsException(index);
        }
        int position = 0;
        for (Map.Entry<Long, E> entry : entries.entrySet()) {
            if (position++ == index) {
                return entry;
            }
        }
        throw new IllegalStateException();
    }
}
