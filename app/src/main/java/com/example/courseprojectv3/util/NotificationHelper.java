package com.example.courseprojectv3.util;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

/**
 * 应用内智能体消息通知工具。
 *
 * 答辩说明：
 * MainActivity 收到完整智能体回复后调用这里，既能 Toast 提示，也能触发系统通知栏通知。
 */
public class NotificationHelper {
    private static final String CHANNEL_ID = "aiot_agent_messages";

    public static void ensureChannel(Context context) {
        // Android 8.0 之后通知必须先创建 NotificationChannel。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "智能助手消息",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            channel.setDescription("AIoT 智能助手新消息提醒");
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    public static void showAgentMessage(Context context, String text) {
        // Android 13 之后通知权限需要运行时授权；没有权限时静默跳过，避免崩溃。
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        ensureChannel(context);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, CHANNEL_ID)
                : new Notification.Builder(context);
        Notification notification = builder
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("AIoT 智能助手")
                .setContentText(shortText(text))
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .build();
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify((int) (System.currentTimeMillis() % 100000), notification);
        }
    }

    private static String shortText(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 46 ? text.substring(0, 46) + "..." : text;
    }
}
