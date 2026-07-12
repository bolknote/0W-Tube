package ru.tubetv.app;

import android.app.UiModeManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;

final class DeviceType {
    static boolean isTelevision(Context context) {
        PackageManager packages = context.getPackageManager();
        if (packages.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
                || packages.hasSystemFeature(PackageManager.FEATURE_TELEVISION)) return true;
        UiModeManager modes = (UiModeManager) context.getSystemService(Context.UI_MODE_SERVICE);
        return modes != null && modes.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION;
    }

    private DeviceType() { }
}
