package android.content;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Preferences that live in memory for the life of the application. A simulated controller starts every session as
 * a freshly installed one, so nothing is carried over from an earlier run. Changes become visible when
 * {@code apply} or {@code commit} returns and listeners are told on the calling thread, where a device tells them
 * on the main thread.
 */
final class InMemorySharedPreferences implements SharedPreferences {
    private final Object lock = new Object();
    private final Map<String, Object> values = new HashMap<>();
    private final Set<OnSharedPreferenceChangeListener> listeners = Collections.newSetFromMap(new WeakHashMap<>());

    @Override
    public Map<String, ?> getAll() {
        synchronized (lock) {
            return new HashMap<>(values);
        }
    }

    @Override
    public String getString(String key, String defValue) {
        return read(key, String.class, defValue);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Set<String> getStringSet(String key, Set<String> defValues) {
        synchronized (lock) {
            Object stored = values.get(key);
            if (stored != null && !(stored instanceof Set)) {
                throw new ClassCastException(key + " is a " + stored.getClass().getSimpleName() + ", not a string set");
            }
            return stored == null ? defValues : (Set<String>) stored;
        }
    }

    @Override
    public int getInt(String key, int defValue) {
        return read(key, Integer.class, defValue);
    }

    @Override
    public long getLong(String key, long defValue) {
        return read(key, Long.class, defValue);
    }

    @Override
    public float getFloat(String key, float defValue) {
        return read(key, Float.class, defValue);
    }

    @Override
    public boolean getBoolean(String key, boolean defValue) {
        return read(key, Boolean.class, defValue);
    }

    private <T> T read(String key, Class<T> type, T defValue) {
        synchronized (lock) {
            Object stored = values.get(key);
            return stored == null ? defValue : type.cast(stored);
        }
    }

    @Override
    public boolean contains(String key) {
        synchronized (lock) {
            return values.containsKey(key);
        }
    }

    @Override
    public Editor edit() {
        return new PendingChanges();
    }

    @Override
    public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        synchronized (lock) {
            listeners.add(listener);
        }
    }

    @Override
    public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        synchronized (lock) {
            listeners.remove(listener);
        }
    }

    /** Changes collected by an editor; a clear applies first, whatever the order of the calls. */
    private final class PendingChanges implements Editor {
        private final Map<String, Object> puts = new HashMap<>();
        private final Set<String> removals = new LinkedHashSet<>();
        private boolean clear;

        @Override
        public Editor putString(String key, String value) {
            return put(key, value);
        }

        @Override
        public Editor putStringSet(String key, Set<String> values) {
            return put(key, values == null ? null : Collections.unmodifiableSet(new HashSet<>(values)));
        }

        @Override
        public Editor putInt(String key, int value) {
            return put(key, value);
        }

        @Override
        public Editor putLong(String key, long value) {
            return put(key, value);
        }

        @Override
        public Editor putFloat(String key, float value) {
            return put(key, value);
        }

        @Override
        public Editor putBoolean(String key, boolean value) {
            return put(key, value);
        }

        /** Storing null removes the key, as on a device. */
        private Editor put(String key, Object value) {
            if (value == null) {
                return remove(key);
            }
            synchronized (this) {
                puts.put(key, value);
                removals.remove(key);
            }
            return this;
        }

        @Override
        public synchronized Editor remove(String key) {
            puts.remove(key);
            removals.add(key);
            return this;
        }

        @Override
        public synchronized Editor clear() {
            clear = true;
            return this;
        }

        @Override
        public boolean commit() {
            publish();
            return true;
        }

        @Override
        public void apply() {
            publish();
        }

        private void publish() {
            List<String> changed = new ArrayList<>();
            List<OnSharedPreferenceChangeListener> toTell;
            synchronized (lock) {
                synchronized (this) {
                    if (clear) {
                        changed.addAll(values.keySet());
                        values.clear();
                    }
                    for (String key : removals) {
                        if (values.remove(key) != null) {
                            changed.add(key);
                        }
                    }
                    for (Map.Entry<String, Object> put : puts.entrySet()) {
                        if (!put.getValue().equals(values.put(put.getKey(), put.getValue()))) {
                            changed.add(put.getKey());
                        }
                    }
                }
                toTell = new ArrayList<>(listeners);
            }
            for (String key : changed) {
                for (OnSharedPreferenceChangeListener listener : toTell) {
                    listener.onSharedPreferenceChanged(InMemorySharedPreferences.this, key);
                }
            }
        }
    }
}
