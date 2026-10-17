package com.example.courseprojectv3.agent;

import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 本地录音云端转写服务。
 *
 * 使用 qwen3-asr-flash 将短 M4A 录音编码为 Base64 Data URL 后转成文字。
 * 文件读取和网络请求在后台线程执行，结果通过主线程回调交给页面。
 */
public class SpeechTranscriptionService {
    // 千问 OpenAI-compatible 接口。语音识别和普通聊天共用 chat/completions 地址，
    // 区别主要体现在 model 与 messages.content 的数据结构。
    private static final String ENDPOINT =
            "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";

    // qwen3-asr-flash 适合 5 分钟以内的短录音，可直接接收 Base64 Data URL。
    private static final String MODEL = "qwen3-asr-flash";

    // Base64 会膨胀约三分之一，因此给接口 10 MB 输入上限预留空间。
    private static final long MAX_AUDIO_BYTES = 7L * 1024L * 1024L;

    /**
     * 转写结果回调。
     *
     * Service 不直接操作输入框和 Toast，而是将成功文本或错误信息交还给 MainActivity，
     * 这样网络层与页面层保持分离。
     */
    public interface Callback {
        void onSuccess(String text);

        void onError(String message);
    }

    // 文件读取、Base64 编码和 HTTP 请求不能在 Android 主线程执行，否则可能造成界面卡顿或 ANR。
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    // 网络任务完成后，通过主线程 Handler 回调 MainActivity，页面才能安全更新控件。
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /**
     * 提交本地录音进行云端转写。
     *
     * @param audioFile MediaRecorder 生成的本地 M4A 文件
     * @param apiKey    设置页面保存的千问 API Key
     * @param callback  成功或失败结果回调
     */
    public void transcribe(File audioFile, String apiKey, Callback callback) {
        // 请求前先在本地检查文件，避免为无效录音发起网络请求。
        if (audioFile == null || !audioFile.exists() || audioFile.length() == 0) {
            callback.onError("录音文件不存在或内容为空");
            return;
        }

        // qwen3-asr-flash 的输入上限为 10 MB；Base64 编码后体积会增加，
        // 所以源文件使用更保守的 7 MB 限制。
        if (audioFile.length() > MAX_AUDIO_BYTES) {
            callback.onError("录音文件过大，请将录音控制在 5 分钟以内");
            return;
        }

        // 本项目复用设置页面中的 DashScope API Key，不在源码中写死密钥。
        if (apiKey == null || apiKey.trim().isEmpty()) {
            callback.onError("请先在设置中填写千问 API Key");
            return;
        }

        // 单线程执行器保证同一 Service 的录音请求按顺序执行，避免并发占用大量内存。
        executor.execute(() -> {
            try {
                String result = callQwenAsr(audioFile, apiKey.trim());
                // Callback 最终会修改输入框，因此必须切回 Android 主线程。
                mainHandler.post(() -> callback.onSuccess(result));
            } catch (Exception e) {
                String message = readableError(e);
                mainHandler.post(() -> callback.onError(message));
            }
        });
    }

