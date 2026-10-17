package com.example.courseprojectv3.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.courseprojectv3.R;
import com.example.courseprojectv3.data.ChatSession;
import com.example.courseprojectv3.util.TextCleaner;
import com.example.courseprojectv3.util.TimeFormatter;

import java.util.ArrayList;
import java.util.List;

/**
 * 历史会话列表适配器。
 *
 * 答辩说明：
 * MainActivity 的左侧抽屉和兼容保留的 HistoryActivity 共用本 Adapter。
 * item_session.xml 使用固定高度单行条目，避免历史记录呈现为占空间较大的卡片列表。
 */
public class SessionAdapter extends RecyclerView.Adapter<SessionAdapter.SessionViewHolder> {
    public interface Listener {
        void onSessionClick(ChatSession session);

        void onSessionDelete(ChatSession session);
    }

    private final Listener listener;
    private final List<ChatSession> sessions = new ArrayList<>();
    private String activeSessionId;

    public SessionAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submit(List<ChatSession> items) {
        submit(items, null);
    }

    public void submit(List<ChatSession> items, String activeId) {
        sessions.clear();
        sessions.addAll(items);
        // activeSessionId 用于让当前会话条目显示 activated 背景状态。
        activeSessionId = activeId;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public SessionViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_session, parent, false);
        return new SessionViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull SessionViewHolder holder, int position) {
        ChatSession session = sessions.get(position);
        // 每个会话严格占一行：标题和摘要负责快速识别，右侧只保留紧凑的消息数。
        holder.name.setText(TextCleaner.forPreview(session.title));
        holder.summary.setText(TextCleaner.forPreview(session.summary));
        holder.count.setText(String.valueOf(session.messages.size()));
        holder.time.setText("更新于 " + TimeFormatter.dateTime(session.updatedAt));
        holder.itemView.setActivated(session.id.equals(activeSessionId));
        // 点击整行负责切换，点击右侧 × 单独触发删除回调。
        holder.itemView.setOnClickListener(v -> listener.onSessionClick(session));
        holder.deleteButton.setOnClickListener(v -> listener.onSessionDelete(session));
    }

    @Override
    public int getItemCount() {
        return sessions.size();
    }

    static class SessionViewHolder extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView summary;
        final TextView count;
        final TextView time;
        final TextView deleteButton;

        SessionViewHolder(@NonNull View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.sessionName);
            summary = itemView.findViewById(R.id.sessionSummaryText);
            count = itemView.findViewById(R.id.sessionCount);
            time = itemView.findViewById(R.id.sessionTime);
            deleteButton = itemView.findViewById(R.id.deleteSessionButton);
        }
    }
}
