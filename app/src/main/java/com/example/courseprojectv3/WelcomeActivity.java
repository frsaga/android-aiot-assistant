package com.example.courseprojectv3;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.animation.AnimationUtils;
import android.widget.TextView;

import com.example.courseprojectv3.storage.AppStorage;
import com.example.courseprojectv3.util.SystemBars;
import com.example.courseprojectv3.util.ThemeMode;

/**
 * 首次进入应用的欢迎页。
 *
 * 答辩说明：
 * 这里体现“Welcome Page 与首次加载引导动效”：进入时播放 slide_up 和 pulse 动画，
 * 点击按钮或页面任意区域后跳转到 MainActivity。
 */
public class WelcomeActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AppStorage storage = new AppStorage(this);
        ThemeMode.apply(this, storage.loadSettings().darkMode);
        super.onCreate(savedInstanceState);
        // Launcher 始终指向 WelcomeActivity，但老用户会在绘制欢迎页前直接进入主页面。
        if (storage.hasCompletedWelcome()) {
            startActivity(new Intent(this, MainActivity.class));
            finish();
            return;
        }
        setContentView(R.layout.activity_welcome);
        SystemBars.apply(getWindow(), findViewById(R.id.welcomeRoot));

        View content = findViewById(R.id.welcomeContent);
        TextView badge = findViewById(R.id.welcomeBadge);
        TextView startButton = findViewById(R.id.startButton);
        // 动效资源放在 res/anim 下，由 Activity 在页面创建时启动。
        content.startAnimation(AnimationUtils.loadAnimation(this, R.anim.slide_up));
        badge.startAnimation(AnimationUtils.loadAnimation(this, R.anim.pulse));
        View.OnClickListener enterApp = v -> {
            storage.markWelcomeCompleted();
            startActivity(new Intent(this, MainActivity.class));
            finish();
        };
        startButton.setOnClickListener(enterApp);
        findViewById(R.id.welcomeRoot).setOnClickListener(enterApp);
    }
}