    /**
     * 读取录音、构造请求 JSON，并调用千问语音识别接口。
     */
    private String callQwenAsr(File audioFile, String apiKey) throws Exception {
        // 录音位于应用私有目录，先读取成字节数组再编码，不需要上传到公网文件服务器。
        byte[] audioBytes = readAllBytes(audioFile);

        // MediaRecorder 使用 MPEG-4 容器和 AAC 编码，对应 audio/mp4。
        // Data URL 由 MIME 类型、base64 标记和音频编码内容三部分组成。
        String dataUrl = "data:audio/mp4;base64,"
                + Base64.encodeToString(audioBytes, Base64.NO_WRAP);

        /*
         * 请求中的语音内容结构：
         *
         * {
         *   "type": "input_audio",
         *   "input_audio": {
         *     "data": "data:audio/mp4;base64,..."
         *   }
         * }
         *
         * 这是千问 OpenAI-compatible 多模态消息要求的格式。
         */
        JSONObject inputAudio = new JSONObject();
        inputAudio.put("data", dataUrl);
        JSONObject contentItem = new JSONObject();
        contentItem.put("type", "input_audio");
        contentItem.put("input_audio", inputAudio);

        // content 使用数组结构，未来也可以在同一条消息中继续加入文字提示。
        JSONArray content = new JSONArray();
        content.put(contentItem);

        // 录音作为用户输入提交，因此消息角色为 user。
        JSONObject userMessage = new JSONObject();
        userMessage.put("role", "user");
        userMessage.put("content", content);
        JSONArray messages = new JSONArray();
        messages.put(userMessage);

        // 明确指定中文可提高当前课程演示场景下的识别稳定性。
        JSONObject asrOptions = new JSONObject();
        asrOptions.put("language", "zh");

        // ITN（逆文本标准化）会把“一百二十三”等口语结果转换成“123”等书面形式。
        asrOptions.put("enable_itn", true);

        // stream=false 表示等待完整转写结果；短录音无需处理流式 SSE。
        JSONObject body = new JSONObject();
        body.put("model", MODEL);
        body.put("messages", messages);
        body.put("stream", false);
        body.put("asr_options", asrOptions);

        // 使用 Android/Java 自带 HttpURLConnection，避免额外引入网络库。
        HttpURLConnection connection = (HttpURLConnection) new URL(ENDPOINT).openConnection();
        connection.setRequestMethod("POST");

        // 连接超时负责建立网络连接，读取超时负责等待模型完成音频转写。
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(60000);
        connection.setDoOutput(true);

        // API Key 通过 Bearer Token 传递，请求正文统一使用 UTF-8 JSON。
        connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        try {
            // try-with-resources 会在写入完成或发生异常后自动关闭输出流。
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }

            int statusCode = connection.getResponseCode();

            // 2xx 读取正常响应；其他状态读取错误流，便于向用户展示真实错误原因。
            String raw = readResponse(
                    statusCode >= 200 && statusCode < 300
                            ? connection.getInputStream()
                            : connection.getErrorStream()
            );
            if (statusCode < 200 || statusCode >= 300) {
                throw new IllegalStateException("接口返回 HTTP " + statusCode + "：" + raw);
            }
            return parseTranscript(raw);
        } finally {
            // 无论请求成功还是失败都断开连接，及时释放底层 Socket。
            connection.disconnect();
        }
    }

    /**
     * 从 OpenAI-compatible 响应中提取最终转写文字。
     */
    private String parseTranscript(String raw) throws Exception {
        JSONObject json = new JSONObject(raw);

        // 标准返回路径为 choices[0].message.content。
        JSONArray choices = json.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            throw new IllegalStateException("接口未返回识别结果");
        }
        JSONObject message = choices.getJSONObject(0).optJSONObject("message");
        if (message == null) {
            throw new IllegalStateException("识别结果格式不正确");
        }
        Object content = message.opt("content");
        String text = "";

        // 大多数情况下 content 是普通字符串。
        if (content instanceof String) {
            text = ((String) content).trim();
        } else if (content instanceof JSONArray) {
            // 兼容多模态接口将文本包装在 content 数组中的返回格式。
            JSONArray items = (JSONArray) content;
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < items.length(); i++) {
                Object item = items.opt(i);
                if (item instanceof JSONObject) {
                    builder.append(((JSONObject) item).optString("text", ""));
                } else if (item != null) {
                    builder.append(item);
                }
            }
            text = builder.toString().trim();
        }

        // 接口请求成功但未识别到有效人声时，也按失败处理并允许用户重试。
        if (text.isEmpty()) {
            throw new IllegalStateException("没有识别到清晰语音，请重试");
        }
        return text;
    }

    /**
     * 将录音文件完整读取为字节数组。
     *
     * 文件已经在 transcribe() 中检查过大小，因此这里可以安全地一次性放入内存。
     */
    private byte[] readAllBytes(File file) throws Exception {
        try (InputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    /**
     * 将 HTTP 响应流读取为完整字符串。
     */
    private String readResponse(InputStream input) throws Exception {
        if (input == null) {
            return "";
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            StringBuilder result = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                result.append(line);
            }
            return result.toString();
        }
    }

    /**
     * 将底层网络或 JSON 异常转换为适合 Toast 展示的简短文本。
     */
    private String readableError(Exception error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return "语音转写失败，请检查网络后重试";
        }
        // 服务端错误正文可能很长，截断后避免 Toast 占满屏幕。
        return message.length() > 260 ? message.substring(0, 260) + "..." : message;
    }
}
