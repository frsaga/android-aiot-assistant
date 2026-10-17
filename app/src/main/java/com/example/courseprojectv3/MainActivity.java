package com.example.courseprojectv3;

import android.Manifest;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.View;
import android.view.animation.AnimationUtils;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Toast;
import android.widget.TextView;
import android.webkit.URLUtil;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.courseprojectv3.adapter.MessageAdapter;
import com.example.courseprojectv3.adapter.SessionAdapter;
import com.example.courseprojectv3.agent.AiAgentService;
import com.example.courseprojectv3.agent.LocalClawAgent;
import com.example.courseprojectv3.agent.SpeechTranscriptionService;
import com.example.courseprojectv3.data.AppSettings;
import com.example.courseprojectv3.data.AttachmentType;
import com.example.courseprojectv3.data.ChatMessage;
import com.example.courseprojectv3.data.ChatSession;
import com.example.courseprojectv3.data.PendingAttachment;
import com.example.courseprojectv3.storage.AppStorage;
import com.example.courseprojectv3.util.AttachmentReader;
import com.example.courseprojectv3.util.NotificationHelper;
import com.example.courseprojectv3.util.SystemBars;
import com.example.courseprojectv3.util.TextCleaner;
import com.example.courseprojectv3.util.ThemeMode;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 主对话页面。
 *
 * 答辩说明：
 * 1. 本项目采用“XML 布局 + Activity 控制逻辑”的传统 Android 架构；
 * 2. MainActivity 负责聊天主流程：用户输入、附件暂存、消息入库、调用智能体、流式刷新 UI；
 * 3. 真正的模型请求封装在 AiAgentService，真正的本地系统能力封装在 LocalClawAgent 和本 Activity 的系统 Intent/Service 调用里。
 */
public class MainActivity extends ComponentActivity {
    private static final int REQ_DOC = 301;
    private static final int REQ_CAMERA = 302;
    private static final int REQ_VOICE = 303;
    private static final int REQ_GALLERY = 304;
    private static final int REQ_PERMISSIONS = 401;
    private static final int REQ_CAMERA_PERMISSION = 402;
    private static final int REQ_AUDIO_PERMISSION = 403;
    private static final String ANDROID_LOGO_URL = "https://raw.githubusercontent.com/google/material-design-icons/master/png/action/android/materialicons/48dp/2x/baseline_android_black_48dp.png";

    private AppStorage storage;
    private ChatSession session;
    private AppSettings settings;
    private MessageAdapter adapter;
    private SessionAdapter drawerSessionAdapter;
    private DrawerLayout drawerLayout;
    private RecyclerView recyclerView;
    private EditText input;
    private TextView title;
    private TextView summary;
    private Uri pendingPhotoUri;
    private String pendingPhotoName = "";
    private PendingAttachment pendingAttachment;
    private boolean openCameraAfterPermission;
    private boolean openVoiceAfterPermission;
    private SpeechRecognizer speechRecognizer;
    private boolean voiceRecognitionActive;
    private MediaRecorder mediaRecorder;
    private File recordingFile;
    private boolean localRecordingActive;
    private boolean transcriptionInProgress;
    private boolean transcriptionFailed;
    private final Map<Long, String> activeDownloadPaths = new HashMap<>();
    private BroadcastReceiver downloadReceiver;
    // 流式输出状态：用于解决“生成时上滑查看历史会被强制拉回底部”的体验问题。
    private boolean streamingReplyActive;
    private boolean userReadingHistory;
    private boolean followStreamingOutput = true;
    // 所有流式增量共用一个延迟滚动任务，避免每个 delta 都向消息队列追加新任务。
    private Runnable pendingAutoScroll;

