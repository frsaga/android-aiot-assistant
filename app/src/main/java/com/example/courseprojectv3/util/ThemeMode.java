package com.example.courseprojectv3.util;

import android.app.UiModeManager;
import android.content.Context;
import android.os.Build;

/**
 * 深浅色模式工具。
 *
 * 答辩说明：
 * 设置页保存 darkMode 后，各 Activity 创建时都会调用这里应用系统夜间模式。
 */
public class ThemeMode {
    public static void apply(Context context, boolean darkMode) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return;
        }
        // UiModeManager 是 Android 系统提供的应用夜间模式切换入口。
        UiModeManager manager = (UiModeManager) context.getSystemService(Context.UI_MODE_SERVICE);
        if (manager != null) {
            manager.setApplicationNightMode(darkMode ? UiModeManager.MODE_NIGHT_YES : UiModeManager.MODE_NIGHT_NO);
        }
    }
}
