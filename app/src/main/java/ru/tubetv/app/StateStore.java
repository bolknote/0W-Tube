package ru.tubetv.app;

import android.content.Context;
import android.content.SharedPreferences;

final class StateStore {
    private static final String PREFS = "playback_state";

    static void saveSearch(Context context, String query, int filter) {
        prefs(context).edit()
                .putString("screen", "search")
                .putString("query", query)
                .putInt("filter", filter)
                .apply();
    }

    static void savePlayer(Context context, VideoItem item, long position) {
        prefs(context).edit()
                .putString("screen", "player")
                .putString("source", item.source)
                .putString("title", item.title)
                .putString("subtitle", item.subtitle)
                .putString("thumbnail", item.thumbnail)
                .putString("resolver_url", item.playUrl)
                .putString("page_url", item.pageUrl)
                .putLong("duration", item.durationMs)
                .putLong("position", Math.max(0, position))
                .apply();
    }

    static void savePlayerPosition(Context context, long position) {
        prefs(context).edit().putLong("position", Math.max(0, position)).apply();
    }

    static void markSearch(Context context) {
        prefs(context).edit().putString("screen", "search").apply();
    }

    static String screen(Context context) { return prefs(context).getString("screen", "search"); }
    static String query(Context context) { return prefs(context).getString("query", ""); }
    static int filter(Context context) { return prefs(context).getInt("filter", 0); }
    static long position(Context context) { return prefs(context).getLong("position", 0L); }

    static VideoItem playerItem(Context context) {
        SharedPreferences p = prefs(context);
        String resolver = p.getString("resolver_url", "");
        if (resolver == null || resolver.isEmpty()) return null;
        return new VideoItem(
                p.getString("source", "RUTUBE"),
                p.getString("title", "Видео"),
                p.getString("subtitle", ""),
                p.getString("thumbnail", ""),
                resolver,
                p.getString("page_url", ""),
                p.getLong("duration", 0L));
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private StateStore() { }
}
