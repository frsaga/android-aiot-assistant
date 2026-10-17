package com.example.courseprojectv3.agent;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.example.courseprojectv3.data.AppSettings;
import com.example.courseprojectv3.data.AttachmentType;
import com.example.courseprojectv3.data.ChatMessage;
import com.example.courseprojectv3.data.ChatSession;
import com.example.courseprojectv3.data.MessageRole;
import com.example.courseprojectv3.util.AttachmentReader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.OutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 云端智能体服务层。
 *
 * 答辩说明：
 * 1. Activity 不直接拼 HTTP 请求，而是通过本类调用千问 OpenAI-compatible 接口；
 * 2. 普通文本、图片识别、PDF 首页识别、文档正文读取都在这里统一组装成 messages；
 * 3. 支持 stream=true 的 SSE 流式响应，收到 delta 后回调给 MainActivity 逐字刷新气泡。
 */
public class AiAgentService {
    private static final String QWEN_ENDPOINT = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
    private static final String QWEN_VISION_MODEL = "qwen-vl-plus";

    public interface Callback {
        void onResponse(String response);
    }

    public interface StreamCallback {
        // 模型每返回一小段文字都会触发，用于实现“打字机式”流式输出。
        void onDelta(String delta);

        // 模型完整结束或本地降级回复结束时触发，用于刷新摘要、通知和持久化。
        void onComplete(String response);
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Context context;

    public AiAgentService(Context context) {
        this.context = context.getApplicationContext();
    }

    public void requestReply(ChatSession session, ChatMessage latest, AppSettings settings, Callback callback) {
        requestReply(session, latest, settings, new StreamCallback() {
            @Override
            public void onDelta(String delta) {
            }

            @Override
            public void onComplete(String response) {
                callback.onResponse(response);
            }
        });
    }

    public void requestReply(ChatSession session, ChatMessage latest, AppSettings settings, StreamCallback callback) {
        if (settings.apiKey == null || settings.apiKey.trim().isEmpty()) {
            // 未配置 API KEY 时走本地演示回复，保证答辩时即使没有网络/Key 也能展示异步流式效果。
            handler.postDelayed(() -> streamLocalText(buildFallbackReply(session, latest, settings), callback), 300);
            return;
        }
        // ExecutorService 负责网络 I/O，Handler 负责把 UI 回调切回 Android 主线程。
        executor.execute(() -> {
            try {
                // 网络请求放在线程池中执行，避免阻塞 Android 主线程造成 ANR。
                String response = callQwenStream(session, latest, settings, callback);
                handler.post(() -> callback.onComplete(response));
            } catch (Exception e) {
                String response = "千问 API 调用失败：" + readableError(e)
                        + "\n\n已保留本轮会话，你可以检查 API KEY、网络或模型名称后重试。";
                streamLocalText(response, callback);
            }
        });
    }

