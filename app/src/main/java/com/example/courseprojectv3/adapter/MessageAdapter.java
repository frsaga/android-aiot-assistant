package com.example.courseprojectv3.adapter;

import android.content.Context;
import android.net.Uri;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.example.courseprojectv3.R;
import com.example.courseprojectv3.data.AttachmentType;
import com.example.courseprojectv3.data.ChatMessage;
import com.example.courseprojectv3.data.MessageRole;
import com.example.courseprojectv3.util.AttachmentReader;
import com.example.courseprojectv3.util.MarkdownRenderer;
import com.example.courseprojectv3.util.TimeFormatter;

import java.util.ArrayList;
import java.util.List;

/**
 * 对话消息列表适配器。
 *
 * 答辩说明：
 * RecyclerView 负责无限滚动列表，MessageAdapter 负责把 ChatMessage 绑定到 item_message.xml。
 * 用户消息靠右显示，智能体消息靠左显示；MarkdownRenderer 负责把 Markdown 转成 TextView 可显示的富文本。
 */
public class MessageAdapter extends RecyclerView.Adapter<MessageAdapter.MessageViewHolder> {
    private final List<ChatMessage> messages = new ArrayList<>();

    public void submit(List<ChatMessage> items) {
        // Activity 每次渲染时提交当前会话的消息列表。
        messages.clear();
        // Adapter 保存列表快照，切换会话时不会继续显示旧 session 的消息。
        messages.addAll(items);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public MessageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_message, parent, false);
        return new MessageViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull MessageViewHolder holder, int position) {
        ChatMessage message = messages.get(position);
        boolean isUser = message.role == MessageRole.USER;
        // 通过 gravity 和背景 drawable 区分“我”和“智能体”的气泡视觉样式。
        LinearLayout root = holder.itemView.findViewById(R.id.messageItemRoot);
        // USER 靠右、AGENT 靠左，背景和文字颜色也使用不同资源。
        root.setGravity(isUser ? Gravity.END : Gravity.START);
        holder.bubble.setBackgroundResource(isUser ? R.drawable.bg_bubble_user : R.drawable.bg_bubble_agent);
        holder.bubbleText.setTextColor(ContextCompat.getColor(
                holder.itemView.getContext(),
                isUser ? R.color.bubble_user_text : R.color.bubble_agent_text
        ));
        bindAttachmentImage(holder, message);
        // 所有文本统一经过 MarkdownRenderer，流式中未闭合的标记也能按普通文本显示。
        MarkdownRenderer.setMarkdown(holder.bubbleText, renderContent(message, holder.itemView.getContext()));
        holder.meta.setText((isUser ? "我" : "智能体") + " · " + TimeFormatter.time(message.createdAt));
    }

    private void bindAttachmentImage(MessageViewHolder holder, ChatMessage message) {
        holder.attachmentImage.setImageDrawable(null);
        if (message.attachmentType != AttachmentType.PHOTO
                || message.attachmentUri == null
                || message.attachmentUri.trim().isEmpty()) {
            holder.attachmentImage.setVisibility(View.GONE);
            return;
        }
        holder.attachmentImage.setVisibility(View.VISIBLE);
        try {
            holder.attachmentImage.setImageURI(Uri.parse(message.attachmentUri));
        } catch (Exception ignored) {
            holder.attachmentImage.setVisibility(View.GONE);
            holder.attachmentImage.setImageDrawable(null);
        }
    }

    private String renderContent(ChatMessage message, Context context) {
        if (message.attachmentType == AttachmentType.PHOTO) {
            return emptyPrompt(message.content);
        }
        if (message.attachmentType == AttachmentType.DOCUMENT) {
            return "📄 " + attachmentName(message, "文档附件", context) + "\n" + emptyPrompt(message.content);
        }
        if (message.attachmentType == AttachmentType.AUDIO) {
            return "🎙 " + attachmentName(message, "语音输入", context) + "\n" + emptyPrompt(message.content);
        }
        return message.content;
    }

    private String attachmentName(ChatMessage message, String fallback, Context context) {
        if (message.attachmentName != null && !message.attachmentName.trim().isEmpty()) {
            return message.attachmentName.trim();
        }
        if (message.attachmentUri != null && !message.attachmentUri.trim().isEmpty()) {
            try {
                return AttachmentReader.displayName(
                        context,
                        Uri.parse(message.attachmentUri)
                );
            } catch (Exception ignored) {
            }
        }
        return fallback;
    }

    private String emptyPrompt(String content) {
        if (content == null || content.trim().isEmpty()) {
            return "未添加文字说明";
        }
        return content;
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }

    public void updateLast() {
        // 流式输出只刷新最后一条智能体气泡，避免整屏列表反复重绘。
        if (!messages.isEmpty()) {
            notifyItemChanged(messages.size() - 1);
        }
    }

    static class MessageViewHolder extends RecyclerView.ViewHolder {
        final LinearLayout bubble;
        final ImageView attachmentImage;
        final TextView bubbleText;
        final TextView meta;

        MessageViewHolder(@NonNull View itemView) {
            super(itemView);
            bubble = itemView.findViewById(R.id.messageBubble);
            attachmentImage = itemView.findViewById(R.id.messageAttachmentImage);
            bubbleText = itemView.findViewById(R.id.messageBubbleText);
            meta = itemView.findViewById(R.id.messageMeta);
        }
    }
}
