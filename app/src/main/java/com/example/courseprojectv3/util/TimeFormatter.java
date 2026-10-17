package com.example.courseprojectv3.util;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 时间格式化工具。
 *
 * 答辩说明：
 * 消息气泡显示 HH:mm，历史会话显示 MM-dd HH:mm，统一放在这里避免各页面重复写格式化代码。
 */
public class TimeFormatter {
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm", Locale.CHINA);
    private static final SimpleDateFormat DATE_TIME = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);

    public static String time(long millis) {
        return TIME.format(new Date(millis));
    }

    public static String dateTime(long millis) {
        return DATE_TIME.format(new Date(millis));
    }
}
