package ru.tubetv.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class WatchProgressStore {
    static final long MIN_POSITION_MS = 60_000L;
    private static final String PREFS = "watch_progress";
    static final int MAX_ENTRIES = 1000;
    private static final String ORDER = "order";

    static void save(Context context, String source, String pageUrl, long positionMs, long durationMs) {
        save(prefs(context), source, pageUrl, positionMs, durationMs);
    }

    static synchronized void save(SharedPreferences preferences, String source, String pageUrl,
                                  long positionMs, long durationMs) {
        if (pageUrl == null || pageUrl.isEmpty()) return;
        String key = key(source, pageUrl);
        // The minute threshold creates a history entry; it must not prevent rewinding
        // an already watched video back to its beginning.
        if (positionMs < MIN_POSITION_MS && !preferences.contains(key + "_position")) return;
        SharedPreferences.Editor editor = preferences.edit()
                .putLong(key + "_position", Math.max(0L, positionMs));
        if (durationMs > 0) editor.putLong(key + "_duration", durationMs);
        touch(preferences, editor, key);
        editor.apply();
    }

    static Progress get(Context context, VideoItem item) {
        return get(prefs(context), item);
    }

    static Progress get(SharedPreferences preferences, VideoItem item) {
        String key = key(item.source, item.pageUrl);
        long position = preferences.getLong(key + "_position", 0L);
        long duration = preferences.getLong(key + "_duration", item.durationMs);
        return new Progress(Math.max(0L, position), Math.max(0L, duration));
    }

    static void setMirrored(Context context, String source, String pageUrl, boolean mirrored) {
        setMirrored(prefs(context), source, pageUrl, mirrored);
    }

    static synchronized void setMirrored(SharedPreferences preferences, String source,
                                         String pageUrl, boolean mirrored) {
        if (pageUrl == null || pageUrl.isEmpty()) return;
        String key = key(source, pageUrl);
        if (preferences.getBoolean(key + "_mirrored", false) == mirrored) return;
        // This setting is commonly changed immediately before leaving the player.
        // Persist it synchronously so aggressive TV firmware cannot kill the process first.
        SharedPreferences.Editor editor = preferences.edit().putBoolean(key + "_mirrored", mirrored);
        touch(preferences, editor, key);
        editor.commit();
    }

    static boolean isMirrored(Context context, String source, String pageUrl) {
        if (pageUrl == null || pageUrl.isEmpty()) return false;
        return prefs(context).getBoolean(key(source, pageUrl) + "_mirrored", false);
    }

    static String key(String source, String pageUrl) {
        String value = (source == null ? "" : source) + '|' + (pageUrl == null ? "" : pageUrl);
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(32);
            String hex = "0123456789abcdef";
            for (int i = 0; i < 16; i++) {
                result.append(hex.charAt((bytes[i] & 0xff) >>> 4));
                result.append(hex.charAt(bytes[i] & 0x0f));
            }
            return result.toString();
        } catch (Exception ignored) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static synchronized void remove(Context context, String key) {
        SharedPreferences preferences = prefs(context);
        List<String> order = new ArrayList<>(Arrays.asList(preferences.getString(ORDER, "").split(",")));
        order.remove(key);
        SharedPreferences.Editor editor = preferences.edit().putString(ORDER, join(order));
        remove(editor, key);
        editor.apply();
    }

    private static void touch(SharedPreferences preferences, SharedPreferences.Editor editor, String key) {
        String encoded = preferences.getString(ORDER, "");
        if (encoded.equals(key) || encoded.startsWith(key + ',')) return;
        Set<String> keys = new LinkedHashSet<>();
        keys.add(key);
        if (!preferences.contains(ORDER)) {
            // Upgrade existing installations without discarding their saved positions.
            for (String field : preferences.getAll().keySet()) {
                int suffix = field.lastIndexOf('_');
                if (suffix > 0) keys.add(field.substring(0, suffix));
            }
        } else {
            keys.addAll(Arrays.asList(encoded.split(",")));
        }
        keys.remove("");
        List<String> retained = new ArrayList<>(MAX_ENTRIES);
        for (String value : keys) {
            if (retained.size() < MAX_ENTRIES) retained.add(value);
            else remove(editor, value);
        }
        editor.putString(ORDER, join(retained));
    }

    private static String join(List<String> values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) result.append(',');
            result.append(value);
        }
        return result.toString();
    }

    private static void remove(SharedPreferences.Editor editor, String key) {
        editor.remove(key + "_position").remove(key + "_duration").remove(key + "_mirrored");
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
