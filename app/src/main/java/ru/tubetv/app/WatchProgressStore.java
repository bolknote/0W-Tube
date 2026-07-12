package ru.tubetv.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

final class WatchProgressStore {
    static final long MIN_POSITION_MS = 60_000L;
    private static final String PREFS = "watch_progress";

    static void save(Context context, String source, String pageUrl, long positionMs, long durationMs) {
        if (positionMs < MIN_POSITION_MS || pageUrl == null || pageUrl.isEmpty()) return;
        String key = key(source, pageUrl);
        prefs(context).edit()
                .putLong(key + "_position", positionMs)
                .putLong(key + "_duration", Math.max(0L, durationMs))
                .apply();
    }

    static Progress get(Context context, VideoItem item) {
        String key = key(item.source, item.pageUrl);
        SharedPreferences preferences = prefs(context);
        long position = preferences.getLong(key + "_position", 0L);
        long duration = preferences.getLong(key + "_duration", item.durationMs);
        return position >= MIN_POSITION_MS ? new Progress(position, duration) : new Progress(0L, duration);
    }

    private static String key(String source, String pageUrl) {
        String value = (source == null ? "" : source) + '|' + (pageUrl == null ? "" : pageUrl);
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(32);
            for (int i = 0; i < 16; i++) result.append(String.format("%02x", bytes[i] & 0xff));
            return result.toString();
        } catch (Exception ignored) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static final class Progress {
        final long positionMs;
        final long durationMs;
        Progress(long positionMs, long durationMs) {
            this.positionMs = positionMs;
            this.durationMs = durationMs;
        }
    }

    private WatchProgressStore() { }
}
