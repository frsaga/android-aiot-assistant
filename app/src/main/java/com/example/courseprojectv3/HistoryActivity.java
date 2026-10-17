package com.example.courseprojectv3;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.courseprojectv3.adapter.SessionAdapter;
import com.example.courseprojectv3.storage.AppStorage;
import com.example.courseprojectv3.util.SystemBars;
import com.example.courseprojectv3.util.ThemeMode;

/**
 * 历史会话列表页面。
 *
 * 答辩说明：
 * 会话数据来自 AppStorage.loadSessions，列表样式类似 DeepSeek 的侧边会话列表：
 * 展示标题、摘要、消息数量和更新时间；点击某一项后把 session_id 传回 MainActivity。
 */
public class HistoryActivity extends Activity {
    private AppStorage storage;
    private SessionAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        storage = new AppStorage(this);
        ThemeMode.apply(this, storage.loadSettings().darkMode);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);
        SystemBars.apply(getWindow(), findViewById(R.id.historyRoot));

        findViewById(R.id.backButton).setOnClickListener(v -> finish());
        TextView clear = findViewById(R.id.clearButton);
        clear.setOnClickListener(v -> {
            // 清空历史只清除会话列表和当前会话 id，不影响模型配置。
            storage.clearSessions();
            adapter.submit(storage.loadSessions());
        });
        RecyclerView recyclerView = findViewById(R.id.sessionRecycler);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new SessionAdapter(new SessionAdapter.Listener() {
            @Override
            public void onSessionClick(com.example.courseprojectv3.data.ChatSession session) {
                // 点击历史项后保存为当前活跃会话，并通过 Intent extra 告诉 MainActivity 加载哪一条。
                storage.saveActiveSessionId(session.id);
                Intent intent = new Intent(HistoryActivity.this, MainActivity.class);
                intent.putExtra("session_id", session.id);
                intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(intent);
                finish();
            }

            @Override
            public void onSessionDelete(com.example.courseprojectv3.data.ChatSession session) {
                new AlertDialog.Builder(HistoryActivity.this)
                        .setTitle("删除会话")
                        .setMessage("确定删除“" + session.title + "”吗？删除后无法恢复。")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("删除", (dialog, which) -> {
                            storage.deleteSession(session.id);
                            adapter.submit(storage.loadSessions());
                        })
                        .show();
            }
        });
        recyclerView.setAdapter(adapter);
        adapter.submit(storage.loadSessions());
    }
}
