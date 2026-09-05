package ru.tubetv.app;

import android.app.Application;

public final class TubeApplication extends Application {
    private final ImageLoader imageLoader = new ImageLoader();

    @Override public void onCreate() {
        super.onCreate();
        ScopedCookies.install();
        clearObsoleteCrashAfterUpdate();
        new Thread(DeviceCapabilities::warmUp, "codec-detection").start();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            CrashLog.save(getApplicationContext(), error);
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    ImageLoader imageLoader() { return imageLoader; }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_RUNNING_LOW) imageLoader.trimMemory();
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
