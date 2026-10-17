package com.example.courseprojectv3.util;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BulletSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.UnderlineSpan;
import android.text.style.URLSpan;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 轻量 Markdown 渲染器。
 *
 * 答辩说明：
 * 项目没有引入第三方 MarkdownView，而是用 Android Spannable 给 TextView 添加标题、粗体、列表和代码样式。
 * 这样保留 XML + Activity 的简单架构，同时满足 AI 回复 Markdown 显示。
 */
public class MarkdownRenderer {
    private static final Pattern HEADING = Pattern.compile("^\\s{0,3}(#{1,6})\\s*(.+?)\\s*#*\\s*$");
    private static final Pattern BULLET = Pattern.compile("^(\\s*)[-*+]\\s+(.+)$");
    private static final Pattern CHECKBOX = Pattern.compile("^(\\s*)[-*+]\\s+\\[([ xX])]\\s+(.+)$");
    private static final Pattern NUMBERED = Pattern.compile("^(\\s*)\\d+[.)]\\s+(.+)$");
    private static final Pattern QUOTE = Pattern.compile("^\\s{0,3}>+\\s?(.*)$");
    private static final Pattern TABLE_SEPARATOR_CELL = Pattern.compile("^:?-{3,}:?$");
    private static final Pattern HORIZONTAL_RULE = Pattern.compile("^\\s{0,3}([-*_])\\s*(\\1\\s*){2,}$");

    public static void setMarkdown(TextView view, String markdown) {
        view.setText(render(markdown == null ? "" : markdown));
    }

    public static CharSequence render(String markdown) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        boolean inCodeBlock = false;
        String fence = "";
        String[] lines = markdown.split("\\n", -1);
        for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
            String rawLine = lines[lineIndex];
            String trimmed = rawLine.trim();
            if (isFence(trimmed)) {
                String marker = trimmed.startsWith("~~~") ? "~~~" : "```";
                if (!inCodeBlock) {
                    inCodeBlock = true;
                    fence = marker;
                } else if (trimmed.startsWith(fence)) {
                    inCodeBlock = false;
                    fence = "";
                }
                continue;
            }