    private AiAgentService aiAgent;
    private SpeechTranscriptionService speechTranscriptionService;
    private final LocalClawAgent clawAgent = new LocalClawAgent();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        storage = new AppStorage(this);
        settings = storage.loadSettings();
        ThemeMode.apply(this, settings.darkMode);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        // DrawerLayout 不会像普通根布局一样可靠消费 padding，主内容和抽屉分别适配系统栏。
        SystemBars.apply(getWindow(), findViewById(R.id.mainRoot));
        SystemBars.apply(getWindow(), findViewById(R.id.navigationDrawer));
        NotificationHelper.ensureChannel(this);
        registerDownloadReceiver();
        aiAgent = new AiAgentService(this);
        speechTranscriptionService = new SpeechTranscriptionService();
        requestBasePermissions();
        bindViews();
        bindBackNavigation();
        loadSessionFromIntent();
        render();
    }

    private void bindViews() {
        drawerLayout = findViewById(R.id.drawerLayout);
        recyclerView = findViewById(R.id.messageRecycler);
        input = findViewById(R.id.messageInput);
        title = findViewById(R.id.sessionTitle);
        summary = findViewById(R.id.sessionSummary);
        adapter = new MessageAdapter();
        LinearLayoutManager manager = new LinearLayoutManager(this);
        manager.setStackFromEnd(true);
        recyclerView.setLayoutManager(manager);
        recyclerView.setAdapter(adapter);
        // 关闭 RecyclerView 默认变更动画，避免流式更新同一个气泡时出现重影、交叠。
        recyclerView.setItemAnimator(null);
        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING && streamingReplyActive) {
                    pauseStreamingAutoScroll();
                } else if (newState == RecyclerView.SCROLL_STATE_IDLE && isAtConversationBottom()) {
                    resumeStreamingAutoScroll();
                }
            }

            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                // 不在这里恢复自动跟随。用户刚从底部开始上滑时，第一小段位移仍可能被判断为
                // “位于底部”；若此时立即恢复，下一段模型输出就会把页面强行拉回底部。
                // 恢复动作统一放到 SCROLL_STATE_IDLE，并再次确认列表确实位于底部。
            }
        });

        findViewById(R.id.header).startAnimation(AnimationUtils.loadAnimation(this, R.anim.fade_in));
        bindConversationDrawer();
        findViewById(R.id.newChatButton).setOnClickListener(v -> createNewConversation());
        findViewById(R.id.sendButton).setOnClickListener(v -> sendText());
        findViewById(R.id.removeAttachmentButton).setOnClickListener(v -> clearPendingAttachment());
        findViewById(R.id.retryTranscriptionButton).setOnClickListener(v -> retryAudioTranscription());
        findViewById(R.id.removePhotoButton).setOnClickListener(v -> clearPendingAttachment());
        findViewById(R.id.cameraButton).setOnClickListener(v -> openImageSourceChooser());
        findViewById(R.id.docButton).setOnClickListener(v -> openDocument());
        findViewById(R.id.voiceButton).setOnClickListener(v -> openVoiceInput());
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                sendText();
                return true;
            }
            return false;
        });
        input.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                userReadingHistory = false;
                followStreamingOutput = true;
                scrollToLatestDelayed();
            }
        });
    }

    /**
     * 初始化类似 ChatGPT 的左侧会话抽屉。
     *
     * 抽屉直接复用 AppStorage 中的持久化会话，不再启动一个独立历史页面；
     * 点击列表项后只替换当前 ChatSession，并立即刷新标题、摘要和消息 RecyclerView。
     */
    private void bindConversationDrawer() {
        RecyclerView sessionRecycler = findViewById(R.id.drawerSessionRecycler);
        sessionRecycler.setLayoutManager(new LinearLayoutManager(this));
        sessionRecycler.setItemAnimator(null);
        drawerSessionAdapter = new SessionAdapter(new SessionAdapter.Listener() {
            @Override
            public void onSessionClick(ChatSession selected) {
                switchConversation(selected);
            }

            @Override
            public void onSessionDelete(ChatSession selected) {
                confirmDeleteConversation(selected);
            }
        });
        sessionRecycler.setAdapter(drawerSessionAdapter);

        findViewById(R.id.historyButton).setOnClickListener(v -> {
            renderDrawerSessions();
            drawerLayout.openDrawer(GravityCompat.START);
        });
        findViewById(R.id.closeDrawerButton).setOnClickListener(v -> drawerLayout.closeDrawer(GravityCompat.START));
        findViewById(R.id.drawerNewChatButton).setOnClickListener(v -> createNewConversation());
        findViewById(R.id.statsButton).setOnClickListener(v -> {
            drawerLayout.closeDrawer(GravityCompat.START);
            startActivity(new Intent(this, StatsActivity.class));
        });
        findViewById(R.id.settingsButton).setOnClickListener(v -> {
            drawerLayout.closeDrawer(GravityCompat.START);
            startActivity(new Intent(this, SettingsActivity.class));
        });
    }

    private void createNewConversation() {
        // 流式回复期间禁止更换 session，避免异步回调把内容写入错误的会话。
        if (streamingReplyActive) {
            Toast.makeText(this, "请等待当前回复生成完成", Toast.LENGTH_SHORT).show();
            return;
        }
        clearPendingAttachment();
        // ChatSession.create() 会生成唯一 id，并加入一条默认欢迎消息。
        session = ChatSession.create();
        // 新会话立即落盘，因此即使用户尚未提问，也能出现在历史列表中。
        storage.upsertSession(session);
        render();
        drawerLayout.closeDrawer(GravityCompat.START);
    }

    private void switchConversation(ChatSession selected) {
        if (selected == null) {
            return;
        }
        if (streamingReplyActive && (session == null || !selected.id.equals(session.id))) {
            Toast.makeText(this, "请等待当前回复生成完成", Toast.LENGTH_SHORT).show();
            return;
        }
        if (session == null || !selected.id.equals(session.id)) {
            // 附件只属于原输入上下文，切换会话时不能带到另一条历史记录中。
            clearPendingAttachment();
            session = selected;
            // 单独保存 active id，使应用下次启动时可以恢复到这条会话。
            storage.saveActiveSessionId(selected.id);
            render();
        }
        drawerLayout.closeDrawer(GravityCompat.START);
    }

    private void confirmDeleteConversation(ChatSession selected) {
        if (selected == null) {
            return;
        }
        if (streamingReplyActive && session != null && selected.id.equals(session.id)) {
            Toast.makeText(this, "当前会话正在生成回复，暂时不能删除", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("删除会话")
                .setMessage("确定删除“" + selected.title + "”吗？删除后无法恢复。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> deleteConversation(selected))
                .show();
    }

    private void deleteConversation(ChatSession selected) {
        boolean deletingCurrent = session != null && selected.id.equals(session.id);
        // 存储层同时处理会话数组和 active_session_id，Activity 不直接操作 JSON。
        storage.deleteSession(selected.id);
        if (deletingCurrent) {
            clearPendingAttachment();
            // 删除当前项后恢复最近会话；若列表为空，存储层会自动创建新会话。
            session = storage.loadActiveSession();
            render();
        } else {
            renderDrawerSessions();
        }
        Toast.makeText(this, "会话已删除", Toast.LENGTH_SHORT).show();
    }

    private void renderDrawerSessions() {
        if (drawerSessionAdapter == null) {
            return;
        }
        drawerSessionAdapter.submit(storage.loadSessions(), session == null ? null : session.id);
    }

    private void loadSessionFromIntent() {
        String id = getIntent().getStringExtra("session_id");
        if (id != null) {
            for (ChatSession item : storage.loadSessions()) {
                if (item.id.equals(id)) {
                    session = item;
                    storage.saveActiveSessionId(id);
                    return;
                }
            }
        }
        session = storage.loadActiveSession();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (storage != null) {
            // 从设置页返回时 Activity 未必重建，因此在 onResume 再读取一次最新配置。
            settings = storage.loadSettings();
            requestBasePermissions();
            renderDrawerSessions();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        loadSessionFromIntent();
        render();
        renderPendingAttachment();
    }

    private void render() {
        render(true);
    }

    private void render(boolean scrollToBottom) {
        // 会话标题和摘要来自 ChatSession.refreshSummary，历史列表也复用同一套摘要数据。
        title.setText(session.title);
        summary.setText(TextCleaner.forPreview(session.summary));
        adapter.submit(session.messages);
        renderDrawerSessions();
        if (scrollToBottom) {
            scrollToLatest();
        }
    }

    private void sendText() {
        if (transcriptionInProgress) {
            Toast.makeText(this, "录音正在转写，请稍候", Toast.LENGTH_SHORT).show();
            return;
        }
        if (transcriptionFailed && pendingAttachment != null
                && pendingAttachment.type == AttachmentType.AUDIO) {
            Toast.makeText(this, "请先重试转写或删除录音", Toast.LENGTH_SHORT).show();
            return;
        }
        // 每次发送前重新读取配置，确保刚关闭 Claw 模式后不会继续执行旧状态。
        settings = storage.loadSettings();
        String text = input.getText().toString().trim();
        if (text.isEmpty() && pendingAttachment == null) {
            Toast.makeText(this, "请输入内容或先添加附件", Toast.LENGTH_SHORT).show();
            return;
        }
        userReadingHistory = false;
        followStreamingOutput = true;
        // 先清空输入框，避免用户连续点击发送产生重复消息。
        input.setText("");
        ChatMessage message;
        boolean hasAttachment = pendingAttachment != null;
        if (pendingAttachment != null) {
            // 附件先进入输入区，用户可补充 prompt 后再发送，逻辑对齐主流 AI 软件。
            String prompt = text.isEmpty() ? defaultAttachmentPrompt(pendingAttachment.type) : text;
            message = ChatMessage.userWithAttachment(
                    prompt,
                    pendingAttachment.type,
                    pendingAttachment.uri,
                    pendingAttachment.label
            );
            clearPendingAttachment();
        } else {
            message = ChatMessage.userText(text);
        }
        addUserMessage(message);

        if (!hasAttachment && isClawModeEnabled()) {
            // Claw 是本地智能体能力，只在开关开启且没有附件时解析系统指令。
            LocalClawAgent.Command command = clawAgent.parse(text);
            if (command.type != LocalClawAgent.ActionType.NONE) {
                addAgentMessage(command.readableText);
                executeClawCommand(command);
                return;
            }
        }
        requestAgentReply(message);
    }

    private void addUserMessage(ChatMessage message) {
        // 数据模型负责刷新标题和摘要，存储层负责 JSON 持久化，最后再更新界面。
        session.addMessage(message);
        storage.upsertSession(session);
        render();
    }

    private void addAgentMessage(String text) {
        session.addMessage(ChatMessage.agent(text));
        storage.upsertSession(session);
        render();
        NotificationHelper.showAgentMessage(this, text);
        Toast.makeText(this, "收到智能体新消息", Toast.LENGTH_SHORT).show();
    }

    private void requestAgentReply(ChatMessage message) {
        setLoading(true);
        streamingReplyActive = true;
        userReadingHistory = false;
        followStreamingOutput = true;
        // 先插入一个空的智能体消息作为“流式气泡容器”，后续 delta 都写入同一对象。
        ChatMessage streamingMessage = ChatMessage.agent("");
        session.addMessage(streamingMessage);
        // 请求发出前保存空气泡；应用异常退出时仍能保留本轮请求的会话结构。
        storage.upsertSession(session);
        render();
        aiAgent.requestReply(session, message, settings, new AiAgentService.StreamCallback() {
            @Override
            public void onDelta(String delta) {
                // 每收到一段模型增量，就追加到同一个 ChatMessage，Adapter 只刷新最后一个气泡。
                boolean shouldFollow = followStreamingOutput && !userReadingHistory;
                streamingMessage.appendContent(delta);
                // 流式过程中持续保存，避免长回答生成到一半时因页面退出而全部丢失。
                storage.upsertSession(session);
                adapter.updateLast();
                if (shouldFollow) {
                    scrollToLatestDelayed();
                }
            }

            @Override
            public void onComplete(String response) {
                // 流式结束后刷新摘要、落盘，并用系统通知栏提示用户“智能体消息已到达”。
                if (streamingMessage.content == null || streamingMessage.content.isEmpty()) {
                    // 某些兼容接口只在完成回调返回全文，没有 delta 时用 response 兜底。
                    streamingMessage.content = response;
                }
                boolean shouldFollow = followStreamingOutput && !userReadingHistory;
                streamingReplyActive = false;
                setLoading(false);
                session.refreshSummary();
                storage.upsertSession(session);
                render(shouldFollow);
                NotificationHelper.showAgentMessage(MainActivity.this, streamingMessage.content);
                Toast.makeText(MainActivity.this, "收到智能体新消息", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void setLoading(boolean loading) {
        View box = findViewById(R.id.loadingBox);
        box.setVisibility(loading ? View.VISIBLE : View.GONE);
        if (loading) {
            // pulse.xml 只控制加载圆点动画，不阻塞模型请求线程。
            findViewById(R.id.loadingDot).startAnimation(AnimationUtils.loadAnimation(this, R.anim.pulse));
        } else {
            findViewById(R.id.loadingDot).clearAnimation();
        }
    }

    private void scrollToLatest() {
        if (session != null && !session.messages.isEmpty()) {
            recyclerView.scrollToPosition(session.messages.size() - 1);
        }
    }

    private void scrollToLatestDelayed() {
        cancelPendingAutoScroll();
        // 等 RecyclerView 完成 item 测量后再滚动，否则刚追加的气泡可能还没有高度。
        pendingAutoScroll = () -> {
            pendingAutoScroll = null;
            if (!streamingReplyActive || (followStreamingOutput && !userReadingHistory)) {
                scrollToLatest();
            }
        };
        recyclerView.postDelayed(pendingAutoScroll, 120);
    }

    private void pauseStreamingAutoScroll() {
        // 用户主动拖动即视为正在阅读历史；无论当前位置是否仍被系统判定为底部，都先锁住。
        userReadingHistory = true;
        followStreamingOutput = false;
        cancelPendingAutoScroll();
    }

    private void resumeStreamingAutoScroll() {
        // 只有用户松手且列表真正回到底部后，才允许后续流式增量继续自动跟随。
        userReadingHistory = false;
        followStreamingOutput = true;
    }

    private void cancelPendingAutoScroll() {
        if (recyclerView != null && pendingAutoScroll != null) {
            recyclerView.removeCallbacks(pendingAutoScroll);
            pendingAutoScroll = null;
        }
    }

    private boolean isAtConversationBottom() {
        if (recyclerView == null || adapter == null || adapter.getItemCount() == 0) {
            return true;
        }
        return !recyclerView.canScrollVertically(1);
    }

    private void bindBackNavigation() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (drawerLayout != null && drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START);
                    return;
                }
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
            }
        });
    }

    private void openDocument() {
        // 调用 Android 系统文件选择器。选择后的 Uri 会在 AttachmentReader 中读取正文或渲染为图片。
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(Intent.createChooser(intent, "选择要上传的文档"), REQ_DOC);
    }

    /** 图片入口统一提供拍照和相册，选择结果都进入同一个待发送预览区。 */
    private void openImageSourceChooser() {
        String[] options = {"拍照", "从相册选择"};
        new AlertDialog.Builder(this)
                .setTitle("添加图片")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        openCamera();
                    } else {
                        openGallery();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void openGallery() {
        // 使用系统文档选择器获取图片 Uri，无需申请宽泛的相册读取权限。
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, REQ_GALLERY);
    }

    private void openVoiceInput() {
        // 本地录音使用同一个按钮切换开始/停止，避免额外增加控制按钮。
        if (localRecordingActive) {
            stopLocalRecording();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            openVoiceAfterPermission = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO_PERMISSION);
            return;
        }
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            // 设备提供 RecognitionService 时优先使用应用内识别，结果可直接回填输入框。
            startInlineSpeechRecognition();
            return;
        }

        // 部分系统只提供带界面的语音识别 Activity，没有可供应用内调用的 RecognitionService。
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN");
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "请说出要添加到输入框的语音内容");
        if (intent.resolveActivity(getPackageManager()) != null) {
            startActivityForResult(intent, REQ_VOICE);
            return;
        }

        // 没有系统识别服务时录制本地音频，停止后自动调用云端 ASR 转成文字。
        startLocalRecording();
    }

    private void startInlineSpeechRecognition() {
        if (voiceRecognitionActive) {
            return;
        }
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {
                voiceRecognitionActive = true;
                setVoiceButtonText("正在聆听…");
                Toast.makeText(MainActivity.this, "请开始说话", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onBeginningOfSpeech() {
            }

            @Override
            public void onRmsChanged(float rmsdB) {
            }

            @Override
            public void onBufferReceived(byte[] buffer) {
            }

            @Override
            public void onEndOfSpeech() {
                setVoiceButtonText("识别中…");
            }

            @Override
            public void onError(int error) {
                voiceRecognitionActive = false;
                setVoiceButtonText("录音");
                Toast.makeText(MainActivity.this, speechErrorText(error), Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onResults(Bundle results) {
                voiceRecognitionActive = false;
                setVoiceButtonText("录音");
                // 系统可能返回多个候选文本，当前使用置信度最高的第一项。
                applyVoiceResults(results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION));
            }

            @Override
            public void onPartialResults(Bundle partialResults) {
            }

            @Override
            public void onEvent(int eventType, Bundle params) {
            }
        });

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN");
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        try {
            speechRecognizer.startListening(intent);
        } catch (Exception e) {
            voiceRecognitionActive = false;
            setVoiceButtonText("录音");
            startLocalRecording();
        }
    }

    private void applyVoiceResults(ArrayList<String> results) {
        if (results == null || results.isEmpty() || results.get(0).trim().isEmpty()) {
            Toast.makeText(this, "没有识别到清晰语音，请重试", Toast.LENGTH_SHORT).show();
            return;
        }
        String text = results.get(0).trim();
        // 识别结果先放入输入框，允许用户人工修改后再发送给模型。
        input.setText(text);
        input.setSelection(input.getText().length());
        // 识别成功后按普通文字发送，不再把音频附件交给聊天模型。
        clearPendingAttachment();
        Toast.makeText(this, "语音已转成文字，可编辑后发送", Toast.LENGTH_SHORT).show();
    }

    private String speechErrorText(int error) {
        if (error == SpeechRecognizer.ERROR_NO_MATCH) {
            return "没有识别到清晰语音，请重试";
        }
        if (error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT) {
            return "语音识别网络不可用，请检查网络后重试";
        }
        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
            return "未授予麦克风权限";
        }
        if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
            return "语音识别服务正忙，请稍后重试";
        }
        return "语音识别失败，请重试";
    }

    private void startLocalRecording() {
        hideKeyboard();
        clearPendingAttachment();
        transcriptionFailed = false;
        // 录音保存在应用私有目录，不依赖外部存储写权限。
        recordingFile = new File(attachmentDirectory(), "voice_" + System.currentTimeMillis() + ".m4a");
        mediaRecorder = new MediaRecorder();
        try {
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mediaRecorder.setAudioEncodingBitRate(96000);
            mediaRecorder.setAudioSamplingRate(44100);
            mediaRecorder.setOutputFile(recordingFile.getAbsolutePath());
            // prepare 完成编码器和文件初始化，start 才真正开始采集麦克风数据。
            mediaRecorder.prepare();
            mediaRecorder.start();
            localRecordingActive = true;
            setVoiceButtonText("停止录音");
            Toast.makeText(this, "已开始录音，再次点击后将自动转成文字", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            releaseMediaRecorder();
            if (recordingFile.exists()) {
                recordingFile.delete();
            }
            Toast.makeText(this, "无法启动麦克风录音", Toast.LENGTH_SHORT).show();
        }
    }

    private void stopLocalRecording() {
        boolean saved = false;
        try {
            // MediaRecorder.stop() 在录音过短时可能抛 RuntimeException，因此必须兜底清理。
            mediaRecorder.stop();
            saved = recordingFile != null && recordingFile.exists() && recordingFile.length() > 0;
        } catch (RuntimeException ignored) {
        } finally {
            releaseMediaRecorder();
            localRecordingActive = false;
            setVoiceButtonText("录音");
        }
        if (!saved) {
            if (recordingFile != null && recordingFile.exists()) {
                recordingFile.delete();
            }
            Toast.makeText(this, "录音时间过短，请重试", Toast.LENGTH_SHORT).show();
            return;
        }
        // FileProvider 把私有文件转换成受控 content:// Uri，避免暴露 file:// 路径。
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", recordingFile);
        setPendingAttachment(new PendingAttachment(AttachmentType.AUDIO, uri.toString(), recordingFile.getName()));
        startAudioTranscription();
    }

    private void startAudioTranscription() {
        if (recordingFile == null || !recordingFile.exists()) {
            Toast.makeText(this, "录音文件不存在，请重新录制", Toast.LENGTH_SHORT).show();
            return;
        }
        settings = storage.loadSettings();
        transcriptionInProgress = true;
        transcriptionFailed = false;
        renderPendingAttachment();
        setVoiceButtonText("转写中…");
        setComposerEnabled(false);
        speechTranscriptionService.transcribe(recordingFile, settings.apiKey,
                new SpeechTranscriptionService.Callback() {
                    @Override
                    public void onSuccess(String text) {
                        transcriptionInProgress = false;
                        transcriptionFailed = false;
                        setComposerEnabled(true);
                        setVoiceButtonText("录音");
                        input.setText(text);
                        input.setSelection(input.getText().length());
                        File completedFile = recordingFile;
                        pendingAttachment = null;
                        recordingFile = null;
                        renderPendingAttachment();
                        if (completedFile != null && completedFile.exists()) {
                            completedFile.delete();
                        }
                        input.requestFocus();
                        Toast.makeText(MainActivity.this,
                                "录音已转成文字，可修改后发送", Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onError(String message) {
                        transcriptionInProgress = false;
                        transcriptionFailed = true;
                        setComposerEnabled(true);
                        setVoiceButtonText("录音");
                        renderPendingAttachment();
                        Toast.makeText(MainActivity.this,
                                "转写失败：" + message, Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void retryAudioTranscription() {
        if (!transcriptionInProgress) {
            startAudioTranscription();
        }
    }

    private void setComposerEnabled(boolean enabled) {
        View sendButton = findViewById(R.id.sendButton);
        View cameraButton = findViewById(R.id.cameraButton);
        View docButton = findViewById(R.id.docButton);
        View voiceButton = findViewById(R.id.voiceButton);
        if (sendButton != null) {
            sendButton.setEnabled(enabled);
            sendButton.setAlpha(enabled ? 1f : 0.55f);
        }
        if (cameraButton != null) {
            cameraButton.setEnabled(enabled);
        }
        if (docButton != null) {
            docButton.setEnabled(enabled);
        }
        if (voiceButton != null) {
            voiceButton.setEnabled(enabled);
        }
        input.setEnabled(enabled);
    }

    private void hideKeyboard() {
        input.clearFocus();
        InputMethodManager manager =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (manager != null) {
            manager.hideSoftInputFromWindow(input.getWindowToken(), 0);
        }
    }

    private void releaseMediaRecorder() {
        if (mediaRecorder != null) {
            try {
                mediaRecorder.release();
            } catch (Exception ignored) {
            }
            mediaRecorder = null;
        }
    }

    private void setVoiceButtonText(String text) {
        TextView voiceButton = findViewById(R.id.voiceButton);
        if (voiceButton != null) {
            voiceButton.setText(text);
        }
    }

    private void openCamera() {
        // 相机权限改为按需申请；用户授权后 onRequestPermissionsResult 会自动继续原指令。
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            openCameraAfterPermission = true;
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA_PERMISSION);
            return;
        }
        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        // 预先创建输出文件，保证相机返回的是原图，而不是 Intent 中的小尺寸缩略图。
        File photo = new File(attachmentDirectory(), "aiot_photo_" + System.currentTimeMillis() + ".jpg");
        pendingPhotoName = photo.getName();
        pendingPhotoUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", photo);
        intent.putExtra(MediaStore.EXTRA_OUTPUT, pendingPhotoUri);
        intent.setClipData(ClipData.newRawUri("AIoT photo", pendingPhotoUri));
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (intent.resolveActivity(getPackageManager()) == null) {
            Toast.makeText(this, "当前设备没有可用的系统相机", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivityForResult(intent, REQ_CAMERA);
        } catch (Exception e) {
            Toast.makeText(this, "当前设备没有可用的相机服务", Toast.LENGTH_SHORT).show();
        }
    }

    private void executeClawCommand(LocalClawAgent.Command command) {
        // 执行前再次检查开关，防止设置刚关闭时仍执行短信、拍照、下载等系统服务。
        if (!isClawModeEnabled()) {
            return;
        }
        if (command.type == LocalClawAgent.ActionType.TAKE_PHOTO) {
            openCamera();
            return;
        }
        if (command.type == LocalClawAgent.ActionType.DOWNLOAD) {
            executeDownload(command);
            return;
        }
        if (command.type == LocalClawAgent.ActionType.SEND_SMS) {
            sendSms(command.phone, command.content);
        }
    }

    private boolean isClawModeEnabled() {
        settings = storage.loadSettings();
        return settings != null && settings.clawMode;
    }

    private void sendSms(String phone, String body) {
        // 始终使用 smsto: 打开系统编辑器：号码自动填入收件人，最终发送由用户确认。
        if (phone == null || phone.trim().isEmpty()) {
            addAgentMessage("没有识别到短信收件人，请输入例如：sms to 10086。");
            return;
        }
        Intent intent = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(phone.trim())));
        intent.putExtra("sms_body", body == null ? "" : body);
        if (intent.resolveActivity(getPackageManager()) == null) {
            addAgentMessage("当前设备没有可用的短信应用。");
            return;
        }
        addAgentMessage("已打开短信编辑器，收件人：" + phone.trim() + "。请确认后发送。");
        startActivity(intent);
    }

    private void executeDownload(LocalClawAgent.Command command) {
        String url = resolveDownloadUrl(command);
        if (url.isEmpty()) {
            String target = command.target == null || command.target.isEmpty() ? "目标资源" : "“" + command.target + "”";
            addAgentMessage("已识别要下载" + target + "，但无法离线确定可靠直链。请附上 http/https 下载链接。");
            return;
        }
        Uri uri = Uri.parse(url);
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
            addAgentMessage("下载链接必须使用 http 或 https。");
            return;
        }
        DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        if (manager == null) {
            addAgentMessage("系统下载服务当前不可用。");
            return;
        }

        String guessed = URLUtil.guessFileName(url, null, null).replaceAll("[^a-zA-Z0-9._-]", "_");
        // 时间戳避免同名文件覆盖，字符过滤避免非法文件名导致系统下载失败。
        String fileName = System.currentTimeMillis() + "_" + (guessed.isEmpty() ? "claw_download" : guessed);
        File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File destination = new File(downloads, fileName);
        try {
            DownloadManager.Request request = new DownloadManager.Request(uri);
            request.setTitle(fileName);
            request.setDescription("AIoT Claw 下载：" + url);
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setAllowedOverMetered(true);
            request.setAllowedOverRoaming(false);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
            // enqueue 返回系统任务 id，后续下载完成广播使用它匹配本应用创建的任务。
            long taskId = manager.enqueue(request);
            activeDownloadPaths.put(taskId, destination.getAbsolutePath());
            addAgentMessage("下载任务已创建（ID " + taskId + "）。\n保存路径：" + destination.getAbsolutePath());
        } catch (IllegalArgumentException | SecurityException e) {
            addAgentMessage("下载任务创建失败：" + e.getMessage());
        }
    }

    private String resolveDownloadUrl(LocalClawAgent.Command command) {
        if (command.url != null && !command.url.trim().isEmpty()) {
            return command.url.trim();
        }
        String target = command.target == null ? "" : command.target.toLowerCase(Locale.ROOT).replace(" ", "");
        if ((target.contains("android") || target.contains("安卓"))
                && (target.contains("图标") || target.contains("logo") || target.contains("icon"))) {
            return ANDROID_LOGO_URL;
        }
        return "";
    }

    private void registerDownloadReceiver() {
        downloadReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) {
                    return;
                }
                long taskId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L);
                // 只处理 activeDownloadPaths 中登记过的任务，忽略设备上其他应用的下载广播。
                if (activeDownloadPaths.containsKey(taskId)) {
                    reportDownloadResult(taskId, activeDownloadPaths.remove(taskId));
                }
            }
        };
        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        // DownloadManager 由系统进程发送完成广播，因此使用 EXPORTED；任务 ID 仍需命中本 Activity 的白名单。
        ContextCompat.registerReceiver(this, downloadReceiver, filter, ContextCompat.RECEIVER_EXPORTED);
    }

    private void reportDownloadResult(long taskId, String path) {
        DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        if (manager == null) {
            return;
        }
        try (Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(taskId))) {
            if (cursor == null || !cursor.moveToFirst()) {
                addAgentMessage("下载任务状态不可用（ID " + taskId + "）。");
                return;
            }
            int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                addAgentMessage("下载完成。\n保存路径：" + path);
            } else {
                int reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                addAgentMessage("下载失败（ID " + taskId + "，错误码 " + reason + "）。");
            }
        }
    }

    @Override
    protected void onDestroy() {
        cancelPendingAutoScroll();
        // SpeechRecognizer、MediaRecorder 和广播接收器都持有系统资源，页面销毁时必须释放。
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
            speechRecognizer = null;
        }
        if (localRecordingActive && mediaRecorder != null) {
            try {
                mediaRecorder.stop();
            } catch (RuntimeException ignored) {
            }
        }
        releaseMediaRecorder();
        if (downloadReceiver != null) {
            unregisterReceiver(downloadReceiver);
            downloadReceiver = null;
        }
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK) {
            return;
        }
        if (requestCode == REQ_DOC && data != null && data.getData() != null) {
            // ACTION_OPEN_DOCUMENT 返回的是系统文档 Uri，持久化读权限后下次会话仍可访问。
            Uri uri = data.getData();
            try {
                // 临时 Uri 权限通常随进程结束失效，这里申请持久权限供历史消息再次读取。
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {
            }
            setPendingAttachment(new PendingAttachment(
                    AttachmentType.DOCUMENT,
                    uri.toString(),
                    AttachmentReader.displayName(this, uri)
            ));
            Toast.makeText(this, "文档已添加，请输入问题后发送", Toast.LENGTH_SHORT).show();
        } else if (requestCode == REQ_CAMERA) {
            // 相机原图已按 EXTRA_OUTPUT 写入 pendingPhotoUri，返回 Intent 通常不携带图片数据。
            setPendingAttachment(new PendingAttachment(
                    AttachmentType.PHOTO,
                    String.valueOf(pendingPhotoUri),
                    safeLabel(pendingPhotoName)
            ));
        } else if (requestCode == REQ_GALLERY && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {
            }
            setPendingAttachment(new PendingAttachment(
                    AttachmentType.PHOTO,
                    uri.toString(),
                    AttachmentReader.displayName(this, uri)
            ));
        } else if (requestCode == REQ_VOICE && data != null) {
            applyVoiceResults(data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS));
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_AUDIO_PERMISSION && openVoiceAfterPermission) {
            openVoiceAfterPermission = false;
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                openVoiceInput();
            } else {
                Toast.makeText(this, "未授予麦克风权限，无法使用录音", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (requestCode != REQ_CAMERA_PERMISSION || !openCameraAfterPermission) {
            return;
        }
        openCameraAfterPermission = false;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            openCamera();
        } else {
            addAgentMessage("相机权限未授予，无法调用系统相机。可在系统设置中允许后重试。");
        }
    }

    private void setPendingAttachment(PendingAttachment attachment) {
        // 待发送附件独立于 ChatMessage；只有用户点击发送后才进入正式会话。
        pendingAttachment = attachment;
        renderPendingAttachment();
    }

    private void clearPendingAttachment() {
        if (transcriptionInProgress) {
            Toast.makeText(this, "录音正在转写，请稍候", Toast.LENGTH_SHORT).show();
            return;
        }
        if (pendingAttachment != null
                && pendingAttachment.type == AttachmentType.AUDIO
                && recordingFile != null
                && recordingFile.exists()) {
            recordingFile.delete();
            recordingFile = null;
        }
        transcriptionFailed = false;
        pendingAttachment = null;
        renderPendingAttachment();
    }

    private void renderPendingAttachment() {
        // 图片显示真实缩略图和右上角删除按钮；文档、语音继续显示文本附件信息。
        View box = findViewById(R.id.pendingAttachmentBox);
        TextView text = findViewById(R.id.pendingAttachmentText);
        TextView retryButton = findViewById(R.id.retryTranscriptionButton);
        TextView removeButton = findViewById(R.id.removeAttachmentButton);
        View photoBox = findViewById(R.id.photoPreviewBox);
        ImageView photoImage = findViewById(R.id.photoPreviewImage);
        if (box == null || text == null || retryButton == null || removeButton == null
                || photoBox == null || photoImage == null) {
            return;
        }
        if (pendingAttachment == null) {
            box.setVisibility(View.GONE);
            photoBox.setVisibility(View.GONE);
            photoImage.setImageDrawable(null);
            text.setText("");
            retryButton.setVisibility(View.GONE);
            removeButton.setVisibility(View.VISIBLE);
        } else if (pendingAttachment.type == AttachmentType.PHOTO) {
            box.setVisibility(View.GONE);
            text.setText("");
            retryButton.setVisibility(View.GONE);
            removeButton.setVisibility(View.VISIBLE);
            photoBox.setVisibility(View.VISIBLE);
            try {
                // 直接通过 content:// Uri 预览，不把整张图片提前读入内存。
                photoImage.setImageURI(Uri.parse(pendingAttachment.uri));
            } catch (Exception ignored) {
                photoImage.setImageDrawable(null);
            }
        } else {
            photoBox.setVisibility(View.GONE);
            photoImage.setImageDrawable(null);
            box.setVisibility(View.VISIBLE);
            if (pendingAttachment.type == AttachmentType.AUDIO) {
                if (transcriptionInProgress) {
                    text.setText("录音 · " + safeLabel(pendingAttachment.label)
                            + "\n正在通过千问 ASR 转成文字…");
                    retryButton.setVisibility(View.GONE);
                    removeButton.setVisibility(View.GONE);
                } else if (transcriptionFailed) {
                    text.setText("录音 · " + safeLabel(pendingAttachment.label)
                            + "\n转写失败，可重试或删除录音");
                    retryButton.setVisibility(View.VISIBLE);
                    removeButton.setVisibility(View.VISIBLE);
                } else {
                    text.setText("录音 · " + safeLabel(pendingAttachment.label));
                    retryButton.setVisibility(View.GONE);
                    removeButton.setVisibility(View.VISIBLE);
                }
            } else {
                text.setText("文档 · " + safeLabel(pendingAttachment.label)
                        + "\n发送前可继续补充问题");
                retryButton.setVisibility(View.GONE);
                removeButton.setVisibility(View.VISIBLE);
            }
        }
    }

    private String defaultAttachmentPrompt(AttachmentType type) {
        if (type == AttachmentType.PHOTO) {
            return "请分析这张图片。";
        }
        if (type == AttachmentType.DOCUMENT) {
            return "请阅读并总结这个文档。";
        }
        if (type == AttachmentType.AUDIO) {
            if (pendingAttachment != null
                    && pendingAttachment.uri != null
                    && !pendingAttachment.uri.trim().isEmpty()) {
                return "这是一段本地录音文件；当前设备没有语音识别服务，请根据我补充的文字回答。";
            }
            return "请根据语音识别出的文字回答。";
        }
        return "请处理这个附件。";
    }

    private String safeLabel(String label) {
        if (label == null || label.trim().isEmpty()) {
            return "已选择";
        }
        return label;
    }

    private File attachmentDirectory() {
        // 所有相机和录音临时文件集中存放，便于 FileProvider 按 file_paths.xml 安全共享。
        File directory = new File(getFilesDir(), "attachments");
        if (!directory.exists()) {
            directory.mkdirs();
        }
        return directory;
    }

    private void requestBasePermissions() {
        // 启动时只申请通知权限；麦克风和相机都在用户实际使用时按需申请。
        List<String> permissions = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!permissions.isEmpty()) {
            requestPermissions(permissions.toArray(new String[0]), REQ_PERMISSIONS);
        }
    }
}