    private String callQwenStream(ChatSession session, ChatMessage latest, AppSettings settings, StreamCallback callback) throws Exception {
        // 附件读取可能包含图片压缩、PDF 渲染或文档解压，因此也放在后台线程执行。
        AttachmentReader.Result attachment = readLatestAttachment(latest);
        JSONObject body = new JSONObject();
        body.put("model", chooseModel(settings.modelName, attachment));
        body.put("temperature", 0.7);
        // 千问兼容 OpenAI chat/completions 的流式协议，响应为 data: {...} 的 SSE 行。
        body.put("stream", true);
        JSONArray messages = new JSONArray();
        JSONObject system = new JSONObject();
        // system 消息约束回答风格，避免生成不适合手机屏幕的超宽内容。
        system.put("role", "system");
        system.put("content", "你是一个 AI物联网专业智能助手。回答要简洁、适合手机对话气泡展示。"
                + "可以使用 Markdown 标题、粗体、列表和代码块。优先使用分点列表，不要输出 HTML 标签；"
                + "除非每个单元格都很短，否则不要使用 Markdown 表格，三列及以上内容必须改成分组列表。"
                + "优先结合 Android、AIoT、智能体和课程设计场景。");
        messages.put(system);
        int start = Math.max(0, session.messages.size() - 8);
        // 只携带最近 8 条消息，控制请求体大小和模型上下文成本。
        for (int i = start; i < session.messages.size(); i++) {
            ChatMessage message = session.messages.get(i);
            // MainActivity 会先创建一个空的“正在输出”气泡，这里跳过它，避免发给模型空 assistant 消息。
            if (message.role == MessageRole.AGENT && (message.content == null || message.content.trim().isEmpty())) {
                continue;
            }
            JSONObject item = new JSONObject();
            item.put("role", message.role == MessageRole.AGENT ? "assistant" : "user");
            item.put("content", message.id.equals(latest.id) ? contentForLatest(message, attachment) : contentForHistory(message));
            messages.put(item);
        }
        if (session.messages.isEmpty() || !session.messages.get(session.messages.size() - 1).id.equals(latest.id)) {
            // 兜底逻辑：如果当前 session 还没包含 latest，也要把本轮用户消息放入模型上下文。
            JSONObject item = new JSONObject();
            item.put("role", "user");
            item.put("content", contentForLatest(latest, attachment));
            messages.put(item);
        }
        body.put("messages", messages);

        HttpURLConnection connection = (HttpURLConnection) new URL(QWEN_ENDPOINT).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Authorization", "Bearer " + settings.apiKey.trim());
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        // 请求体使用 UTF-8，保证中文 prompt 和文档正文不会乱码。
        try (OutputStream output = connection.getOutputStream()) {
            output.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            // 非 2xx 时读取服务端错误正文，方便用户区分 Key、模型名和网络问题。
            BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getErrorStream(), StandardCharsets.UTF_8));
            StringBuilder raw = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                raw.append(line);
            }
            throw new IllegalStateException("HTTP " + code + " " + raw);
        }
        StringBuilder full = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // 每行 SSE 可能是空行、增量 JSON，或 [DONE] 结束标记。
                String delta = parseStreamDelta(line);
                if (delta == null) {
                    break;
                }
                if (!delta.isEmpty()) {
                    full.append(delta);
                    postDelta(callback, delta);
                }
            }
        }
        String response = polishForMobile(full.toString());
        return response.isEmpty() ? "千问已返回空内容。" : response;
    }

    private AttachmentReader.Result readLatestAttachment(ChatMessage latest) throws Exception {
        // 只有本轮用户消息带附件时才读取，历史附件只保留文字摘要，避免重复读取大文件。
        if (latest.attachmentType == AttachmentType.NONE) {
            return null;
        }
        if (context == null) {
            throw new IllegalStateException("缺少 Context，无法读取附件内容");
        }
        return AttachmentReader.read(context, latest.attachmentType, latest.attachmentUri);
    }

    private Object contentForLatest(ChatMessage message, AttachmentReader.Result attachment) {
        // 图片、PDF 首页等视觉输入使用 OpenAI-compatible 的 content array：text + image_url。
        if (message.attachmentType == AttachmentType.PHOTO && attachment != null && attachment.hasImage()) {
            return visionContent("用户问题：" + message.content + "\n请真实识别图片内容，并结合 AIoT/Android 课程项目场景回答。", attachment.imageDataUrl);
        }
        if (message.attachmentType == AttachmentType.DOCUMENT && attachment != null) {
            if (attachment.hasText()) {
                // txt、md、csv、json、xml、html、docx 等可读取正文的文档直接拼入 prompt。
                return "用户问题：" + message.content
                        + "\n文档名称：" + attachment.displayName
                        + "\n读取结果：" + attachment.note
                        + "\n文档正文如下：\n" + attachment.extractedText;
            }
            if (attachment.hasImage()) {
                // PDF 或图片型文档转成图片交给视觉模型识别。
                return visionContent("用户问题：" + message.content
                        + "\n文档名称：" + attachment.displayName
                        + "\n" + attachment.note
                        + "\n请阅读图片/文档页面内容并回答用户问题。", attachment.imageDataUrl);
            }
            return "用户上传了文档，但无法读取正文。用户问题：" + message.content + "\n说明：" + attachment.note;
        }
        if (message.attachmentType == AttachmentType.AUDIO) {
            // 新流程会先通过 Qwen-ASR 转写，再按普通文本发送；此处仅兼容旧历史消息。
            return message.content;
        }
        return message.content;
    }

    private JSONArray visionContent(String prompt, String imageDataUrl) {
        JSONArray content = new JSONArray();
        JSONObject text = new JSONObject();
        JSONObject imageUrl = new JSONObject();
        JSONObject image = new JSONObject();
        try {
            text.put("type", "text");
            text.put("text", prompt);
            // OpenAI-compatible 视觉格式要求 image_url 字段中放 URL 或 data URL。
            imageUrl.put("url", imageDataUrl);
            image.put("type", "image_url");
            image.put("image_url", imageUrl);
            content.put(text);
            content.put(image);
        } catch (Exception ignored) {
        }
        return content;
    }

    private String contentForHistory(ChatMessage message) {
        if (message.attachmentType == AttachmentType.PHOTO) {
            return "用户曾上传图片并提问：" + message.content;
        }
        if (message.attachmentType == AttachmentType.DOCUMENT) {
            return "用户曾上传文档并提问：" + message.content;
        }
        if (message.attachmentType == AttachmentType.AUDIO) {
            return "用户曾通过语音输入：" + message.content;
        }
        return message.content;
    }

    private String chooseModel(String modelName, AttachmentReader.Result attachment) {
        // 只要本轮附件含图片，就自动切到视觉模型；普通文本问题使用设置页选择的文本模型。
        if (attachment != null && attachment.hasImage()) {
            return QWEN_VISION_MODEL;
        }
        return normalizeModel(modelName);
    }

    private String normalizeModel(String modelName) {
        if (modelName == null || modelName.trim().isEmpty()) {
            return "qwen-plus";
        }
        if (modelName.equals("AIoT-MultiModal-Pro") || modelName.equals("Qwen-Omni")) {
            return "qwen-plus";
        }
        return modelName.trim();
    }

    private String readableError(Exception e) {
        String message = e.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return e.getClass().getSimpleName();
        }
        return message.length() > 220 ? message.substring(0, 220) + "..." : message;
    }

    private String parseStreamDelta(String line) throws Exception {
        // 解析 SSE：正常增量在 choices[0].delta.content，[DONE] 表示流结束。
        String trimmed = line == null ? "" : line.trim();
        if (trimmed.isEmpty() || !trimmed.startsWith("data:")) {
            return "";
        }
        String payload = trimmed.substring(5).trim();
        if ("[DONE]".equals(payload)) {
            return null;
        }
        JSONObject json = new JSONObject(payload);
        JSONArray choices = json.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            return "";
        }
        JSONObject choice = choices.optJSONObject(0);
        if (choice == null) {
            return "";
        }
        JSONObject delta = choice.optJSONObject("delta");
        if (delta != null) {
            // 流式接口通常把新增字符放在 delta.content。
            Object content = delta.opt("content");
            if (content != null && !"null".equals(String.valueOf(content))) {
                return String.valueOf(content);
            }
        }
        JSONObject message = choice.optJSONObject("message");
        // 少数兼容接口不返回 delta，而是在 message.content 中一次性返回全文。
        return message == null ? "" : message.optString("content", "");
    }

    private void postDelta(StreamCallback callback, String delta) {
        handler.post(() -> callback.onDelta(delta));
    }

    private void streamLocalText(String text, StreamCallback callback) {
        // 本地回复也拆成单字回调，演示时能看到和云端一致的逐字生成体验。
        String value = text == null ? "" : text;
        int intervalMs = 18;
        for (int i = 0; i < value.length(); i++) {
            String delta = String.valueOf(value.charAt(i));
            // 每个字符按固定延迟投递到主线程，模拟真实 SSE 的逐字显示。
            handler.postDelayed(() -> callback.onDelta(delta), (long) i * intervalMs);
        }
        handler.postDelayed(() -> callback.onComplete(value), (long) value.length() * intervalMs + 40);
    }

    private String polishForMobile(String text) {
        if (text == null) {
            return "";
        }
        return text.trim();
    }

    private String buildFallbackReply(ChatSession session, ChatMessage latest, AppSettings settings) {
        // 没有 API KEY 或调用失败时的本地降级，不冒充真实大模型，只用于功能演示和离线答辩。
        String model = settings.modelName == null ? "qwen-plus" : normalizeModel(settings.modelName);
        if (latest.attachmentType == AttachmentType.PHOTO) {
            return "已接收图片输入。当前没有检测到 API KEY，因此使用本地演示回复；配置千问 API KEY 后会调用 " + model + "。";
        }
        if (latest.attachmentType == AttachmentType.DOCUMENT) {
            return "文档已加入当前会话。当前没有检测到 API KEY，因此使用本地演示回复；配置千问 API KEY 后会调用 " + model + "。";
        }
        if (latest.attachmentType == AttachmentType.AUDIO) {
            return "已接收语音内容。当前没有检测到 API KEY，因此使用本地演示回复；配置千问 API KEY 后会调用 " + model + "。";
        }
        String text = latest.content == null ? "" : latest.content.trim();
        if (text.contains("物联网") || text.contains("iot") || text.contains("IoT")) {
            return "从 AIoT 角度看，可以按“设备感知层、网络传输层、智能决策层、移动端交互层”拆解。当前 APP 已覆盖会话、统计、配置和本地智能体演示模块。";
        }
        if (text.contains("架构") || text.contains("答辩")) {
            return "答辩时建议说明：Activity + XML 负责界面，Adapter 负责列表，AppStorage 负责持久化，AiAgentService 负责异步模型响应，LocalClawAgent 负责本地系统能力调用。";
        }
        if (session.messages.size() < 4) {
            return "我已经建立当前会话上下文。你可以继续提问，或上传拍照、文档、录音来测试多模态流程。";
        }
        return "收到。当前使用 " + model + " 生成回复，并已把这轮对话保存到本地历史会话中。";
    }
}
