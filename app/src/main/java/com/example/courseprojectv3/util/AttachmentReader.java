package com.example.courseprojectv3.util;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.Base64;

import com.example.courseprojectv3.data.AttachmentType;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 多模态附件读取工具。
 *
 * 答辩说明：
 * 1. 图片会转成 base64 data URL，交给 qwen-vl-plus 做视觉识别；
 * 2. PDF 使用 Android PdfRenderer 把第一页渲染成图片，再交给视觉模型；
 * 3. txt/md/csv/json/xml/html/docx 会读取正文，作为文本上下文交给普通模型。
 */
public class AttachmentReader {
    private static final int MAX_TEXT_BYTES = 256 * 1024;
    private static final int MAX_TEXT_CHARS = 12000;
    private static final int MAX_IMAGE_SIDE = 1280;

    public static class Result {
        public final String displayName;
        public final String extractedText;
        public final String imageDataUrl;
        public final String note;

        Result(String displayName, String extractedText, String imageDataUrl, String note) {
            this.displayName = displayName;
            this.extractedText = extractedText;
            this.imageDataUrl = imageDataUrl;
            this.note = note;
        }

        public boolean hasImage() {
            return imageDataUrl != null && !imageDataUrl.isEmpty();
        }

        public boolean hasText() {
            return extractedText != null && !extractedText.trim().isEmpty();
        }
    }

    public static Result read(Context context, AttachmentType type, String uriText) throws IOException {
        // 所有系统选择器返回的文件都用 Uri 访问，符合 Android 沙盒存储规则。
        if (uriText == null || uriText.isEmpty()) {
            return new Result("", "", "", "没有可读取的附件 Uri。");
        }
        Uri uri = Uri.parse(uriText);
        String name = displayName(context, uri);
        String mime = context.getContentResolver().getType(uri);
        String lowerName = name.toLowerCase(Locale.ROOT);
        String lowerMime = mime == null ? "" : mime.toLowerCase(Locale.ROOT);

        // 同时检查业务类型、MIME 和扩展名，兼容部分文件选择器返回 MIME 不准确的情况。
        if (type == AttachmentType.PHOTO || lowerMime.startsWith("image/") || isImageName(lowerName)) {
            // 图片类附件走视觉输入，不在本地做 OCR。
            return new Result(name, "", imageToDataUrl(context, uri), "图片已作为视觉输入发送给 Qwen-VL。");
        }
        if (lowerMime.equals("application/pdf") || lowerName.endsWith(".pdf")) {
            // PDF 先渲染第一页，解决模型接口不能直接读取本地 Uri 的问题。
            return new Result(name, "", pdfFirstPageToDataUrl(context, uri), "PDF 已渲染第一页作为视觉文档发送给 Qwen-VL。");
        }
        if (lowerName.endsWith(".docx") || lowerMime.contains("wordprocessingml")) {
            // docx 本质是 zip 包，这里读取 word/document.xml 并清理标签得到正文。
            return new Result(name, readDocx(context, uri), "", "已解析 docx 正文。");
        }
        if (isTextLike(lowerName, lowerMime)) {
            return new Result(name, readText(context, uri), "", "已读取文档正文。");
        }
        return new Result(name, "", "", "暂不支持直接解析该格式，请选择 txt、md、csv、json、xml、html、docx、pdf 或图片文档。");
    }

    public static String displayName(Context context, Uri uri) {
        // 通过 OpenableColumns 读取用户看到的文件名，用于 prompt 和会话气泡展示。
        try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String value = cursor.getString(index);
                    if (value != null && !value.trim().isEmpty()) {
                        return value;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        String last = uri.getLastPathSegment();
        return last == null ? "附件" : last;
    }

    private static boolean isImageName(String name) {
        return name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".webp");
    }

    private static boolean isTextLike(String name, String mime) {
        return mime.startsWith("text/")
                || mime.contains("json")
                || mime.contains("xml")
                || name.endsWith(".txt")
                || name.endsWith(".md")
                || name.endsWith(".csv")
                || name.endsWith(".json")
                || name.endsWith(".xml")
                || name.endsWith(".html")
                || name.endsWith(".htm");
    }

