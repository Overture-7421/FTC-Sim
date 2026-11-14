package android.os;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * A mapping from string keys to values. Reading a key that is absent, or that holds a value of another type,
 * yields the default, as on Android. Only the value types the hosted libraries exchange are provided.
 */
public final class Bundle {
    private final Map<String, Object> values = new HashMap<>();

    public Bundle() {}

    public Bundle(Bundle other) {
        values.putAll(other.values);
    }

    public int size() {
        return values.size();
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    public void clear() {
        values.clear();
    }

    public boolean containsKey(String key) {
        return values.containsKey(key);
    }

    public void remove(String key) {
        values.remove(key);
    }

    public Set<String> keySet() {
        return values.keySet();
    }

    public void putString(String key, String value) {
        values.put(key, value);
    }

    public String getString(String key) {
        return getString(key, null);
    }

    public String getString(String key, String defaultValue) {
        return values.get(key) instanceof String value ? value : defaultValue;
    }

    public void putInt(String key, int value) {
        values.put(key, value);
    }

    public int getInt(String key) {
        return getInt(key, 0);
    }

    public int getInt(String key, int defaultValue) {
        return values.get(key) instanceof Integer value ? value : defaultValue;
    }

    public void putLong(String key, long value) {
        values.put(key, value);
    }

    public long getLong(String key) {
        return getLong(key, 0L);
    }

    public long getLong(String key, long defaultValue) {
        return values.get(key) instanceof Long value ? value : defaultValue;
    }

    public void putBoolean(String key, boolean value) {
        values.put(key, value);
    }

    public boolean getBoolean(String key) {
        return getBoolean(key, false);
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        return values.get(key) instanceof Boolean value ? value : defaultValue;
    }

    public void putFloat(String key, float value) {
        values.put(key, value);
    }

    public float getFloat(String key) {
        return getFloat(key, 0f);
    }

    public float getFloat(String key, float defaultValue) {
        return values.get(key) instanceof Float value ? value : defaultValue;
    }

    public void putDouble(String key, double value) {
        values.put(key, value);
    }

    public double getDouble(String key) {
        return getDouble(key, 0d);
    }

    public double getDouble(String key, double defaultValue) {
        return values.get(key) instanceof Double value ? value : defaultValue;
    }

    @Override
    public String toString() {
        return "Bundle[" + values + "]";
    }
}
