package com.example.courseprojectv3.util;

import android.view.View;
import android.view.Window;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

public class SystemBars {
    /**
     * 统一处理状态栏、导航栏和软键盘避让。
     *
     * 课程项目里每个 Activity 都是 XML + Activity 架构，没有使用 AppCompat 的 Toolbar。
     * 因此这里把系统栏适配集中到一个工具类中：页面根布局只需要调用一次 apply，
     * 顶部返回键、底部输入框、ScrollView 内容都会自动获得安全内边距。
     */
    public static void apply(Window window, View root) {
        // 所有版本都使用同一种 edge-to-edge 策略，避免二级页面在不同模拟器版本上顶部表现不一致。
        WindowCompat.setDecorFitsSystemWindows(window, false);
        final int left = root.getPaddingLeft();
        final int top = root.getPaddingTop();
        final int right = root.getPaddingRight();
        final int bottom = root.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            // 键盘弹出时用键盘高度；键盘收起时用导航栏高度，保证输入框不被遮住。
            int bottomInset = Math.max(bars.bottom, ime.bottom);
            view.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + bottomInset);
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
    }
}
