package com.example.courseprojectv3.data;

/**
 * 发送前暂存在输入框区域的附件。
 *
 * 答辩说明：
 * 用户拍照/选文档/录音后，附件不会立刻发送，而是先保存成 PendingAttachment，
 * 等用户补充 prompt 并点击发送后才变成 ChatMessage。
 */
public class PendingAttachment {
    public final AttachmentType type;
    public final String uri;
    public final String label;

    public PendingAttachment(AttachmentType type, String uri, String label) {
        this.type = type;
        this.uri = uri;
        this.label = label;
    }
}
