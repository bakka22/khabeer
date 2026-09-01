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
    private androidx.appcompat.app.AlertDialog mApprovalDialog;
    private Runnable mApprovalCountdown;
    private long mApprovalDeadline;
    private static final long APPROVAL_AUTO_DENY_MS = 120_000;
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
    private android.widget.ImageView mSelectedProviderIcon;
    private TextView mSelectedProviderTitle;
    private TextView mSelectedProviderBody;
    private TextView mSetupStatus;
    private TextView mShellStatus;
    private TextView mProviderStatus;
    private TextView mActivityStatus;
    private LinearLayout mRecentRuns;
    private View mSessionsPage;
    private LinearLayout mSessionsList;
    private MaterialButton mProviderButton;
    private boolean mPickingSessionProvider;
    private com.google.android.material.button.MaterialButton mUseSavedConfigButton;
    private View mOpenCodePage;
    private View mExtensionsPage;
    private LinearLayout mExtensionsList;
    private MaterialButton mExtensionsButton;
    private LinearLayout mArchivedRuns;
    private View mProvidersHeader;
    private View mSessionsHeader;
    private View mArchivedHeader;
    private TextView mProvidersChevron;
    private TextView mSessionsChevron;
    private TextView mArchivedChevron;
    private boolean mProvidersExpanded;
    private boolean mSessionsExpanded;
    private boolean mArchivedExpanded;
    private String mCurrentRunId;
    private TextView mTerminalTitle;
    private TextView mEmptyChatHint;
    private FrameLayout mTerminalContainer;
    private TerminalView mTerminalView;
    private TextView mSetupTitle;
    private TextView mSetupModelDisplay;

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
    private boolean mUserScrolledUp;
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
        AiThemeMode.apply(this);
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
        if (mDrawer != null && mDrawer.isDrawerOpen(findViewById(R.id.ai_drawer_panel))) {
            mDrawer.closeDrawer(findViewById(R.id.ai_drawer_panel));
            return;
        }
        if (mOpenCodePage != null && mOpenCodePage.getVisibility() == View.VISIBLE) {
            mOpenCodePage.setVisibility(View.GONE);
            if (mPickingSessionProvider) {
                mPickingSessionProvider = false;
                showChatPage();
            } else {
                mHomePanel.setVisibility(View.VISIBLE);
                showFeaturedProviders();
            }
            return;
        }
        if (mExtensionsPage != null && mExtensionsPage.getVisibility() == View.VISIBLE) {
            mExtensionsPage.setVisibility(View.GONE);
            mHomePanel.setVisibility(View.VISIBLE);
            showFeaturedProviders();
            return;
        }
        if (mSessionsPage != null && mSessionsPage.getVisibility() == View.VISIBLE) {
            mSessionsPage.setVisibility(View.GONE);
            mHomePanel.setVisibility(View.VISIBLE);
            showFeaturedProviders();
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
        mSessionsPage = findViewById(R.id.ai_sessions_page);
        mSessionsList = findViewById(R.id.ai_sessions_list);
        mProviderButton = findViewById(R.id.ai_provider_button);
        mOpenCodePage = findViewById(R.id.ai_opencode_page);
        mExtensionsPage = findViewById(R.id.ai_extensions_page);
        mExtensionsList = findViewById(R.id.ai_extensions_list);
        mExtensionsButton = findViewById(R.id.ai_extensions_button);
        mArchivedRuns = findViewById(R.id.ai_archived_runs);
        mProvidersHeader = findViewById(R.id.ai_providers_header);
        mSessionsHeader = findViewById(R.id.ai_sessions_header);
        mArchivedHeader = findViewById(R.id.ai_archived_header);
        mProvidersChevron = findViewById(R.id.ai_providers_chevron);
        mSessionsChevron = findViewById(R.id.ai_sessions_chevron);
        mArchivedChevron = findViewById(R.id.ai_archived_chevron);
        mTerminalTitle = findViewById(R.id.ai_terminal_title);
        mEmptyChatHint = findViewById(R.id.ai_empty_chat_hint);
        mTerminalContainer = findViewById(R.id.ai_terminal_container);
        mTerminalView = findViewById(R.id.ai_terminal_view);
        mSetupTitle = findViewById(R.id.ai_setup_title);
        mSetupModelDisplay = findViewById(R.id.ai_model_display);
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
        if (mMenuButton != null) mMenuButton.setOnClickListener(view -> { if (mDrawer != null) mDrawer.openDrawer(findViewById(R.id.ai_drawer_panel)); });
        if (mSettingsButton != null) mSettingsButton.setOnClickListener(view -> createNewSessionChat());
        if (mStopButton != null) mStopButton.setVisibility(View.GONE);
        if (mTerminalCard != null) mTerminalCard.setVisibility(View.GONE);
        View navHome = findViewById(R.id.nav_home);
        View navSessions = findViewById(R.id.nav_sessions);
        View navShell = findViewById(R.id.nav_shell);
        View navProviders = findViewById(R.id.nav_providers);
        View navSettings = findViewById(R.id.nav_settings);
        if (navHome != null) navHome.setOnClickListener(v -> showFeaturedProviders());
        if (navSessions != null) navSessions.setOnClickListener(v -> showSessionsPage());
        if (navShell != null) navShell.setOnClickListener(v -> openChatLastActive());
        if (navProviders != null) navProviders.setOnClickListener(v -> showProviderDirectory());
        if (navSettings != null) navSettings.setOnClickListener(v -> startActivity(new android.content.Intent(this, com.termux.app.activities.SettingsActivity.class)));
        View setupBack = findViewById(R.id.ai_setup_back);
        if (setupBack != null) setupBack.setOnClickListener(v -> {
            if (mPickingSessionProvider) {
                mPickingSessionProvider = false;
                showChatPage();
                return;
            }
            showFeaturedProviders();
        });
        setupDrawerSections();
        mConversationScroll.setOnScrollChangeListener((androidx.core.widget.NestedScrollView.OnScrollChangeListener) (v, scrollX, scrollY, oldX, oldY) -> {
            View content = v.getChildAt(0);
            if (content == null) return;
            int range = content.getHeight() - v.getHeight();
            mUserScrolledUp = range > 0 && scrollY < range - dp(56);
        });
    }

    private void setupDrawerSections() {
        if (mProvidersHeader != null) mProvidersHeader.setOnClickListener(v -> {
            mProvidersExpanded = !mProvidersExpanded;
            applySectionState();
        });
        if (mSessionsHeader != null) mSessionsHeader.setOnClickListener(v -> {
            mSessionsExpanded = !mSessionsExpanded;
            applySectionState();
        });
        if (mArchivedHeader != null) mArchivedHeader.setOnClickListener(v -> {
            mArchivedExpanded = !mArchivedExpanded;
            applySectionState();
        });
        applySectionState();
    }

    private void applySectionState() {
        setSectionExpanded(mDrawerProviderList, mProvidersChevron, mProvidersExpanded);
        setSectionExpanded(mRecentRuns, mSessionsChevron, mSessionsExpanded);
        boolean hasArchived = mArchivedRuns != null && mArchivedRuns.getChildCount() > 0;
        if (mArchivedHeader != null) mArchivedHeader.setVisibility(hasArchived ? View.VISIBLE : View.GONE);
        setSectionExpanded(mArchivedRuns, mArchivedChevron, mArchivedExpanded && hasArchived);
    }

    private void setSectionExpanded(View list, TextView chevron, boolean expanded) {
        if (list == null) return;
        list.setVisibility(expanded ? View.VISIBLE : View.GONE);
        if (chevron != null) chevron.setRotation(expanded ? 180f : 0f);
    }

    private void setupActions() {
        mStartButton.setOnClickListener(view -> {
            if (mPickingSessionProvider) applySessionProviderChoice();
            else showChatPage();
        });
        mChatSendButton.setOnClickListener(view -> sendPrompt());
        mStopButton.setOnClickListener(view -> {
            if (mRuntimeService != null) mRuntimeService.stopActiveRun();
        });
        mBrowseButton.setOnClickListener(view -> pickWorkspace());
        mStorageButton.setOnClickListener(view -> ensureStorageAccess());
        mOpenShellButton.setOnClickListener(view -> {
            openShell();
            if (mDrawer != null) mDrawer.closeDrawer(findViewById(R.id.ai_drawer_panel));
        });
        if (mExtensionsButton != null) mExtensionsButton.setOnClickListener(view -> {
            showExtensionsPage();
            if (mDrawer != null) mDrawer.closeDrawer(findViewById(R.id.ai_drawer_panel));
        });
        mTerminalButton.setOnClickListener(view -> mTerminalCard.setVisibility(View.GONE));
        mLoginButton.setOnClickListener(view -> showApiKeyDialog());
        mModelButton.setOnClickListener(view -> showModelDialog());
        if (mProviderButton != null) mProviderButton.setOnClickListener(view -> pickSessionProvider());
        mReasoningButton.setOnClickListener(view -> showReasoningDialog());
        mApprovalButton.setOnClickListener(view -> showApprovalDialog());
        mThinkingButton.setOnClickListener(view -> toggleThinkingDetails());
        mAttachButton.setOnClickListener(view -> showAttachDialog());
        mNewSessionButton.setOnClickListener(view -> {
            createNewSessionChat();
            if (mDrawer != null) mDrawer.closeDrawer(findViewById(R.id.ai_drawer_panel));
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

    private void showSessionsPage() {
        if (mSessionsPage == null) return;
        mHomePanel.setVisibility(View.GONE);
        mSetupPanel.setVisibility(View.GONE);
        mChatPage.setVisibility(View.GONE);
        if (mOpenCodePage != null) mOpenCodePage.setVisibility(View.GONE);
        if (mExtensionsPage != null) mExtensionsPage.setVisibility(View.GONE);
        mSessionsPage.setVisibility(View.VISIBLE);
        mChatTitle.setText("Sessions");
        refreshSessionsPage();
        if (mRuntimeService != null) mRuntimeService.backfillSessionTitles();
    }

    private void refreshSessionsPage() {
        if (mSessionsList == null) return;
        mSessionsList.removeAllViews();
        TextView heading = new TextView(this);
        heading.setText("SESSIONS");
        heading.setTextColor(color(R.color.ai_text));
        heading.setTextSize(12);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        heading.setLetterSpacing(0.08f);
        heading.setPadding(0, dp(8), 0, dp(10));
        mSessionsList.addView(heading);
        java.util.List<AiDatabase.RunRecord> sessions = mRuntimeService == null
            ? new ArrayList<>() : mRuntimeService.getSessions();
        if (sessions.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No sessions yet. Start one from Home or the + button.");
            empty.setTextColor(color(R.color.ai_text_muted));
            empty.setTextSize(13);
            empty.setPadding(0, dp(6), 0, dp(16));
            mSessionsList.addView(empty);
        } else {
            for (AiDatabase.RunRecord run : sessions) mSessionsList.addView(createSessionRow(run, false));
        }
        java.util.List<AiDatabase.RunRecord> archived = mRuntimeService == null
            ? new ArrayList<>() : mRuntimeService.getArchivedSessions();
        if (!archived.isEmpty()) {
            TextView archivedHeading = new TextView(this);
            archivedHeading.setText("ARCHIVED");
            archivedHeading.setTextColor(color(R.color.ai_text_muted));
            archivedHeading.setTextSize(12);
            archivedHeading.setTypeface(Typeface.DEFAULT_BOLD);
            archivedHeading.setLetterSpacing(0.08f);
            archivedHeading.setPadding(0, dp(18), 0, dp(10));
            mSessionsList.addView(archivedHeading);
            for (AiDatabase.RunRecord run : archived) mSessionsList.addView(createSessionRow(run, true));
        }
    }

    /** Opens the chat with the last interacted session, or starts a fresh one. */
    private void openChatLastActive() {
        if (mRuntimeService == null || !mRuntimeBound) {
            showError("Native runtime is still starting.");
            return;
        }
        AiDatabase.RunRecord active = mRuntimeService.getActiveRun();
        if (active != null) {
            mCurrentRunId = active.id;
            rebuildTranscript(active.id);
            syncControlLabels();
            showChatPage();
            return;
        }
        java.util.List<AiDatabase.RunRecord> sessions = mRuntimeService.getSessions();
        if (!sessions.isEmpty()) {
            openResumedSession(sessions.get(0).id);
            return;
        }
        createNewSessionChat();
    }

    /** New session = an instant empty chat wired to the last configured
     * provider/model; the previous session stays live in the background. */
    private void createNewSessionChat() {
        if (mRuntimeService == null || !mRuntimeBound) {
            showError("Native runtime is still starting.");
            return;
        }
        mRuntimeService.newSession();
        mCurrentRunId = null;
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
        mSuggestionStrip.setVisibility(View.VISIBLE);
        mChatPage.setVisibility(View.VISIBLE);
        mHomePanel.setVisibility(View.GONE);
        mSetupPanel.setVisibility(View.GONE);
        if (mSessionsPage != null) mSessionsPage.setVisibility(View.GONE);
        if (mOpenCodePage != null) mOpenCodePage.setVisibility(View.GONE);
        if (mExtensionsPage != null) mExtensionsPage.setVisibility(View.GONE);
        setStatus("New session ready.", false);
    }

    /** Session provider switching: a full providers page — pick a provider,
     * land on its configuration page, and Continue binds it to THIS session
     * only (Hermes: the session row carries its own route). */
    private void pickSessionProvider() {
        mPickingSessionProvider = true;
        showProviderDirectory();
    }

    /** Setup page in session-picking mode: UI-level selection only — nothing
     * is written to the global provider config until Continue. */
    private void openSessionProviderSetup(AiProviderProfile profile) {
        if ("opencode".equals(profile.id)) {
            showOpenCodeSetupPage(true);
            return;
        }
        mSelectedProfile = profile;
        mSelectedModel = mProviderConfig.getModel(profile);
        if ("opencode".equals(profile.id) && (TextUtils.isEmpty(mSelectedModel) || "gpt-4o".equals(mSelectedModel))) mSelectedModel = "big-pickle";
        syncControlLabels();
        mSelectedProviderIcon.setImageResource(iconForProvider(profile.id));
        mSelectedProviderTitle.setText(profile.name);
        mSelectedProviderBody.setText(profile.description);
        mSetupTitle.setText("Setup " + profile.name);
        boolean ready = !profile.apiKeyAuth || mProviderConfig.hasApiKey(profile);
        mLoginButton.setText("opencode".equals(profile.id) ? "Configure OpenCode" : (ready ? "Update API key" : "Add API key"));
        mProviderStatus.setText(ready
            ? "Provider configured. Continue binds it to this session."
            : "Configuration needed: add an API key for this provider.");
        mProviderStatus.setTextColor(color(ready ? R.color.ai_success : R.color.ai_warning));

        // Saved credentials? Offer a one-tap bind without re-entering config.
        if (ready) {
            if (mUseSavedConfigButton == null) {
                mUseSavedConfigButton = new com.google.android.material.button.MaterialButton(this);
                mUseSavedConfigButton.setText("Use saved configuration");
                mUseSavedConfigButton.setIconResource(R.drawable.ic_ai_check);
                mUseSavedConfigButton.setIconTint(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
                mUseSavedConfigButton.setTextColor(0xFFFFFFFF);
                mUseSavedConfigButton.setAllCaps(false);
                mUseSavedConfigButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color(R.color.ai_accent)));
                mUseSavedConfigButton.setCornerRadius(dp(14));
                mUseSavedConfigButton.setOnClickListener(view -> applySessionProviderChoice());
            }
            ViewGroup summaryCard = (ViewGroup) mSelectedProviderIcon.getParent();
            ViewGroup parent = summaryCard == null ? null : (ViewGroup) summaryCard.getParent();
            if (parent != null && mUseSavedConfigButton.getParent() == null) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.setMargins(0, dp(10), 0, 0);
                mUseSavedConfigButton.setLayoutParams(lp);
                parent.addView(mUseSavedConfigButton, parent.indexOfChild(summaryCard) + 1);
            }
            if (mUseSavedConfigButton.getParent() != null) mUseSavedConfigButton.setVisibility(View.VISIBLE);
        } else if (mUseSavedConfigButton != null) {
            mUseSavedConfigButton.setVisibility(View.GONE);
        }
        showSetupPage();
    }

    /** Continue pressed on the session-provider setup page. */
    private void applySessionProviderChoice() {
        AiProviderProfile profile = mSelectedProfile;
        mPickingSessionProvider = false;
        if (profile == null) { showChatPage(); return; }
        if (mRuntimeService != null && mHasNativeSession) {
            mRuntimeService.setSessionProvider(profile.id);
            AiDatabase.RunRecord viewed = mRuntimeService.getActiveRun();
            if (viewed != null && !TextUtils.isEmpty(viewed.lastResolvedModel)) mSelectedModel = viewed.lastResolvedModel;
            syncControlLabels();
        }
        setStatus("Session provider: " + profile.name, false);
        showChatPage();
    }

    /** Proper OpenCode setup: Free, Zen and Go each get their own clearly
     * labeled configuration card — nothing shares or overwrites anything. */
    private void showOpenCodeSetupPage(boolean sessionMode) {
        mPickingSessionProvider = sessionMode;
        mSelectedProfile = AiProviderProfile.find("opencode");
        mHomePanel.setVisibility(View.GONE);
        mSetupPanel.setVisibility(View.GONE);
        mChatPage.setVisibility(View.GONE);
        if (mSessionsPage != null) mSessionsPage.setVisibility(View.GONE);
        if (mExtensionsPage != null) mExtensionsPage.setVisibility(View.GONE);
        mChatTitle.setText("OpenCode routes");

        ScrollView scroll = (ScrollView) findViewById(R.id.ai_opencode_scroll);
        LinearLayout list = findViewById(R.id.ai_opencode_list);
        if (scroll == null || list == null) return;
        list.removeAllViews();

        TextView title = new TextView(this);
        title.setText(sessionMode ? "OpenCode — choose a route for this session" : "OpenCode routes");
        title.setTextColor(color(R.color.ai_text));
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(0, dp(12), 0, dp(4));
        list.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText(sessionMode
            ? "Each route keeps its own key and model. Save & Use binds the route to this session only."
            : "Each route keeps its own key and model. Save & Use makes the route active for new sessions.");
        subtitle.setTextColor(color(R.color.ai_text_muted));
        subtitle.setTextSize(13);
        subtitle.setPadding(0, 0, 0, dp(12));
        list.addView(subtitle);

        list.addView(opencodeRouteCard(AiProviderConfig.OC_ROUTE_FREE, "Free", "Keyless — no API key needed", sessionMode));
        list.addView(opencodeRouteCard(AiProviderConfig.OC_ROUTE_ZEN, "Zen", "Requires a Zen API key", sessionMode));
        list.addView(opencodeRouteCard(AiProviderConfig.OC_ROUTE_GO, "Go", "Requires a Go API key", sessionMode));

        mChatPage.setVisibility(View.GONE);
        mSessionsPage.setVisibility(View.GONE);
        mHomePanel.setVisibility(View.GONE);
        findViewById(R.id.ai_opencode_page).setVisibility(View.VISIBLE);
    }

    private View opencodeRouteCard(String route, String name, String description, boolean sessionMode) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.setBackgroundResource(R.drawable.bg_provider_card);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(0, 0, 0, dp(12));
        card.setLayoutParams(cardLp);

        TextView nameView = new TextView(this);
        nameView.setText("OpenCode " + name);
        nameView.setTextColor(color(R.color.ai_text));
        nameView.setTextSize(15);
        nameView.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(nameView);

        boolean active = AiProviderConfig.OC_ROUTE_FREE.equals(route)
            ? "free".equals(mProviderConfig.getOpenCodeSelectedRoute())
            : mProviderConfig.getOpenCodeSelectedRoute().equals(route);
        TextView status = new TextView(this);
        boolean hasKey = !AiProviderConfig.ocRouteNeedsKey(route) || !TextUtils.isEmpty(mProviderConfig.getOpenCodeRouteKey(route));
        status.setText((active ? "Active route · " : "") + description
            + (AiProviderConfig.ocRouteNeedsKey(route)
                ? (hasKey ? " · key saved" : " · no key yet")
                : ""));
        status.setTextColor(color(active ? R.color.ai_success : R.color.ai_text_muted));
        status.setTextSize(11);
        status.setPadding(0, dp(2), 0, dp(8));
        card.addView(status);

        TextView modelLabel = new TextView(this);
        modelLabel.setText("Model");
        modelLabel.setTextColor(color(R.color.ai_text_muted));
        modelLabel.setTextSize(11);
        card.addView(modelLabel);

        final EditText modelInput = new EditText(this);
        modelInput.setSingleLine(true);
        modelInput.setText(mProviderConfig.getOpenCodeRouteModel(route));
        modelInput.setTextColor(color(R.color.ai_text));
        card.addView(modelInput);

        final boolean needsKey = AiProviderConfig.ocRouteNeedsKey(route);
        TextView keyLabel = new TextView(this);
        keyLabel.setText("API key");
        keyLabel.setTextColor(color(R.color.ai_text_muted));
        keyLabel.setTextSize(11);
        keyLabel.setPadding(0, dp(8), 0, 0);
        keyLabel.setVisibility(needsKey ? View.VISIBLE : View.GONE);
        card.addView(keyLabel);

        final EditText keyInput = new EditText(this);
        keyInput.setSingleLine(true);
        keyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        String savedKey = mProviderConfig.getOpenCodeRouteKey(route);
        keyInput.setText(savedKey == null ? "" : savedKey);
        keyInput.setTextColor(color(R.color.ai_text));
        keyInput.setVisibility(needsKey ? View.VISIBLE : View.GONE);
        card.addView(keyInput);

        MaterialButton useButton = new MaterialButton(this);
        useButton.setText(sessionMode ? "Save & Use for this session" : "Save & Use");
        useBtnStyle(useButton);
        useButton.setOnClickListener(v -> {
            mProviderConfig.setOpenCodeRouteModel(route, modelInput.getText().toString());
            if (needsKey) mProviderConfig.setOpenCodeRouteKey(route, keyInput.getText().toString());
            mProviderConfig.setOpenCodeSelectedRoute(route);
            mSelectedProfile = AiProviderProfile.find("opencode");
            mSelectedModel = mProviderConfig.getOpenCodeRouteModel(route);
            if (sessionMode && mRuntimeService != null && mHasNativeSession) {
                mRuntimeService.setSessionProvider("opencode");
                mRuntimeService.setSessionRoute(route);
                mPickingSessionProvider = false;
                mSelectedModel = mRuntimeService.getActiveRun() == null
                    ? mSelectedModel : mRuntimeService.getActiveRun().lastResolvedModel;
            }
            selectProvider(mSelectedProfile, false);
            setStatus("OpenCode " + AiProviderConfig.ocRouteLabel(route) + " saved.", false);
            if (sessionMode) {
                mPickingSessionProvider = false;
                showChatPage();
            } else {
                showFeaturedProviders();
            }
        });
        card.addView(useButton);
        return card;
    }

    private void useBtnStyle(MaterialButton button) {
        button.setTextColor(0xFFFFFFFF);
        button.setAllCaps(false);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color(R.color.ai_accent)));
        button.setCornerRadius(dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(10), 0, 0);
        button.setLayoutParams(lp);
    }

    /** Skills & extensions page (Hermes skills): list installed skills, toggle
     * them, and read the full SKILL.md. The skills root is
     * $HOME/.termuxAI/skills — drop a folder with a SKILL.md in there (or sync
     * one from a desktop Hermes install) and it appears here. */
    private void showExtensionsPage() {
        if (mExtensionsPage == null) return;
        mHomePanel.setVisibility(View.GONE);
        mSetupPanel.setVisibility(View.GONE);
        mChatPage.setVisibility(View.GONE);
        if (mSessionsPage != null) mSessionsPage.setVisibility(View.GONE);
        if (mOpenCodePage != null) mOpenCodePage.setVisibility(View.GONE);
        mChatTitle.setText("Skills & extensions");
        refreshExtensionsPage();
        mExtensionsPage.setVisibility(View.VISIBLE);
    }

    private void refreshExtensionsPage() {
        if (mExtensionsList == null) return;
        mExtensionsList.removeAllViews();

        TextView title = new TextView(this);
        title.setText("Skills");
        title.setTextColor(color(R.color.ai_text));
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(0, dp(12), 0, dp(4));
        mExtensionsList.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Procedural memory for the agent. The model sees each skill's name and description, and loads the full instructions with skill_view when relevant. Scripts run through the terminal tool.");
        subtitle.setTextColor(color(R.color.ai_text_muted));
        subtitle.setTextSize(12);
        subtitle.setPadding(0, 0, 0, dp(10));
        mExtensionsList.addView(subtitle);

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams toolbarLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        toolbarLp.setMargins(0, 0, 0, dp(12));
        toolbar.setLayoutParams(toolbarLp);

        TextView pathHint = new TextView(this);
        pathHint.setText("$HOME/.termuxAI/skills");
        pathHint.setTextColor(color(R.color.ai_text_muted));
        pathHint.setTextSize(11);
        pathHint.setTypeface(Typeface.MONOSPACE);
        LinearLayout.LayoutParams pathLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        pathHint.setLayoutParams(pathLp);
        toolbar.addView(pathHint);

        MaterialButton refresh = new MaterialButton(this);
        refresh.setText("Refresh");
        refresh.setTextSize(12);
        refresh.setAllCaps(false);
        refresh.setStrokeColor(android.content.res.ColorStateList.valueOf(color(R.color.ai_border)));
        refresh.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color(R.color.ai_surface)));
        refresh.setTextColor(color(R.color.ai_text));
        refresh.setCornerRadius(dp(10));
        refresh.setOnClickListener(v -> {
            AiSkillRegistry.invalidate();
            refreshExtensionsPage();
            setStatus("Skills refreshed.", false);
        });
        toolbar.addView(refresh);
        mExtensionsList.addView(toolbar);

        Set<String> disabled = AiSkillRegistry.readDisabled();
        List<AiSkillRegistry.Skill> skills = AiSkillRegistry.listSkills();
        boolean anyVisible = false;
        for (AiSkillRegistry.Skill skill : skills) {
            if (!skill.platformSupported) continue;
            anyVisible = true;
            mExtensionsList.addView(createSkillCard(skill, disabled.contains(skill.name)));
        }
        for (AiSkillRegistry.Skill skill : skills) {
            if (skill.platformSupported) continue;
            anyVisible = true;
            mExtensionsList.addView(createSkillCard(skill, true));
        }
        if (!anyVisible) {
            TextView empty = new TextView(this);
            empty.setText("No skills installed. Skills live in $HOME/.termuxAI/skills — one folder per skill with a SKILL.md inside.");
            empty.setTextColor(color(R.color.ai_text_muted));
            empty.setTextSize(13);
            empty.setPadding(0, dp(6), 0, dp(16));
            mExtensionsList.addView(empty);
        }
        buildMcpSection();
    }

    /** MCP servers section (Streamable HTTP, phase 2): list, add, edit,
     * test, enable, remove. Tool calls on untrusted servers gate through the
     * approval dialog at dispatch time. */
    private void buildMcpSection() {
        TextView title = new TextView(this);
        title.setText("MCP servers");
        title.setTextColor(color(R.color.ai_text));
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(0, dp(24), 0, dp(4));
        mExtensionsList.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Remote tools via Model Context Protocol (Streamable HTTP). The agent calls them like built-in tools under mcp__server__tool names. Untrusted servers ask before every call.");
        subtitle.setTextColor(color(R.color.ai_text_muted));
        subtitle.setTextSize(12);
        subtitle.setPadding(0, 0, 0, dp(10));
        mExtensionsList.addView(subtitle);

        if (mRuntimeService == null || !mRuntimeBound) {
            TextView waiting = new TextView(this);
            waiting.setText("Runtime still starting — MCP management unlocks when it is bound.");
            waiting.setTextColor(color(R.color.ai_text_muted));
            waiting.setTextSize(12);
            waiting.setPadding(0, dp(4), 0, dp(12));
            mExtensionsList.addView(waiting);
            return;
        }

        MaterialButton add = new MaterialButton(this);
        add.setText("Add server");
        add.setTextSize(12);
        add.setAllCaps(false);
        add.setTextColor(0xFFFFFFFF);
        add.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color(R.color.ai_accent)));
        add.setCornerRadius(dp(10));
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        addLp.setMargins(0, 0, 0, dp(12));
        add.setLayoutParams(addLp);
        add.setOnClickListener(v -> showMcpServerDialog(null));
        mExtensionsList.addView(add);

        java.util.List<AiDatabase.McpServerRecord> servers = mRuntimeService.getMcpServers();
        if (servers.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No MCP servers configured. Examples: https://mcp.deepwiki.com/mcp (keyless), https://mcp.context7.com/mcp (keyless).");
            empty.setTextColor(color(R.color.ai_text_muted));
            empty.setTextSize(12);
            empty.setPadding(0, dp(2), 0, dp(12));
            mExtensionsList.addView(empty);
        }
        for (AiDatabase.McpServerRecord server : servers) {
            mExtensionsList.addView(createMcpServerCard(server));
        }
    }

    private View createMcpServerCard(AiDatabase.McpServerRecord server) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.setBackgroundResource(R.drawable.bg_provider_card);
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnLongClickListener(v -> { confirmMcpServerRemove(server); return true; });
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(0, 0, 0, dp(12));
        card.setLayoutParams(cardLp);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(header);

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        header.addView(text, textLp);

        TextView nameView = new TextView(this);
        nameView.setText(server.name);
        nameView.setTextColor(color(R.color.ai_text));
        nameView.setTextSize(15);
        nameView.setTypeface(Typeface.DEFAULT_BOLD);
        text.addView(nameView);

        TextView urlView = new TextView(this);
        urlView.setText(server.url == null ? "" : oneLine(server.url, 48));
        urlView.setTextColor(color(R.color.ai_text_muted));
        urlView.setTextSize(11);
        urlView.setTypeface(Typeface.MONOSPACE);
        urlView.setPadding(0, dp(2), 0, 0);
        text.addView(urlView);

        boolean connected = "connected".equals(server.lastStatus);
        boolean failed = server.lastStatus != null && server.lastStatus.startsWith("failed");
        TextView status = new TextView(this);
        int toolCount = 0;
        try {
            JSONArray cached = server.lastToolsJson == null ? null : new JSONArray(server.lastToolsJson);
            toolCount = cached == null ? 0 : cached.length();
        } catch (Exception ignored) {}
        String statusText = !server.enabled ? "disabled"
            : connected ? "connected · " + toolCount + " tools" + ("trusted".equals(server.trust) ? " · trusted" : "")
            : failed ? server.lastStatus
            : server.lastStatus == null ? "not tested yet" : server.lastStatus;
        status.setText(statusText);
        status.setTextColor(color(!server.enabled || failed ? R.color.ai_warning
            : connected ? R.color.ai_success : R.color.ai_text_muted));
        status.setTextSize(11);
        status.setPadding(0, dp(4), 0, 0);
        text.addView(status);

        android.widget.Switch toggle = new android.widget.Switch(this);
        toggle.setChecked(server.enabled);
        toggle.setThumbTintList(android.content.res.ColorStateList.valueOf(color(R.color.ai_accent)));
        toggle.setOnClickListener(v -> {
            boolean enable = !server.enabled;
            AiDatabase.McpServerRecord fresh = mRuntimeService.getMcpServer(server.name);
            if (fresh != null) {
                fresh.enabled = enable;
                fresh.lastStatus = enable ? fresh.lastStatus : "disabled";
                mRuntimeService.saveMcpServer(fresh);
                mRuntimeService.refreshMcpServer(server.name);
                setStatus(enable ? "MCP '" + server.name + "' enabled." : "MCP '" + server.name + "' disabled.", false);
            }
            refreshExtensionsPage();
        });
        header.addView(toggle);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams buttonsLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        buttonsLp.setMargins(0, dp(8), 0, 0);
        buttons.setLayoutParams(buttonsLp);

        MaterialButton test = new MaterialButton(this);
        test.setText("Test");
        test.setTextSize(12);
        test.setAllCaps(false);
        test.setStrokeColor(android.content.res.ColorStateList.valueOf(color(R.color.ai_border)));
        test.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color(R.color.ai_surface)));
        test.setTextColor(color(R.color.ai_text));
        test.setCornerRadius(dp(10));
        test.setOnClickListener(v -> {
            setStatus("Testing MCP '" + server.name + "'…", false);
            mRuntimeService.testMcpServer(server.name);
        });
        buttons.addView(test);

        MaterialButton edit = new MaterialButton(this);
        edit.setText("Edit");
        edit.setTextSize(12);
        edit.setAllCaps(false);
        edit.setStrokeColor(android.content.res.ColorStateList.valueOf(color(R.color.ai_border)));
        edit.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color(R.color.ai_surface)));
        edit.setTextColor(color(R.color.ai_text));
        edit.setCornerRadius(dp(10));
        LinearLayout.LayoutParams editLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        editLp.setMargins(dp(8), 0, 0, 0);
        edit.setLayoutParams(editLp);
        edit.setOnClickListener(v -> showMcpServerDialog(server.name));
        buttons.addView(edit);

        card.addView(buttons);
        card.setOnClickListener(v -> showMcpServerDialog(server.name));
        return card;
    }

    private void confirmMcpServerRemove(AiDatabase.McpServerRecord server) {
        new MaterialAlertDialogBuilder(this)
            .setTitle("Remove MCP server")
            .setMessage("Remove '" + server.name + "' and its saved token? Its tools stop being offered to the agent.")
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Remove", (dialog, which) -> {
                mRuntimeService.deleteMcpServer(server.name);
                mRuntimeService.onMcpServerDeleted(server.name);
                refreshExtensionsPage();
            })
            .show();
    }

    /** Add/edit dialog for an MCP server (Streamable HTTP). */
    private void showMcpServerDialog(@Nullable String existingName) {
        AiDatabase.McpServerRecord existing = existingName == null ? null : mRuntimeService.getMcpServer(existingName);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);

        EditText nameInput = new EditText(this);
        nameInput.setSingleLine(true);
        nameInput.setHint("Name (e.g. deepwiki)");
        nameInput.setText(existing == null ? "" : existing.name);
        nameInput.setEnabled(existing == null);
        nameInput.setTextColor(color(R.color.ai_text));

        EditText urlInput = new EditText(this);
        urlInput.setSingleLine(true);
        urlInput.setHint("https://server.example/mcp");
        urlInput.setText(existing == null ? "" : existing.url == null ? "" : existing.url);
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlInput.setTextColor(color(R.color.ai_text));

        android.widget.CheckBox useToken = new android.widget.CheckBox(this);
        useToken.setText("Authorization: Bearer token");
        useToken.setTextColor(color(R.color.ai_text));
        useToken.setChecked(existing != null && "header".equals(existing.authType));

        EditText tokenInput = new EditText(this);
        tokenInput.setSingleLine(true);
        tokenInput.setHint("Bearer token (stored encrypted)");
        tokenInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        tokenInput.setText(existing == null || !"header".equals(existing.authType)
            ? "" : mProviderConfig.getMcpServerToken(existing.name));
        tokenInput.setTextColor(color(R.color.ai_text));
        tokenInput.setVisibility(useToken.isChecked() ? View.VISIBLE : View.GONE);
        useToken.setOnCheckedChangeListener((b, checked) -> tokenInput.setVisibility(checked ? View.VISIBLE : View.GONE));

        android.widget.CheckBox trusted = new android.widget.CheckBox(this);
        trusted.setText("Trusted — skip approval for this server's tools");
        trusted.setTextColor(color(R.color.ai_text));
        trusted.setChecked(existing != null && "trusted".equals(existing.trust));

        TextView warning = new TextView(this);
        warning.setText("Only add servers you trust. Untrusted servers ask for approval on every tool call.");
        warning.setTextColor(color(R.color.ai_text_muted));
        warning.setTextSize(11);
        warning.setPadding(0, dp(6), 0, 0);

        form.addView(nameInput, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        form.addView(urlInput, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        form.addView(useToken);
        form.addView(tokenInput);
        form.addView(trusted);
        form.addView(warning);

        new MaterialAlertDialogBuilder(this)
            .setTitle(existing == null ? "Add MCP server" : "Edit " + existing.name)
            .setView(form)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Save", (dialog, which) -> {
                String name = nameInput.getText().toString().trim().replaceAll("[^A-Za-z0-9_-]", "_").toLowerCase();
                String url = urlInput.getText().toString().trim();
                if (name.isEmpty() || !url.startsWith("http")) {
                    showError("MCP server needs a name and an http(s) URL.");
                    return;
                }
                AiDatabase.McpServerRecord record = existing == null ? new AiDatabase.McpServerRecord() : existing;
                record.name = name;
                record.url = url;
                boolean withToken = useToken.isChecked();
                record.authType = withToken ? "header" : "none";
                record.trust = trusted.isChecked() ? "trusted" : "untrusted";
                if (record.lastStatus != null && record.lastStatus.startsWith("failed")) record.lastStatus = null;
                mRuntimeService.saveMcpServer(record);
                mProviderConfig.setMcpServerToken(name, withToken ? tokenInput.getText().toString().trim() : null);
                mRuntimeService.refreshMcpServer(name);
                setStatus("MCP '" + name + "' saved. Testing…", false);
                mUiHandler.postDelayed(() -> {
                    refreshExtensionsPage();
                    mRuntimeService.testMcpServer(name);
                }, 200);
            })
            .show();
    }

    private View createSkillCard(AiSkillRegistry.Skill skill, boolean disabled) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.setBackgroundResource(R.drawable.bg_provider_card);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(0, 0, 0, dp(12));
        card.setLayoutParams(cardLp);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(header);

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        header.addView(text, textLp);

        TextView nameView = new TextView(this);
        nameView.setText(skill.name);
        nameView.setTextColor(color(R.color.ai_text));
        nameView.setTextSize(15);
        nameView.setTypeface(Typeface.DEFAULT_BOLD);
        text.addView(nameView);

        TextView meta = new TextView(this);
        StringBuilder metaText = new StringBuilder(skill.category);
        if (!TextUtils.isEmpty(skill.version)) metaText.append(" · v").append(skill.version);
        if (!skill.platformSupported) metaText.append(" · not available on Android");
        else if (disabled) metaText.append(" · disabled");
        meta.setText(metaText.toString());
        meta.setTextColor(color(!skill.platformSupported || disabled ? R.color.ai_warning : R.color.ai_text_muted));
        meta.setTextSize(11);
        meta.setPadding(0, dp(2), 0, 0);
        text.addView(meta);

        android.widget.Switch toggle = new android.widget.Switch(this);
        toggle.setChecked(!disabled && skill.platformSupported);
        toggle.setEnabled(skill.platformSupported);
        toggle.setThumbTintList(android.content.res.ColorStateList.valueOf(color(R.color.ai_accent)));
        toggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
            AiSkillRegistry.setEnabled(skill.name, isChecked);
            setStatus(isChecked ? "Skill '" + skill.name + "' enabled." : "Skill '" + skill.name + "' disabled.", false);
            refreshExtensionsPage();
        });
        header.addView(toggle);

        if (!TextUtils.isEmpty(skill.description)) {
            TextView desc = new TextView(this);
            desc.setText(skill.description);
            desc.setTextColor(color(R.color.ai_text_muted));
            desc.setTextSize(12);
            desc.setPadding(0, dp(6), 0, 0);
            card.addView(desc);
        }

        card.setOnClickListener(v -> showSkillViewer(skill));
        toggle.setOnClickListener(v -> {
            // Let the Switch handle it; prevent the card's viewer from opening.
        });
        return card;
    }

    /** Renders SKILL.md with Markwon (frontmatter stripped — it is model data, not reader content). */
    private void showSkillViewer(AiSkillRegistry.Skill skill) {
        String content = AiSkillRegistry.viewTool(skill.name, null, null);
        String markdown = skill.name;
        try {
            JSONObject result = new JSONObject(content);
            if (result.optBoolean("success", false)) markdown = result.optString("content", "");
            else markdown = result.optString("error", "Could not load skill.");
        } catch (Exception ignored) {}
        markdown = stripFrontmatterForDisplay(markdown);
        TextView body = new TextView(this);
        body.setTextColor(color(R.color.ai_text));
        body.setTextSize(13);
        body.setPadding(dp(20), dp(16), dp(20), dp(16));
        body.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        try {
            io.noties.markwon.Markwon.create(this).setMarkdown(body, markdown);
        } catch (Exception e) {
            body.setText(markdown);
        }
        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        new MaterialAlertDialogBuilder(this)
            .setTitle(skill.name)
            .setView(scroll)
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

    private String stripFrontmatterForDisplay(String markdown) {
        if (markdown == null) return "";
        String trimmed = markdown.trim();
        if (!trimmed.startsWith("---")) return markdown;
        java.util.regex.Matcher matcher = java.util.regex.Pattern
            .compile("(?m)^---\\s*$").matcher(trimmed.substring(3));
        if (!matcher.find()) return markdown;
        return trimmed.substring(3).substring(matcher.end()).trim();
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
        mProviderGrid.addView(createSkillsTile());
        mHomePanel.setVisibility(View.VISIBLE);
        mSetupPanel.setVisibility(View.GONE);
        mChatPage.setVisibility(View.GONE);
        if (mSessionsPage != null) mSessionsPage.setVisibility(View.GONE);
        if (mOpenCodePage != null) mOpenCodePage.setVisibility(View.GONE);
        if (mExtensionsPage != null) mExtensionsPage.setVisibility(View.GONE);
        mChatTitle.setText("Mobile Hermes");
    }

    private void showProviderDirectory() {
        mShowingProviderDirectory = true;
        mProviderGrid.removeAllViews();
        mHomeTitle.setText(mPickingSessionProvider ? "Choose a provider for this session" : "More providers");
        mHomeBody.setText(mPickingSessionProvider
            ? "Pick a provider, configure it if needed, then Continue to bind it to this session only."
            : "Hermes provider registry. Adapters marked “coming next” are listed honestly until their native request/auth flow is implemented.");
        for (AiProviderProfile profile : AiProviderProfile.PROFILES) {
            boolean featured = "openai".equals(profile.id) || "anthropic".equals(profile.id) || "opencode".equals(profile.id);
            if (!featured || mPickingSessionProvider) mProviderGrid.addView(createProviderTile(profile));
        }
        mHomePanel.setVisibility(View.VISIBLE);
        mSetupPanel.setVisibility(View.GONE);
        mChatPage.setVisibility(View.GONE);
        if (mSessionsPage != null) mSessionsPage.setVisibility(View.GONE);
        if (mExtensionsPage != null) mExtensionsPage.setVisibility(View.GONE);
    }

    private MaterialCardView createProviderTile(AiProviderProfile profile) {
        MaterialCardView card = new MaterialCardView(this);
        card.setCardBackgroundColor(color(R.color.ai_surface));
        card.setStrokeColor(color(R.color.ai_border));
        card.setStrokeWidth(dp(1));
        card.setRadius(dp(16));
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(view -> {
            if (mPickingSessionProvider) openSessionProviderSetup(profile);
            else selectProvider(profile, true);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(10));
        card.setLayoutParams(lp);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.HORIZONTAL);
        content.setGravity(Gravity.CENTER_VERTICAL);
        content.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.addView(content);

        android.widget.ImageView icon = new android.widget.ImageView(this);
        icon.setImageResource(iconForProvider(profile.id));
        icon.setPadding(dp(6), dp(6), dp(6), dp(6));
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(48), dp(48));
        content.addView(icon, ip);

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        tp.setMargins(dp(12), 0, 0, 0);
        content.addView(text, tp);

        TextView title = new TextView(this);
        title.setText(profile.name);
        title.setTextColor(color(R.color.ai_text));
        title.setTextSize(14);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        text.addView(title);

        TextView body = new TextView(this);
        body.setText(shortProviderDesc(profile));
        body.setTextColor(color(R.color.ai_text_muted));
        body.setTextSize(11);
        body.setMaxLines(2);
        text.addView(body);

        if (!profile.implemented) {
            TextView state = new TextView(this);
            state.setText("Coming next");
            state.setTextColor(color(R.color.ai_warning));
            state.setTextSize(10);
            text.addView(state);
        }
        return card;
    }

    private int iconForProvider(String id) {
        if ("openai".equals(id) || "openai-codex".equals(id)) return R.drawable.ic_provider_openai;
        if ("anthropic".equals(id)) return R.drawable.ic_provider_anthropic;
        if ("opencode".equals(id)) return R.drawable.ic_provider_opencode;
        if ("openrouter".equals(id)) return R.drawable.ic_provider_openrouter;
        if ("github-copilot".equals(id)) return R.drawable.ic_provider_copilot;
        if ("gemini".equals(id) || "vertex".equals(id)) return R.drawable.ic_provider_gemini;
        if ("deepseek".equals(id)) return R.drawable.ic_provider_deepseek;
        if ("kimi-coding".equals(id) || "kimi".equals(id)) return R.drawable.ic_provider_kimi;
        if ("azure-foundry".equals(id)) return R.drawable.ic_provider_azure;
        if ("bedrock".equals(id)) return R.drawable.ic_provider_aws;
        if ("nvidia".equals(id)) return R.drawable.ic_provider_nvidia;
        if ("alibaba".equals(id) || "qwen-oauth".equals(id)) return R.drawable.ic_provider_qwen;
        if ("ollama-cloud".equals(id)) return R.drawable.ic_provider_ollama;
        if ("huggingface".equals(id)) return R.drawable.ic_provider_huggingface;
        if ("minimax".equals(id)) return R.drawable.ic_provider_minimax;
        if ("lmstudio".equals(id)) return R.drawable.ic_provider_lmstudio;
        return R.drawable.ic_provider_more;
    }

    private String shortProviderDesc(AiProviderProfile p) {
        if ("openai".equals(p.id)) return "GPT 4o, 4.1 & more";
        if ("anthropic".equals(p.id)) return "Claude 3.5, Opus, Sonnet";
        if ("opencode".equals(p.id)) return "OpenCode LLMs & tools";
        if ("gemini".equals(p.id)) return "Gemini 1.5 Pro & Flash";
        if ("deepseek".equals(p.id)) return "DeepSeek Coder V2, V3";
        if ("kimi-coding".equals(p.id)) return "Kimi K2, K2 Instruct";
        return p.description.length() > 32 ? p.description.substring(0, 32) : p.description;
    }

    private MaterialCardView createMoreProvidersTile() {
        MaterialCardView card = new MaterialCardView(this);
        card.setCardBackgroundColor(color(R.color.ai_surface));
        card.setStrokeColor(color(R.color.ai_accent));
        card.setStrokeWidth(dp(1));
        card.setRadius(dp(16));
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(view -> showProviderDirectory());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(10));
        card.setLayoutParams(lp);

        LinearLayout content = new LinearLayout(this);
        content.setGravity(Gravity.CENTER_VERTICAL);
        content.setPadding(dp(14), dp(14), dp(14), dp(14));
        content.setOrientation(LinearLayout.HORIZONTAL);
        card.addView(content);
        android.widget.ImageView icon = new android.widget.ImageView(this);
        icon.setImageResource(R.drawable.ic_provider_more);
        icon.setBackgroundResource(R.drawable.bg_ai_harness_badge);
        icon.setPadding(dp(10), dp(10), dp(10), dp(10));
        content.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        textParams.setMargins(dp(14), 0, 0, 0);
        content.addView(text, textParams);

        TextView title = new TextView(this);
        title.setText("More providers");
        title.setTextColor(color(R.color.ai_text));
        title.setTextSize(13);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        text.addView(title);

        TextView body = new TextView(this);
        body.setText("Explore 30+ providers and custom endpoints");
        body.setTextColor(color(R.color.ai_text_muted));
        body.setTextSize(11);
        text.addView(body);
        return card;
    }

    /** Home tile into the Skills & extensions page. */
    private MaterialCardView createSkillsTile() {
        MaterialCardView card = new MaterialCardView(this);
        card.setCardBackgroundColor(color(R.color.ai_surface));
        card.setStrokeColor(color(R.color.ai_border));
        card.setStrokeWidth(dp(1));
        card.setRadius(dp(16));
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(view -> showExtensionsPage());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(10));
        card.setLayoutParams(lp);

        LinearLayout content = new LinearLayout(this);
        content.setGravity(Gravity.CENTER_VERTICAL);
        content.setPadding(dp(14), dp(14), dp(14), dp(14));
        content.setOrientation(LinearLayout.HORIZONTAL);
        card.addView(content);
        android.widget.ImageView icon = new android.widget.ImageView(this);
        icon.setImageResource(R.drawable.ic_ai_package);
        icon.setBackgroundResource(R.drawable.bg_ai_harness_badge);
        icon.setPadding(dp(10), dp(10), dp(10), dp(10));
        icon.setColorFilter(color(R.color.ai_text));
        content.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        textParams.setMargins(dp(14), 0, 0, 0);
        content.addView(text, textParams);

        TextView title = new TextView(this);
        title.setText("Skills & extensions");
        title.setTextColor(color(R.color.ai_text));
        title.setTextSize(13);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        text.addView(title);

        TextView body = new TextView(this);
        body.setText("Agent skills (SKILL.md) — enable, disable, review");
        body.setTextColor(color(R.color.ai_text_muted));
        body.setTextSize(11);
        text.addView(body);
        return card;
    }

    private View createProviderRow(AiProviderProfile profile) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(10), dp(10), dp(10));
        row.setClickable(true);
        row.setFocusable(true);
        row.setBackgroundResource(R.drawable.bg_provider_card);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.setMargins(0, 0, 0, dp(6));
        row.setLayoutParams(rowLp);

        android.widget.ImageView icon = new android.widget.ImageView(this);
        icon.setImageResource(iconForProvider(profile.id));
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(26), dp(26));
        iconLp.setMargins(0, 0, dp(10), 0);
        icon.setLayoutParams(iconLp);
        row.addView(icon);

        TextView label = new TextView(this);
        label.setText(profile.name);
        label.setTextColor(color(R.color.ai_text));
        label.setTextSize(14);
        row.addView(label);

        row.setOnClickListener(view -> {
            selectProvider(profile, true);
            if (mDrawer != null) mDrawer.closeDrawer(findViewById(R.id.ai_drawer_panel));
        });
        return row;
    }

    private void selectProvider(@Nullable AiProviderProfile profile, boolean userInitiated) {
        mSelectedProfile = profile == null ? AiProviderProfile.firstAgentProfile() : profile;
        mProviderConfig.setSelectedProviderId(mSelectedProfile.id);
        mSelectedModel = mProviderConfig.getModel(mSelectedProfile);
        if ("opencode".equals(mSelectedProfile.id) && (mSelectedModel == null || mSelectedModel.equals("gpt-4o") || mSelectedModel.isEmpty())) mSelectedModel = "big-pickle";
        syncControlLabels();
        mSelectedProviderIcon.setImageResource(iconForProvider(mSelectedProfile.id));
        mSelectedProviderTitle.setText(mSelectedProfile.name);
        mSelectedProviderBody.setText(mSelectedProfile.description);
        if (mEmptyChatHint instanceof TextView) ((TextView) mEmptyChatHint).setText(mSelectedProfile.mark);
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
        if ("opencode".equals(mSelectedProfile.id)) {
            if (userInitiated) showOpenCodeSetupPage(false);
            return;
        }
        if (userInitiated || mSetupPanel.getVisibility() == View.VISIBLE) showSetupPage();
    }

    private void showSetupPage() {
        mHomePanel.setVisibility(View.GONE);
        mSetupPanel.setVisibility(View.VISIBLE);
        mChatPage.setVisibility(View.GONE);
        if (mSessionsPage != null) mSessionsPage.setVisibility(View.GONE);
        if (mOpenCodePage != null) mOpenCodePage.setVisibility(View.GONE);
        if (mExtensionsPage != null) mExtensionsPage.setVisibility(View.GONE);
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
        if (mSessionsPage != null) mSessionsPage.setVisibility(View.GONE);
        if (mOpenCodePage != null) mOpenCodePage.setVisibility(View.GONE);
        if (mExtensionsPage != null) mExtensionsPage.setVisibility(View.GONE);
        syncControlLabels();
    }

    private void startNewSession() {
        if (mRuntimeService != null) mRuntimeService.newSession();
        mCurrentRunId = null;
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
        mUserScrolledUp = false;
        mPromptInput.setText("");
        mEmptyChatHint.setVisibility(View.GONE);
        mSuggestionStrip.setVisibility(View.GONE);
        mStreamingAgentBubble = null;
        mStopButton.setVisibility(View.VISIBLE);
        if (mHasNativeSession) {
            // Session-authoritative: provider/model/credentials resolve from
            // the session row inside the service (Hermes session model).
            mRuntimeService.sendPrompt(prompt, clean(mSelectedEffort), mSelectedApproval);
        } else {
            String apiKey = mProviderConfig.getApiKey(profile);
            String baseUrl = mProviderConfig.getBaseUrl(profile);
            String model = TextUtils.isEmpty(mSelectedModel) ? profile.defaultModel : mSelectedModel;
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
        final AiProviderProfile profile = mSelectedProfile;
        String resolvedBaseUrl = mProviderConfig.getBaseUrl(profile);
        String resolvedApiKey = mProviderConfig.getApiKey(profile);
        // Session-scoped: a session bound to an OpenCode route lists THAT
        // route's models, not the globally selected route's.
        if (mHasNativeSession && mRuntimeService != null && "opencode".equals(profile.id)) {
            AiDatabase.RunRecord viewed = mRuntimeService.getActiveRun();
            if (viewed != null && !TextUtils.isEmpty(viewed.route)) {
                resolvedBaseUrl = AiProviderConfig.ocRouteUrl(viewed.route);
                resolvedApiKey = mProviderConfig.getOpenCodeRouteKey(viewed.route);
            }
        }
        final String baseUrl = resolvedBaseUrl;
        final String apiKey = resolvedApiKey;
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
        String defaultModel = sessionDefaultModel(profile);
        items.add("Default (" + defaultModel + ")");
        items.add("Enter model ID manually…");
        items.addAll(models);
        new MaterialAlertDialogBuilder(this)
            .setTitle(profile.name + " models")
            .setItems(items.toArray(new String[0]), (dialog, which) -> {
                if (which == 0) setSelectedModel(profile, defaultModel);
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

    private String sessionDefaultModel(AiProviderProfile profile) {
        if (mHasNativeSession && mRuntimeService != null && "opencode".equals(profile.id)) {
            AiDatabase.RunRecord viewed = mRuntimeService.getActiveRun();
            if (viewed != null && !TextUtils.isEmpty(viewed.route))
                return AiProviderConfig.ocRouteDefaultModel(viewed.route);
        }
        return profile.defaultModel;
    }

    private void setSelectedModel(AiProviderProfile profile, String model) {
        String cleanModel = model == null ? "" : model.trim();
        if (cleanModel.isEmpty()) cleanModel = profile.defaultModel;
        mSelectedModel = cleanModel;
        mProviderConfig.setModel(profile, cleanModel);
        // Session-scoped /model switch (Hermes _persist_model_switch_to_session):
        // the model lives on the session row so resume restores this choice.
        if (mRuntimeService != null && mHasNativeSession) mRuntimeService.setSessionModel(cleanModel);
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
        if (mModelButton != null) mModelButton.setText(model);
        if (mProviderButton != null) mProviderButton.setText(mSelectedProfile.name);
        if (mSetupModelDisplay != null) mSetupModelDisplay.setText(model);
        if (mSetupTitle != null) mSetupTitle.setText("Setup " + mSelectedProfile.name);
        if (mReasoningButton != null) mReasoningButton.setText(TextUtils.isEmpty(mSelectedEffort) ? "Reasoning auto" : "Reasoning " + mSelectedEffort);
        if (mApprovalButton != null) mApprovalButton.setText(APPROVAL_NEVER.equals(mSelectedApproval) ? "No approvals" : "Ask approvals");
        if (mThinkingButton != null) mThinkingButton.setText(mShowThinkingDetails ? "Thinking details" : "Thinking compact");
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
        // Background sessions stream updates too — only the run the service
        // currently has in view may drive the chat UI state.
        if (run != null && mRuntimeBound && mRuntimeService != null) {
            AiDatabase.RunRecord viewed = mRuntimeService.getActiveRun();
            if (viewed == null || !viewed.id.equals(run.id)) {
                refreshRecentRuns();
                if (mSessionsPage != null && mSessionsPage.getVisibility() == View.VISIBLE) refreshSessionsPage();
                return;
            }
        }
        mHasNativeSession = run != null && run.state != AiRunStateMachine.State.FAILED;
        mRunActive = run != null && run.state != AiRunStateMachine.State.COMPLETED
            && run.state != AiRunStateMachine.State.FAILED
            && run.state != AiRunStateMachine.State.CANCELED;
        mStopButton.setVisibility(mRunActive ? View.VISIBLE : View.GONE);
        if (!mRunActive) {
            hideThinkingBubble();
            clearReasoningBuffer();
            mReasoningBubble = null;
            mCurrentToolBubble = null;
        }
        if (run != null) setStatus(run.state.name().toLowerCase(), run.state == AiRunStateMachine.State.FAILED);
        boolean runSwitched = run != null && !run.id.equals(mCurrentRunId);
        mCurrentRunId = run == null ? null : run.id;
        if (runSwitched && mChatMessages != null && mChatMessages.getChildCount() == 0 && mRuntimeService != null) {
            // Runtime restored a session the UI has never shown (app restart,
            // service reconnect): bring its persisted transcript back on screen.
            rebuildTranscript(run.id);
        }
        refreshRecentRuns();
        if (mSessionsPage != null && mSessionsPage.getVisibility() == View.VISIBLE) refreshSessionsPage();
    }

    @Override
    public void onProtocolEvent(String runId, String method, JSONObject payload) {
        if (runId != null && mCurrentRunId != null && !runId.equals(mCurrentRunId)
            && !method.contains("requestApproval") && !method.startsWith("session/")) return;
        if ("turn/started".equals(method)) {
            mStreamingAgentBubble = null;
            clearReasoningBuffer();
            mReasoningBubble = null;
            mCurrentToolBubble = null;
            showThinkingBubble();
        } else if ("tool/callStarted".equals(method)) {
            // Reasoning segment ends here: flush any buffered thinking above
            // the tool bubble, then retire it so the next reasoning delta
            // starts a fresh bubble below the tool call.
            endThinkingSegment();
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
        } else if ("mcp/status".equals(method)) {
            if (mExtensionsPage != null && mExtensionsPage.getVisibility() == View.VISIBLE) {
                String server = payload == null ? "" : payload.optString("server", "");
                setStatus(payload != null && payload.optBoolean("ok")
                    ? "MCP '" + server + "' connected."
                    : "MCP '" + server + "' failed: " + payload.optString("error", ""), !payload.optBoolean("ok"));
                refreshExtensionsPage();
            }
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
        if (runId != null && mCurrentRunId != null && !runId.equals(mCurrentRunId)) {
            setStatus(message, true);
            return;
        }
        showError(message);
        addSystemMessage(message);
    }

    private void addUserMessage(String text) {
        addBubble("You", text, true, R.drawable.bg_ai_user_bubble, R.color.ai_text);
    }

    private void appendAgentDelta(String text) {
        if (TextUtils.isEmpty(text) || "Thinking…".equals(text)) return;
        endThinkingSegment();
        mCurrentToolBubble = null;
        if (mStreamingAgentBubble == null)
            mStreamingAgentBubble = addBubble(mSelectedProfile == null ? "Agent" : mSelectedProfile.name,
                "", false, R.drawable.bg_ai_agent_bubble, R.color.ai_text);
        mStreamingAgentBubble.append(text);
        scrollConversation();
    }

    private void appendToolOutput(String text) {
        if (TextUtils.isEmpty(text)) return;
        endThinkingSegment();
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

    /** Retire the live reasoning bubble so the next thinking segment starts fresh. */
    private void retireReasoningBubble() {
        if (mReasoningBubble == null) return;
        View wrapper = (View) mReasoningBubble.summary.getParent();
        if (wrapper != null && wrapper.getParent() instanceof ViewGroup)
            ((ViewGroup) wrapper.getParent()).removeView(wrapper);
        mReasoningBubble = null;
    }

    private void appendReasoningDelta(String text) {
        if (TextUtils.isEmpty(text)) return;
        if (mShowThinkingDetails) {
            mPendingReasoning.append(text);
            mUiHandler.removeCallbacks(mReasoningFlusher);
            mUiHandler.postDelayed(mReasoningFlusher, REASONING_FLUSH_DELAY_MS);
        } else {
            // Reasoning resumed after a tool call or message: start the next
            // thinking segment bubble instead of staying silent.
            if (mThinkingBubble == null) showThinkingBubble();
            appendEvent("reasoning", oneLine(text, 220));
        }
    }

    /** Ends the current thinking/reasoning segment: flushes buffered reasoning
     * into its bubble (so it lands above whatever comes next), then retires
     * both bubble states. The next reasoning delta starts a fresh segment. */
    private void endThinkingSegment() {
        flushReasoningUi();
        // A segment that never received reasoning keeps only its "Waiting…"
        // placeholder — remove that stub instead of leaving it in the transcript.
        if (mReasoningBubble != null
            && "Waiting for provider response…\n".contentEquals(mReasoningBubble.details)) {
            retireReasoningBubble();
        }
        mReasoningBubble = null;
        hideThinkingBubble();
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
        // Respect a manually collapsed bubble — do not force it open again.
        mReasoningBubble.detail.setVisibility(mReasoningBubble.expanded ? View.VISIBLE : View.GONE);
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
        boolean isSkillsTool = "skills_list".equals(name) || "skill_view".equals(name);
        String label = isSkillsTool
            ? name + (TextUtils.isEmpty(command) ? "" : " · " + command)
            : "Terminal · " + (TextUtils.isEmpty(command) ? name : oneLine(command, 96));
        mCurrentToolBubble = startExpandableBubble("Tool call", label, true);
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
        View.OnClickListener toggle = view -> {
            toolBubble.expanded = !toolBubble.expanded;
            toolBubble.detail.setVisibility(toolBubble.expanded ? View.VISIBLE : View.GONE);
            scrollConversation();
        };
        // The whole bubble toggles: summary, detail text, and wrapper — so a
        // tap anywhere on the expanded bubble collapses it again.
        summary.setOnClickListener(toggle);
        detail.setOnClickListener(toggle);
        wrapper.setOnClickListener(toggle);
        wrapper.setClickable(true);
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
        // The dialog must never be dismissible from outside taps or the back
        // button — a dismissed dialog would leave the turn wedged in
        // WAITING_APPROVAL. A visible countdown auto-denies on inactivity.
        mApprovalDialog = new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ai_approval_title)
            .setMessage(approvalMessage(command, APPROVAL_AUTO_DENY_MS))
            .setCancelable(false)
            .setNegativeButton(R.string.ai_deny, (dialog, which) -> {
                closeApprovalDialog();
                answerApproval(id, false);
            })
            .setPositiveButton(R.string.ai_allow_once, (dialog, which) -> {
                closeApprovalDialog();
                answerApproval(id, true);
            })
            .show();
        mApprovalDeadline = System.currentTimeMillis() + APPROVAL_AUTO_DENY_MS;
        mApprovalCountdown = new Runnable() {
            @Override
            public void run() {
                if (mApprovalDialog == null || !mApprovalDialog.isShowing()) return;
                long remaining = mApprovalDeadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    closeApprovalDialog();
                    answerApproval(id, false);
                    return;
                }
                mApprovalDialog.setMessage(approvalMessage(command, remaining));
                mUiHandler.postDelayed(this, 1000);
            }
        };
        mUiHandler.post(mApprovalCountdown);
    }

    private String approvalMessage(String command, long remainingMs) {
        long seconds = (remainingMs + 999) / 1000;
        return command + "\n\nAuto-denies in " + seconds + "s if there is no response.";
    }

    private void closeApprovalDialog() {
        if (mApprovalCountdown != null) mUiHandler.removeCallbacks(mApprovalCountdown);
        mApprovalCountdown = null;
        if (mApprovalDialog != null) {
            try { mApprovalDialog.dismiss(); } catch (Exception ignored) {}
            mApprovalDialog = null;
        }
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
        mRecentRuns.removeAllViews();
        for (AiDatabase.RunRecord run : mRuntimeService.getSessions()) {
            mRecentRuns.addView(createSessionRow(run, false));
        }
        if (mArchivedRuns != null) {
            mArchivedRuns.removeAllViews();
            for (AiDatabase.RunRecord run : mRuntimeService.getArchivedSessions()) {
                mArchivedRuns.addView(createSessionRow(run, true));
            }
        }
        applySectionState();
    }

    private View createSessionRow(AiDatabase.RunRecord run, boolean archived) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setBackgroundResource(R.drawable.bg_provider_card);
        row.setClickable(true);
        row.setFocusable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(8));
        row.setLayoutParams(lp);

        TextView title = new TextView(this);
        String titleText = TextUtils.isEmpty(run.title) ? "Untitled session" : run.title;
        if (run.id != null && run.id.equals(mCurrentRunId)) titleText = "● " + titleText;
        title.setText(titleText);
        title.setTextColor(color(R.color.ai_text));
        title.setTextSize(13);
        title.setMaxLines(1);
        row.addView(title);

        TextView meta = new TextView(this);
        meta.setText(sessionMeta(run, archived));
        meta.setTextColor(color(R.color.ai_text_muted));
        meta.setTextSize(11);
        meta.setMaxLines(1);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mp.setMargins(0, dp(2), 0, 0);
        meta.setLayoutParams(mp);
        row.addView(meta);

        row.setOnClickListener(v -> resumeSession(run, archived));
        if (!archived) row.setOnLongClickListener(v -> { confirmArchive(run); return true; });
        return row;
    }

    private String sessionMeta(AiDatabase.RunRecord run, boolean archived) {
        StringBuilder sub = new StringBuilder();
        AiProviderProfile profile = AiProviderProfile.find(run.harnessId);
        sub.append(profile != null ? profile.name
            : (TextUtils.isEmpty(run.harnessId) ? "Agent" : run.harnessId));
        if (!TextUtils.isEmpty(run.lastResolvedModel)) sub.append(" · ").append(run.lastResolvedModel);
        sub.append(" · ").append(relativeTime(run.updatedAt));
        if (archived) sub.append(" · archived");
        else if (run.state == AiRunStateMachine.State.FAILED) sub.append(" · failed");
        else if (run.state == AiRunStateMachine.State.CANCELED) sub.append(" · interrupted");
        else if (run.state == AiRunStateMachine.State.RUNNING
            || run.state == AiRunStateMachine.State.WAITING_APPROVAL
            || run.state == AiRunStateMachine.State.CONNECTING
            || run.state == AiRunStateMachine.State.STARTING) sub.append(" · active");
        return sub.toString();
    }

    private String relativeTime(long when) {
        if (when <= 0) return "just now";
        long minutes = (System.currentTimeMillis() - when) / 60000;
        if (minutes < 1) return "just now";
        if (minutes < 60) return minutes + "m ago";
        long hours = minutes / 60;
        if (hours < 24) return hours + "h ago";
        return (hours / 24) + "d ago";
    }

    private void resumeSession(AiDatabase.RunRecord run, boolean archived) {
        if (mRuntimeService == null || !mRuntimeBound) {
            showError("Native runtime is still starting.");
            return;
        }
        if (archived) {
            new MaterialAlertDialogBuilder(this)
                .setTitle("Restore session")
                .setMessage("Move this archived session back into Sessions and open it?")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Restore", (dialog, which) -> {
                    mRuntimeService.archiveRun(run.id, false);
                    openResumedSession(run.id);
                })
                .show();
            return;
        }
        openResumedSession(run.id);
    }

    private void openResumedSession(String runId) {
        AiDatabase.RunRecord resumed = mRuntimeService.getActiveRun();
        if (resumed != null && runId.equals(resumed.id)) {
            AiProviderProfile runProfile = AiProviderProfile.find(resumed.harnessId);
            if (runProfile != null) selectProvider(runProfile, false);
            if (!TextUtils.isEmpty(resumed.lastResolvedModel)) mSelectedModel = resumed.lastResolvedModel;
        }
        mRuntimeService.resumeRun(runId);
        AiDatabase.RunRecord after = mRuntimeService.getActiveRun();
        if (after == null || !runId.equals(after.id)) return;
        mCurrentRunId = after.id;
        rebuildTranscript(after.id);
        syncControlLabels();
        if (mDrawer != null) mDrawer.closeDrawer(findViewById(R.id.ai_drawer_panel));
        showChatPage();
    }

    private void confirmArchive(AiDatabase.RunRecord run) {
        if (mRuntimeService == null) return;
        boolean isActive = run.id != null && run.id.equals(mCurrentRunId);
        new MaterialAlertDialogBuilder(this)
            .setTitle("Archive session")
            .setMessage(isActive
                ? "This stops the current task and hides the session. Its full history stays in Archived."
                : "Hide this session from the list? Its history stays in Archived.")
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Archive", (dialog, which) -> {
                mRuntimeService.archiveRun(run.id, true);
                if (isActive) startNewSession();
            })
            .show();
    }

    private void rebuildTranscript(String runId) {
        if (mChatMessages == null || mRuntimeService == null) return;
        mChatMessages.removeAllViews();
        mStreamingAgentBubble = null;
        hideThinkingBubble();
        clearReasoningBuffer();
        mReasoningBubble = null;
        mCurrentToolBubble = null;
        org.json.JSONArray transcript = mRuntimeService.getTranscript(runId);
        int rendered = 0;
        for (int i = 0; i < transcript.length(); i++) {
            org.json.JSONObject row = transcript.optJSONObject(i);
            if (row == null) continue;
            String role = row.optString("role");
            String content = row.optString("content");
            if (TextUtils.isEmpty(content) || content.trim().isEmpty()) continue;
            if ("Thinking…".equals(content.trim()) || "Thinking...".equals(content.trim())) continue; // legacy placeholder rows
            if ("user".equals(role)) {
                addUserMessage(content);
                rendered++;
            } else if ("assistant".equals(role)) {
                addBubble("Agent", content, false, R.drawable.bg_ai_agent_bubble, R.color.ai_text);
                rendered++;
            }
        }
        if (rendered == 0) {
            mEmptyChatHint.setVisibility(View.VISIBLE);
        } else {
            mEmptyChatHint.setVisibility(View.GONE);
            mSuggestionStrip.setVisibility(View.GONE);
            mUserScrolledUp = false;
            scrollConversation();
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
        if (mUserScrolledUp) return;
        // A freshly inflated transcript lays out a frame after the first post,
        // so scroll twice: the second lands after the real content height exists.
        mConversationScroll.post(() -> {
            mConversationScroll.fullScroll(ScrollView.FOCUS_DOWN);
            mConversationScroll.post(() -> mConversationScroll.fullScroll(ScrollView.FOCUS_DOWN));
        });
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
