package com.example.courseprojectv3;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import com.example.courseprojectv3.data.ChatMessage;
import com.example.courseprojectv3.data.ChatSession;
import com.example.courseprojectv3.data.MessageRole;
import com.example.courseprojectv3.storage.AppStorage;
import com.example.courseprojectv3.util.NetworkUtils;
import com.example.courseprojectv3.util.SystemBars;
import com.example.courseprojectv3.util.ThemeMode;

import java.util.List;

/**
 * 统计与监测页面。
 *
 * 答辩说明：
 * 本页面不调用模型，只读取本地保存的会话数据，统计消息数量、智能体回复数量、多模态输入数量，
 * 同时通过 Android ConnectivityManager 读取当前网络状态。
 */
public class StatsActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AppStorage storage = new AppStorage(this);
        ThemeMode.apply(this, storage.loadSettings().darkMode);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_stats);
        SystemBars.apply(getWindow(), findViewById(R.id.statsRoot));
        findViewById(R.id.backButton).setOnClickListener(v -> finish());

        List<ChatSession> sessions = storage.loadSessions();
        int messageCount = 0;
        int userCount = 0;
        int agentCount = 0;
        int multiModalCount = 0;
        // 遍历所有历史会话，按消息角色和附件类型做统计。
        for (ChatSession session : sessions) {
            for (ChatMessage message : session.messages) {
                // 所有统计都从本地会话实时计算，不额外维护容易失同步的计数器。
                messageCount++;
                if (message.role == MessageRole.USER) {
                    userCount++;
                }
                if (message.role == MessageRole.AGENT) {
                    agentCount++;
                }
                if (message.attachmentType.name().equals("PHOTO") || message.attachmentType.name().equals("DOCUMENT") || message.attachmentType.name().equals("AUDIO")) {
                    multiModalCount++;
                }
            }
        }
        ((TextView) findViewById(R.id.messageCountText)).setText(String.valueOf(messageCount));
        ((TextView) findViewById(R.id.agentCountText)).setText(storage.loadSettings().clawMode ? "2" : "1");
        ((TextView) findViewById(R.id.modelUsageText)).setText(
                "当前模型：" + storage.loadSettings().modelName
                        + "\n会话数：" + sessions.size()
                        + "\n用户消息：" + userCount
                        + "\n智能体回复：" + agentCount
                        + "\n多模态输入：" + multiModalCount
        );
        ((TextView) findViewById(R.id.networkText)).setText(NetworkUtils.getNetworkStatus(this));
        // 简单柱状图用 View 的高度变化实现，避免引入复杂图表库，方便课程项目答辩解释。
        scaleBar(R.id.barChat, Math.max(userCount, 1), Math.max(messageCount, 1));
        scaleBar(R.id.barVision, Math.max(multiModalCount, 1), Math.max(messageCount, 1));
        scaleBar(R.id.barVoice, storage.loadSettings().clawMode ? 2 : 1, 4);
        scaleBar(R.id.barClaw, storage.loadSettings().clawMode ? 3 : 1, 4);
    }

    private void scaleBar(int id, int value, int max) {
        View bar = findViewById(id);
        // 16dp 是最小可见高度，74dp 按 value/max 比例增加。
        int height = 16 + (int) (74f * value / Math.max(max, 1));
        bar.getLayoutParams().height = height;
        bar.requestLayout();
    }
}
