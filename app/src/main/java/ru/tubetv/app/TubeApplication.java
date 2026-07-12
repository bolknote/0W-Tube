package ru.tubetv.app;

import android.app.Application;

import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;

public final class TubeApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        if (CookieHandler.getDefault() == null) {
            CookieHandler.setDefault(new CookieManager(null, CookiePolicy.ACCEPT_ALL));
        }
        clearObsoleteCrashAfterUpdate();
        new Thread(DeviceCapabilities::warmUp, "codec-detection").start();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            CrashLog.save(getApplicationContext(), error);
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    private void clearObsoleteCrashAfterUpdate() {
        try {
            long current = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
            android.content.SharedPreferences preferences = getSharedPreferences("crash_log", MODE_PRIVATE);
            if (preferences.getLong("diagnostic_version", -1L) != current) {
                preferences.edit().remove("last_crash").putLong("diagnostic_version", current).apply();
            }
        } catch (Exception ignored) { }
    }
}
