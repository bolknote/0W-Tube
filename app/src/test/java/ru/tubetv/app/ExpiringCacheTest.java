package ru.tubetv.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class ExpiringCacheTest {
    @Test public void reuseDoesNotExtendTemporaryMetadataLifetime() {
        long[] now = {0};
        ExpiringCache<String, String> cache = new ExpiringCache<>(2, 100, () -> now[0]);
        cache.put("video", "old");
        now[0] = 99;
        assertEquals("old", cache.get("video"));
        now[0] = 100;
        assertNull(cache.get("video"));
        cache.put("video", "new");
        assertEquals("new", cache.get("video"));
    }

    @Test public void capacityEvictsLeastRecentlyUsedEntry() {
        ExpiringCache<String, Integer> cache = new ExpiringCache<>(2, 100);
        cache.put("a", 1);
        cache.put("b", 2);
        cache.get("a");
        cache.put("c", 3);
        assertNull(cache.get("b"));
        assertEquals(Integer.valueOf(1), cache.get("a"));
        assertEquals(Integer.valueOf(3), cache.get("c"));
    }
}
