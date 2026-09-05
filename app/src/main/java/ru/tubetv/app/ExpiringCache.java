package ru.tubetv.app;

import java.util.LinkedHashMap;
import java.util.Iterator;

/** A small in-memory LRU; wall-clock changes cannot extend an entry's lifetime. */
final class ExpiringCache<K, V> {
    private final LinkedHashMap<K, Entry<V>> entries = new LinkedHashMap<>(16, .75f, true);
    private final int capacity;
    private final long ttlMs;
    interface Clock { long now(); }
    private final Clock clock;

    ExpiringCache(int capacity, long ttlMs) {
        this(capacity, ttlMs, () -> System.nanoTime() / 1_000_000L);
    }

    ExpiringCache(int capacity, long ttlMs, Clock clock) {
        if (capacity < 1 || ttlMs < 1) throw new IllegalArgumentException();
        this.capacity = capacity;
        this.ttlMs = ttlMs;
        this.clock = clock;
    }

    synchronized V get(K key) {
        Entry<V> entry = entries.get(key);
        if (entry == null) return null;
        if (clock.now() - entry.createdAt >= ttlMs) {
            entries.remove(key);
            return null;
        }
        return entry.value;
    }

    synchronized void put(K key, V value) {
        long now = clock.now();
        Iterator<Entry<V>> iterator = entries.values().iterator();
        while (iterator.hasNext()) if (now - iterator.next().createdAt >= ttlMs) iterator.remove();
        entries.put(key, new Entry<>(value, now));
        while (entries.size() > capacity) entries.remove(entries.keySet().iterator().next());
    }

    private static final class Entry<V> {
        final V value;
        final long createdAt;
        Entry(V value, long createdAt) { this.value = value; this.createdAt = createdAt; }
    }
}
