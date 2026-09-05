package ru.tubetv.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class WatchHistoryStore {
    private static final String PREFS = "watch_history";
    private static final String ORDER = "order";
    private static final String LEGACY_MIGRATED = "legacy_migrated";
    private static final String ENTRY_PREFIX = "video_";
    private static final int MAX_ENTRIES = WatchProgressStore.MAX_ENTRIES;

    static synchronized void record(Context context, VideoItem item,
                                    boolean trafficMode, int targetHeight,
                                    boolean audioOnly) {
        if (item == null || item.pageUrl == null || item.pageUrl.isEmpty()) return;
        String key = WatchProgressStore.key(item.source, item.pageUrl);
        try {
            JSONObject value = new JSONObject()
                    .put("s", item.source)
                    .put("t", item.title)
                    .put("u", item.subtitle)
                    .put("i", item.thumbnail)
                    .put("r", item.playUrl)
                    .put("p", item.pageUrl)
                    .put("d", item.durationMs)
                    .put("w", item.maxWidth)
                    .put("h", item.maxHeight)
                    .put("m", trafficMode)
                    .put("q", targetHeight)
                    .put("a", audioOnly)
                    .put("l", System.currentTimeMillis());

            SharedPreferences preferences = prefs(context);
            List<String> order = readOrder(preferences);
            order.remove(key);
            order.add(0, key);
            SharedPreferences.Editor editor = preferences.edit()
                    .putString(ENTRY_PREFIX + key, value.toString());
            while (order.size() > MAX_ENTRIES) {
                String removed = order.remove(order.size() - 1);
                editor.remove(ENTRY_PREFIX + removed);
                WatchProgressStore.remove(context, removed);
            }
            editor.putString(ORDER, join(order)).apply();
        } catch (Exception ignored) {
        }
    }

    static synchronized void migrateLegacyPlayer(Context context, VideoItem item,
                                                 boolean trafficMode, int targetHeight,
                                                 boolean audioOnly) {
        SharedPreferences preferences = prefs(context);
        if (preferences.getBoolean(LEGACY_MIGRATED, false)) return;
        preferences.edit().putBoolean(LEGACY_MIGRATED, true).apply();
        if (item == null) return;
        WatchProgressStore.Progress progress = WatchProgressStore.get(context, item);
        if (progress.positionMs < WatchProgressStore.MIN_POSITION_MS) return;
        long duration = progress.durationMs > 0 ? progress.durationMs : item.durationMs;
        record(context, item.withDuration(duration), trafficMode, targetHeight, audioOnly);
    }

    static synchronized List<Entry> load(Context context) {
        SharedPreferences preferences = prefs(context);
        List<String> order = readOrder(preferences);
        if (order.isEmpty()) return Collections.emptyList();
        List<Entry> result = new ArrayList<>(order.size());
        for (String key : order) {
            String encoded = preferences.getString(ENTRY_PREFIX + key, null);
            if (encoded == null || encoded.isEmpty()) continue;
            try {
                JSONObject value = new JSONObject(encoded);
                VideoItem item = new VideoItem(
                        value.optString("s", "RUTUBE"),
                        value.optString("t", "Видео"),
                        value.optString("u", ""),
                        value.optString("i", ""),
                        value.optString("r", ""),
                        value.optString("p", ""),
                        Math.max(0L, value.optLong("d", 0L)))
                        .withQuality(Math.max(0, value.optInt("w", 0)),
                                Math.max(0, value.optInt("h", 0)));
                if (item.playUrl.isEmpty() || item.pageUrl.isEmpty()) continue;
                result.add(new Entry(item,
                        value.optBoolean("m", false),
                        Math.max(0, value.optInt("q", 0)),
                        value.optBoolean("a", false),
                        Math.max(0L, value.optLong("l", 0L))));
            } catch (Exception ignored) {
            }
        }
        return result;
    }

    static boolean contains(Context context, VideoItem item) {
        if (item == null || item.pageUrl == null || item.pageUrl.isEmpty()) return false;
        String key = WatchProgressStore.key(item.source, item.pageUrl);
        return prefs(context).contains(ENTRY_PREFIX + key);
    }

    private static List<String> readOrder(SharedPreferences preferences) {
        String encoded = preferences.getString(ORDER, "");
        List<String> result = new ArrayList<>();
        if (encoded == null || encoded.isEmpty()) return result;
        String[] values = encoded.split(",");
        Set<String> seen = new HashSet<>();
        for (String value : values) {
            if (!value.isEmpty() && seen.add(value)) result.add(value);
            if (result.size() == MAX_ENTRIES) break;
        }
        return result;
    }

    private static String join(List<String> values) {
        StringBuilder result = new StringBuilder(values.size() * 33);
        for (String value : values) {
            if (result.length() > 0) result.append(',');
            result.append(value);
        }
        return result.toString();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static final class Entry {
        final VideoItem item;
        final boolean trafficMode;
        final int targetHeight;
        final boolean audioOnly;
        final long lastWatchedAt;

        Entry(VideoItem item, boolean trafficMode, int targetHeight,
              boolean audioOnly, long lastWatchedAt) {
            this.item = item;
            this.trafficMode = trafficMode;
            this.targetHeight = targetHeight;
            this.audioOnly = audioOnly;
            this.lastWatchedAt = lastWatchedAt;
        }
    }

    private WatchHistoryStore() { }
}
