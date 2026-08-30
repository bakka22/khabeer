package com.termux.app;

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.widget.NestedScrollView;
import androidx.drawerlayout.widget.DrawerLayout;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputLayout;
import com.termux.R;
import com.termux.shared.android.PermissionUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Native mobile agent workspace: provider config + polished chat + optional Termux shell. */
public final class AiActivity extends AppCompatActivity implements AiRuntimeService.Listener {

    private static final int REQUEST_PICK_WORKSPACE = 7001;

    private static final String MODEL_AUTO = "";
    private static final String EFFORT_AUTO = "";
    private static final String APPROVAL_ON_REQUEST = "onRequest";
    private static final String APPROVAL_NEVER = "never";
    private static final int REASONING_FLUSH_DELAY_MS = 250;
    private static final int MAX_VISIBLE_REASONING_CHARS = 12000;

    private final Set<Long> mDisplayedRequestIds = new HashSet<>();
    private final List<String> mAttachedPaths = new ArrayList<>();

    private DrawerLayout mDrawer;
    private NestedScrollView mConversationScroll;
    private View mHomePanel;
    private View mSetupPanel;
    private View mChatPage;
    private View mSuggestionStrip;
    private View mTerminalCard;
    private LinearLayout mProviderGrid;
    private LinearLayout mDrawerProviderList;
    private LinearLayout mChatMessages;
    private LinearLayout mAttachmentList;
    private EditText mWorkspaceInput;
    private TextInputLayout mWorkspaceInputLayout;
    private EditText mPromptInput;
    private MaterialButton mStartButton;
    private MaterialButton mStopButton;
    private MaterialButton mTerminalButton;
    private MaterialButton mOpenShellButton;
    private MaterialButton mBrowseButton;
    private MaterialButton mStorageButton;
    private MaterialButton mLoginButton;
    private MaterialButton mModelButton;
    private MaterialButton mReasoningButton;
    private MaterialButton mApprovalButton;
    private MaterialButton mThinkingButton;
    private MaterialButton mAttachButton;
    private MaterialButton mChatSendButton;
    private MaterialButton mNewSessionButton;
    private ImageButton mSettingsButton;
    private ImageButton mMenuButton;
    private TextView mChatTitle;
    private TextView mHomeTitle;
    private TextView mHomeBody;
    private TextView mSelectedProviderIcon;
    private TextView mSelectedProviderTitle;
    private TextView mSelectedProviderBody;
    private TextView mSetupStatus;
    private TextView mShellStatus;
    private TextView mProviderStatus;
    private TextView mActivityStatus;
    private LinearLayout mRecentRuns;
    private TextView mTerminalTitle;
    private TextView mEmptyChatHint;
    private FrameLayout mTerminalContainer;
    private TerminalView mTerminalView;

    private AiProviderConfig mProviderConfig;
    private AiProviderProfile mSelectedProfile;
    private AiRuntimeService mRuntimeService;
    private TermuxService mTermuxService;
    private boolean mRuntimeBound;
    private boolean mTermuxBound;
    private boolean mRunActive;
    private boolean mHasNativeSession;
    private boolean mShowingProviderDirectory;
    private String mSelectedModel = MODEL_AUTO;
    private String mSelectedEffort = EFFORT_AUTO;
    private String mSelectedApproval = APPROVAL_ON_REQUEST;
    private TextView mStreamingAgentBubble;
    private TextView mThinkingBubble;
    private ToolBubble mReasoningBubble;
    private ToolBubble mCurrentToolBubble;
    private boolean mShowThinkingDetails;
    private int mThinkingFrame;
    private final StringBuilder mPendingReasoning = new StringBuilder();

    private final Handler mUiHandler = new Handler(Looper.getMainLooper());
    private final Runnable mThinkingAnimator = new Runnable() {
        @Override
        public void run() {
            if (mThinkingBubble == null) return;
            String[] frames = new String[]{"Thinking", "Thinking.", "Thinking..", "Thinking..."};
            mThinkingBubble.setText(frames[mThinkingFrame++ % frames.length]);
            mUiHandler.postDelayed(this, 450);
        }
    };
    private final Runnable mReasoningFlusher = new Runnable() {
        @Override
        public void run() {
            flushReasoningUi();
        }
    };

