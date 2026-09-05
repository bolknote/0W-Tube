package ru.tubetv.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class StateStoreTest {
    @Test public void oldBackgroundVideoCannotOverwriteNewVideosResumePosition() {
        MemoryPreferences preferences = new MemoryPreferences();
        preferences.edit().putString("source", "RUTUBE").putString("page_url", "new")
                .putLong("position", 15_000).apply();
        StateStore.savePlayerPosition(preferences, "RUTUBE", "old", 600_000);
        StateStore.savePlayerPosition(preferences, "VK VIDEO", "new", 800_000);
        assertEquals(15_000, preferences.getLong("position", -1));
        StateStore.savePlayerPosition(preferences, "RUTUBE", "new", 20_000);
        assertEquals(20_000, preferences.getLong("position", -1));
    }
}
