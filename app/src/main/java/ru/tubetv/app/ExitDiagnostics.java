package ru.tubetv.app;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Build;

import java.util.List;

final class ExitDiagnostics {
    static String consume(Context context) {
        if (Build.VERSION.SDK_INT < 30) return null;
        try {
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            List<ApplicationExitInfo> exits = manager.getHistoricalProcessExitReasons(context.getPackageName(), 0, 5);
            if (exits == null || exits.isEmpty()) return null;
            ApplicationExitInfo exit = exits.get(0);
            long seen = context.getSharedPreferences("crash_log", Context.MODE_PRIVATE)
                    .getLong("last_exit_timestamp", 0L);
            if (exit.getTimestamp() <= seen) return null;
            context.getSharedPreferences("crash_log", Context.MODE_PRIVATE).edit()
                    .putLong("last_exit_timestamp", exit.getTimestamp()).apply();
            int reason = exit.getReason();
            if (reason != ApplicationExitInfo.REASON_CRASH
                    && reason != ApplicationExitInfo.REASON_CRASH_NATIVE
                    && reason != ApplicationExitInfo.REASON_ANR
                    && reason != ApplicationExitInfo.REASON_LOW_MEMORY
                    && reason != ApplicationExitInfo.REASON_SIGNALED
                    && reason != ApplicationExitInfo.REASON_INITIALIZATION_FAILURE) return null;
            return "Системная причина: " + reasonName(reason)
                    + "\nОписание: " + exit.getDescription()
                    + "\nСтатус: " + exit.getStatus()
                    + "\nPSS/RSS: " + exit.getPss() + "/" + exit.getRss() + " КБ";
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String reasonName(int reason) {
        switch (reason) {
            case ApplicationExitInfo.REASON_CRASH: return "Java crash";
            case ApplicationExitInfo.REASON_CRASH_NATIVE: return "native crash";
            case ApplicationExitInfo.REASON_ANR: return "ANR";
            case ApplicationExitInfo.REASON_LOW_MEMORY: return "нехватка памяти";
            case ApplicationExitInfo.REASON_SIGNALED: return "процесс завершён сигналом";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: return "ошибка инициализации";
            default: return String.valueOf(reason);
        }
    }

    private ExitDiagnostics() { }
}