    private static String readText(Context context, Uri uri) throws IOException {
        // 文本文档限制最大读取大小，避免超长文件导致内存占用过高或模型上下文过长。
        byte[] bytes = readBytes(context.getContentResolver(), uri, MAX_TEXT_BYTES);
        String value = new String(bytes, StandardCharsets.UTF_8);
        return limit(value.replace('\u0000', ' '));
    }

    private static String readDocx(Context context, Uri uri) throws IOException {
        // 只解析 docx 主文档正文，满足课程项目“读取文档内容”的核心要求。
        try (InputStream input = context.getContentResolver().openInputStream(uri);
             ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if ("word/document.xml".equals(entry.getName())) {
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    byte[] buffer = new byte[4096];
                    int read;
                    while ((read = zip.read(buffer)) != -1 && output.size() < MAX_TEXT_BYTES) {
                        output.write(buffer, 0, read);
                    }
                    String xml = output.toString(StandardCharsets.UTF_8.name());
                    String text = xml
                            .replaceAll("</w:p>", "\n")
                            .replaceAll("<[^>]+>", "")
                            .replace("&lt;", "<")
                            .replace("&gt;", ">")
                            .replace("&amp;", "&")
                            .replace("&quot;", "\"")
                            .replace("&apos;", "'");
                    return limit(text);
                }
            }
        }
        return "";
    }

    private static byte[] readBytes(ContentResolver resolver, Uri uri, int limit) throws IOException {
        try (InputStream input = resolver.openInputStream(uri);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1 && output.size() < limit) {
                // 最后一轮只写剩余允许字节，保证严格不超过 MAX_TEXT_BYTES。
                int allowed = Math.min(read, limit - output.size());
                output.write(buffer, 0, allowed);
            }
            return output.toByteArray();
        }
    }

    private static String imageToDataUrl(Context context, Uri uri) throws IOException {
        // 先读取图片尺寸再按比例采样，避免把过大的图片直接编码进请求体。
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(input, null, bounds);
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        // inSampleSize 按 2 的倍数缩小，减少 Base64 请求体和移动端内存压力。
        options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight);
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            Bitmap bitmap = BitmapFactory.decodeStream(input, null, options);
            if (bitmap == null) {
                throw new IOException("无法解码图片");
            }
            return bitmapToDataUrl(bitmap);
        }
    }

    private static String pdfFirstPageToDataUrl(Context context, Uri uri) throws IOException {
        // PdfRenderer 是 Android 系统提供的 PDF 渲染能力，不需要额外第三方库。
        try (ParcelFileDescriptor descriptor = context.getContentResolver().openFileDescriptor(uri, "r");
             PdfRenderer renderer = new PdfRenderer(descriptor)) {
            if (renderer.getPageCount() == 0) {
                throw new IOException("PDF 没有页面");
            }
            PdfRenderer.Page page = renderer.openPage(0);
            int width = Math.min(MAX_IMAGE_SIDE, page.getWidth() * 2);
            int height = Math.max(1, width * page.getHeight() / Math.max(page.getWidth(), 1));
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            // PDF 页面可能带透明背景，先铺白色避免视觉模型收到黑底页面。
            canvas.drawColor(Color.WHITE);
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            page.close();
            return bitmapToDataUrl(bitmap);
        }
    }

    private static int sampleSize(int width, int height) {
        int sample = 1;
        while (width / sample > MAX_IMAGE_SIDE || height / sample > MAX_IMAGE_SIDE) {
            sample *= 2;
        }
        return Math.max(sample, 1);
    }

    private static String bitmapToDataUrl(Bitmap bitmap) {
        // 模型接口要求远程 URL 或 data URL，这里把本地图片编码成 data:image/jpeg;base64。
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        // JPEG 82 在清晰度和请求体大小之间折中，适合移动网络上传。
        bitmap.compress(Bitmap.CompressFormat.JPEG, 82, output);
        String base64 = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP);
        return "data:image/jpeg;base64," + base64;
    }

    private static String limit(String value) {
        String trimmed = value == null ? "" : value.replaceAll("[\\t ]+", " ").trim();
        if (trimmed.length() <= MAX_TEXT_CHARS) {
            return trimmed;
        }
        return trimmed.substring(0, MAX_TEXT_CHARS) + "\n\n[文档较长，已截取前 " + MAX_TEXT_CHARS + " 字用于分析]";
    }
}
