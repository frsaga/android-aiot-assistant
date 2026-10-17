package com.example.courseprojectv3.storage;

import android.content.Context;
import android.content.SharedPreferences;

import com.example.courseprojectv3.data.AppSettings;
import com.example.courseprojectv3.data.ChatSession;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 本地数据持久化层。
 *
 * 答辩说明：
 * 项目没有引入数据库，使用 SharedPreferences 保存 JSON 字符串。
 * 优点是课程项目结构轻量、代码易读；会话、摘要、API KEY、主题和 Claw 开关都集中在这里读写。
 */
public class AppStorage {
    private static final String PREF = "aiot_assistant_store";
    private static final String KEY_SESSIONS = "sessions_json";
    private static final String KEY_ACTIVE = "active_session_id";
    private static final String KEY_MODEL = "model";
    private static final String KEY_API = "api_key";
    private static final String KEY_DARK = "dark";
    private static final String KEY_CLAW = "claw";
    private static final String KEY_WELCOME_COMPLETED = "welcome_completed";

    private final SharedPreferences prefs;

    public AppStorage(Context context) {
        prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public List<ChatSession> loadSessions() {
        // 从 SharedPreferences 读出 JSON 数组，再还原为 ChatSession 列表。
        List<ChatSession> sessions = new ArrayList<>();
        String raw = prefs.getString(KEY_SESSIONS, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                // 每个 JSON 对象由数据模型自己还原，存储层不关心消息字段细节。
                sessions.add(ChatSession.fromJson(array.getJSONObject(i)));
            }
        } catch (JSONException ignored) {
            // 本地 JSON 损坏时返回空列表，避免启动阶段直接崩溃。
        }
        // 最近更新的会话排在抽屉顶部。
        Collections.sort(sessions, (a, b) -> Long.compare(b.updatedAt, a.updatedAt));
        return sessions;
    }

    public void saveSessions(List<ChatSession> sessions) {
        // 会话对象自己负责 toJson，这里只负责把列表整体落盘。
        JSONArray array = new JSONArray();
        for (ChatSession session : sessions) {
            try {
                array.put(session.toJson());
            } catch (JSONException ignored) {
                // 单条会话序列化失败时跳过该项，尽量保存其余有效数据。
            }
        }
        // apply 异步写入磁盘，不阻塞消息列表的主线程刷新。
        prefs.edit().putString(KEY_SESSIONS, array.toString()).apply();
    }

    public ChatSession loadActiveSession() {
        // 优先恢复上次打开的会话；没有历史时自动创建一个新会话。
        List<ChatSession> sessions = loadSessions();
        String activeId = prefs.getString(KEY_ACTIVE, "");
        for (ChatSession session : sessions) {
            if (session.id.equals(activeId)) {
                // active id 命中时恢复用户上次正在查看的会话。
                return session;
            }
        }
        if (!sessions.isEmpty()) {
            return sessions.get(0);
        }
        ChatSession session = ChatSession.create();
        sessions.add(session);
        saveActiveSessionId(session.id);
        saveSessions(sessions);
        return session;
    }

    public void upsertSession(ChatSession session) {
        // upsert 表示“有则更新，无则插入”，发送消息和流式输出时都会调用它。
        List<ChatSession> sessions = loadSessions();
        boolean replaced = false;
        for (int i = 0; i < sessions.size(); i++) {
            if (sessions.get(i).id.equals(session.id)) {
                // 使用稳定 id 替换旧对象，避免同一会话被重复插入。
                sessions.set(i, session);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            sessions.add(session);
        }
        sessions.sort(Comparator.comparingLong((ChatSession item) -> item.updatedAt).reversed());
        saveSessions(sessions);
        saveActiveSessionId(session.id);
    }

    public void clearSessions() {
        prefs.edit().remove(KEY_SESSIONS).remove(KEY_ACTIVE).apply();
    }

    /**
     * 按会话 id 删除一条历史记录。
     * 如果删除的是当前会话，则把最近的一条剩余会话设为活跃会话；
     * 若列表已空，则清除活跃 id，MainActivity 会按现有规则自动创建新会话。
     */
    public void deleteSession(String sessionId) {
        List<ChatSession> sessions = loadSessions();
        // removeIf 只删除 id 匹配的记录，不影响其他历史会话。
        sessions.removeIf(item -> item.id.equals(sessionId));
        saveSessions(sessions);

        String activeId = prefs.getString(KEY_ACTIVE, "");
        if (sessionId.equals(activeId)) {
            SharedPreferences.Editor editor = prefs.edit();
            if (sessions.isEmpty()) {
                // 清除 active id 后，MainActivity 下次会通过 loadActiveSession 创建新会话。
                editor.remove(KEY_ACTIVE);
            } else {
                // 列表已经按更新时间排序，因此第 0 条就是最近会话。
                editor.putString(KEY_ACTIVE, sessions.get(0).id);
            }
            editor.apply();
        }
    }

    public void saveActiveSessionId(String id) {
        prefs.edit().putString(KEY_ACTIVE, id).apply();
    }

    /**
     * Welcome Page 只面向真正的新用户。
     * 兼容旧版本：升级前已经存在会话数据的用户也视为完成过首次引导。
     */
    public boolean hasCompletedWelcome() {
        return prefs.getBoolean(KEY_WELCOME_COMPLETED, false) || prefs.contains(KEY_SESSIONS);
    }

    public void markWelcomeCompleted() {
        prefs.edit().putBoolean(KEY_WELCOME_COMPLETED, true).commit();
    }

    public AppSettings loadSettings() {
        // 设置页和主页面都从这里读取最新配置，避免 Activity 之间状态不同步。
        AppSettings settings = new AppSettings();
        settings.modelName = prefs.getString(KEY_MODEL, settings.modelName);
        if ("AIoT-MultiModal-Pro".equals(settings.modelName) || "Qwen-Omni".equals(settings.modelName)) {
            // 兼容旧版本曾保存的展示名称，迁移到当前真实 API 模型名。
            settings.modelName = "qwen-plus";
        }
        settings.apiKey = prefs.getString(KEY_API, "");
        settings.darkMode = prefs.getBoolean(KEY_DARK, false);
        settings.clawMode = prefs.getBoolean(KEY_CLAW, false);
        return settings;
    }

    public void saveSettings(AppSettings settings) {
        // 使用 commit 同步保存，保证刚关闭 Claw 后主页面立刻读到新值。
        prefs.edit()
                .putString(KEY_MODEL, settings.modelName)
                .putString(KEY_API, settings.apiKey)
                .putBoolean(KEY_DARK, settings.darkMode)
                .putBoolean(KEY_CLAW, settings.clawMode)
                .commit();
    }
}