            int lineStart = out.length();
            if (inCodeBlock) {
                out.append(rawLine).append('\n');
                out.setSpan(new TypefaceSpan("monospace"), lineStart, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                continue;
            }

            if (trimmed.isEmpty()) {
                appendSoftLine(out);
                continue;
            }
            if (lineIndex + 1 < lines.length && isTableSeparator(lines[lineIndex + 1])) {
                lineIndex = appendTable(out, lines, lineIndex);
                continue;
            }
            if (HORIZONTAL_RULE.matcher(trimmed).matches()) {
                out.append("----------------").append('\n');
                continue;
            }

            Matcher heading = HEADING.matcher(rawLine);
            if (heading.matches()) {
                appendHeading(out, heading.group(2), heading.group(1).length(), lineStart);
            } else {
                Matcher quote = QUOTE.matcher(rawLine);
                Matcher checkbox = CHECKBOX.matcher(rawLine);
                Matcher bullet = BULLET.matcher(rawLine);
                Matcher numbered = NUMBERED.matcher(rawLine);
                if (quote.matches()) {
                    appendInline(out, quote.group(1));
                    out.setSpan(new StyleSpan(Typeface.ITALIC), lineStart, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                } else if (checkbox.matches()) {
                    String checked = checkbox.group(2).trim().equalsIgnoreCase("x") ? "[x] " : "[ ] ";
                    appendIndent(out, checkbox.group(1));
                    appendInline(out, checked + checkbox.group(3));
                    out.setSpan(new BulletSpan(18), lineStart, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                } else if (bullet.matches()) {
                    appendIndent(out, bullet.group(1));
                    appendInline(out, bullet.group(2));
                    out.setSpan(new BulletSpan(18), lineStart, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                } else if (numbered.matches()) {
                    appendIndent(out, numbered.group(1));
                    appendInline(out, rawLine.trim());
                } else {
                    appendInline(out, rawLine);
                }
            }
            out.append('\n');
        }
        trimTrailingNewlines(out);
        return out;
    }

    private static boolean isFence(String trimmed) {
        return trimmed.startsWith("```") || trimmed.startsWith("~~~");
    }

    private static void appendHeading(SpannableStringBuilder out, String text, int level, int lineStart) {
        appendInline(out, text);
        out.setSpan(new StyleSpan(Typeface.BOLD), lineStart, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        float size;
        if (level == 1) {
            size = 1.22f;
        } else if (level == 2) {
            size = 1.14f;
        } else if (level == 3) {
            size = 1.08f;
        } else {
            size = 1.02f;
        }
        out.setSpan(new RelativeSizeSpan(size), lineStart, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static int appendTable(SpannableStringBuilder out, String[] lines, int headerIndex) {
        List<List<String>> rows = new ArrayList<>();
        List<String> header = splitTableRow(lines[headerIndex]);
        List<String> separator = splitTableRow(lines[headerIndex + 1]);
        int columnCount = Math.max(header.size(), separator.size());
        if (columnCount < 2) {
            appendInline(out, lines[headerIndex]);
            out.append('\n');
            return headerIndex;
        }

        rows.add(normalizeRow(header, columnCount));
        int lastIndex = headerIndex + 1;
        for (int i = headerIndex + 2; i < lines.length; i++) {
            if (!looksLikeTableRow(lines[i])) {
                break;
            }
            rows.add(normalizeRow(splitTableRow(lines[i]), columnCount));
            lastIndex = i;
        }

        int[] widths = tableColumnWidths(rows, columnCount);
        // TextView 没有横向滚动能力。列数多或内容过长时，等宽表格会在手机上被强制折行，
        // 因此自动改成“每条记录一组字段”的纵向布局。
        if (shouldStackTable(widths)) {
            appendStackedTable(out, rows);
            return lastIndex;
        }
        int tableStart = out.length();
        int headerStart = out.length();
        appendTableRow(out, rows.get(0), widths);
        int headerEnd = out.length();
        appendTableDivider(out, widths);
        for (int i = 1; i < rows.size(); i++) {
            appendTableRow(out, rows.get(i), widths);
        }
        int tableEnd = out.length();
        out.setSpan(new TypefaceSpan("monospace"), tableStart, tableEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new StyleSpan(Typeface.BOLD), headerStart, headerEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return lastIndex;
    }

    private static boolean shouldStackTable(int[] widths) {
        int totalWidth = Math.max(0, widths.length - 1) * 3;
        for (int width : widths) {
            totalWidth += width;
            if (width > 22) {
                return true;
            }
        }
        return widths.length >= 3 || totalWidth > 38;
    }

    private static void appendStackedTable(SpannableStringBuilder out, List<List<String>> rows) {
        if (rows.size() <= 1) {
            return;
        }
        List<String> headers = rows.get(0);
        for (int rowIndex = 1; rowIndex < rows.size(); rowIndex++) {
            List<String> row = rows.get(rowIndex);
            int blockStart = out.length();
            String title = row.isEmpty() ? "" : row.get(0);
            if (!title.isEmpty()) {
                out.append("• ");
                appendInline(out, title);
                out.setSpan(new StyleSpan(Typeface.BOLD), blockStart, out.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.append('\n');
            }
            for (int column = 1; column < headers.size() && column < row.size(); column++) {
                String value = row.get(column);
                if (value.isEmpty()) {
                    continue;
                }
                String label = headers.get(column).isEmpty() ? "说明" : headers.get(column);
                out.append("  ");
                int labelStart = out.length();
                appendInline(out, label + "：");
                out.setSpan(new StyleSpan(Typeface.BOLD), labelStart, out.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                appendInline(out, value);
                out.append('\n');
            }
            if (rowIndex < rows.size() - 1) {
                out.append('\n');
            }
        }
    }

    private static boolean isTableSeparator(String line) {
        List<String> cells = splitTableRow(line);
        if (cells.size() < 2) {
            return false;
        }
        for (String cell : cells) {
            if (!TABLE_SEPARATOR_CELL.matcher(cell.replace(" ", "")).matches()) {
                return false;
            }
        }
        return true;
    }

    private static boolean looksLikeTableRow(String line) {
        String trimmed = line == null ? "" : line.trim();
        return !trimmed.isEmpty() && splitTableRow(trimmed).size() >= 2 && containsUnescapedPipe(trimmed);
    }

    private static List<String> splitTableRow(String line) {
        String value = line == null ? "" : line.trim();
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean inCode = false;
        boolean escaped = false;
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (escaped) {
                cell.append(current);
                escaped = false;
                continue;
            }
            if (current == '\\') {
                escaped = true;
                continue;
            }
            if (current == '`') {
                inCode = !inCode;
                cell.append(current);
                continue;
            }
            if (current == '|' && !inCode) {
                cells.add(cell.toString().trim());
                cell.setLength(0);
            } else {
                cell.append(current);
            }
        }
        if (escaped) {
            cell.append('\\');
        }
        cells.add(cell.toString().trim());

        if (value.startsWith("|") && !cells.isEmpty()) {
            cells.remove(0);
        }
        if (value.endsWith("|") && !cells.isEmpty()) {
            cells.remove(cells.size() - 1);
        }
        return cells;
    }

    private static boolean containsUnescapedPipe(String value) {
        boolean escaped = false;
        boolean inCode = false;
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (escaped) {
                escaped = false;
            } else if (current == '\\') {
                escaped = true;
            } else if (current == '`') {
                inCode = !inCode;
            } else if (current == '|' && !inCode) {
                return true;
            }
        }
        return false;
    }

    private static List<String> normalizeRow(List<String> row, int columnCount) {
        List<String> normalized = new ArrayList<>();
        for (int i = 0; i < columnCount; i++) {
            normalized.add(i < row.size() ? stripInlineForTable(row.get(i)) : "");
        }
        return normalized;
    }

    private static String stripInlineForTable(String value) {
        return value
                // 模型有时会在 Markdown 表格单元格中混入 HTML 换行。
                .replaceAll("(?i)<br\\s*/?>", "；")
                .replaceAll("\\*\\*(.+?)\\*\\*", "$1")
                .replaceAll("__(.+?)__", "$1")
                .replaceAll("~~(.+?)~~", "$1")
                .replaceAll("`(.+?)`", "$1")
                .trim();
    }

    private static int[] tableColumnWidths(List<List<String>> rows, int columnCount) {
        int[] widths = new int[columnCount];
        for (List<String> row : rows) {
            for (int i = 0; i < columnCount; i++) {
                widths[i] = Math.max(widths[i], displayWidth(row.get(i)));
            }
        }
        for (int i = 0; i < widths.length; i++) {
            widths[i] = Math.max(2, widths[i]);
        }
        return widths;
    }

    private static void appendTableRow(SpannableStringBuilder out, List<String> row, int[] widths) {
        for (int i = 0; i < widths.length; i++) {
            if (i > 0) {
                out.append(" │ ");
            }
            String value = row.get(i);
            out.append(value);
            appendSpaces(out, widths[i] - displayWidth(value));
        }
        out.append('\n');
    }

    private static void appendTableDivider(SpannableStringBuilder out, int[] widths) {
        for (int i = 0; i < widths.length; i++) {
            if (i > 0) {
                out.append("─┼─");
            }
            for (int j = 0; j < widths[i]; j++) {
                out.append('─');
            }
        }
        out.append('\n');
    }

    private static int displayWidth(String value) {
        int width = 0;
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            width += isWideCodePoint(codePoint) ? 2 : 1;
            offset += Character.charCount(codePoint);
        }
        return width;
    }

    private static boolean isWideCodePoint(int codePoint) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(codePoint);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.HIRAGANA
                || block == Character.UnicodeBlock.KATAKANA
                || block == Character.UnicodeBlock.HANGUL_SYLLABLES
                || block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS;
    }

    private static void appendSpaces(SpannableStringBuilder out, int count) {
        for (int i = 0; i < count; i++) {
            out.append(' ');
        }
    }

    private static void appendSoftLine(SpannableStringBuilder out) {
        if (out.length() == 0 || out.charAt(out.length() - 1) != '\n') {
            out.append('\n');
        }
    }

    private static void appendIndent(SpannableStringBuilder out, String indent) {
        int count = indent == null ? 0 : Math.min(indent.length(), 6);
        for (int i = 0; i < count; i++) {
            out.append(' ');
        }
    }

    private static void appendInline(SpannableStringBuilder out, String text) {
        int i = 0;
        while (i < text.length()) {
            int htmlBreakLength = htmlBreakLengthAt(text, i);
            if (htmlBreakLength > 0) {
                out.append('\n');
                i += htmlBreakLength;
                continue;
            }
            if (text.charAt(i) == '\\' && i + 1 < text.length()) {
                out.append(text.charAt(i + 1));
                i += 2;
                continue;
            }
            if (text.startsWith("![", i)) {
                int closeLabel = text.indexOf("](", i + 2);
                int closeUrl = closeLabel < 0 ? -1 : text.indexOf(')', closeLabel + 2);
                if (closeUrl > closeLabel) {
                    String label = text.substring(i + 2, closeLabel).trim();
                    appendStyled(out, label.isEmpty() ? "图片" : "图片: " + label, null, false, true, false, null);
                    i = closeUrl + 1;
                    continue;
                }
            }
            if (text.charAt(i) == '[') {
                int closeLabel = text.indexOf("](", i + 1);
                int closeUrl = closeLabel < 0 ? -1 : text.indexOf(')', closeLabel + 2);
                if (closeUrl > closeLabel) {
                    String label = text.substring(i + 1, closeLabel);
                    String url = text.substring(closeLabel + 2, closeUrl);
                    appendStyled(out, label, null, false, true, false, url);
                    i = closeUrl + 1;
                    continue;
                }
            }
            if (text.startsWith("**", i) || text.startsWith("__", i)) {
                String marker = text.substring(i, i + 2);
                int end = text.indexOf(marker, i + 2);
                if (end > i + 2) {
                    appendStyled(out, text.substring(i + 2, end), Typeface.BOLD, false, false, false, null);
                    i = end + 2;
                    continue;
                }
            }
            if (text.startsWith("~~", i)) {
                int end = text.indexOf("~~", i + 2);
                if (end > i + 2) {
                    appendStyled(out, text.substring(i + 2, end), null, false, false, true, null);
                    i = end + 2;
                    continue;
                }
            }
            if (text.charAt(i) == '`') {
                int end = text.indexOf('`', i + 1);
                if (end > i + 1) {
                    appendCode(out, text.substring(i + 1, end));
                    i = end + 1;
                    continue;
                }
            }
            if (isItalicMarker(text, i)) {
                char marker = text.charAt(i);
                int end = findItalicEnd(text, marker, i + 1);
                if (end > i + 1) {
                    appendStyled(out, text.substring(i + 1, end), Typeface.ITALIC, false, false, false, null);
                    i = end + 1;
                    continue;
                }
            }
            out.append(text.charAt(i));
            i++;
        }
    }

    private static int htmlBreakLengthAt(String text, int index) {
        int remaining = text.length() - index;
        if (remaining >= 4 && text.regionMatches(true, index, "<br>", 0, 4)) {
            return 4;
        }
        if (remaining >= 5 && text.regionMatches(true, index, "<br/>", 0, 5)) {
            return 5;
        }
        if (remaining >= 6 && text.regionMatches(true, index, "<br />", 0, 6)) {
            return 6;
        }
        return 0;
    }

    private static boolean isItalicMarker(String text, int index) {
        char marker = text.charAt(index);
        if (marker != '*' && marker != '_') {
            return false;
        }
        if (index + 1 < text.length() && text.charAt(index + 1) == marker) {
            return false;
        }
        if (index > 0 && Character.isLetterOrDigit(text.charAt(index - 1))) {
            return false;
        }
        return index + 1 < text.length() && !Character.isWhitespace(text.charAt(index + 1));
    }

    private static int findItalicEnd(String text, char marker, int start) {
        for (int i = start; i < text.length(); i++) {
            if (text.charAt(i) == marker
                    && (i + 1 >= text.length() || text.charAt(i + 1) != marker)
                    && i > start
                    && !Character.isWhitespace(text.charAt(i - 1))) {
                return i;
            }
        }
        return -1;
    }

    private static void appendStyled(SpannableStringBuilder out, String text, Integer typeface, boolean code,
                                     boolean underline, boolean strike, String url) {
        int start = out.length();
        appendInline(out, text);
        if (typeface != null) {
            out.setSpan(new StyleSpan(typeface), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (code) {
            out.setSpan(new TypefaceSpan("monospace"), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (underline) {
            out.setSpan(new UnderlineSpan(), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (strike) {
            out.setSpan(new StrikethroughSpan(), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (url != null && !url.trim().isEmpty()) {
            out.setSpan(new URLSpan(url), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private static void appendCode(SpannableStringBuilder out, String text) {
        int start = out.length();
        out.append(text);
        out.setSpan(new TypefaceSpan("monospace"), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static void trimTrailingNewlines(SpannableStringBuilder out) {
        while (out.length() > 0 && out.charAt(out.length() - 1) == '\n') {
            out.delete(out.length() - 1, out.length());
        }
    }
}
