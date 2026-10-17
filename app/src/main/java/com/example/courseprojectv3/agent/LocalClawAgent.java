package com.example.courseprojectv3.agent;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Claw 本地智能体的“指令识别层”。
 *
 * 答辩说明：
 * 这里没有直接执行系统能力，只负责把自然语言解析成 Command。
 * 真正的拍照、短信、下载由 MainActivity 调用 Android 系统 Intent、SmsManager、DownloadManager 完成。
 */
public class LocalClawAgent {
    private static final Pattern URL_PATTERN = Pattern.compile("(?i)(?:https?://|www\\.)[^\\s，。；;]+");
    private static final Pattern PHONE_PATTERN = Pattern.compile("(?<!\\d)(?:\\+?\\d[\\d -]{1,22}\\d|\\d{3,20})(?!\\d)");
    private static final Pattern BODY_PATTERN = Pattern.compile("(?i)(?:内容|正文|消息|message)\\s*[:：]?\\s*(.+)$");

    public enum ActionType {
        NONE,
        SEND_SMS,
        TAKE_PHOTO,
        DOWNLOAD
    }

    public static class Command {
        public final ActionType type;
        public final String phone;
        public final String content;
        public final String url;
        public final String target;
        public final String readableText;

        Command(ActionType type, String phone, String content, String url, String target, String readableText) {
            this.type = type;
            this.phone = phone;
            this.content = content;
            this.url = url;
            this.target = target;
            this.readableText = readableText;
        }
    }

    public Command parse(String input) {
        String text = input == null ? "" : input.trim();
        if (text.isEmpty()) {
            return none();
        }
        String lower = text.toLowerCase(Locale.ROOT);
        // 课程项目中采用轻量关键词解析，优点是离线可用、逻辑清晰、便于答辩展示。
        if (text.contains("短信") || lower.contains("sms")) {
            // 先识别动作类型，再分别提取号码和正文，执行层无需再次解析自然语言。
            String phone = extractPhone(text);
            String body = extractSmsBody(text);
            String recipient = phone.isEmpty() ? "，但没有识别到收件人" : "，收件人 " + phone;
            return new Command(ActionType.SEND_SMS, phone, body, "", "", "Claw 已识别短信指令" + recipient + "。");
        }
        if (text.contains("拍照") || text.contains("相机") || lower.contains("camera") || lower.contains("take photo")) {
            return new Command(ActionType.TAKE_PHOTO, "", "", "", "", "Claw 已识别拍照指令，正在调用系统相机。");
        }
        if (text.contains("下载") || lower.contains("download")) {
            String url = extractUrl(text);
            String target = extractDownloadTarget(text, url);
            return new Command(ActionType.DOWNLOAD, "", "", url, target, "Claw 已识别下载指令。");
        }
        return none();
    }

    private String extractPhone(String text) {
        // 同时支持完整手机号和 10086、95533 等短服务号码。
        Matcher matcher = PHONE_PATTERN.matcher(text);
        while (matcher.find()) {
            // 去除用户输入中的空格和短横线，再按数字位数过滤误匹配。
            String value = matcher.group().replace(" ", "").replace("-", "");
            int digitCount = value.replaceAll("\\D", "").length();
            if (digitCount >= 3 && digitCount <= 20) {
                return value;
            }
        }
        return "";
    }

    private String extractSmsBody(String text) {
        Matcher matcher = BODY_PATTERN.matcher(text);
        if (matcher.find() && !matcher.group(1).trim().isEmpty()) {
            return matcher.group(1).trim();
        }
        return "这是一条由 AIoT 智能助手 Claw 模式生成的短信。";
    }

    private String extractUrl(String text) {
        Matcher matcher = URL_PATTERN.matcher(text);
        if (matcher.find()) {
            String value = matcher.group();
            return value.startsWith("www.") ? "https://" + value : value;
        }
        return "";
    }

    private String extractDownloadTarget(String text, String url) {
        // 去掉命令词和 URL，只保留“Android 图标”等自然语言资源名称。
        String value = url.isEmpty() ? text : text.replace(url, " ");
        return value
                .replaceAll("(?i)download", " ")
                .replace("我要", " ")
                .replace("我想", " ")
                .replace("请", " ")
                .replace("帮我", " ")
                .replace("下载", " ")
                .replace("链接", " ")
                .replaceAll("[：:，,。]", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }

    private Command none() {
        return new Command(ActionType.NONE, "", "", "", "", "");
    }
}
