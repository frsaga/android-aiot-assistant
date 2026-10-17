package com.example.courseprojectv3.data;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 单条聊天消息的数据模型。
 *
 * 答辩说明：
 * role 区分用户和智能体，attachmentType/attachmentUri 保存多模态附件信息；
 * toJson/fromJson 让消息可以被 AppStorage 持久化到本地。
 */
public class ChatMessage {
    public final String id;
    public final MessageRole role;
    public String content;
    public final AttachmentType attachmentType;
    public final String attachmentUri;
    public final String attachmentName;
    public final long createdAt;

    public ChatMessage(String id, MessageRole role, String content, AttachmentType attachmentType,
                       String attachmentUri, String attachmentName, long createdAt) {
        this.id = id;
        this.role = role;
        this.content = content;
        this.attachmentType = attachmentType;
        this.attachmentUri = attachmentUri;
        this.attachmentName = attachmentName == null ? "" : attachmentName;
        this.createdAt = createdAt;
    }

    public static ChatMessage userText(String content) {
        // nanoTime 用作进程内高精度唯一 id，createdAt 另存真实墙上时间用于界面显示。
        return new ChatMessage(String.valueOf(System.nanoTime()), MessageRole.USER, content,
                AttachmentType.NONE, "", "", System.currentTimeMillis());
    }

    public static ChatMessage userAttachment(AttachmentType type, String uri, String label) {
        return new ChatMessage(String.valueOf(System.nanoTime()), MessageRole.USER, label,
                type, uri, label, System.currentTimeMillis());
    }

    public static ChatMessage userWithAttachment(String content, AttachmentType type, String uri) {
        return userWithAttachment(content, type, uri, "");
    }

    public static ChatMessage userWithAttachment(String content, AttachmentType type, String uri, String displayName) {
        // 消息只保存附件 Uri 和名称，真正读取文件内容发生在 AiAgentService 的后台线程。
        return new ChatMessage(String.valueOf(System.nanoTime()), MessageRole.USER, content,
                type, uri, displayName, System.currentTimeMillis());
    }

    public static ChatMessage agent(String content) {
        return new ChatMessage(String.valueOf(System.nanoTime()), MessageRole.AGENT, content,
                AttachmentType.NONE, "", "", System.currentTimeMillis());
    }

    public void appendContent(String delta) {
        // 流式输出时不会新建多条消息，而是不断把 delta 追加到同一条智能体消息里。
        content = (content == null ? "" : content) + (delta == null ? "" : delta);
    }

    public JSONObject toJson() throws JSONException {
        // JSON 字段保持简单，便于答辩时展示本地持久化结构。
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("role", role.name());
        json.put("content", content);
        json.put("attachmentType", attachmentType.name());
        json.put("attachmentUri", attachmentUri);
        json.put("attachmentName", attachmentName);
        json.put("createdAt", createdAt);
        return json;
    }

    public static ChatMessage fromJson(JSONObject json) throws JSONException {
        // opt 系列方法提供默认值，可兼容旧版本 JSON 缺少 attachmentName 等字段。
        return new ChatMessage(
                json.optString("id"),
                MessageRole.valueOf(json.optString("role", MessageRole.USER.name())),
                json.optString("content"),
                AttachmentType.valueOf(json.optString("attachmentType", AttachmentType.NONE.name())),
                json.optString("attachmentUri"),
                json.optString("attachmentName"),
                json.optLong("createdAt", System.currentTimeMillis())
        );
    }
}
