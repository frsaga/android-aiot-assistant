package com.example.courseprojectv3.util;

/**
 * 文本预览清理工具。
 *
 * 答辩说明：
 * 历史摘要、标题栏摘要不需要显示 Markdown 符号和表情，因此统一在这里清理。
 */
public class TextCleaner {
    public static String forPreview(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("**", "")
                .replace("`", "")
                .replace("✅", "")
                .replace("📁", "")
                .replace("🔍", "")
                .replace("👇", "")
                .replace("🔷", "")
                .replaceAll("[\\x{1F300}-\\x{1FAFF}\\x{2600}-\\x{27BF}]", "")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
