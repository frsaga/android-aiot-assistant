package com.example.courseprojectv3.data;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 会话数据模型。
 *
 * 答辩说明：
 * 一个 ChatSession 包含多条 ChatMessage，并保存标题、摘要、创建时间、更新时间。
 * 历史会话列表展示的标题和摘要，就是由 refreshSummary 自动生成的。
 */
public class ChatSession {
    public final String id;
    public String title;
    public String summary;
    public final long createdAt;
    public long updatedAt;
    public final List<ChatMessage> messages = new ArrayList<>();

    public ChatSession(String id, String title, String summary, long createdAt, long updatedAt) {
        this.id = id;
        this.title = title;
        this.summary = summary;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static ChatSession create() {
        // 新建会话时自动加入一条欢迎消息，让首页不是空白状态。
        long now = System.currentTimeMillis();
        String title = "新会话 " + new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(now));
        ChatSession session = new ChatSession(String.valueOf(now), title, "等待开始对话", now, now);
        session.messages.add(ChatMessage.agent("你好，我是 AIoT 智能助手。可以进行专业问答、上传拍照/文档/录音，也可以在设置中开启 Claw 模式执行本地指令。"));
        return session;
    }

    public void addMessage(ChatMessage message) {
        // 每新增一条消息都更新时间，并自动刷新会话摘要。
        messages.add(message);
        updatedAt = System.currentTimeMillis();
        refreshSummary();
    }

    public void refreshSummary() {
        // 标题取第一条用户问题，摘要取最后一条智能体回复，适合历史列表快速预览。
        String firstUser = "";
        String lastAgent = "";
        for (ChatMessage message : messages) {
            if (message.role == MessageRole.USER && firstUser.isEmpty()) {
                // 只记录第一条用户问题，保证标题在后续对话中保持稳定。
                firstUser = clean(message.content);
            }
            if (message.role == MessageRole.AGENT) {
                // 不断覆盖 lastAgent，循环结束后留下的就是最新智能体回复。
                lastAgent = clean(message.content);
            }
        }
        if (!firstUser.isEmpty()) {
            title = firstUser.length() > 16 ? firstUser.substring(0, 16) + "..." : firstUser;
        }
        if (!lastAgent.isEmpty()) {
            summary = lastAgent.length() > 48 ? lastAgent.substring(0, 48) + "..." : lastAgent;
        } else if (!firstUser.isEmpty()) {
            summary = firstUser;
        }
    }

    private String clean(String value) {
        // 历史列表只需要纯文本预览，因此移除换行、部分 Markdown 标记和 Emoji。
        return value == null ? "" : value
                .replace('\n', ' ')
                .replace("**", "")
                .replace("`", "")
                .replaceAll("[\\x{1F300}-\\x{1FAFF}\\x{2600}-\\x{27BF}]", "")
                .trim();
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("title", title);
        json.put("summary", summary);
        json.put("createdAt", createdAt);
        json.put("updatedAt", updatedAt);
        JSONArray array = new JSONArray();
        for (ChatMessage message : messages) {
            // 会话和消息形成嵌套 JSON，读取时可完整恢复聊天顺序。
            array.put(message.toJson());
        }
        json.put("messages", array);
        return json;
    }

    public static ChatSession fromJson(JSONObject json) throws JSONException {
        ChatSession session = new ChatSession(
                json.optString("id"),
                json.optString("title"),
                json.optString("summary"),
                json.optLong("createdAt"),
                json.optLong("updatedAt")
        );
        JSONArray array = json.optJSONArray("messages");
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                // 按数组原顺序恢复，确保 RecyclerView 中消息时间线不被打乱。
                session.messages.add(ChatMessage.fromJson(array.getJSONObject(i)));
            }
        }
        return session;
    }
}
