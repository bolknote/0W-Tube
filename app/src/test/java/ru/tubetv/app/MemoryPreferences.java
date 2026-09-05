package ru.tubetv.app;

import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** In-memory persistence for store regression tests; no Android stub defaults. */
final class MemoryPreferences implements SharedPreferences {
    private final Map<String, Object> values = new HashMap<>();
    int commits;
    @Override public Map<String, ?> getAll() { return new HashMap<>(values); }
    @Override public String getString(String key, String fallback) { return (String) values.getOrDefault(key, fallback); }
    @SuppressWarnings("unchecked")
    @Override public Set<String> getStringSet(String key, Set<String> fallback) {
        return (Set<String>) values.getOrDefault(key, fallback);
    }
    @Override public int getInt(String key, int fallback) { return (Integer) values.getOrDefault(key, fallback); }
    @Override public long getLong(String key, long fallback) { return (Long) values.getOrDefault(key, fallback); }
    @Override public float getFloat(String key, float fallback) { return (Float) values.getOrDefault(key, fallback); }
    @Override public boolean getBoolean(String key, boolean fallback) { return (Boolean) values.getOrDefault(key, fallback); }
    @Override public boolean contains(String key) { return values.containsKey(key); }
    @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { }
    @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { }
    @Override public Editor edit() {
        return new Editor() {
            private final Map<String, Object> pending = new HashMap<>();
            private final Set<String> removed = new HashSet<>();
            private boolean clear;
            @Override public Editor putString(String key, String value) { pending.put(key, value); return this; }
            @Override public Editor putStringSet(String key, Set<String> value) { pending.put(key, value); return this; }
            @Override public Editor putInt(String key, int value) { pending.put(key, value); return this; }
            @Override public Editor putLong(String key, long value) { pending.put(key, value); return this; }
            @Override public Editor putFloat(String key, float value) { pending.put(key, value); return this; }
            @Override public Editor putBoolean(String key, boolean value) { pending.put(key, value); return this; }
            @Override public Editor remove(String key) { removed.add(key); return this; }
            @Override public Editor clear() { clear = true; return this; }
            @Override public boolean commit() { commits++; apply(); return true; }
            @Override public void apply() {
                if (clear) values.clear();
                for (String key : removed) values.remove(key);
                values.putAll(pending);
            }
        };
    }
}