    private final ServiceConnection mRuntimeConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mRuntimeBound = true;
            mRuntimeService = ((AiRuntimeService.LocalBinder) service).getService();
            mRuntimeService.addListener(AiActivity.this);
            refreshRecentRuns();
            setStatus("Native agent runtime ready.", false);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mRuntimeBound = false;
            mRuntimeService = null;
            setStatus("Native agent runtime disconnected.", true);
        }
    };

    private final ServiceConnection mTermuxConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mTermuxBound = true;
            mTermuxService = ((TermuxService.LocalBinder) service).service;
            mShellStatus.setText("Shell available as an optional tool view.");
            mShellStatus.setTextColor(color(R.color.ai_success));
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mTermuxBound = false;
            mTermuxService = null;
            mShellStatus.setText("Optional shell disconnected.");
            mShellStatus.setTextColor(color(R.color.ai_warning));
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ai_workspace);
        mProviderConfig = new AiProviderConfig(this);
        bindViews();
        setupTerminalView();
        setupChrome();
        setupProviderTiles();
        setupActions();
        selectProvider(AiProviderProfile.find(mProviderConfig.getSelectedProviderId()), false);
        mChatTitle.setText("Mobile Hermes");
        bindRuntime();
        bindTermux();
    }

    @Override
    protected void onDestroy() {
        mUiHandler.removeCallbacks(mThinkingAnimator);
        mUiHandler.removeCallbacks(mReasoningFlusher);
        if (mRuntimeService != null) mRuntimeService.removeListener(this);
        if (mRuntimeBound) unbindService(mRuntimeConnection);
        if (mTermuxBound) unbindService(mTermuxConnection);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (mDrawer != null && mDrawer.isOpen()) {
            mDrawer.close();
            return;
        }
        if (mShowingProviderDirectory) {
            showFeaturedProviders();
            return;
        }
        if (mSetupPanel != null && mSetupPanel.getVisibility() == View.VISIBLE) {
            mSetupPanel.setVisibility(View.GONE);
            mChatPage.setVisibility(View.GONE);
            mHomePanel.setVisibility(View.VISIBLE);
            mChatTitle.setText("Mobile Hermes");
            showFeaturedProviders();
            return;
        }
        if (mChatPage != null && mChatPage.getVisibility() == View.VISIBLE && !mRunActive) {
            mChatPage.setVisibility(View.GONE);
            mSetupPanel.setVisibility(View.GONE);
            mHomePanel.setVisibility(View.VISIBLE);
            mChatTitle.setText("Mobile Hermes");
            showFeaturedProviders();
            return;
        }
        super.onBackPressed();
    }

    private void bindViews() {
        mDrawer = findViewById(R.id.ai_drawer);
        mConversationScroll = findViewById(R.id.ai_conversation_scroll);
        mHomePanel = findViewById(R.id.ai_home_panel);
        mSetupPanel = findViewById(R.id.ai_setup_panel);
        mChatPage = findViewById(R.id.ai_chat_page);
        mSuggestionStrip = findViewById(R.id.ai_suggestion_strip);
        mTerminalCard = findViewById(R.id.ai_terminal_card);
        mProviderGrid = findViewById(R.id.ai_harness_grid);
        mDrawerProviderList = findViewById(R.id.ai_drawer_harness_list);
        mChatMessages = findViewById(R.id.ai_chat_messages);
        mAttachmentList = findViewById(R.id.ai_attachment_list);
        mWorkspaceInput = findViewById(R.id.ai_workspace_input);
        mWorkspaceInputLayout = findViewById(R.id.ai_workspace_input_layout);
        mPromptInput = findViewById(R.id.ai_prompt_input);
        mStartButton = findViewById(R.id.ai_start_button);
        mStopButton = findViewById(R.id.ai_stop_button);
        mTerminalButton = findViewById(R.id.ai_terminal_button);
        mOpenShellButton = findViewById(R.id.ai_open_shell_button);
        mBrowseButton = findViewById(R.id.ai_browse_button);
        mStorageButton = findViewById(R.id.ai_storage_button);
        mLoginButton = findViewById(R.id.ai_login_button);
        mModelButton = findViewById(R.id.ai_model_button);
        mReasoningButton = findViewById(R.id.ai_reasoning_button);
        mApprovalButton = findViewById(R.id.ai_approval_button);
        mThinkingButton = findViewById(R.id.ai_thinking_button);
        mAttachButton = findViewById(R.id.ai_attach_button);
        mChatSendButton = findViewById(R.id.ai_chat_send_button);
        mNewSessionButton = findViewById(R.id.ai_new_session_button);
        mSettingsButton = findViewById(R.id.ai_settings_button);
        mMenuButton = findViewById(R.id.ai_menu_button);
        mChatTitle = findViewById(R.id.ai_chat_title);
        mHomeTitle = findViewById(R.id.ai_home_title_text);
        mHomeBody = findViewById(R.id.ai_home_body_text);
        mSelectedProviderIcon = findViewById(R.id.ai_selected_harness_icon);
        mSelectedProviderTitle = findViewById(R.id.ai_selected_harness_title);
        mSelectedProviderBody = findViewById(R.id.ai_selected_harness_body);
        mSetupStatus = findViewById(R.id.ai_setup_status);
        mShellStatus = findViewById(R.id.ai_shell_status);
        mProviderStatus = findViewById(R.id.ai_harness_status);
        mActivityStatus = findViewById(R.id.ai_activity_status);
        mRecentRuns = findViewById(R.id.ai_recent_runs);
        mTerminalTitle = findViewById(R.id.ai_terminal_title);
        mEmptyChatHint = findViewById(R.id.ai_empty_chat_hint);
        mTerminalContainer = findViewById(R.id.ai_terminal_container);
        mTerminalView = findViewById(R.id.ai_terminal_view);
        mWorkspaceInput.setText(TermuxConstants.TERMUX_HOME_DIR_PATH);
    }

    private void setupTerminalView() {
        mTerminalView.setTerminalViewClient(new AiTerminalViewClient(this, mTerminalView));
        mTerminalView.setTextSize(14);
        mTerminalView.setTypeface(Typeface.MONOSPACE);
        mTerminalView.setBackgroundColor(color(R.color.ai_terminal));
    }

    private void setupChrome() {
        mChatTitle.setText("Mobile Hermes");
        mMenuButton.setOnClickListener(view -> mDrawer.open());
        mSettingsButton.setOnClickListener(view -> startNewSession());
        mStopButton.setVisibility(View.GONE);
        mTerminalCard.setVisibility(View.GONE);
    }

    private void setupActions() {
        mStartButton.setOnClickListener(view -> showChatPage());
        mChatSendButton.setOnClickListener(view -> sendPrompt());
        mStopButton.setOnClickListener(view -> {
            if (mRuntimeService != null) mRuntimeService.stopActiveRun();
        });
        mBrowseButton.setOnClickListener(view -> pickWorkspace());
        mStorageButton.setOnClickListener(view -> ensureStorageAccess());
        mOpenShellButton.setOnClickListener(view -> {
            openShell();
            mDrawer.close();
        });
        mTerminalButton.setOnClickListener(view -> mTerminalCard.setVisibility(View.GONE));
        mLoginButton.setOnClickListener(view -> showApiKeyDialog());
        mModelButton.setOnClickListener(view -> showModelDialog());
        mReasoningButton.setOnClickListener(view -> showReasoningDialog());
        mApprovalButton.setOnClickListener(view -> showApprovalDialog());
        mThinkingButton.setOnClickListener(view -> toggleThinkingDetails());
        mAttachButton.setOnClickListener(view -> showAttachDialog());
        mNewSessionButton.setOnClickListener(view -> {
            startNewSession();
            mDrawer.close();
        });
        findViewById(R.id.ai_suggestion_project_plan).setOnClickListener(view ->
            mPromptInput.setText("Create a concise project plan for this folder. Inspect files first."));
        findViewById(R.id.ai_suggestion_fix_tests).setOnClickListener(view ->
            mPromptInput.setText("Inspect the project, find a failing test or obvious bug, propose the fix, then ask before changing files."));
    }

    private void setupProviderTiles() {
        showFeaturedProviders();
        mDrawerProviderList.removeAllViews();
        for (AiProviderProfile profile : AiProviderProfile.PROFILES) {
            mDrawerProviderList.addView(createProviderRow(profile));
        }
    }

    private void showFeaturedProviders() {
        mShowingProviderDirectory = false;
        mProviderGrid.removeAllViews();
        mHomeTitle.setText("Choose your agent");
        mHomeBody.setText("Start with OpenAI, Anthropic, or OpenCode. More providers opens the full Hermes registry.");
        for (String id : AiProviderProfile.FEATURED_PROVIDER_IDS) {
            AiProviderProfile profile = AiProviderProfile.find(id);
            if (profile != null) mProviderGrid.addView(createProviderTile(profile));
        }
        mProviderGrid.addView(createMoreProvidersTile());
        mHomePanel.setVisibility(View.VISIBLE);
        mSetupPanel.setVisibility(View.GONE);
        mChatPage.setVisibility(View.GONE);
        mChatTitle.setText("Mobile Hermes");
    }

    private void showProviderDirectory() {
        mShowingProviderDirectory = true;
        mProviderGrid.removeAllViews();
        mHomeTitle.setText("More providers");
        mHomeBody.setText("Hermes provider registry. Adapters marked “coming next” are listed honestly until their native request/auth flow is implemented.");
        for (AiProviderProfile profile : AiProviderProfile.PROFILES) {
            boolean featured = "openai".equals(profile.id) || "anthropic".equals(profile.id) || "opencode".equals(profile.id);
            if (!featured) mProviderGrid.addView(createProviderTile(profile));
        }
        mHomePanel.setVisibility(View.VISIBLE);
        mSetupPanel.setVisibility(View.GONE);
        mChatPage.setVisibility(View.GONE);
    }

    private MaterialCardView createProviderTile(AiProviderProfile profile) {
        MaterialCardView card = new MaterialCardView(this);
        card.setCardBackgroundColor(color(R.color.ai_surface));
        card.setStrokeColor(color(R.color.ai_border));
        card.setStrokeWidth(dp(1));
        card.setRadius(dp(24));
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(view -> selectProvider(profile, true));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(0, 0, 0, dp(12));
        card.setLayoutParams(cardParams);

        LinearLayout content = new LinearLayout(this);
        content.setGravity(Gravity.CENTER_VERTICAL);
        content.setPadding(dp(16), dp(14), dp(16), dp(14));
        content.setOrientation(LinearLayout.HORIZONTAL);
        card.addView(content);

        TextView mark = badge(profile.mark, 52);
        content.addView(mark);

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        textParams.setMargins(dp(14), 0, 0, 0);
        text.setLayoutParams(textParams);
        content.addView(text);

        TextView title = new TextView(this);
        title.setText(profile.name);
        title.setTextColor(color(R.color.ai_text));
        title.setTextSize(17);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        text.addView(title);

        TextView body = new TextView(this);
        body.setText(profile.description);
        body.setTextColor(color(R.color.ai_text_muted));
        body.setTextSize(13);
        body.setMaxLines(2);
        text.addView(body);

        if (!profile.implemented) {
            TextView state = new TextView(this);
            state.setText("Adapter coming next");
            state.setTextColor(color(R.color.ai_warning));
            state.setTextSize(12);
            state.setPadding(0, dp(6), 0, 0);
            text.addView(state);
        }
        return card;
    }

    private MaterialCardView createMoreProvidersTile() {
        MaterialCardView card = new MaterialCardView(this);
        card.setCardBackgroundColor(color(R.color.ai_surface));
        card.setStrokeColor(color(R.color.ai_border));
        card.setStrokeWidth(dp(1));
        card.setRadius(dp(24));
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(view -> showProviderDirectory());
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(0, 0, 0, dp(12));
        card.setLayoutParams(cardParams);

        LinearLayout content = new LinearLayout(this);
        content.setGravity(Gravity.CENTER_VERTICAL);
        content.setPadding(dp(16), dp(14), dp(16), dp(14));
        content.setOrientation(LinearLayout.HORIZONTAL);
        card.addView(content);
        content.addView(badge("…", 52));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        textParams.setMargins(dp(14), 0, 0, 0);
        content.addView(text, textParams);

        TextView title = new TextView(this);
        title.setText("More providers");
        title.setTextColor(color(R.color.ai_text));
        title.setTextSize(17);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        text.addView(title);

        TextView body = new TextView(this);
        body.setText("Browse every provider registered from Hermes Agent.");
        body.setTextColor(color(R.color.ai_text_muted));
        body.setTextSize(13);
        text.addView(body);
        return card;
    }

    private View createProviderRow(AiProviderProfile profile) {
        TextView row = new TextView(this);
        row.setText(profile.mark + "  " + profile.name);
        row.setTextColor(color(R.color.ai_text));
        row.setTextSize(14);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(12), dp(10), dp(12));
        row.setOnClickListener(view -> {
            selectProvider(profile, true);
            mDrawer.close();
        });
        return row;
    }

    private void selectProvider(@Nullable AiProviderProfile profile, boolean userInitiated) {
        mSelectedProfile = profile == null ? AiProviderProfile.firstAgentProfile() : profile;
        mProviderConfig.setSelectedProviderId(mSelectedProfile.id);
        mSelectedModel = mProviderConfig.getModel(mSelectedProfile);
        syncControlLabels();
        mSelectedProviderIcon.setText(mSelectedProfile.mark);
        mSelectedProviderTitle.setText(mSelectedProfile.name);
        mSelectedProviderBody.setText(mSelectedProfile.description);
        mEmptyChatHint.setText(mSelectedProfile.mark);
        mChatTitle.setText(mSelectedProfile.terminalOnly ? "Termux Shell" : mSelectedProfile.name);

        if (mSelectedProfile.terminalOnly) {
            mProviderStatus.setText("Direct shell is optional and separate from agent chat.");
            mSetupStatus.setText("Open the shell from the drawer when you want a raw terminal.");
            if (userInitiated) openShell();
            return;
        }

        boolean openCode = isOpenCode(mSelectedProfile);
        boolean ready = !mSelectedProfile.apiKeyAuth || mProviderConfig.hasApiKey(mSelectedProfile);
        mLoginButton.setText(openCode ? "Configure OpenCode" : (ready ? "Update API key" : "Add API key"));
        mProviderStatus.setText(ready
            ? (openCode ? "OpenCode is ready. Free is keyless; Zen/Go can use API keys." : "Provider configured. The chat screen can now call the model directly.")
            : "Configuration needed: add an API key for this provider.");
        mProviderStatus.setTextColor(color(ready ? R.color.ai_success : R.color.ai_warning));
        mSetupStatus.setText("Configuration is separate from chat. Model, reasoning, approval and files are controlled in chat.");
        if (userInitiated || mSetupPanel.getVisibility() == View.VISIBLE) showSetupPage();
    }

    private void showSetupPage() {
        mHomePanel.setVisibility(View.GONE);
        mSetupPanel.setVisibility(View.VISIBLE);
        mChatPage.setVisibility(View.GONE);
    }

    private void showChatPage() {
        if (mSelectedProfile == null || mSelectedProfile.terminalOnly) {
            showError("Choose an API provider first.");
            return;
        }
        if (!mSelectedProfile.implemented) {
            showError(mSelectedProfile.name + " is registered from Hermes, but its native mobile adapter is not implemented yet.");
            showSetupPage();
            return;
        }
        if (mSelectedProfile.apiKeyAuth && !mProviderConfig.hasApiKey(mSelectedProfile)) {
            showError("Add an API key before opening chat.");
            showSetupPage();
            return;
        }
        if (validateWorkspace() == null) return;
        mHomePanel.setVisibility(View.GONE);
        mSetupPanel.setVisibility(View.GONE);
        mChatPage.setVisibility(View.VISIBLE);
        syncControlLabels();
    }

    private void startNewSession() {
        if (mRuntimeService != null) mRuntimeService.stopActiveRun();
        mRunActive = false;
        mHasNativeSession = false;
        mChatMessages.removeAllViews();
        mStreamingAgentBubble = null;
        hideThinkingBubble();
        clearReasoningBuffer();
        mReasoningBubble = null;
        mCurrentToolBubble = null;
        mStopButton.setVisibility(View.GONE);
        mEmptyChatHint.setVisibility(View.VISIBLE);
        mChatTitle.setText("Mobile Hermes");
        mChatPage.setVisibility(View.GONE);
        mSetupPanel.setVisibility(View.GONE);
        mHomePanel.setVisibility(View.VISIBLE);
        setStatus("New session ready.", false);
        showFeaturedProviders();
    }

    private void sendPrompt() {
        showChatPage();
        if (mRuntimeService == null || !mRuntimeBound) {
            showError("Native runtime is still starting.");
            return;
        }
        String prompt = mPromptInput.getText() == null ? "" : mPromptInput.getText().toString().trim();
        if (prompt.isEmpty()) {
            mPromptInput.setError(getString(R.string.ai_prompt_required));
            return;
        }
        if (!mAttachedPaths.isEmpty()) prompt += "\n\nAttached files:\n- " + TextUtils.join("\n- ", mAttachedPaths);
        AiProviderProfile profile = mSelectedProfile;
        String workspace = validateWorkspace();
        if (profile == null || workspace == null) return;
        addUserMessage(prompt);
        mPromptInput.setText("");
        mEmptyChatHint.setVisibility(View.GONE);
        mSuggestionStrip.setVisibility(View.GONE);
        mStreamingAgentBubble = null;
        mStopButton.setVisibility(View.VISIBLE);
        String apiKey = mProviderConfig.getApiKey(profile);
        String baseUrl = mProviderConfig.getBaseUrl(profile);
        String model = TextUtils.isEmpty(mSelectedModel) ? profile.defaultModel : mSelectedModel;
        if (mHasNativeSession) {
            mRuntimeService.sendPrompt(prompt, profile.id, baseUrl, apiKey, model, clean(mSelectedEffort), mSelectedApproval);
        } else {
            mRuntimeService.startAgent(profile.id, baseUrl, apiKey, workspace, prompt, model, clean(mSelectedEffort), mSelectedApproval);
        }
    }

    private void showApiKeyDialog() {
        if (isOpenCode(mSelectedProfile)) {
            showOpenCodeDialog();
            return;
        }
        if (mSelectedProfile == null || !mSelectedProfile.apiKeyAuth) return;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(8), dp(18), 0);
        EditText baseUrl = new EditText(this);
        baseUrl.setSingleLine(true);
        baseUrl.setHint("Responses endpoint");
        baseUrl.setText(mProviderConfig.getBaseUrl(mSelectedProfile));
        EditText apiKey = new EditText(this);
        apiKey.setSingleLine(true);
        apiKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        apiKey.setHint("API key");
        box.addView(baseUrl);
        box.addView(apiKey);
        new MaterialAlertDialogBuilder(this)
            .setTitle(mSelectedProfile.name + " configuration")
            .setMessage("This screen configures credentials before chat. API keys are used by the native mobile agent runtime.")
            .setView(box)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.ai_continue, (dialog, which) -> {
                mProviderConfig.setBaseUrl(mSelectedProfile, baseUrl.getText().toString());
                String key = apiKey.getText().toString();
                if (!key.trim().isEmpty()) mProviderConfig.setApiKey(mSelectedProfile, key);
                selectProvider(mSelectedProfile, false);
                setStatus("Provider configuration saved.", false);
            })
            .show();
    }

    private void showOpenCodeDialog() {
        final String[] labels = new String[]{
            "OpenCode Free — keyless",
            "OpenCode Zen — API key",
            "OpenCode Go — API key"
        };
        final String[] baseUrls = new String[]{
            "https://opencode.ai/zen/v1",
            "https://opencode.ai/zen/v1",
            "https://opencode.ai/zen/go/v1"
        };
        final String[] models = new String[]{
            "big-pickle",
            "x-preview",
            "glm-5"
        };
        new MaterialAlertDialogBuilder(this)
            .setTitle("OpenCode route")
            .setItems(labels, (dialog, which) -> configureOpenCodeRoute(labels[which], baseUrls[which], models[which], which != 0))
            .show();
    }

    private void configureOpenCodeRoute(String label, String baseUrlValue, String modelValue, boolean needsKey) {
        if (mSelectedProfile == null) return;
        if (!needsKey) {
            mProviderConfig.setBaseUrl(mSelectedProfile, baseUrlValue);
            mProviderConfig.setModel(mSelectedProfile, modelValue);
            mProviderConfig.setApiKey(mSelectedProfile, "");
            mSelectedModel = modelValue;
            selectProvider(mSelectedProfile, false);
            setStatus(label + " selected.", false);
            return;
        }

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(8), dp(18), 0);
        EditText model = new EditText(this);
        model.setSingleLine(true);
        model.setHint("Model");
        model.setText(modelValue);
        EditText apiKey = new EditText(this);
        apiKey.setSingleLine(true);
        apiKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        apiKey.setHint(label.contains("Go") ? "OPENCODE_GO_API_KEY" : "OPENCODE_ZEN_API_KEY");
        box.addView(model);
        box.addView(apiKey);

        new MaterialAlertDialogBuilder(this)
            .setTitle(label)
            .setMessage("The native OpenCode adapter will send Chat Completions tool calls through " + baseUrlValue + ".")
            .setView(box)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.ai_continue, (dialog, which) -> {
                String key = apiKey.getText().toString().trim();
                if (key.isEmpty()) {
                    showError("OpenCode Zen/Go needs an API key.");
                    return;
                }
                mProviderConfig.setBaseUrl(mSelectedProfile, baseUrlValue);
                mProviderConfig.setModel(mSelectedProfile, model.getText().toString());
                mProviderConfig.setApiKey(mSelectedProfile, key);
                mSelectedModel = model.getText().toString();
                selectProvider(mSelectedProfile, false);
                setStatus(label + " configured.", false);
            })
            .show();
    }

    private void showModelDialog() {
        if (mSelectedProfile == null) return;
        AiProviderProfile profile = mSelectedProfile;
        String baseUrl = mProviderConfig.getBaseUrl(profile);
        String apiKey = mProviderConfig.getApiKey(profile);
        MaterialAlertDialogBuilder loadingBuilder = new MaterialAlertDialogBuilder(this)
            .setTitle("Loading models")
            .setMessage("Fetching " + profile.name + " models from " + AiModelCatalog.modelsUrl(baseUrl) + "…")
            .setNegativeButton(android.R.string.cancel, null);
        androidx.appcompat.app.AlertDialog loading = loadingBuilder.show();

        new Thread(() -> {
            try {
                List<String> models = AiModelCatalog.fetch(profile, baseUrl, apiKey);
                runOnUiThread(() -> {
                    loading.dismiss();
                    showModelListDialog(profile, models);
                });
            } catch (Exception e) {
                String message = e.getMessage() == null ? e.toString() : e.getMessage();
                runOnUiThread(() -> {
                    loading.dismiss();
                    showModelLoadFailedDialog(profile, message);
                });
            }
        }, "mobile-hermes-model-catalog").start();
    }

    private void showModelListDialog(AiProviderProfile profile, List<String> models) {
        ArrayList<String> items = new ArrayList<>();
        items.add("Default (" + profile.defaultModel + ")");
        items.add("Enter model ID manually…");
        items.addAll(models);
        new MaterialAlertDialogBuilder(this)
            .setTitle(profile.name + " models")
            .setItems(items.toArray(new String[0]), (dialog, which) -> {
                if (which == 0) setSelectedModel(profile, profile.defaultModel);
                else if (which == 1) showManualModelDialog(profile);
                else setSelectedModel(profile, items.get(which));
            })
            .show();
    }

    private void showModelLoadFailedDialog(AiProviderProfile profile, String message) {
        new MaterialAlertDialogBuilder(this)
            .setTitle("Couldn’t load live models")
            .setMessage(message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Enter model ID", (dialog, which) -> showManualModelDialog(profile))
            .show();
    }

    private void showManualModelDialog(AiProviderProfile profile) {
        EditText model = new EditText(this);
        model.setSingleLine(true);
        model.setHint("Model ID");
        model.setText(TextUtils.isEmpty(mSelectedModel) ? profile.defaultModel : mSelectedModel);
        new MaterialAlertDialogBuilder(this)
            .setTitle("Model ID")
            .setView(model)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.ai_continue, (dialog, which) ->
                setSelectedModel(profile, model.getText().toString()))
            .show();
    }

    private void setSelectedModel(AiProviderProfile profile, String model) {
        String cleanModel = model == null ? "" : model.trim();
        if (cleanModel.isEmpty()) cleanModel = profile.defaultModel;
        mSelectedModel = cleanModel;
        mProviderConfig.setModel(profile, cleanModel);
        syncControlLabels();
        setStatus("Model selected: " + cleanModel, false);
    }

    private void showReasoningDialog() {
        final String[] labels = new String[]{"Auto", "Low", "Medium", "High", "XHigh", "Ultra"};
        final String[] values = new String[]{"", "low", "medium", "high", "xhigh", "ultra"};
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ai_select_reasoning_title)
            .setItems(labels, (dialog, which) -> {
                mSelectedEffort = values[which];
                syncControlLabels();
            })
            .show();
    }

    private void showApprovalDialog() {
        final String[] labels = new String[]{"Ask before terminal commands", "Run without asking"};
        final String[] values = new String[]{APPROVAL_ON_REQUEST, APPROVAL_NEVER};
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ai_select_approval_title)
            .setItems(labels, (dialog, which) -> {
                mSelectedApproval = values[which];
                syncControlLabels();
            })
            .show();
    }

    private void showAttachDialog() {
        EditText pathInput = new EditText(this);
        pathInput.setSingleLine(true);
        pathInput.setHint(R.string.ai_attach_file_hint);
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ai_attach_file)
            .setView(pathInput)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.ai_continue, (dialog, which) -> {
                String path = pathInput.getText().toString().trim();
                if (!path.isEmpty()) {
                    mAttachedPaths.add(path);
                    refreshAttachments();
                }
            })
            .show();
    }

    private void syncControlLabels() {
        if (mSelectedProfile == null) return;
        String model = TextUtils.isEmpty(mSelectedModel) ? mSelectedProfile.defaultModel : mSelectedModel;
        mModelButton.setText(model);
        mReasoningButton.setText(TextUtils.isEmpty(mSelectedEffort) ? "Reasoning auto" : "Reasoning " + mSelectedEffort);
        mApprovalButton.setText(APPROVAL_NEVER.equals(mSelectedApproval) ? "No approvals" : "Ask approvals");
        mThinkingButton.setText(mShowThinkingDetails ? "Thinking details" : "Thinking compact");
    }

    private void refreshAttachments() {
        mAttachmentList.removeAllViews();
        for (String path : mAttachedPaths) {
            TextView chip = new TextView(this);
            chip.setText(path);
            chip.setTextColor(color(R.color.ai_text));
            chip.setTextSize(12);
            chip.setPadding(dp(12), dp(6), dp(12), dp(6));
            chip.setBackgroundResource(R.drawable.bg_ai_chip);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMargins(0, 0, dp(8), dp(6));
            mAttachmentList.addView(chip, params);
        }
    }

    private void openShell() {
        if (!mTermuxBound || mTermuxService == null) {
            showError("Termux shell service is still starting.");
            return;
        }
        String workspace = validateWorkspace();
        if (workspace == null) return;
        TermuxSession session = mTermuxService.createTermuxSession(null, null, null, workspace, false, "Mobile Hermes shell");
        if (session == null) {
            showError("Unable to open the optional Termux shell.");
            return;
        }
        TerminalSession terminalSession = session.getTerminalSession();
        mTerminalView.attachSession(terminalSession);
        mTerminalTitle.setText("Optional Termux shell");
        mTerminalCard.setVisibility(View.VISIBLE);
        mTerminalView.requestFocus();
    }

    private void bindRuntime() {
        Intent intent = new Intent(this, AiRuntimeService.class);
        startService(intent);
        bindService(intent, mRuntimeConnection, 0);
    }

    private void bindTermux() {
        Intent intent = new Intent(this, TermuxService.class);
        startService(intent);
        bindService(intent, mTermuxConnection, 0);
    }

    @Nullable
    private String validateWorkspace() {
        String path = MobileHermesToolExecutor.normalizeWorkspace(mWorkspaceInput.getText().toString());
        if (path == null) {
            mWorkspaceInputLayout.setError("Choose a readable folder under Termux home or shared storage.");
            return null;
        }
        mWorkspaceInputLayout.setError(null);
        return path;
    }

    private void pickWorkspace() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_PICK_WORKSPACE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK_WORKSPACE || resultCode != RESULT_OK || data == null || data.getData() == null)
            return;
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri,
                data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
        } catch (SecurityException ignored) {
        }
        String path = pathFromTreeUri(uri);
        if (path == null || MobileHermesToolExecutor.normalizeWorkspace(path) == null) {
            showError("That folder is not accessible from the Termux shell.");
            return;
        }
        mWorkspaceInput.setText(path);
    }

    @Nullable
    private String pathFromTreeUri(Uri uri) {
        String documentId;
        try {
            documentId = DocumentsContract.getTreeDocumentId(uri);
        } catch (Exception e) {
            return null;
        }
        if (TextUtils.isEmpty(documentId)) return null;
        if ("com.android.externalstorage.documents".equals(uri.getAuthority()) && documentId.startsWith("primary:"))
            return "/storage/emulated/0/" + documentId.substring("primary:".length());
        if ((TermuxConstants.TERMUX_PACKAGE_NAME + ".documents").equals(uri.getAuthority()))
            return documentId;
        return null;
    }

    private void ensureStorageAccess() {
        if (PermissionUtils.checkAndRequestLegacyOrManageExternalStoragePermission(this, -1, true)) {
            TermuxInstaller.setupStorageSymlinks(this);
            setStatus(getString(R.string.ai_phone_storage_ready), false);
        }
    }

    @Override
    public void onRunChanged(AiDatabase.RunRecord run) {
        mHasNativeSession = run != null && run.state != AiRunStateMachine.State.FAILED
            && run.state != AiRunStateMachine.State.CANCELED;
        mRunActive = run != null && run.state != AiRunStateMachine.State.COMPLETED
            && run.state != AiRunStateMachine.State.FAILED
            && run.state != AiRunStateMachine.State.CANCELED;
        mStopButton.setVisibility(mRunActive ? View.VISIBLE : View.GONE);
        if (!mRunActive) {
            hideThinkingBubble();
            mReasoningBubble = null;
            mCurrentToolBubble = null;
        }
        if (run != null) setStatus(run.state.name().toLowerCase(), run.state == AiRunStateMachine.State.FAILED);
        refreshRecentRuns();
    }

    @Override
    public void onProtocolEvent(String runId, String method, JSONObject payload) {
        if ("turn/started".equals(method)) {
            mStreamingAgentBubble = null;
            clearReasoningBuffer();
            mReasoningBubble = null;
            mCurrentToolBubble = null;
            showThinkingBubble();
        } else if ("tool/callStarted".equals(method)) {
            hideThinkingBubble();
            mStreamingAgentBubble = null;
            startToolBubble(payload);
        } else if ("item/reasoning/delta".equals(method)) {
            appendReasoningDelta(payload.optString("text"));
        } else if ("item/agentMessage/delta".equals(method)) {
            appendAgentDelta(payload.optString("text"));
        } else if ("item/commandExecution/outputDelta".equals(method)) {
            appendToolOutput(payload.optString("text"));
        } else if (method.contains("requestApproval")) {
            showApproval(payload);
        } else {
            appendEvent(method, payload.toString());
        }
    }

    @Override
    public void onAuthenticationUrl(String runId, String loginId, String url) {
        appendEvent("auth/url", url);
    }

    @Override
    public void onDeviceCode(String runId, String loginId, String url, String code) {
        appendEvent("auth/device", url + " " + code);
    }

    @Override
    public void onRuntimeError(String runId, String message) {
        showError(message);
        addSystemMessage(message);
    }

    private void addUserMessage(String text) {
        addBubble("You", text, true, R.drawable.bg_ai_user_bubble, R.color.ai_text);
    }

    private void appendAgentDelta(String text) {
        if (TextUtils.isEmpty(text) || "Thinking…".equals(text)) return;
        hideThinkingBubble();
        mCurrentToolBubble = null;
        if (mStreamingAgentBubble == null)
            mStreamingAgentBubble = addBubble(mSelectedProfile == null ? "Agent" : mSelectedProfile.name,
                "", false, R.drawable.bg_ai_agent_bubble, R.color.ai_text);
        mStreamingAgentBubble.append(text);
        scrollConversation();
    }

    private void appendToolOutput(String text) {
        if (TextUtils.isEmpty(text)) return;
        hideThinkingBubble();
        mStreamingAgentBubble = null;
        if (mCurrentToolBubble == null) startToolBubble(new JSONObject());
        mCurrentToolBubble.details.append(text);
        mCurrentToolBubble.detail.setText(mCurrentToolBubble.details.toString());
        scrollConversation();
    }

    private void addSystemMessage(String text) {
        addBubble("System", text, false, R.drawable.bg_ai_tool_bubble, R.color.ai_text_muted);
    }

    private void toggleThinkingDetails() {
        mShowThinkingDetails = !mShowThinkingDetails;
        if (mShowThinkingDetails) hideThinkingBubble();
        syncControlLabels();
    }

    private void showThinkingBubble() {
        if (mShowThinkingDetails) {
            mReasoningBubble = startExpandableBubble("Reasoning", "Thinking details · live", false);
            mReasoningBubble.expanded = true;
            mReasoningBubble.detail.setVisibility(View.VISIBLE);
            mReasoningBubble.details.append("Waiting for provider response…\n");
            mReasoningBubble.detail.setText(mReasoningBubble.details.toString());
            return;
        }
        hideThinkingBubble();
        mThinkingFrame = 0;
        mThinkingBubble = addBubble(mSelectedProfile == null ? "Agent" : mSelectedProfile.name,
            "Thinking", false, R.drawable.bg_ai_tool_bubble, R.color.ai_text_muted);
        mUiHandler.post(mThinkingAnimator);
    }

    private void hideThinkingBubble() {
        mUiHandler.removeCallbacks(mThinkingAnimator);
        if (mThinkingBubble == null) return;
        View wrapper = (View) mThinkingBubble.getParent();
        if (wrapper != null && wrapper.getParent() instanceof ViewGroup)
            ((ViewGroup) wrapper.getParent()).removeView(wrapper);
        mThinkingBubble = null;
    }

    private void appendReasoningDelta(String text) {
        if (TextUtils.isEmpty(text)) return;
        if (mShowThinkingDetails) {
            mPendingReasoning.append(text);
            mUiHandler.removeCallbacks(mReasoningFlusher);
            mUiHandler.postDelayed(mReasoningFlusher, REASONING_FLUSH_DELAY_MS);
        } else {
            appendEvent("reasoning", oneLine(text, 220));
        }
    }

    private void flushReasoningUi() {
        if (mPendingReasoning.length() == 0 || !mShowThinkingDetails) return;
        hideThinkingBubble();
        if (mReasoningBubble == null) {
            mReasoningBubble = startExpandableBubble("Reasoning", "Thinking details · live", false);
            mReasoningBubble.expanded = true;
            mReasoningBubble.detail.setVisibility(View.VISIBLE);
        }
        mReasoningBubble.details.append(mPendingReasoning);
        mPendingReasoning.setLength(0);
        trimVisibleReasoning(mReasoningBubble.details);
        mReasoningBubble.detail.setText(mReasoningBubble.details.toString());
        scrollConversation();
    }

    private void trimVisibleReasoning(StringBuilder reasoning) {
        if (reasoning.length() <= MAX_VISIBLE_REASONING_CHARS) return;
        int remove = reasoning.length() - MAX_VISIBLE_REASONING_CHARS;
        reasoning.delete(0, remove);
        reasoning.insert(0, "… earlier thinking hidden for performance …\n");
    }

    private void clearReasoningBuffer() {
        mUiHandler.removeCallbacks(mReasoningFlusher);
        mPendingReasoning.setLength(0);
    }

    private void startToolBubble(JSONObject payload) {
        String command = payload == null ? "" : payload.optString("command", "");
        String name = payload == null ? "" : payload.optString("name", "terminal");
        if (TextUtils.isEmpty(name)) name = "terminal";
        mCurrentToolBubble = startExpandableBubble("Tool call",
            "Terminal · " + (TextUtils.isEmpty(command) ? name : oneLine(command, 96)), true);
        appendEvent("tool", TextUtils.isEmpty(command) ? name : command);
        scrollConversation();
    }

    private ToolBubble startExpandableBubble(String label, String summaryText, boolean monospaceDetail) {
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setGravity(Gravity.START);
        LinearLayout.LayoutParams wrapperParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wrapperParams.setMargins(0, dp(8), 0, dp(8));
        mChatMessages.addView(wrapper, wrapperParams);

        TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setTextColor(color(R.color.ai_text_muted));
        labelView.setTextSize(11);
        wrapper.addView(labelView);

        TextView summary = new TextView(this);
        summary.setSingleLine(true);
        summary.setEllipsize(TextUtils.TruncateAt.END);
        summary.setText(summaryText);
        summary.setTextColor(color(R.color.ai_text_muted));
        summary.setTextSize(14);
        summary.setPadding(dp(14), dp(10), dp(14), dp(10));
        summary.setBackgroundResource(R.drawable.bg_ai_tool_bubble);
        LinearLayout.LayoutParams bubbleParams = new LinearLayout.LayoutParams(
            Math.min(getResources().getDisplayMetrics().widthPixels - dp(64), dp(640)),
            ViewGroup.LayoutParams.WRAP_CONTENT);
        wrapper.addView(summary, bubbleParams);

        TextView detail = new TextView(this);
        detail.setTextColor(color(R.color.ai_text_muted));
        detail.setTextSize(12);
        if (monospaceDetail) detail.setTypeface(Typeface.MONOSPACE);
        detail.setLineSpacing(dp(2), 1f);
        detail.setPadding(dp(14), dp(10), dp(14), dp(10));
        detail.setBackgroundResource(R.drawable.bg_ai_tool_bubble);
        detail.setVisibility(View.GONE);
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(
            Math.min(getResources().getDisplayMetrics().widthPixels - dp(64), dp(640)),
            ViewGroup.LayoutParams.WRAP_CONTENT);
        detailParams.setMargins(0, dp(4), 0, 0);
        wrapper.addView(detail, detailParams);

        ToolBubble toolBubble = new ToolBubble(summary, detail);
        toolBubble.expanded = false;
        summary.setOnClickListener(view -> {
            toolBubble.expanded = !toolBubble.expanded;
            toolBubble.detail.setVisibility(toolBubble.expanded ? View.VISIBLE : View.GONE);
            scrollConversation();
        });
        scrollConversation();
        return toolBubble;
    }

    private TextView addBubble(String label, String text, boolean alignEnd, int background, int textColor) {
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setGravity(alignEnd ? Gravity.END : Gravity.START);
        LinearLayout.LayoutParams wrapperParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wrapperParams.setMargins(0, dp(8), 0, dp(8));
        mChatMessages.addView(wrapper, wrapperParams);

        TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setTextColor(color(R.color.ai_text_muted));
        labelView.setTextSize(11);
        wrapper.addView(labelView);

        TextView bubble = new TextView(this);
        bubble.setText(text);
        bubble.setTextColor(color(textColor));
        bubble.setTextSize(15);
        bubble.setLineSpacing(dp(2), 1f);
        bubble.setPadding(dp(14), dp(10), dp(14), dp(10));
        bubble.setBackgroundResource(background);
        LinearLayout.LayoutParams bubbleParams = new LinearLayout.LayoutParams(
            Math.min(getResources().getDisplayMetrics().widthPixels - dp(64), dp(640)),
            ViewGroup.LayoutParams.WRAP_CONTENT);
        wrapper.addView(bubble, bubbleParams);
        scrollConversation();
        return bubble;
    }

    private void showApproval(JSONObject payload) {
        long id = payload.optLong("_requestId", -1);
        if (id < 0 || mDisplayedRequestIds.contains(id)) return;
        mDisplayedRequestIds.add(id);
        String command = payload.optString("command", payload.optString("cwd", ""));
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ai_approval_title)
            .setMessage(command)
            .setNegativeButton(R.string.ai_deny, (dialog, which) -> answerApproval(id, false))
            .setPositiveButton(R.string.ai_allow_once, (dialog, which) -> answerApproval(id, true))
            .show();
    }

    private void answerApproval(long requestId, boolean approved) {
        if (mRuntimeService == null) return;
        JSONObject result = new JSONObject();
        try {
            result.put("approved", approved);
        } catch (Exception ignored) {
        }
        mRuntimeService.respondToRequest(requestId, result);
    }

    private void refreshRecentRuns() {
        if (mRuntimeService == null || mRecentRuns == null) return;
        while (mRecentRuns.getChildCount() > 1) mRecentRuns.removeViewAt(1);
        for (AiDatabase.RunRecord run : mRuntimeService.getRecentRuns()) {
            TextView item = new TextView(this);
            item.setText(run.harnessId + " · " + run.state.name().toLowerCase());
            item.setTextColor(color(R.color.ai_text_muted));
            item.setTextSize(12);
            item.setPadding(0, dp(8), 0, dp(8));
            mRecentRuns.addView(item);
        }
    }

    private TextView badge(String text, int sizeDp) {
        TextView badge = new TextView(this);
        badge.setText(text);
        badge.setGravity(Gravity.CENTER);
        badge.setTextColor(color(R.color.ai_text));
        badge.setTextSize(18);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setBackgroundResource(R.drawable.bg_ai_harness_badge);
        badge.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return badge;
    }

    private void appendEvent(String method, String text) {
        // Intentionally not rendered in the chat UI. Persisted runtime events live in AiDatabase.
    }

    private String oneLine(String value, int maxChars) {
        String clean = value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
        while (clean.contains("  ")) clean = clean.replace("  ", " ");
        if (maxChars > 0 && clean.length() > maxChars) return clean.substring(0, maxChars - 1) + "…";
        return clean;
    }

    private void setStatus(String text, boolean warning) {
        if (mActivityStatus == null) return;
        mActivityStatus.setText(text);
        mActivityStatus.setTextColor(color(warning ? R.color.ai_warning : R.color.ai_text_muted));
    }

    private void showError(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        setStatus(message, true);
    }

    private void scrollConversation() {
        mConversationScroll.post(() -> mConversationScroll.fullScroll(ScrollView.FOCUS_DOWN));
    }

    private int color(int resId) {
        return ContextCompat.getColor(this, resId);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Nullable
    private String clean(@Nullable String value) {
        return TextUtils.isEmpty(value) ? null : value;
    }

    private boolean isOpenCode(@Nullable AiProviderProfile profile) {
        return profile != null && "opencode".equals(profile.id);
    }

    private static final class ToolBubble {
        final TextView summary;
        final TextView detail;
        final StringBuilder details = new StringBuilder();
        boolean expanded;

        ToolBubble(TextView summary, TextView detail) {
            this.summary = summary;
            this.detail = detail;
        }
    }
}
