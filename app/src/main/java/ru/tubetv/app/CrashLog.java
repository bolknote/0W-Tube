package ru.tubetv.app;

import android.content.Context;

import java.io.PrintWriter;
import java.io.StringWriter;

final class CrashLog {
    private static final String PREFS = "crash_log";
    private static final String KEY = "last_crash";

    static void save(Context context, Throwable error) {
        try {
            StringWriter text = new StringWriter();
            error.printStackTrace(new PrintWriter(text));
            String value = text.toString();
            if (value.length() > 7000) value = value.substring(0, 7000);
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, value).commit();
        } catch (Throwable ignored) { }
    }

    static String consume(Context context) {
        android.content.SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String value = preferences.getString(KEY, null);
        if (value != null) preferences.edit().remove(KEY).apply();
        return value;
    }

    private CrashLog() { }
}
