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
import android.widget.CheckBox;

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
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

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
    // New-session / in-chat-switch flow (Home grid reused as the flow page).
    private boolean mNsActive;
    private String mSelectedRoute;
    private boolean mNsForSession;
    private int mNsStep; // 0 providers, 1 routes, 2 models
    private AiProviderProfile mNsProfile;
    private String mNsRoute;
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
        mChatTitle.setText("khabeer");
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
            } else if (mNsActive) {
                mNsStep = 1;
                renderNsStep();
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
        if (mNsActive) {
            if (mNsStep > 0) {
                if (mNsStep == 2 && (mNsProfile == null || !"opencode".equals(mNsProfile.id))) mNsStep = 0;
                else mNsStep--;
                if (mNsStep == 0) mNsProfile = null;
                renderNsStep();
            } else if (mNsForSession) {
                mNsActive = false;
                showChatPage();
            } else {
                mNsActive = false;
                showFeaturedProviders();
            }
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
            mChatTitle.setText("khabeer");
            showFeaturedProviders();
            return;
        }
        if (mChatPage != null && mChatPage.getVisibility() == View.VISIBLE && !mRunActive) {
            mChatPage.setVisibility(View.GONE);
            mSetupPanel.setVisibility(View.GONE);
            mHomePanel.setVisibility(View.VISIBLE);
            mChatTitle.setText("khabeer");
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
        mChatTitle.setText("khabeer");
        if (mMenuButton != null) mMenuButton.setOnClickListener(view -> { if (mDrawer != null) mDrawer.openDrawer(findViewById(R.id.ai_drawer_panel)); });
        if (mSettingsButton != null) mSettingsButton.setOnClickListener(view -> showAppSettingsDialog());
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
        if (navProviders != null) navProviders.setOnClickListener(v -> showExtensionsPage());
        if (navSettings != null) navSettings.setOnClickListener(v -> showMorePage());
        View setupBack = findViewById(R.id.ai_setup_back);
        if (setupBack != null) setupBack.setOnClickListener(v -> {
            if (mPickingSessionProvider) {
                mPickingSessionProvider = false;
                showChatPage();
                return;
            }
            if (mNsActive) {
                renderNsStep();
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
            showNewSessionPage(false);
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
        setMorePageVisible(false);
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
        MaterialButton newSession = smallMemoryButton("+ New session");
        newSession.setOnClickListener(v -> showNewSessionPage(false));
        mSessionsList.addView(newSession);
        java.util.List<AiDatabase.RunRecord> sessions = mRuntimeService == null
            ? new ArrayList<>() : mRuntimeService.getSessions();
        if (sessions.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No sessions yet. Use + New session above to start one.");
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
        showNewSessionPage(false);
    }

    /** New session = an instant empty chat wired to the last configured
     * provider/model; the previous session stays live in the background. */
    private void createNewSessionChat() {
        if (mRuntimeService == null || !mRuntimeBound) {
            showError("Native runtime is still starting.");
            return;
        }
        // New sessions always go through the provider/model flow — an empty
        // chat with no selection is a dead end.
        showNewSessionPage(false);
    }

    /** Session provider switching: a full providers page — pick a provider,
     * land on its configuration page, and Continue binds it to THIS session
     * only (khabeer: the session row carries its own route). */
    private void pickSessionProvider() {
        showNewSessionPage(true);
    }

    /** Setup page in session-picking mode: UI-level selection only — nothing
     * is written to the global provider config until Continue. */
    private void openSessionProviderSetup(AiProviderProfile profile) {
        if ("opencode".equals(profile.id)) {
            showOpenCodeSetupPage(true);
            return;
        }
        if ("openai-codex".equals(profile.id)) {
            mSelectedProfile = profile;
            showCodexLoginDialog(true);
            return;
        }
        if ("nous".equals(profile.id) || "github-copilot".equals(profile.id) || "qwen-oauth".equals(profile.id)
            || "anthropic".equals(profile.id) || "xai".equals(profile.id)) {
            mSelectedProfile = profile;
            showProviderLoginChooser(profile, true);
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
        setMorePageVisible(false);
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
            if (mNsActive) {
                mNsProfile = AiProviderProfile.find("opencode");
                mNsRoute = route;
                mNsStep = 2;
                renderNsStep();
                return;
            }
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

    /** Bottom-nav "More" page — intentionally empty for now; sections land
     *  here later. */
    private void showMorePage() {
        View morePage = findViewById(R.id.ai_more_page);
        if (morePage == null) return;
        mHomePanel.setVisibility(View.GONE);
        mSetupPanel.setVisibility(View.GONE);
        mChatPage.setVisibility(View.GONE);
        if (mSessionsPage != null) mSessionsPage.setVisibility(View.GONE);
        if (mOpenCodePage != null) mOpenCodePage.setVisibility(View.GONE);
        View extensions = findViewById(R.id.ai_extensions_page);
        if (extensions != null) extensions.setVisibility(View.GONE);
        mChatTitle.setText("More");
        populateMorePage((LinearLayout) morePage);
        setMorePageVisible(true);
    }

    private void setMorePageVisible(boolean visible) {
        View scroll = findViewById(R.id.ai_more_scroll);
        View page = findViewById(R.id.ai_more_page);
        if (scroll != null) scroll.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (page != null) page.setVisibility(View.VISIBLE);
        if (visible && scroll instanceof NestedScrollView) ((NestedScrollView) scroll).scrollTo(0, 0);
    }

    private void populateMorePage(LinearLayout morePage) {
        morePage.removeAllViews();
        morePage.setPadding(dp(16), dp(16), dp(16), dp(100));

        TextView title = new TextView(this);
        title.setText("More");
        title.setTextColor(color(R.color.ai_text));
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        morePage.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Memory, identity, and agent configuration.");
        subtitle.setTextColor(color(R.color.ai_text_muted));
        subtitle.setTextSize(13);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.setMargins(0, dp(4), 0, dp(14));
        subtitle.setLayoutParams(subLp);
        morePage.addView(subtitle);

        morePage.addView(createMoreEntry(
            "Memory",
            "Edit SOUL.md, MEMORY.md, and USER.md. The agent can write curated memory through the memory tool.",
            "🧠",
            v -> showMemoryPage()));
        morePage.addView(createMoreEntry(
            "Subagents",
            "See delegated child runs, inspect their transcripts, and configure their step and timeout bounds.",
            "🤖",
            v -> showSubagentsPage()));
    }

    private void showSubagentsPage() {
        View morePage = findViewById(R.id.ai_more_page);
        if (!(morePage instanceof LinearLayout)) return;
        LinearLayout page = (LinearLayout) morePage;
        page.removeAllViews();
        page.setPadding(dp(16), dp(16), dp(16), dp(100));
        mChatTitle.setText("Subagents");

        TextView title = new TextView(this);
        title.setText("Subagents");
        title.setTextColor(color(R.color.ai_text));
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        page.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Child turns the agent delegates via delegate_task. They run hidden with the parent provider and model: no memory writes, no skill management, no further delegation, terminal approvals auto-deny, token spend rolls into the parent ledger.");
        subtitle.setTextColor(color(R.color.ai_text_muted));
        subtitle.setTextSize(13);
        subtitle.setLineSpacing(dp(2), 1.0f);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.setMargins(0, dp(4), 0, dp(14));
        page.addView(subtitle, subLp);

        page.addView(nsBackRow("More", () -> showMorePage()));
        page.addView(createSubagentStatusCard());
        page.addView(createSubagentSettingsCard());

        TextView runsTitle = new TextView(this);
        runsTitle.setText("RECENT RUNS");
        runsTitle.setTextColor(color(R.color.ai_text));
        runsTitle.setTextSize(12);
        runsTitle.setTypeface(Typeface.DEFAULT_BOLD);
        runsTitle.setLetterSpacing(0.08f);
        runsTitle.setPadding(0, dp(8), 0, dp(10));
        page.addView(runsTitle);

        java.util.List<AiDatabase.RunRecord> runs =
            mRuntimeService == null ? new ArrayList<>() : mRuntimeService.getSubagentRuns();
        if (runs.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No delegated runs yet. The agent calls delegate_task when a job splits off.");
            empty.setTextColor(color(R.color.ai_text_muted));
            empty.setTextSize(13);
            empty.setPadding(0, dp(6), 0, dp(16));
            page.addView(empty);
        } else {
            for (AiDatabase.RunRecord run : runs) page.addView(createSubagentRow(run));
        }
    }

    private View createSubagentStatusCard() {
        JSONObject stats = mRuntimeService == null ? new JSONObject() : mRuntimeService.getSubagentStats();
        return nsRowCard("Child runs: " + stats.optInt("runs", 0)
            + " · transcript messages: " + stats.optInt("messages", 0),
            "Token spend attributes to parent sessions (single ledger).", true);
    }

    private View createSubagentSettingsCard() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int steps = mProviderConfig == null ? 20 : mProviderConfig.getSubagentMaxSteps();
        int timeout = mProviderConfig == null ? 300 : mProviderConfig.getSubagentTimeoutSeconds();
        MaterialButton stepsBtn = smallMemoryButton("Max steps per child: " + steps);
        stepsBtn.setOnClickListener(v -> showMemoryNumberDialog("Max steps per subagent turn", steps, 5, 80, value -> {
            if (mProviderConfig != null) mProviderConfig.setSubagentMaxSteps(value);
            showSubagentsPage();
        }));
        MaterialButton timeoutBtn = smallMemoryButton("Child timeout: " + timeout + "s");
        timeoutBtn.setOnClickListener(v -> showMemoryNumberDialog("Subagent timeout (seconds)", timeout, 60, 1800, value -> {
            if (mProviderConfig != null) mProviderConfig.setSubagentTimeoutSeconds(value);
            showSubagentsPage();
        }));
        box.addView(stepsBtn);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(8), 0, 0);
        box.addView(timeoutBtn, lp);
        TextView note = new TextView(this);
        note.setText("Blocked for children: delegate_task, memory, skill_manage. Terminal runs read-only in practice — writes auto-deny without asking.");
        note.setTextColor(color(R.color.ai_text_muted));
        note.setTextSize(11);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        np.setMargins(0, dp(8), 0, dp(12));
        box.addView(note, np);
        return box;
    }

    private View createSubagentRow(AiDatabase.RunRecord run) {
        String model = TextUtils.isEmpty(run.lastResolvedModel) ? run.modelOverride : run.lastResolvedModel;
        String state = run.state == null ? "" : run.state.name().toLowerCase();
        MaterialCardView card = nsRowCard(
            (model == null ? "subagent" : model) + " · " + state,
            "child of " + (run.parentSessionId == null ? "?" : run.parentSessionId.substring(0, Math.min(8, run.parentSessionId.length())))
                + " · " + relativeTime(run.updatedAt),
            true);
        card.setOnClickListener(v -> showSubagentTranscript(run));
        return card;
    }

    private void showSubagentTranscript(AiDatabase.RunRecord run) {
        StringBuilder text = new StringBuilder();
        if (mRuntimeService != null) {
            JSONArray rows = mRuntimeService.getTranscript(run.id);
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) continue;
                text.append("[").append(row.optString("role", "?")).append("]\n");
                String content = row.optString("content", "");
                if (content.length() > 2000) content = content.substring(0, 2000) + "…";
                text.append(content).append("\n\n");
            }
        }
        if (text.length() == 0) text.append("No transcript rows for this run.");
        TextView body = new TextView(this);
        body.setText(text.toString());
        body.setTextColor(color(R.color.ai_text));
        body.setTextSize(12);
        body.setPadding(dp(4), dp(4), dp(4), dp(4));
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(body);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(420));
        scroll.setLayoutParams(lp);
        new MaterialAlertDialogBuilder(this)
            .setTitle("Subagent transcript")
            .setView(scroll)
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

    private View createMoreEntry(String title, String body, String icon, View.OnClickListener listener) {
        MaterialCardView card = new MaterialCardView(this);
        card.setCardBackgroundColor(color(R.color.ai_surface_elevated));
        card.setStrokeColor(color(R.color.ai_border));
        card.setStrokeWidth(dp(1));
        card.setRadius(dp(18));
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(listener);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(0, 0, 0, dp(10));
        card.setLayoutParams(cardLp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.addView(row);

        TextView glyph = new TextView(this);
        glyph.setText(icon);
        glyph.setTextSize(24);
        glyph.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams glyphLp = new LinearLayout.LayoutParams(dp(44), dp(44));
        row.addView(glyph, glyphLp);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textsLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textsLp.setMargins(dp(12), 0, dp(8), 0);
        row.addView(texts, textsLp);

        TextView tvTitle = new TextView(this);
        tvTitle.setText(title);
        tvTitle.setTextColor(color(R.color.ai_text));
        tvTitle.setTextSize(16);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        texts.addView(tvTitle);

        TextView tvBody = new TextView(this);
        tvBody.setText(body);
        tvBody.setTextColor(color(R.color.ai_text_muted));
        tvBody.setTextSize(12);
        tvBody.setLineSpacing(dp(1), 1.0f);
        texts.addView(tvBody);

        TextView chevron = new TextView(this);
        chevron.setText("›");
        chevron.setTextColor(color(R.color.ai_text_muted));
        chevron.setTextSize(28);
        row.addView(chevron);
        return card;
    }

    private void showMemoryPage() {
        View morePage = findViewById(R.id.ai_more_page);
        if (!(morePage instanceof LinearLayout)) return;
        LinearLayout page = (LinearLayout) morePage;
        page.removeAllViews();
        page.setPadding(dp(16), dp(16), dp(16), dp(100));
        mChatTitle.setText("Memory");

        TextView title = new TextView(this);
        title.setText("Memory");
        title.setTextColor(color(R.color.ai_text));
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        page.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Hermes-style persistent memory. SOUL.md is identity memory; MEMORY.md and USER.md are curated facts the agent can update through the memory tool.");
        subtitle.setTextColor(color(R.color.ai_text_muted));
        subtitle.setTextSize(13);
        subtitle.setLineSpacing(dp(2), 1.0f);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.setMargins(0, dp(4), 0, dp(14));
        page.addView(subtitle, subLp);

        page.addView(createMemoryStatusCard());
        page.addView(createJourneyEntryCard());
        page.addView(createPendingMemoryCard());

        page.addView(createMemoryEditor(
            AiMemoryStore.TARGET_SOUL,
            "SOUL.md",
            "Assistant identity/persona. Injected first into the session prompt. User-owned; not a normal memory-tool target.",
            AiMemoryStore.SOUL_LIMIT));
        page.addView(createMemoryEditor(
            AiMemoryStore.TARGET_USER,
            "USER.md",
            "Who the user is: preferences, style, stable personal workflow facts.",
            AiMemoryStore.USER_LIMIT));
        page.addView(createMemoryEditor(
            AiMemoryStore.TARGET_MEMORY,
            "MEMORY.md",
            "Agent notes: environment facts, project conventions, durable lessons.",
            AiMemoryStore.MEMORY_LIMIT));
    }

    private View createJourneyEntryCard() {
        MaterialCardView card = new MaterialCardView(this);
        card.setCardBackgroundColor(color(R.color.ai_surface_elevated));
        card.setStrokeColor(color(R.color.ai_border));
        card.setStrokeWidth(dp(1));
        card.setRadius(dp(18));
        card.setClickable(true);
        card.setFocusable(true);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(0, 0, 0, dp(12));
        card.setLayoutParams(cardLp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.addView(row);

        TextView glyph = new TextView(this);
        glyph.setText("🧭");
        glyph.setTextSize(24);
        glyph.setGravity(Gravity.CENTER);
        row.addView(glyph, new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        textLp.setMargins(dp(12), 0, dp(8), 0);
        row.addView(text, textLp);

        TextView title = new TextView(this);
        title.setText("Journey");
        title.setTextColor(color(R.color.ai_text));
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        text.addView(title);

        TextView body = new TextView(this);
        body.setText("Browse the real memory graph: MEMORY.md entries, USER.md entries, and installed skills.");
        body.setTextColor(color(R.color.ai_text_muted));
        body.setTextSize(12);
        body.setLineSpacing(dp(1), 1.0f);
        text.addView(body);

        TextView chevron = new TextView(this);
        chevron.setText("›");
        chevron.setTextColor(color(R.color.ai_text_muted));
        chevron.setTextSize(28);
        row.addView(chevron);
        card.setOnClickListener(v -> showJourneyPage());
        return card;
    }

    private void showJourneyPage() {
        View morePage = findViewById(R.id.ai_more_page);
        if (!(morePage instanceof LinearLayout)) return;
        LinearLayout page = (LinearLayout) morePage;
        page.removeAllViews();
        page.setPadding(dp(16), dp(16), dp(16), dp(100));
        mChatTitle.setText("Journey");

        TextView title = new TextView(this);
        title.setText("Journey");
        title.setTextColor(color(R.color.ai_text));
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        page.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("A concrete view of the persistent context the app can carry across work: curated memory files plus procedural skill memory.");
        subtitle.setTextColor(color(R.color.ai_text_muted));
        subtitle.setTextSize(13);
        subtitle.setLineSpacing(dp(2), 1.0f);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.setMargins(0, dp(4), 0, dp(14));
        page.addView(subtitle, subLp);

        MaterialButton back = smallMemoryButton("Back to Memory");
        back.setOnClickListener(v -> showMemoryPage());
        page.addView(back, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        addJourneyMemorySection(page, AiMemoryStore.TARGET_USER, "USER.md nodes", AiMemoryStore.USER_LIMIT);
        addJourneyMemorySection(page, AiMemoryStore.TARGET_MEMORY, "MEMORY.md nodes", AiMemoryStore.MEMORY_LIMIT);
        addJourneySkillSection(page);
    }

    private void addJourneyMemorySection(LinearLayout page, String target, String title, int limit) {
        TextView heading = journeyHeading(title);
        page.addView(heading);
        List<String> entries = memoryEntries(target);
        if (entries.isEmpty()) {
            TextView empty = journeyEmpty("No entries yet.");
            page.addView(empty);
            return;
        }
        for (int i = 0; i < entries.size(); i++) {
            page.addView(createJourneyMemoryNode(target, i, entries.get(i), limit));
        }
    }

    private void addJourneySkillSection(LinearLayout page) {
        TextView heading = journeyHeading("Skill nodes");
        page.addView(heading);
        Set<String> disabled = AiSkillRegistry.readDisabled();
        List<AiSkillRegistry.Skill> skills = AiSkillRegistry.listSkills();
        boolean any = false;
        for (AiSkillRegistry.Skill skill : skills) {
            if (skill == null) continue;
            any = true;
            page.addView(createJourneySkillNode(skill, disabled.contains(skill.name)));
        }
        if (!any) page.addView(journeyEmpty("No skills installed."));
    }

    private TextView journeyHeading(String text) {
        TextView heading = new TextView(this);
        heading.setText(text);
        heading.setTextColor(color(R.color.ai_text));
        heading.setTextSize(17);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        heading.setPadding(0, dp(18), 0, dp(8));
        return heading;
    }

    private TextView journeyEmpty(String text) {
        TextView empty = new TextView(this);
        empty.setText(text);
        empty.setTextColor(color(R.color.ai_text_muted));
        empty.setTextSize(12);
        empty.setPadding(0, 0, 0, dp(10));
        return empty;
    }

    private View createJourneyMemoryNode(String target, int index, String entry, int limit) {
        MaterialCardView card = journeyNodeCard();
        LinearLayout box = journeyNodeBox(card);
        String nodeId = "memory:" + target + ":" + index;
        TextView title = new TextView(this);
        title.setText(nodeId);
        title.setTextColor(color(R.color.ai_text));
        title.setTextSize(13);
        title.setTypeface(Typeface.MONOSPACE);
        box.addView(title);

        TextView body = new TextView(this);
        body.setText(entry);
        body.setTextColor(color(R.color.ai_text_muted));
        body.setTextSize(12);
        body.setLineSpacing(dp(1), 1.0f);
        body.setPadding(0, dp(6), 0, dp(8));
        box.addView(body);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        MaterialButton edit = smallMemoryButton("Edit");
        MaterialButton delete = smallMemoryButton("Delete");
        buttons.addView(edit, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams delLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        delLp.setMargins(dp(8), 0, 0, 0);
        buttons.addView(delete, delLp);
        box.addView(buttons);

        edit.setOnClickListener(v -> editJourneyMemoryNode(target, index, entry, limit));
        delete.setOnClickListener(v -> deleteJourneyMemoryNode(target, index));
        return card;
    }

    private View createJourneySkillNode(AiSkillRegistry.Skill skill, boolean disabled) {
        MaterialCardView card = journeyNodeCard();
        card.setClickable(true);
        card.setFocusable(true);
        LinearLayout box = journeyNodeBox(card);

        TextView title = new TextView(this);
        title.setText("skill:" + skill.name);
        title.setTextColor(color(R.color.ai_text));
        title.setTextSize(13);
        title.setTypeface(Typeface.MONOSPACE);
        box.addView(title);

        TextView meta = new TextView(this);
        String state = !skill.platformSupported ? "not available on Android" : (disabled ? "disabled" : "enabled");
        meta.setText(skill.category + " · " + state + " · " + skill.relPath);
        meta.setTextColor(color(!skill.platformSupported || disabled ? R.color.ai_warning : R.color.ai_text_muted));
        meta.setTextSize(11);
        meta.setPadding(0, dp(4), 0, dp(4));
        box.addView(meta);

        if (!TextUtils.isEmpty(skill.description)) {
            TextView desc = new TextView(this);
            desc.setText(skill.description);
            desc.setTextColor(color(R.color.ai_text_muted));
            desc.setTextSize(12);
            desc.setPadding(0, dp(2), 0, 0);
            box.addView(desc);
        }
        card.setOnClickListener(v -> showSkillViewer(skill));
        return card;
    }

    private MaterialCardView journeyNodeCard() {
        MaterialCardView card = new MaterialCardView(this);
        card.setCardBackgroundColor(color(R.color.ai_surface_elevated));
        card.setStrokeColor(color(R.color.ai_border));
        card.setStrokeWidth(dp(1));
        card.setRadius(dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(10));
        card.setLayoutParams(lp);
        return card;
    }

    private LinearLayout journeyNodeBox(MaterialCardView card) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.addView(box);
        return box;
    }

    private List<String> memoryEntries(String target) {
        List<String> entries = new ArrayList<>();
        String raw = AiMemoryStore.readRaw(target);
        if (TextUtils.isEmpty(raw.trim())) return entries;
        String[] parts = raw.split("\\n§\\n");
        for (String part : parts) {
            String clean = part == null ? "" : part.trim();
            if (!TextUtils.isEmpty(clean) && !entries.contains(clean)) entries.add(clean);
        }
        return entries;
    }

    private void editJourneyMemoryNode(String target, int index, String current, int limit) {
        EditText editor = new EditText(this);
        editor.setSingleLine(false);
        editor.setMinLines(4);
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setText(current);
        editor.setSelection(editor.length());
        editor.setTextColor(color(R.color.ai_text));
        editor.setHintTextColor(color(R.color.ai_text_dim));
        editor.setBackgroundColor(color(R.color.ai_surface_muted));
        editor.setPadding(dp(12), dp(10), dp(12), dp(10));
        new MaterialAlertDialogBuilder(this)
            .setTitle("Edit memory node")
            .setMessage("Node: memory:" + target + ":" + index + "\nStore cap: " + limit + " chars")
            .setView(editor)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", (dialog, which) -> {
                List<String> entries = memoryEntries(target);
                if (index < 0 || index >= entries.size()) { showError("Memory node no longer exists."); return; }
                entries.set(index, editor.getText() == null ? "" : editor.getText().toString().trim());
                saveJourneyEntries(target, entries, limit);
            })
            .show();
    }

    private void deleteJourneyMemoryNode(String target, int index) {
        new MaterialAlertDialogBuilder(this)
            .setTitle("Delete memory node?")
            .setMessage("Delete memory:" + target + ":" + index + " from " + (AiMemoryStore.TARGET_USER.equals(target) ? "USER.md" : "MEMORY.md") + "?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete", (dialog, which) -> {
                List<String> entries = memoryEntries(target);
                if (index < 0 || index >= entries.size()) { showError("Memory node no longer exists."); return; }
                entries.remove(index);
                saveJourneyEntries(target, entries, AiMemoryStore.limitFor(target));
            })
            .show();
    }

    private void saveJourneyEntries(String target, List<String> entries, int limit) {
        ArrayList<String> clean = new ArrayList<>();
        for (String entry : entries) {
            String e = entry == null ? "" : entry.trim();
            if (!TextUtils.isEmpty(e) && !clean.contains(e)) clean.add(e);
        }
        String serialized = TextUtils.join(AiMemoryStore.ENTRY_DELIMITER, clean);
        if (serialized.length() > limit) {
            showError("That edit is " + serialized.length() + "/" + limit + " chars. Shorten it before saving.");
            return;
        }
        JSONObject result = AiMemoryStore.saveRaw(target, serialized);
        if (result.optBoolean("success")) {
            Toast.makeText(this, "Memory node saved", Toast.LENGTH_SHORT).show();
            showJourneyPage();
        } else {
            showError(result.optString("error", "Memory node save failed."));
        }
    }

    private View createMemoryStatusCard() {
        MaterialCardView card = new MaterialCardView(this);
        card.setCardBackgroundColor(color(R.color.ai_surface_elevated));
        card.setStrokeColor(color(R.color.ai_border));
        card.setStrokeWidth(dp(1));
        card.setRadius(dp(18));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(0, 0, 0, dp(12));
        card.setLayoutParams(cardLp);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.addView(box);

        TextView heading = new TextView(this);
        heading.setText("Memory system");
        heading.setTextColor(color(R.color.ai_text));
        heading.setTextSize(17);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(heading);

        TextView summary = new TextView(this);
        summary.setText(memoryStatusText());
        summary.setTextColor(color(R.color.ai_text_muted));
        summary.setTextSize(12);
        summary.setLineSpacing(dp(2), 1.0f);
        LinearLayout.LayoutParams summaryLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        summaryLp.setMargins(0, dp(8), 0, dp(8));
        box.addView(summary, summaryLp);

        TextView note = new TextView(this);
        note.setText("Conversation history is saved automatically and exposed to the model through session_search. Memory file edits affect new sessions; active sessions keep their frozen prompt snapshot.");
        note.setTextColor(color(R.color.ai_text_dim));
        note.setTextSize(11);
        note.setLineSpacing(dp(1), 1.0f);
        box.addView(note);

        box.addView(memoryToggle("Enable MEMORY.md", mProviderConfig == null || mProviderConfig.isMemoryEnabled(), checked -> {
            if (mProviderConfig != null) mProviderConfig.setMemoryEnabled(checked);
            showMemoryPage();
        }));
        box.addView(memoryToggle("Enable USER.md", mProviderConfig == null || mProviderConfig.isUserMemoryEnabled(), checked -> {
            if (mProviderConfig != null) mProviderConfig.setUserMemoryEnabled(checked);
            showMemoryPage();
        }));
        box.addView(memoryToggle("Require approval before memory writes", mProviderConfig != null && mProviderConfig.isMemoryWriteApprovalEnabled(), checked -> {
            if (mProviderConfig != null) mProviderConfig.setMemoryWriteApprovalEnabled(checked);
            showMemoryPage();
        }));
        int nudgeEvery = mProviderConfig == null ? 10 : mProviderConfig.getMemoryNudgeInterval();
        box.addView(memoryToggle("Enable " + nudgeEvery + "-turn memory review nudge", mProviderConfig == null || mProviderConfig.isMemoryNudgeEnabled(), checked -> {
            if (mProviderConfig != null) mProviderConfig.setMemoryNudgeEnabled(checked);
            showMemoryPage();
        }));

        LinearLayout tuning = new LinearLayout(this);
        tuning.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams tuningLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tuningLp.setMargins(0, dp(8), 0, 0);
        box.addView(tuning, tuningLp);

        MaterialButton nudgeBtn = smallMemoryButton("Review every " + nudgeEvery);
        MaterialButton warnBtn = smallMemoryButton("Warn " + (mProviderConfig == null ? 90 : mProviderConfig.getMemoryWarnPct()) + "%");
        MaterialButton autoBtn = smallMemoryButton("Auto " + (mProviderConfig == null ? 95 : mProviderConfig.getMemoryAutoPct()) + "%");
        MaterialButton notifyBtn = smallMemoryButton("Notices: " + (mProviderConfig == null ? "on" : mProviderConfig.getMemoryNotifyMode()));
        tuning.addView(nudgeBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams tuneLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        tuneLp.setMargins(dp(6), 0, 0, 0);
        tuning.addView(warnBtn, tuneLp);
        LinearLayout.LayoutParams tuneLp2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        tuneLp2.setMargins(dp(6), 0, 0, 0);
        tuning.addView(autoBtn, tuneLp2);
        LinearLayout.LayoutParams tuneLp3 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        tuneLp3.setMargins(dp(6), 0, 0, 0);
        tuning.addView(notifyBtn, tuneLp3);

        nudgeBtn.setOnClickListener(v -> showMemoryNumberDialog("Review every N user turns", nudgeEvery, 1, 100, value -> {
            if (mProviderConfig != null) mProviderConfig.setMemoryNudgeInterval(value);
            showMemoryPage();
        }));
        warnBtn.setOnClickListener(v -> showMemoryNumberDialog("Warn when context reaches %", mProviderConfig == null ? 90 : mProviderConfig.getMemoryWarnPct(), 50, 99, value -> {
            if (mProviderConfig != null) mProviderConfig.setMemoryWarnPct(value);
            showMemoryPage();
        }));
        autoBtn.setOnClickListener(v -> showMemoryNumberDialog("Auto-compact when context reaches %", mProviderConfig == null ? 95 : mProviderConfig.getMemoryAutoPct(), 50, 100, value -> {
            if (mProviderConfig != null) mProviderConfig.setMemoryAutoPct(value);
            showMemoryPage();
        }));
        notifyBtn.setOnClickListener(v -> {
            String[] modes = new String[]{"off", "on", "verbose"};
            String current = mProviderConfig == null ? "on" : mProviderConfig.getMemoryNotifyMode();
            int checked = "off".equals(current) ? 0 : "verbose".equals(current) ? 2 : 1;
            new MaterialAlertDialogBuilder(this)
                .setTitle("Background review notices")
                .setSingleChoiceItems(modes, checked, (dialog, which) -> {
                    if (mProviderConfig != null) mProviderConfig.setMemoryNotifyMode(modes[which]);
                    dialog.dismiss();
                    showMemoryPage();
                })
                .setNegativeButton("Cancel", null)
                .show();
        });

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams buttonsLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        buttonsLp.setMargins(0, dp(10), 0, 0);
        box.addView(buttons, buttonsLp);

        MaterialButton resetUser = smallMemoryButton("Reset USER.md");
        MaterialButton resetMemory = smallMemoryButton("Reset MEMORY.md");
        buttons.addView(resetUser, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams rmLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        rmLp.setMargins(dp(8), 0, 0, 0);
        buttons.addView(resetMemory, rmLp);

        resetUser.setOnClickListener(v -> confirmResetMemory(AiMemoryStore.TARGET_USER, "USER.md"));
        resetMemory.setOnClickListener(v -> confirmResetMemory(AiMemoryStore.TARGET_MEMORY, "MEMORY.md"));
        return card;
    }

    private interface BoolConsumer { void accept(boolean checked); }

    private interface IntConsumer { void accept(int value); }

    private void showMemoryNumberDialog(String title, int current, int min, int max, IntConsumer onSave) {
        EditText editor = new EditText(this);
        editor.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        editor.setText(String.valueOf(current));
        editor.setSelection(editor.length());
        editor.setTextColor(color(R.color.ai_text));
        editor.setHintTextColor(color(R.color.ai_text_dim));
        editor.setBackgroundColor(color(R.color.ai_surface_muted));
        editor.setPadding(dp(12), dp(10), dp(12), dp(10));
        new MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(editor)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", (dialog, which) -> {
                int value;
                try { value = Integer.parseInt(editor.getText() == null ? "" : editor.getText().toString().trim()); }
                catch (Exception e) { showError("Enter a number between " + min + " and " + max + "."); return; }
                if (value < min || value > max) { showError("Enter a number between " + min + " and " + max + "."); return; }
                onSave.accept(value);
            })
            .show();
    }

    private View memoryToggle(String text, boolean checked, BoolConsumer onChange) {
        CheckBox cb = new CheckBox(this);
        cb.setText(text);
        cb.setChecked(checked);
        cb.setTextColor(color(R.color.ai_text));
        cb.setTextSize(12);
        cb.setButtonTintList(android.content.res.ColorStateList.valueOf(color(R.color.ai_accent)));
        cb.setPadding(0, dp(6), 0, dp(2));
        cb.setOnCheckedChangeListener((buttonView, isChecked) -> onChange.accept(isChecked));
        return cb;
    }

    private MaterialButton smallMemoryButton(String text) {
        MaterialButton b = new MaterialButton(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(12);
        b.setTextColor(color(R.color.ai_text));
        b.setStrokeColor(android.content.res.ColorStateList.valueOf(color(R.color.ai_border)));
        b.setStrokeWidth(dp(1));
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color(R.color.ai_surface_muted)));
        b.setCornerRadius(dp(12));
        return b;
    }

    private void confirmResetMemory(String target, String label) {
        new MaterialAlertDialogBuilder(this)
            .setTitle("Reset " + label + "?")
            .setMessage("This clears the curated entries in " + label + ". SOUL.md and session history are not touched.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Reset", (dialog, which) -> {
                JSONObject result = AiMemoryStore.reset(target);
                if (result.optBoolean("success")) {
                    Toast.makeText(this, label + " reset", Toast.LENGTH_SHORT).show();
                    showMemoryPage();
                } else {
                    showError(result.optString("error", "Reset failed."));
                }
            })
            .show();
    }

    private String memoryStatusText() {
        try {
            JSONObject status = AiMemoryStore.status();
            JSONObject soul = status.optJSONObject("soul");
            JSONObject user = status.optJSONObject("user");
            JSONObject memory = status.optJSONObject("memory");
            return "SOUL.md: " + statusLine(soul) + "\n" +
                "USER.md: " + statusLine(user) + "\n" +
                "MEMORY.md: " + statusLine(memory) + "\n" +
                "Pending staged writes: " + status.optInt("pending_count", 0) + "\n" +
                "Write approval: " + ((mProviderConfig != null && mProviderConfig.isMemoryWriteApprovalEnabled()) ? "on" : "off") + "\n" +
                "Nudge loop: " + ((mProviderConfig == null || mProviderConfig.isMemoryNudgeEnabled()) ? "on every " + (mProviderConfig == null ? 10 : mProviderConfig.getMemoryNudgeInterval()) + " user turns" : "off") + "\n" +
                "Review notices: " + (mProviderConfig == null ? "on" : mProviderConfig.getMemoryNotifyMode()) + "\n" +
                "Context gating: warn " + (mProviderConfig == null ? 90 : mProviderConfig.getMemoryWarnPct()) + "% · auto-compact " + (mProviderConfig == null ? 95 : mProviderConfig.getMemoryAutoPct()) + "%";
        } catch (Exception e) {
            return "Memory status unavailable.";
        }
    }

    private String statusLine(JSONObject o) {
        if (o == null) return "unavailable";
        return o.optInt("chars") + "/" + o.optInt("limit") + " chars · " + o.optInt("entry_count") + " entr" + (o.optInt("entry_count") == 1 ? "y" : "ies");
    }

    private View createPendingMemoryCard() {
        JSONArray pending = AiMemoryStore.pendingWrites();
        MaterialCardView card = new MaterialCardView(this);
        card.setCardBackgroundColor(color(R.color.ai_surface_elevated));
        card.setStrokeColor(color(R.color.ai_border));
        card.setStrokeWidth(dp(1));
        card.setRadius(dp(18));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(0, 0, 0, dp(12));
        card.setLayoutParams(cardLp);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.addView(box);

        TextView heading = new TextView(this);
        heading.setText("Pending memory writes");
        heading.setTextColor(color(R.color.ai_text));
        heading.setTextSize(17);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(heading);

        TextView body = new TextView(this);
        body.setText(pending.length() == 0
            ? "No staged writes. The default Hermes behavior is direct model writes for MEMORY.md/USER.md; staged review is available for gated/background writes."
            : pending.length() + " write(s) waiting for review.");
        body.setTextColor(color(R.color.ai_text_muted));
        body.setTextSize(12);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bodyLp.setMargins(0, dp(6), 0, dp(8));
        box.addView(body, bodyLp);

        for (int i = 0; i < pending.length(); i++) {
            JSONObject item = pending.optJSONObject(i);
            if (item == null) continue;
            box.addView(createPendingMemoryRow(item));
        }
        return card;
    }

    private View createPendingMemoryRow(JSONObject item) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));
        String id = item.optString("id");
        JSONObject args = item.optJSONObject("args");

        TextView text = new TextView(this);
        text.setText((TextUtils.isEmpty(id) ? "pending" : id) + "\n" + oneLine(args == null ? "" : args.toString(), 140));
        text.setTextColor(color(R.color.ai_text_muted));
        text.setTextSize(11);
        row.addView(text);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        MaterialButton approve = smallMemoryButton("Approve");
        MaterialButton reject = smallMemoryButton("Reject");
        buttons.addView(approve, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams rejectLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        rejectLp.setMargins(dp(8), 0, 0, 0);
        buttons.addView(reject, rejectLp);
        row.addView(buttons);

        approve.setOnClickListener(v -> {
            JSONObject result = AiMemoryStore.approvePending(id);
            if (result.optBoolean("success")) {
                Toast.makeText(this, "Memory write approved", Toast.LENGTH_SHORT).show();
                showMemoryPage();
            } else showError(result.optString("error", "Approval failed."));
        });
        reject.setOnClickListener(v -> {
            JSONObject result = AiMemoryStore.rejectPending(id);
            if (result.optBoolean("success")) {
                Toast.makeText(this, "Memory write rejected", Toast.LENGTH_SHORT).show();
                showMemoryPage();
            } else showError(result.optString("error", "Reject failed."));
        });
        return row;
    }

    private View createMemoryEditor(String target, String title, String body, int limit) {
        MaterialCardView card = new MaterialCardView(this);
        card.setCardBackgroundColor(color(R.color.ai_surface_elevated));
        card.setStrokeColor(color(R.color.ai_border));
        card.setStrokeWidth(dp(1));
        card.setRadius(dp(18));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(0, 0, 0, dp(12));
        card.setLayoutParams(cardLp);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.addView(box);

        TextView heading = new TextView(this);
        heading.setText(title);
        heading.setTextColor(color(R.color.ai_text));
        heading.setTextSize(17);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(heading);

        TextView desc = new TextView(this);
        desc.setText(body);
        desc.setTextColor(color(R.color.ai_text_muted));
        desc.setTextSize(12);
        desc.setLineSpacing(dp(1), 1.0f);
        box.addView(desc);

        EditText editor = new EditText(this);
        editor.setSingleLine(false);
        editor.setMinLines(AiMemoryStore.TARGET_SOUL.equals(target) ? 5 : 4);
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setText(AiMemoryStore.readRaw(target));
        editor.setTextColor(color(R.color.ai_text));
        editor.setHintTextColor(color(R.color.ai_text_dim));
        editor.setTextSize(13);
        editor.setBackgroundColor(color(R.color.ai_surface_muted));
        editor.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams editorLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        editorLp.setMargins(0, dp(10), 0, dp(8));
        box.addView(editor, editorLp);

        TextView counter = new TextView(this);
        counter.setGravity(Gravity.END);
        counter.setTextColor(editor.length() > limit ? color(R.color.ai_error) : color(R.color.ai_text_muted));
        counter.setText(editor.length() + "/" + limit);
        counter.setTextSize(11);
        box.addView(counter);

        editor.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                int len = s == null ? 0 : s.length();
                counter.setText(len + "/" + limit);
                counter.setTextColor(len > limit ? color(R.color.ai_error) : color(R.color.ai_text_muted));
            }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });

        MaterialButton save = new MaterialButton(this);
        save.setText("Save " + title);
        save.setAllCaps(false);
        save.setTextColor(0xFFFFFFFF);
        save.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color(R.color.ai_accent)));
        save.setCornerRadius(dp(12));
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        saveLp.setMargins(0, dp(8), 0, 0);
        box.addView(save, saveLp);
        save.setOnClickListener(v -> {
            JSONObject result = AiMemoryStore.saveRaw(target, editor.getText() == null ? "" : editor.getText().toString());
            if (result.optBoolean("success")) {
                setStatus(title + " saved. It affects the next session snapshot.", false);
                Toast.makeText(this, title + " saved", Toast.LENGTH_SHORT).show();
            } else {
                showError(result.optString("error", "Memory save failed."));
            }
        });

        return card;
    }

    /** Top-bar gear: the khabeer app settings that exist today. */
    private void showAppSettingsDialog() {
        String[] modes = new String[]{"Dark", "Light"};
        String current = AiThemeMode.mode(this);
        int checked = AiThemeMode.LIGHT.equals(current) ? 1 : 0;
        new MaterialAlertDialogBuilder(this)
            .setTitle("Settings")
            .setSingleChoiceItems(modes, checked, (dialog, which) -> {
                AiThemeMode.set(this, which == 1 ? AiThemeMode.LIGHT : AiThemeMode.DARK);
                dialog.dismiss();
            })
            .setNeutralButton("Skills & extensions", (dialog, which) -> showExtensionsPage())
            .setNegativeButton("Sessions", (dialog, which) -> showSessionsPage())
            .show();
    }

    /** Skills & extensions page (khabeer skills): list installed skills, toggle
     * them, and read the full SKILL.md. The skills root is
     * $HOME/.khabeer/skills — drop a folder with a SKILL.md in there (or sync
     * one from a desktop khabeer install) and it appears here. */
    private void showExtensionsPage() {
        if (mExtensionsPage == null) return;
        setMorePageVisible(false);
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
        pathHint.setText("$HOME/.khabeer/skills");
        pathHint.setTextColor(color(R.color.ai_text_muted));
        pathHint.setTextSize(11);
        pathHint.setTypeface(Typeface.MONOSPACE);
        LinearLayout.LayoutParams pathLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        pathHint.setLayoutParams(pathLp);
        toolbar.addView(pathHint);

        MaterialButton install = new MaterialButton(this);
        install.setText("Install");
        install.setTextSize(12);
        install.setAllCaps(false);
        install.setStrokeColor(android.content.res.ColorStateList.valueOf(color(R.color.ai_border)));
        install.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color(R.color.ai_surface)));
        install.setTextColor(color(R.color.ai_text));
        install.setCornerRadius(dp(10));
        LinearLayout.LayoutParams installLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        installLp.setMargins(0, 0, dp(8), 0);
        install.setLayoutParams(installLp);
        install.setOnClickListener(v -> showSkillInstallDialog());
        toolbar.addView(install);

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
            empty.setText("No skills installed. Skills live in $HOME/.khabeer/skills — one folder per skill with a SKILL.md inside.");
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
        String summary = "stdio".equals(server.transport)
            ? "$ " + (server.command == null ? "" : server.command)
                + (server.argsJson == null ? "" : " " + argsPreview(server.argsJson))
            : server.url;
        urlView.setText(summary == null ? "" : oneLine(summary, 52));
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

    private String argsPreview(String argsJson) {
        try {
            StringBuilder sb = new StringBuilder();
            JSONArray args = new JSONArray(argsJson);
            for (int i = 0; i < args.length(); i++) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(args.optString(i));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private void confirmMcpServerRemove(AiDatabase.McpServerRecord server) {        new MaterialAlertDialogBuilder(this)
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

        android.widget.CheckBox stdioBox = new android.widget.CheckBox(this);
        stdioBox.setText("Run a local command instead (stdio, in Termux)");
        stdioBox.setTextColor(color(R.color.ai_text));
        stdioBox.setChecked(existing != null && "stdio".equals(existing.transport));

        EditText commandInput = new EditText(this);
        commandInput.setSingleLine(true);
        commandInput.setHint("Command (e.g. python, node, npx)");
        commandInput.setText(existing == null ? "" : existing.command == null ? "" : existing.command);
        commandInput.setTextColor(color(R.color.ai_text));

        EditText argsInput = new EditText(this);
        argsInput.setSingleLine(true);
        argsInput.setHint("Arguments, separated by spaces");
        argsInput.setText(existing == null ? "" : argsPreview(existing.argsJson));
        argsInput.setTextColor(color(R.color.ai_text));

        View.OnClickListener transportToggle = v -> {
            boolean stdio = stdioBox.isChecked();
            urlInput.setVisibility(stdio ? View.GONE : View.VISIBLE);
            commandInput.setVisibility(stdio ? View.VISIBLE : View.GONE);
            argsInput.setVisibility(stdio ? View.VISIBLE : View.GONE);
        };
        stdioBox.setOnClickListener(transportToggle);
        urlInput.setVisibility(stdioBox.isChecked() ? View.GONE : View.VISIBLE);
        commandInput.setVisibility(stdioBox.isChecked() ? View.VISIBLE : View.GONE);
        argsInput.setVisibility(stdioBox.isChecked() ? View.VISIBLE : View.GONE);

        TextView authLabel = new TextView(this);
        authLabel.setText("Authentication (HTTP servers)");
        authLabel.setTextColor(color(R.color.ai_text_muted));
        authLabel.setTextSize(12);

        android.widget.RadioGroup authGroup = new android.widget.RadioGroup(this);
        android.widget.RadioButton authNone = new android.widget.RadioButton(this);
        authNone.setText("None (keyless server)");
        android.widget.RadioButton authHeader = new android.widget.RadioButton(this);
        authHeader.setText("Bearer token (stored encrypted)");
        android.widget.RadioButton authOauth = new android.widget.RadioButton(this);
        authOauth.setText("OAuth 2.0 — sign in with browser");
        for (android.widget.RadioButton button : new android.widget.RadioButton[]{authNone, authHeader, authOauth})
            button.setTextColor(color(R.color.ai_text));
        authGroup.addView(authNone);
        authGroup.addView(authHeader);
        authGroup.addView(authOauth);
        String existingAuth = existing == null ? "none" : existing.authType;
        if ("header".equals(existingAuth)) authHeader.setChecked(true);
        else if ("oauth".equals(existingAuth)) authOauth.setChecked(true);
        else authNone.setChecked(true);

        EditText tokenInput = new EditText(this);
        tokenInput.setSingleLine(true);
        tokenInput.setHint("Bearer token (stored encrypted)");
        tokenInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        tokenInput.setText(existing == null || !"header".equals(existing.authType)
            ? "" : mProviderConfig.getMcpServerToken(existing.name));
        tokenInput.setTextColor(color(R.color.ai_text));
        tokenInput.setVisibility(authHeader.isChecked() ? View.VISIBLE : View.GONE);

        TextView oauthHint = new TextView(this);
        oauthHint.setText(existing != null && "oauth".equals(existing.authType)
            ? "OAuth is configured. Saving opens a new sign-in if tokens are missing."
            : "On save, your browser opens to authorize, and the callback is caught on this device.");
        oauthHint.setTextColor(color(R.color.ai_text_muted));
        oauthHint.setTextSize(11);
        oauthHint.setVisibility(authOauth.isChecked() ? View.VISIBLE : View.GONE);
        authGroup.setOnCheckedChangeListener((g, id) -> {
            tokenInput.setVisibility(id == authHeader.getId() ? View.VISIBLE : View.GONE);
            oauthHint.setVisibility(id == authOauth.getId() ? View.VISIBLE : View.GONE);
        });

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
        form.addView(stdioBox);
        form.addView(commandInput, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        form.addView(argsInput, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        form.addView(authLabel);
        form.addView(authGroup);
        form.addView(tokenInput);
        form.addView(oauthHint);
        form.addView(trusted);
        form.addView(warning);

        new MaterialAlertDialogBuilder(this)
            .setTitle(existing == null ? "Add MCP server" : "Edit " + existing.name)
            .setView(form)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Save", (dialog, which) -> {
                String name = nameInput.getText().toString().trim().replaceAll("[^A-Za-z0-9_-]", "_").toLowerCase();
                String url = urlInput.getText().toString().trim();
                if (name.isEmpty()) {
                    showError("MCP server needs a name.");
                    return;
                }
                boolean stdio = stdioBox.isChecked();
                if (!stdio && !url.startsWith("http")) {
                    showError("An HTTP MCP server needs an http(s) URL.");
                    return;
                }
                boolean withHeader = authHeader.isChecked() && !stdio;
                boolean withOauth = authOauth.isChecked() && !stdio;
                AiDatabase.McpServerRecord record = existing == null ? new AiDatabase.McpServerRecord() : existing;
                record.name = name;
                record.transport = stdio ? "stdio" : "http";
                if (stdio) {
                    record.url = null;
                    record.command = commandInput.getText().toString().trim();
                    record.argsJson = argsToJson(argsInput.getText().toString());
                    if (record.command.isEmpty()) {
                        showError("A stdio server needs a command.");
                        return;
                    }
                } else {
                    if (!url.startsWith("http")) {
                        showError("An HTTP MCP server needs an http(s) URL.");
                        return;
                    }
                    record.url = url;
                    record.command = null;
                    record.argsJson = null;
                }
                record.authType = withHeader ? "header" : withOauth ? "oauth" : "none";
                if ("none".equals(record.authType)) record.oauthJson = null;
                record.trust = trusted.isChecked() ? "trusted" : "untrusted";
                if (record.lastStatus != null && record.lastStatus.startsWith("failed")) record.lastStatus = null;
                mRuntimeService.saveMcpServer(record);
                mProviderConfig.setMcpServerToken(name, withHeader ? tokenInput.getText().toString().trim() : null);
                if (withOauth) {
                    boolean hasTokens = !TextUtils.isEmpty(mProviderConfig.getMcpServerToken(name))
                        && !TextUtils.isEmpty(mProviderConfig.getMcpServerRefresh(name));
                    if (!hasTokens) {
                        dialog.dismiss();
                        startMcpOAuthSignIn(record);
                        return;
                    }
                    setStatus("OAuth tokens already stored for '" + name + "'.", false);
                }
                mRuntimeService.refreshMcpServer(name);
                setStatus("MCP '" + name + "' saved. Testing…", false);
                mUiHandler.postDelayed(() -> {
                    refreshExtensionsPage();
                    mRuntimeService.testMcpServer(name);
                }, 200);
            })
            .show();
    }

    /** Splits the args line on whitespace into a JSON string array. */
    private String argsToJson(String line) {
        JSONArray array = new JSONArray();
        for (String arg : (line == null ? "" : line.trim()).split("\\s+")) {
            if (!arg.isEmpty()) array.put(arg);
        }
        return array.length() == 0 ? null : array.toString();
    }

    // ------------------------------------------------------------------
    // MCP OAuth 2.0 sign-in (authorization code + PKCE, loopback callback
    // with a manual paste fallback — khabeer mcp_oauth flow)
    // ------------------------------------------------------------------

    private void startMcpOAuthSignIn(AiDatabase.McpServerRecord record) {
        setStatus("Discovering OAuth endpoints for '" + record.name + "'…", false);
        new Thread(() -> {
            ServerSocket loopback = null;
            try {
                JSONObject meta = AiMcpRegistry.discoverOAuth(record.url);
                JSONObject oauth = TextUtils.isEmpty(record.oauthJson) ? new JSONObject() : new JSONObject(record.oauthJson);
                String redirectUri;
                SynchronousQueue<String> codeQueue = new SynchronousQueue<>();
                loopback = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
                int port = loopback.getLocalPort();
                redirectUri = "http://127.0.0.1:" + port + "/callback";
                oauth.put("redirect_uri", redirectUri);
                String expectedState = base64UrlRandom(16);

                Thread listener = spawnLoopbackListener(loopback, codeQueue, expectedState);
                listener.start();

                String clientId = oauth.optString("client_id", "");
                if (TextUtils.isEmpty(clientId)) {
                    clientId = AiMcpRegistry.registerClient(meta, redirectUri, record.name);
                    oauth.put("client_id", clientId);
                }
                oauth.put("authorization_endpoint", meta.optString("authorization_endpoint"));
                oauth.put("token_endpoint", meta.optString("token_endpoint"));
                String verifier = base64UrlRandom(32);
                oauth.put("code_verifier", verifier);
                oauth.put("state", expectedState);
                record.oauthJson = oauth.toString();
                mRuntimeService.saveMcpServer(record);

                String challenge = base64Url(MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII)));
                String authUrl = AiMcpRegistry.buildAuthorizationUrl(meta, clientId, redirectUri,
                    expectedState, challenge, meta.optString("resource"));

                runOnUiThread(() -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(authUrl))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (Exception e) {
                        showError("No browser available — use the paste fallback.");
                    }
                    showOAuthPasteFallback(codeQueue);
                    setStatus("Waiting for authorization (browser)…", false);
                });

                String candidate = codeQueue.poll(5, TimeUnit.MINUTES);
                if (TextUtils.isEmpty(candidate)) throw new IllegalStateException("Timed out waiting for the authorization response.");
                String code = extractOAuthParam(candidate, "code");
                String state = extractOAuthParam(candidate, "state");
                if (TextUtils.isEmpty(code)) throw new IllegalStateException("No authorization code in the response.");
                if (!expectedState.equals(state)) throw new IllegalStateException("state mismatch — aborting (possible CSRF).");

                JSONObject tokens = AiMcpRegistry.exchangeAuthorizationCode(meta, clientId, code, redirectUri, verifier);
                String access = tokens.optString("access_token", "");
                if (TextUtils.isEmpty(access)) throw new IllegalStateException("Token endpoint returned no access_token.");
                mProviderConfig.setMcpServerToken(record.name, access);
                String refresh = tokens.optString("refresh_token", "");
                if (!TextUtils.isEmpty(refresh)) mProviderConfig.setMcpServerRefresh(record.name, refresh);
                oauth.remove("code_verifier");
                oauth.remove("state");
                record.oauthJson = oauth.toString();
                record.authType = "oauth";
                mRuntimeService.saveMcpServer(record);
                runOnUiThread(() -> setStatus("OAuth sign-in complete for '" + record.name + "'. Testing…", false));
                mUiHandler.postDelayed(() -> {
                    refreshExtensionsPage();
                    mRuntimeService.testMcpServer(record.name);
                }, 200);
            } catch (Exception e) {
                String message = e.getMessage() == null ? e.toString() : e.getMessage();
                runOnUiThread(() -> setStatus("OAuth sign-in failed: " + message, true));
            } finally {
                if (loopback != null && !loopback.isClosed()) {
                    try { loopback.close(); } catch (Exception ignored) {}
                }
            }
        }, "mcp-oauth-signin").start();
    }

    /** Accepts one browser redirect on the loopback socket; offers the full
     * callback URL to the queue and answers the browser. */
    private Thread spawnLoopbackListener(ServerSocket loopback, SynchronousQueue<String> codeQueue, String expectedState) {
        return new Thread(() -> {
            try {
                loopback.setSoTimeout(5 * 60 * 1000);
                try (Socket socket = loopback.accept()) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    String requestLine = reader.readLine();
                    String body = "<html><body><h2>Authorization received.</h2>"
                        + "<p>You can close this tab and return to Termux.</p></body></html>";
                    byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\n"
                        + "Content-Type: text/html; charset=utf-8\r\n"
                        + "Content-Length: " + bodyBytes.length + "\r\n"
                        + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().write(bodyBytes);
                    socket.getOutputStream().flush();
                    if (requestLine != null && requestLine.contains("/callback")) codeQueue.offer(requestLine);
                }
            } catch (Exception ignored) {
                // Timed out or socket closed — the paste fallback still works.
            }
        }, "mcp-oauth-loopback");
    }

    /** Fallback for browsers that block localhost redirects: the user copies
     * the redirected URL (or just the code) and pastes it here. */
    private void showOAuthPasteFallback(SynchronousQueue<String> codeQueue) {
        EditText paste = new EditText(this);
        paste.setSingleLine(true);
        paste.setHint("Paste the redirected URL (or the code)");
        new MaterialAlertDialogBuilder(this)
            .setTitle("If the browser didn't return automatically")
            .setMessage("Copy the address your browser ended up on after signing in, and paste it here. You can also paste just the code= value.")
            .setView(paste)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                String value = paste.getText().toString().trim();
                if (!value.isEmpty()) codeQueue.offer(value);
            })
            .show();
    }

    @Nullable
    private static String extractOAuthParam(String candidate, String param) {
        try {
            if (candidate.contains(" ")) candidate = candidate.substring(candidate.indexOf(' ') + 1);
            if (candidate.contains("HTTP/")) candidate = candidate.substring(0, candidate.indexOf("HTTP/")).trim();
            String query = candidate.contains("?") ? candidate.substring(candidate.indexOf('?') + 1) : candidate;
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) continue;
                if (URLDecoder.decode(pair.substring(0, eq), "UTF-8").equals(param))
                    return URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String base64UrlRandom(int byteCount) {
        byte[] bytes = new byte[byteCount];
        new SecureRandom().nextBytes(bytes);
        return base64Url(bytes);
    }

    private static String base64Url(byte[] bytes) {
        return android.util.Base64.encodeToString(bytes, android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP);
    }

    // ------------------------------------------------------------------
    // Skill installation: download archive → extract to a quarantine dir →
    // guard scan → user confirm → move into $HOME/.khabeer/skills
    // ------------------------------------------------------------------

    private void showSkillInstallDialog() {
        EditText urlInput = new EditText(this);
        urlInput.setSingleLine(true);
        urlInput.setHint("https://github.com/owner/repo/archive/refs/heads/main.zip");
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        new MaterialAlertDialogBuilder(this)
            .setTitle("Install skill from URL")
            .setMessage("Point this at a .zip or .tar.gz containing one or more skills (folders with a SKILL.md inside). "
                + "The archive is downloaded, extracted to a quarantine directory, scanned for dangerous patterns, "
                + "and shown to you before anything is installed.")
            .setView(urlInput)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Download & scan", (dialog, which) -> {
                String url = urlInput.getText().toString().trim();
                if (!url.startsWith("http")) {
                    showError("Enter an http(s) URL to a .zip or .tar.gz.");
                    return;
                }
                setStatus("Downloading skill archive…", false);
                new Thread(() -> installSkillsFromUrl(url), "skill-install").start();
            })
            .show();
    }

    private void installSkillsFromUrl(String url) {
        File quarantine = new File(getCacheDir(), "skill_install");
        try {
            deleteDirectory(quarantine);
            if (!url.startsWith("http")) throw new IllegalStateException("Not an http(s) URL.");
            File archive = downloadArchive(url, new File(getCacheDir(), "skill_install_download"));
            byte[] magic = new byte[2];
            try (InputStream in = new FileInputStream(archive)) {
                if (in.read(magic) != 2) throw new IllegalStateException("Downloaded file is empty.");
            }
            if (magic[0] == 'P' && magic[1] == 'K') extractZip(archive, quarantine);
            else if ((magic[0] & 0xFF) == 0x1f && (magic[1] & 0xFF) == 0x8b) extractTarGz(archive, quarantine);
            else throw new IllegalStateException("Unsupported archive type (expected .zip or .tar.gz).");

            List<File> candidates = new ArrayList<>();
            collectSkillCandidates(quarantine, candidates, 0);
            if (candidates.isEmpty()) throw new IllegalStateException("No SKILL.md found in the archive.");
            List<AiSkillRegistry.ScanReport> reports = new ArrayList<>();
            for (File candidate : candidates) reports.add(AiSkillRegistry.scanSkillDir(candidate));
            runOnUiThread(() -> showSkillInstallConfirm(candidates, reports));
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            runOnUiThread(() -> setStatus("Skill install failed: " + message, true));
            deleteDirectory(quarantine);
        }
    }

    private File downloadArchive(String url, File dest) throws Exception {
        HttpURLConnection connection;
        String current = url;
        for (int redirects = 0; redirects < 5; redirects++) {
            connection = (HttpURLConnection) new URL(current).openConnection();
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(30_000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "termux-ai/1.0 (skill install)");
            int code = connection.getResponseCode();
            if (code >= 300 && code < 400) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (TextUtils.isEmpty(location)) throw new IllegalStateException("Redirect without a Location header.");
                current = location;
                continue;
            }
            try (InputStream in = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
                 FileOutputStream out = new FileOutputStream(dest)) {
                if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code + " while downloading.");
                byte[] buffer = new byte[8192];
                long total = 0;
                int read;
                while ((read = in.read(buffer)) > 0) {
                    total += read;
                    if (total > 50L * 1024 * 1024) throw new IllegalStateException("Archive exceeds the 50 MB limit.");
                    out.write(buffer, 0, read);
                }
            } finally {
                connection.disconnect();
            }
            return dest;
        }
        throw new IllegalStateException("Too many redirects.");
    }

    private void extractZip(File archive, File target) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(new FileInputStream(archive)))) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                File out = new File(target, entry.getName());
                if (!out.getCanonicalPath().startsWith(target.getCanonicalPath() + File.separator)
                    && !out.getCanonicalPath().equals(target.getCanonicalPath()))
                    throw new IllegalStateException("Archive entry escapes the extraction directory: " + entry.getName());
                if (entry.isDirectory()) {
                    out.mkdirs();
                    continue;
                }
                out.getParentFile().mkdirs();
                try (BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(out))) {
                    int read;
                    while ((read = zip.read(buffer)) > 0) output.write(buffer, 0, read);
                }
                zip.closeEntry();
            }
        }
    }

    private void extractTarGz(File archive, File target) throws Exception {
        try (DataInputStream tar = new DataInputStream(new BufferedInputStream(new GZIPInputStream(new FileInputStream(archive), 8192)))) {
            byte[] header = new byte[512];
            String pendingLongName = null;
            while (true) {
                try {
                    tar.readFully(header);
                } catch (java.io.EOFException eof) {
                    break;
                }
                if (header[0] == 0) break;
                String name = pendingLongName != null ? pendingLongName : readTarString(header, 0, 100);
                pendingLongName = null;
                long size = readTarSize(header, 124);
                int type = header[156];
                if (type == 'L') { // GNU long name
                    byte[] data = new byte[(int) size];
                    tar.readFully(data);
                    pendingLongName = new String(data, StandardCharsets.UTF_8).trim();
                    continue;
                }
                if (type == 'x' || type == 'g') { // pax headers — skip
                    skipFully(tar, (size + 511) / 512 * 512);
                    continue;
                }
                if (type == '5' || name.endsWith("/")) {
                    new File(target, sanitizeTarName(name)).mkdirs();
                    skipFully(tar, (size + 511) / 512 * 512);
                    continue;
                }
                if (type == 0 || type == '0') {
                    File out = new File(target, sanitizeTarName(name));
                    if (!out.getCanonicalPath().startsWith(target.getCanonicalPath() + File.separator))
                        throw new IllegalStateException("Archive entry escapes the extraction directory: " + name);
                    out.getParentFile().mkdirs();
                    try (BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(out))) {
                        byte[] buffer = new byte[8192];
                        long remaining = size;
                        while (remaining > 0) {
                            int chunk = (int) Math.min(buffer.length, remaining);
                            tar.readFully(buffer, 0, chunk);
                            output.write(buffer, 0, chunk);
                            remaining -= chunk;
                        }
                    }
                    long padding = (512 - (size % 512)) % 512;
                    if (padding > 0) skipFully(tar, padding);
                    continue;
                }
                skipFully(tar, (size + 511) / 512 * 512); // links, devices, etc.
            }
        }
    }

    private static void skipFully(DataInputStream stream, long count) throws Exception {
        while (count > 0) {
            int skipped = stream.skipBytes((int) Math.min(count, Integer.MAX_VALUE));
            if (skipped <= 0) throw new java.io.EOFException("Unexpected end of tar archive");
            count -= skipped;
        }
    }

    private static String readTarString(byte[] header, int offset, int length) {
        int end = offset;
        while (end < offset + length && header[end] != 0) end++;
        return new String(header, offset, end - offset, StandardCharsets.UTF_8).trim();
    }

    private static long readTarSize(byte[] header, int offset) {
        long size = 0;
        boolean started = false;
        for (int i = offset; i < offset + 12; i++) {
            byte b = header[i];
            if (b == 0 || b == ' ') {
                if (started) break;
                continue;
            }
            started = true;
            size = (size << 3) + (b - '0');
        }
        return size;
    }

    private static String sanitizeTarName(String name) {
        return name.replace('\\', '/').replaceFirst("^\\./", "").replaceFirst("^/", "");
    }

    private void collectSkillCandidates(File dir, List<File> out, int depth) {
        if (depth > 3 || out.size() >= 10) return;
        if (new File(dir, "SKILL.md").isFile()) {
            out.add(dir);
            return; // a skill dir is not scanned further down
        }
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) if (child.isDirectory()) collectSkillCandidates(child, out, depth + 1);
    }

    private void showSkillInstallConfirm(List<File> candidates, List<AiSkillRegistry.ScanReport> reports) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(4), dp(8), dp(4), 0);
        final List<File> installable = new ArrayList<>();
        final List<File> blocked = new ArrayList<>();
        List<android.widget.CheckBox> checks = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            File candidate = candidates.get(i);
            AiSkillRegistry.ScanReport report = reports.get(i);
            boolean dangerous = "dangerous".equals(report.verdict);
            if (dangerous) blocked.add(candidate);
            else installable.add(candidate);
            android.widget.CheckBox check = new android.widget.CheckBox(this);
            check.setText(candidate.getName()
                + (dangerous ? " — BLOCKED (dangerous scan)"
                : " — " + report.verdict + (report.findings.isEmpty() ? "" : " (" + report.findings.size() + " findings)")));
            check.setTextColor(color(dangerous ? R.color.ai_error : R.color.ai_text));
            check.setEnabled(!dangerous);
            check.setChecked(!dangerous && "safe".equals(report.verdict));
            box.addView(check);
            checks.add(check);
            if (!report.findings.isEmpty()) {
                TextView detail = new TextView(this);
                detail.setText(report.reportText());
                detail.setTextColor(color(R.color.ai_text_muted));
                detail.setTextSize(11);
                detail.setPadding(dp(24), 0, 0, dp(6));
                box.addView(detail);
            }
        }
        new MaterialAlertDialogBuilder(this)
            .setTitle("Install " + installable.size() + " skill" + (installable.size() == 1 ? "" : "s") + "?")
            .setMessage(blocked.isEmpty()
                ? "Scan complete. Install the checked skills into $HOME/.khabeer/skills?"
                : blocked.size() + " skill(s) were BLOCKED by the security scan and cannot be installed.")
            .setView(box)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Install", (dialog, which) -> {
                StringBuilder installed = new StringBuilder();
                List<String> failures = new ArrayList<>();
                for (int i = 0; i < candidates.size(); i++) {
                    if (!checks.get(i).isChecked()) continue;
                    String name = candidates.get(i).getName().replaceAll("[^A-Za-z0-9._-]", "-").toLowerCase();
                    String error = AiSkillRegistry.installSkill(candidates.get(i), name);
                    if (error != null) failures.add(name + ": " + error);
                    else installed.append(name).append(", ");
                }
                deleteDirectory(new File(getCacheDir(), "skill_install"));
                AiSkillRegistry.invalidate();
                refreshExtensionsPage();
                if (failures.isEmpty()) setStatus("Installed: " + installed, false);
                else setStatus("Installed: " + installed + "Failed: " + TextUtils.join("; ", failures), true);
            })
            .show();
    }

    private static void deleteDirectory(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] children = dir.listFiles();
        if (children != null) for (File child : children) deleteDirectory(child);
        dir.delete();
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

    /** Home is configuration only: credentials, endpoints, and default
     * models per provider. No chat selection and no session creation
     * happen here — those live in the New Session flow. */
    private void showFeaturedProviders() {
        mShowingProviderDirectory = false;
        mNsActive = false;
        setMorePageVisible(false);
        mProviderGrid.removeAllViews();
        mHomeTitle.setText("Configure");
        mHomeBody.setText("Set up providers below: API keys, sign-ins, endpoints, and default models. Start chats from Sessions or Chat.");
        for (AiProviderProfile profile : AiProviderProfile.PROFILES) {
            mProviderGrid.addView(createProviderTile(profile));
        }
        mHomePanel.setVisibility(View.VISIBLE);
        mSetupPanel.setVisibility(View.GONE);
        mChatPage.setVisibility(View.GONE);
        if (mSessionsPage != null) mSessionsPage.setVisibility(View.GONE);
        if (mOpenCodePage != null) mOpenCodePage.setVisibility(View.GONE);
        if (mExtensionsPage != null) mExtensionsPage.setVisibility(View.GONE);
        mChatTitle.setText("khabeer");
    }

    private void showProviderDirectory() {
        mShowingProviderDirectory = true;
        setMorePageVisible(false);
        mProviderGrid.removeAllViews();
        mHomeTitle.setText(mPickingSessionProvider ? "Choose a provider for this session" : "More providers");
        mHomeBody.setText(mPickingSessionProvider
            ? "Pick a provider, configure it if needed, then Continue to bind it to this session only."
            : "khabeer provider registry. Adapters marked “coming next” are listed honestly until their native request/auth flow is implemented.");
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

    // ------------------------------------------------------------------
    // New Session flow (also serves in-chat provider switching when
    // mNsForSession is true): last-used shortcut → provider → OpenCode
    // route → live model list → chat. Every step has a way forward and a
    // way back; nothing here configures credentials (that is Home).
    // ------------------------------------------------------------------

    private void showNewSessionPage(boolean forSession) {
        mNsActive = true;
        mNsForSession = forSession;
        mNsStep = 0;
        mNsProfile = null;
        mNsRoute = null;
        renderNsStep();
    }

    private void renderNsStep() {
        mShowingProviderDirectory = false;
        setMorePageVisible(false);
        mHomePanel.setVisibility(View.VISIBLE);
        mSetupPanel.setVisibility(View.GONE);
        mChatPage.setVisibility(View.GONE);
        if (mSessionsPage != null) mSessionsPage.setVisibility(View.GONE);
        if (mOpenCodePage != null) mOpenCodePage.setVisibility(View.GONE);
        if (mExtensionsPage != null) mExtensionsPage.setVisibility(View.GONE);
        mChatTitle.setText(mNsForSession ? "Switch provider" : "New session");
        mProviderGrid.removeAllViews();
        if (mNsStep == 1) renderNsRoutes();
        else if (mNsStep == 2) renderNsModels();
        else renderNsProviders();
    }

    private void renderNsProviders() {
        mHomeTitle.setText(mNsForSession ? "Switch provider" : "New session");
        mHomeBody.setText(mNsForSession
            ? "Pick a configured provider, then a model. The transcript stays; the next turn uses the new setup."
            : "Reuse your last setup or pick a configured provider, then a model.");
        if (!mNsForSession) addNsLastUsedCard();
        TextView section = nsSectionLabel("PROVIDERS");
        mProviderGrid.addView(section);
        boolean any = false;
        for (AiProviderProfile profile : AiProviderProfile.PROFILES) {
            if (profile.terminalOnly || !profile.implemented) continue;
            mProviderGrid.addView(createProviderTile(profile));
            any = true;
        }
        if (!any) {
            mProviderGrid.addView(nsNote("No provider is configured yet."));
        }
        MaterialButton home = smallMemoryButton("Configure providers on Home");
        home.setOnClickListener(v -> {
            mNsActive = false;
            showFeaturedProviders();
        });
        mProviderGrid.addView(home);
    }

    private void addNsLastUsedCard() {
        String providerId = mProviderConfig == null ? "" : mProviderConfig.getLastProviderId();
        String model = mProviderConfig == null ? "" : mProviderConfig.getLastModel();
        String route = mProviderConfig == null ? "" : mProviderConfig.getLastRoute();
        AiProviderProfile profile = AiProviderProfile.find(providerId);
        if (profile == null || TextUtils.isEmpty(model)) return;
        if (!mProviderConfig.isProviderConfigured(profile)) return;
        if ("opencode".equals(profile.id) && !TextUtils.isEmpty(route) && !mProviderConfig.isRouteConfigured(route)) return;
        String label = profile.name + " · " + model
            + ("opencode".equals(profile.id) && !TextUtils.isEmpty(route)
                ? " (" + AiProviderConfig.ocRouteLabel(route) + ")" : "");
        MaterialCardView card = new MaterialCardView(this);
        card.setCardBackgroundColor(color(R.color.ai_surface_elevated));
        card.setStrokeColor(color(R.color.ai_accent));
        card.setStrokeWidth(dp(2));
        card.setRadius(dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(10));
        card.setLayoutParams(lp);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.addView(box);
        TextView text = new TextView(this);
        text.setText("Last used\n" + label);
        text.setTextColor(color(R.color.ai_text));
        text.setTextSize(13);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        box.addView(text, tp);
        MaterialButton start = smallMemoryButton("Start chat");
        start.setOnClickListener(v -> {
            if ("opencode".equals(profile.id) && TextUtils.isEmpty(route)) {
                mNsProfile = profile;
                mNsStep = 1;
                renderNsStep();
                return;
            }
            nsModelPicked(profile, TextUtils.isEmpty(route) ? null : route, model);
        });
        box.addView(start);
        mProviderGrid.addView(card);
    }

    private TextView nsSectionLabel(String text) {
        TextView section = new TextView(this);
        section.setText(text);
        section.setTextColor(color(R.color.ai_text));
        section.setTextSize(12);
        section.setTypeface(Typeface.DEFAULT_BOLD);
        section.setLetterSpacing(0.08f);
        section.setPadding(0, dp(8), 0, dp(10));
        return section;
    }

    private TextView nsNote(String text) {
        TextView note = new TextView(this);
        note.setText(text);
        note.setTextColor(color(R.color.ai_text_muted));
        note.setTextSize(13);
        note.setPadding(0, dp(6), 0, dp(16));
        return note;
    }

    private void nsProviderTapped(AiProviderProfile profile) {
        if (profile == null || profile.terminalOnly || !profile.implemented) {
            showError("This provider is not available for chat yet.");
            return;
        }
        if (!mProviderConfig.isProviderConfigured(profile)) {
            Toast.makeText(this, profile.name + " needs configuration first.", Toast.LENGTH_SHORT).show();
            openProviderConfig(profile);
            return;
        }
        if ("opencode".equals(profile.id)) {
            mNsProfile = profile;
            mNsStep = 1;
            renderNsStep();
            return;
        }
        mNsProfile = profile;
        mNsRoute = null;
        mNsStep = 2;
        renderNsStep();
    }

    private void renderNsRoutes() {
        mHomeTitle.setText("OpenCode route");
        mHomeBody.setText("Each route has its own key and models. Pick the route this session will use.");
        mNsProfile = AiProviderProfile.find("opencode");
        mProviderGrid.addView(nsBackRow("Providers", () -> {
            mNsStep = 0;
            mNsProfile = null;
            renderNsStep();
        }));
        String[] routes = new String[]{AiProviderConfig.OC_ROUTE_FREE, AiProviderConfig.OC_ROUTE_ZEN, AiProviderConfig.OC_ROUTE_GO};
        for (String route : routes) {
            boolean ready = mProviderConfig.isRouteConfigured(route);
            String status = ready ? "✓ " + mProviderConfig.getOpenCodeRouteModel(route) : "Needs an API key";
            MaterialCardView card = nsRowCard(AiProviderConfig.ocRouteLabel(route), status, ready);
            final String tapped = route;
            card.setOnClickListener(v -> nsRouteTapped(tapped));
            mProviderGrid.addView(card);
        }
    }

    private void nsRouteTapped(String route) {
        if (!mProviderConfig.isRouteConfigured(route)) {
            Toast.makeText(this, "OpenCode " + AiProviderConfig.ocRouteLabel(route) + " needs an API key — opening setup.", Toast.LENGTH_LONG).show();
            showOpenCodeSetupPage(false);
            return;
        }
        mNsRoute = route;
        mNsStep = 2;
        renderNsStep();
    }

    private void renderNsModels() {
        final AiProviderProfile profile = mNsProfile;
        if (profile == null) {
            mNsStep = 0;
            renderNsStep();
            return;
        }
        String where = profile.name + ("opencode".equals(profile.id) && mNsRoute != null
            ? " · " + AiProviderConfig.ocRouteLabel(mNsRoute) : "");
        mHomeTitle.setText("Choose a model");
        mHomeBody.setText("Live models from " + where + ". Pick one to continue.");
        mProviderGrid.addView(nsBackRow("opencode".equals(profile.id) ? "Routes" : "Providers", () -> {
            if ("opencode".equals(profile.id)) mNsStep = 1;
            else {
                mNsStep = 0;
                mNsProfile = null;
            }
            renderNsStep();
        }));
        TextView loading = nsNote("Loading models from " + where + "…");
        mProviderGrid.addView(loading);
        final String route = mNsRoute;
        new Thread(() -> {
            List<String> models;
            try {
                models = nsFetchModels(profile, route);
            } catch (Exception e) {
                String message = e.getMessage() == null ? e.toString() : e.getMessage();
                runOnUiThread(() -> {
                    if (!mNsActive || mNsStep != 2 || mNsProfile != profile) return;
                    renderNsModelsError(message);
                });
                return;
            }
            final List<String> result = models;
            runOnUiThread(() -> {
                if (!mNsActive || mNsStep != 2 || mNsProfile != profile) return;
                if (result == null || result.isEmpty()) renderNsModelsError("The provider returned no models.");
                else renderNsModelList(result);
            });
        }, "khabeer-ns-models").start();
    }

    private List<String> nsFetchModels(AiProviderProfile profile, @Nullable String route) throws Exception {
        if ("openai-codex".equals(profile.id)) {
            String token = mProviderConfig.getProviderToken("openai-codex");
            return AiModelCatalog.fetch(profile, ProviderLogin.CODEX_BASE_URL, token,
                ProviderLogin.codexAccountId(token));
        }
        String baseUrl = mProviderConfig.getBaseUrl(profile);
        String apiKey = mProviderConfig.resolveCredential(profile);
        if ("opencode".equals(profile.id) && !TextUtils.isEmpty(route)) {
            baseUrl = AiProviderConfig.ocRouteUrl(route);
            String routeKey = mProviderConfig.getOpenCodeRouteKey(route);
            if (!TextUtils.isEmpty(routeKey)) apiKey = routeKey;
        }
        return AiModelCatalog.fetch(profile, baseUrl, apiKey);
    }

    private void renderNsModelList(List<String> models) {
        mProviderGrid.removeAllViews();
        renderNsModelsHeader();
        for (String model : models) {
            MaterialCardView card = nsRowCard(model, null, true);
            card.setOnClickListener(v -> nsModelPicked(mNsProfile, mNsRoute, model));
            mProviderGrid.addView(card);
        }
        MaterialButton manual = smallMemoryButton("Enter model ID manually…");
        manual.setOnClickListener(v -> showNsManualModelDialog());
        mProviderGrid.addView(manual);
    }

    private void renderNsModelsHeader() {
        final AiProviderProfile profile = mNsProfile;
        String where = profile == null ? "" : profile.name + ("opencode".equals(profile.id) && mNsRoute != null
            ? " · " + AiProviderConfig.ocRouteLabel(mNsRoute) : "");
        mHomeTitle.setText("Choose a model");
        mHomeBody.setText("Live models from " + where + ". Pick one to continue.");
        mProviderGrid.addView(nsBackRow("opencode".equals(profile == null ? "" : profile.id) ? "Routes" : "Providers", () -> {
            if (profile != null && "opencode".equals(profile.id)) mNsStep = 1;
            else {
                mNsStep = 0;
                mNsProfile = null;
            }
            renderNsStep();
        }));
    }

    private void renderNsModelsError(String message) {
        mProviderGrid.removeAllViews();
        renderNsModelsHeader();
        mProviderGrid.addView(nsNote("Couldn’t load live models: " + message));
        MaterialButton retry = smallMemoryButton("Retry");
        retry.setOnClickListener(v -> renderNsStep());
        mProviderGrid.addView(retry);
        MaterialButton manual = smallMemoryButton("Enter model ID manually…");
        manual.setOnClickListener(v -> showNsManualModelDialog());
        mProviderGrid.addView(manual);
    }

    private void showNsManualModelDialog() {
        final AiProviderProfile profile = mNsProfile;
        if (profile == null) return;
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Model ID");
        input.setTextColor(color(R.color.ai_text));
        input.setHintTextColor(color(R.color.ai_text_dim));
        input.setBackgroundColor(color(R.color.ai_surface_muted));
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        new MaterialAlertDialogBuilder(this)
            .setTitle("Model ID")
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.ai_continue, (dialog, which) -> {
                String id = input.getText() == null ? "" : input.getText().toString().trim();
                if (TextUtils.isEmpty(id)) {
                    showError("Enter a model ID.");
                    return;
                }
                nsModelPicked(profile, mNsRoute, id);
            })
            .show();
    }

    private View nsBackRow(String label, Runnable back) {
        MaterialButton b = smallMemoryButton("‹ " + label);
        b.setOnClickListener(v -> back.run());
        return b;
    }

    private MaterialCardView nsRowCard(String title, @Nullable String status, boolean ready) {
        MaterialCardView card = new MaterialCardView(this);
        card.setCardBackgroundColor(color(R.color.ai_surface));
        card.setStrokeColor(color(R.color.ai_border));
        card.setStrokeWidth(dp(1));
        card.setRadius(dp(16));
        card.setClickable(true);
        card.setFocusable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(10));
        card.setLayoutParams(lp);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.addView(box);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(color(ready ? R.color.ai_text : R.color.ai_text_muted));
        t.setTextSize(14);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(t);
        if (!TextUtils.isEmpty(status)) {
            TextView s = new TextView(this);
            s.setText(status);
            s.setTextColor(color(ready ? R.color.ai_success : R.color.ai_warning));
            s.setTextSize(11);
            box.addView(s);
        }
        return card;
    }

    /** Model chosen in the flow: start a session, or rebind the live one. */
    private void nsModelPicked(AiProviderProfile profile, @Nullable String route, String model) {
        if (profile == null || TextUtils.isEmpty(model)) return;
        if (mRuntimeService == null || !mRuntimeBound) {
            showError("Native runtime is still starting.");
            return;
        }
        mSelectedProfile = profile;
        mSelectedModel = model;
        mSelectedRoute = route;
        syncControlLabels();
        if (mNsForSession) {
            mRuntimeService.setSessionProvider(profile.id);
            if ("opencode".equals(profile.id) && !TextUtils.isEmpty(route)) mRuntimeService.setSessionRoute(route);
            mRuntimeService.setSessionModel(model);
            mNsActive = false;
            showChatPage();
            return;
        }
        // New sessions start on the first typed message: stage the fully
        // resolved choice and open an empty chat. The send path resolves
        // route credentials and passes the route to startAgent (F1).
        mNsActive = false;
        mChatMessages.removeAllViews();
        mStreamingAgentBubble = null;
        hideThinkingBubble();
        clearReasoningBuffer();
        mStopButton.setVisibility(View.GONE);
        mEmptyChatHint.setVisibility(View.VISIBLE);
        mSuggestionStrip.setVisibility(View.VISIBLE);
        setStatus(profile.name + " · " + model + " ready. Type your first message.", false);
        showChatPage();
    }

    /** Fully resolved endpoint + credential for a flow choice. Never falls
     * back to profile leftovers for OpenCode routes (F1). */
    private String[] nsCredentials(AiProviderProfile profile, @Nullable String route) {
        if ("opencode".equals(profile.id) && !TextUtils.isEmpty(route)) {
            String key = mProviderConfig.getOpenCodeRouteKey(route);
            if (TextUtils.isEmpty(key)) key = mProviderConfig.resolveCredential(profile);
            return new String[]{AiProviderConfig.ocRouteUrl(route), key};
        }
        return new String[]{mProviderConfig.getBaseUrl(profile), mProviderConfig.resolveCredential(profile)};
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
            if (mNsActive) nsProviderTapped(profile);
            else if (mPickingSessionProvider) openSessionProviderSetup(profile);
            else openProviderConfig(profile);
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
        body.setText(mProviderConfig == null ? shortProviderDesc(profile) : mProviderConfig.configuredSummary(profile));
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
            openProviderConfig(profile);
            if (mDrawer != null) mDrawer.closeDrawer(findViewById(R.id.ai_drawer_panel));
        });
        return row;
    }

    /** Home-level configuration entry: opens a provider's config surface
     * without selecting anything for chat. Chat selection lives only in
     * the New Session flow and the in-chat switch flow. */
    private void openProviderConfig(AiProviderProfile profile) {
        if (profile == null) return;
        mSelectedProfile = profile;
        syncControlLabels();
        mSelectedProviderIcon.setImageResource(iconForProvider(mSelectedProfile.id));
        mSelectedProviderTitle.setText(mSelectedProfile.name);
        mSelectedProviderBody.setText(mSelectedProfile.description);
        if ("opencode".equals(mSelectedProfile.id)) {
            showOpenCodeSetupPage(false);
            return;
        }
        if ("openai-codex".equals(mSelectedProfile.id)) {
            showCodexLoginDialog(false);
            return;
        }
        if ("nous".equals(mSelectedProfile.id) || "github-copilot".equals(mSelectedProfile.id)
            || "qwen-oauth".equals(mSelectedProfile.id)) {
            showProviderLoginChooser(mSelectedProfile, false);
            return;
        }
        if ("anthropic".equals(mSelectedProfile.id) || "xai".equals(mSelectedProfile.id)) {
            showProviderLoginChooser(mSelectedProfile, false);
            return;
        }
        showSetupPage();
    }

    private void selectProvider(@Nullable AiProviderProfile profile, boolean userInitiated) {
        mSelectedProfile = profile == null ? AiProviderProfile.firstAgentProfile() : profile;
        mProviderConfig.setSelectedProviderId(mSelectedProfile.id);
        mSelectedModel = mProviderConfig.getModel(mSelectedProfile);
        // F2 fix: an OpenCode default is its selected route's model, not the
        // profile-level leftover.
        if ("opencode".equals(mSelectedProfile.id)) {
            String routeModel = mProviderConfig.getOpenCodeRouteModel(mProviderConfig.getOpenCodeSelectedRoute());
            if (mSelectedModel == null || mSelectedModel.equals("gpt-4o") || mSelectedModel.isEmpty()
                || mSelectedModel.equals(mSelectedProfile.defaultModel)) mSelectedModel = routeModel;
        }
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
        if ("openai-codex".equals(mSelectedProfile.id)) {
            if (userInitiated) showCodexLoginDialog(false);
            return;
        }
        if ("nous".equals(mSelectedProfile.id) || "github-copilot".equals(mSelectedProfile.id)
            || "qwen-oauth".equals(mSelectedProfile.id)) {
            if (userInitiated) showProviderLoginChooser(mSelectedProfile, false);
            return;
        }
        if ("anthropic".equals(mSelectedProfile.id) || "xai".equals(mSelectedProfile.id)) {
            if (userInitiated) showProviderLoginChooser(mSelectedProfile, false);
            return;
        }
        if (userInitiated || mSetupPanel.getVisibility() == View.VISIBLE) showSetupPage();
    }

    /** Providers that support both an API key and a subscription login get a
     *  chooser; login-only providers go straight to their flow. */
    private void showProviderLoginChooser(AiProviderProfile profile, boolean sessionMode) {
        boolean loginOnly = "nous".equals(profile.id) || "github-copilot".equals(profile.id) || "qwen-oauth".equals(profile.id);
        if (loginOnly) {
            if ("qwen-oauth".equals(profile.id)) showQwenPasteDialog(profile, sessionMode);
            else showDeviceCodeLogin(profile, sessionMode);
            return;
        }
        String loginLabel = "anthropic".equals(profile.id) ? "Sign in with Claude (Pro/Max)" : "Sign in with " + profile.name;
        new MaterialAlertDialogBuilder(this)
            .setTitle(profile.name)
            .setItems(new String[]{
                "Use an API key",
                loginLabel,
                "Cancel"
            }, (dialog, which) -> {
                if (which == 0) showApiKeyDialog();
                else if (which == 1) {
                    if ("anthropic".equals(profile.id)) showAnthropicPkceLogin(profile, sessionMode);
                    else showDeviceCodeLogin(profile, sessionMode);
                }
            })
            .show();
    }

    private void showSetupPage() {
        setMorePageVisible(false);
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
            showError(mSelectedProfile.name + " is registered from khabeer, but its native mobile adapter is not implemented yet.");
            showSetupPage();
            return;
        }
        if (mSelectedProfile.apiKeyAuth && !mProviderConfig.hasApiKey(mSelectedProfile)) {
            showError("Add an API key before opening chat.");
            showSetupPage();
            return;
        }
        if (validateWorkspace() == null) return;
        setMorePageVisible(false);
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
        mChatTitle.setText("khabeer");
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
        if ("/retry".equals(prompt)) {
            mPromptInput.setText("");
            if (mRuntimeService != null) mRuntimeService.retryLastTurn();
            return;
        }
        if ("/undo".equals(prompt) || prompt.startsWith("/undo ")) {
            mPromptInput.setText("");
            handleUndoCommand(prompt);
            return;
        }
        if (!mAttachedPaths.isEmpty()) prompt += "\n\nAttached files:\n- " + TextUtils.join("\n- ", mAttachedPaths);
        AiProviderProfile profile = mSelectedProfile;
        String workspace = validateWorkspace();
        if (profile == null || workspace == null) return;
        // Slash-skill invocation: "/skill-name extra text" loads the skill's
        // full SKILL.md into the outgoing message (khabeer skill_commands).
        // Anything that doesn't resolve to an installed skill is sent as-is.
        String typedPrompt = prompt;
        String[] invocation = AiSkillRegistry.buildSkillInvocationMessage(prompt);
        if (invocation != null && mAttachedPaths.isEmpty()) {
            prompt = invocation[0];
            setStatus("Loaded skill" + (invocation[1].contains(",") ? "s" : "") + ": " + invocation[1], false);
        }
        addUserMessage(typedPrompt);
        mUserScrolledUp = false;
        mPromptInput.setText("");
        mEmptyChatHint.setVisibility(View.GONE);
        mSuggestionStrip.setVisibility(View.GONE);
        mStreamingAgentBubble = null;
        mStopButton.setVisibility(View.VISIBLE);
        if (mHasNativeSession) {
            // Session-authoritative: provider/model/credentials resolve from
            // the session row inside the service (khabeer session model).
            mRuntimeService.sendPrompt(prompt, clean(mSelectedEffort), mSelectedApproval);
        } else {
            // F1 fix: resolve route endpoint/key/model here so the FIRST
            // turn already runs on the right route — never profile leftovers.
            String route = null;
            if ("opencode".equals(profile.id)) {
                route = TextUtils.isEmpty(mSelectedRoute)
                    ? mProviderConfig.getOpenCodeSelectedRoute() : mSelectedRoute;
            }
            String[] creds = nsCredentials(profile, route);
            String model = mSelectedModel;
            if (TextUtils.isEmpty(model)) {
                model = "opencode".equals(profile.id)
                    ? mProviderConfig.getOpenCodeRouteModel(route) : profile.defaultModel;
            }
            mRuntimeService.startAgent(profile.id, creds[0], creds[1], workspace, prompt, model,
                clean(mSelectedEffort), mSelectedApproval, route);
        }
    }

    /** /undo [N]: back up N turns, then echo the removed text as a plain
     * system bubble (never persisted) so it can be copied and resent. */
    private void handleUndoCommand(String prompt) {
        int n = 1;
        String arg = prompt.length() > 5 ? prompt.substring(5).trim().split("\\s+")[0] : "";
        if (!TextUtils.isEmpty(arg)) {
            try {
                n = Math.max(1, Integer.parseInt(arg));
            } catch (Exception e) {
                showError("Usage: /undo [turns]. Example: /undo 2");
                return;
            }
        }
        if (mRuntimeService == null || !mRuntimeBound) {
            showError("Native runtime is still starting.");
            return;
        }
        JSONObject result = mRuntimeService.undoTurns(n);
        if (!result.optBoolean("success")) {
            showError(result.optString("error", "Undo failed."));
            return;
        }
        String removed = result.optString("target_text", "");
        if (removed.length() > 400) removed = removed.substring(0, 400) + "…";
        addSystemMessage("Undid " + result.optInt("turns_undone") + " turn(s) (" +
            result.optInt("rewound_count") + " messages hidden, kept for audit).\nRemoved: " + removed);
        setStatus("Undid " + result.optInt("turns_undone") + " turn(s).", false);
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
        // Codex model listing needs the account header — dedicated fetcher.
        if ("openai-codex".equals(profile.id)) {
            fetchCodexModelsAndPick(profile, false);
            return;
        }
        String resolvedBaseUrl = mProviderConfig.getBaseUrl(profile);
        String resolvedApiKey = mProviderConfig.resolveCredential(profile);
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
        }, "khabeer-model-catalog").start();
    }

    // ------------------------------------------------------------------
    // ChatGPT / Codex subscription sign-in (device code — khabeer port of
    // the reference codex login: request user code, user types it at
    // auth.openai.com/codex/device, poll, exchange, store Keystore secrets)
    // ------------------------------------------------------------------

    private volatile boolean mCodexLoginCancelled;

    private void showCodexLoginDialog(boolean sessionMode) {
        AiProviderProfile profile = AiProviderProfile.find("openai-codex");
        if (profile == null) return;
        mCodexLoginCancelled = false;
        boolean signedIn = mProviderConfig.hasProviderLogin("openai-codex");

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(10), dp(22), 0);

        TextView status = new TextView(this);
        status.setText(signedIn
            ? "Signed in to ChatGPT. You can re-authenticate or pick a Codex model."
            : "Sign in with your ChatGPT Plus/Pro account to use Codex models. A browser window opens; you type a short code.");
        status.setTextColor(color(R.color.ai_text));
        status.setTextSize(13);
        box.addView(status);

        TextView codeView = new TextView(this);
        codeView.setTypeface(Typeface.MONOSPACE);
        codeView.setTextSize(26);
        codeView.setLetterSpacing(0.2f);
        codeView.setTextColor(color(R.color.ai_accent));
        codeView.setPadding(0, dp(16), 0, dp(4));
        codeView.setVisibility(View.GONE);
        box.addView(codeView);

        TextView hint = new TextView(this);
        hint.setText("Open  " + ProviderLogin.CODEX_DEVICE_URL + "  and enter the code above.");
        hint.setTextColor(color(R.color.ai_text_muted));
        hint.setTextSize(11);
        hint.setVisibility(View.GONE);
        box.addView(hint);

        MaterialButton openBrowser = new MaterialButton(this);
        openBrowser.setText("Open " + ProviderLogin.CODEX_DEVICE_URL.replaceAll("https://", ""));
        openBrowser.setAllCaps(false);
        openBrowser.setTextColor(0xFFFFFFFF);
        openBrowser.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color(R.color.ai_accent)));
        openBrowser.setCornerRadius(dp(10));
        openBrowser.setEnabled(false);
        openBrowser.setAlpha(0.5f);
        LinearLayout.LayoutParams browserLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        browserLp.topMargin = dp(12);
        openBrowser.setLayoutParams(browserLp);
        openBrowser.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(ProviderLogin.CODEX_DEVICE_URL))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e) {
                showError("No browser available.");
            }
        });
        box.addView(openBrowser);

        MaterialButton chooseModel = new MaterialButton(this);
        chooseModel.setText("Choose Codex model");
        chooseModel.setAllCaps(false);
        chooseModel.setTextColor(color(R.color.ai_text));
        chooseModel.setStrokeColor(android.content.res.ColorStateList.valueOf(color(R.color.ai_border)));
        chooseModel.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color(R.color.ai_surface)));
        chooseModel.setCornerRadius(dp(10));
        LinearLayout.LayoutParams modelLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        modelLp.topMargin = dp(8);
        chooseModel.setLayoutParams(modelLp);
        chooseModel.setVisibility(signedIn ? View.VISIBLE : View.GONE);
        chooseModel.setOnClickListener(v -> fetchCodexModelsAndPick(profile, sessionMode));
        box.addView(chooseModel);

        MaterialButton signIn = new MaterialButton(this);
        signIn.setText(signedIn ? "Re-authenticate" : "Sign in with ChatGPT");
        signIn.setAllCaps(false);
        signIn.setTextColor(color(R.color.ai_text));
        signIn.setStrokeColor(android.content.res.ColorStateList.valueOf(color(R.color.ai_border)));
        signIn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color(R.color.ai_surface)));
        signIn.setCornerRadius(dp(10));
        LinearLayout.LayoutParams signInLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        signInLp.topMargin = dp(8);
        signIn.setLayoutParams(signInLp);
        box.addView(signIn);

        final androidx.appcompat.app.AlertDialog[] dialogHolder = new androidx.appcompat.app.AlertDialog[1];
        signIn.setOnClickListener(v -> {
            if (mCodexLoginCancelled) mCodexLoginCancelled = false;
            signIn.setEnabled(false);
            signIn.setText("Waiting for authorization…");
            status.setText("Requesting a device code…");
            status.setTextColor(color(R.color.ai_text_muted));
            new Thread(() -> {
                try {
                    JSONObject code = ProviderLogin.codexRequestDeviceCode();
                    String userCode = code.optString("user_code", "");
                    String deviceAuthId = code.optString("device_auth_id", "");
                    int interval = Math.max(3, code.optInt("interval", 5));
                    runOnUiThread(() -> {
                        codeView.setText(userCode);
                        codeView.setVisibility(View.VISIBLE);
                        hint.setVisibility(View.VISIBLE);
                        openBrowser.setEnabled(true);
                        openBrowser.setAlpha(1f);
                        status.setText("Type this code in the browser to authorize khabeer.");
                    });
                    long deadline = System.currentTimeMillis() + 15 * 60_000L;
                    while (System.currentTimeMillis() < deadline && !mCodexLoginCancelled) {
                        try { Thread.sleep(interval * 1000L); } catch (InterruptedException ie) { return; }
                        if (mCodexLoginCancelled) return;
                        JSONObject poll = ProviderLogin.codexPollDeviceCode(deviceAuthId, userCode);
                        if (!poll.optBoolean("authorized")) continue;
                        JSONObject tokens = ProviderLogin.codexExchange(
                            poll.optString("authorization_code"), poll.optString("code_verifier"));
                        String access = tokens.optString("access_token", "");
                        String refresh = tokens.optString("refresh_token", "");
                        if (TextUtils.isEmpty(access)) throw new Exception("Sign-in returned no access token.");
                        mProviderConfig.setProviderToken("openai-codex", access);
                        if (!TextUtils.isEmpty(refresh)) mProviderConfig.setProviderRefresh("openai-codex", refresh);
                        JSONObject state = new JSONObject();
                        String accountId = ProviderLogin.codexAccountId(access);
                        if (!TextUtils.isEmpty(accountId)) state.put("account_id", accountId);
                        state.put("obtained_at", System.currentTimeMillis());
                        mProviderConfig.setProviderState("openai-codex", state.toString());
                        if (mCodexLoginCancelled) return;
                        runOnUiThread(() -> {
                            status.setText("Signed in to ChatGPT ✓");
                            if (dialogHolder[0] != null && dialogHolder[0].isShowing()) dialogHolder[0].dismiss();
                            if (sessionMode) {
                                mRuntimeService.setSessionProvider(profile.id);
                                showChatPage();
                            } else {
                                showChatPage();
                            }
                            fetchCodexModelsAndPick(profile, sessionMode);
                            setStatus("Signed in to ChatGPT.", false);
                        });
                        return;
                    }
                    if (!mCodexLoginCancelled)
                        runOnUiThread(() -> {
                            status.setText("Timed out waiting for authorization — try again.");
                            status.setTextColor(color(R.color.ai_warning));
                            signIn.setEnabled(true);
                            signIn.setText("Sign in with ChatGPT");
                        });
                } catch (Exception e) {
                    final String message = e.getMessage() == null ? e.toString() : e.getMessage();
                    if (mCodexLoginCancelled) return;
                    runOnUiThread(() -> {
                        status.setText("Sign-in failed: " + message);
                        status.setTextColor(color(R.color.ai_warning));
                        signIn.setEnabled(true);
                        signIn.setText("Sign in with ChatGPT");
                    });
                }
            }, "codex-login").start();
        });

        dialogHolder[0] = new MaterialAlertDialogBuilder(this)
            .setTitle("ChatGPT (Codex) sign-in")
            .setView(box)
            .setNegativeButton(android.R.string.cancel, (dialog, which) -> mCodexLoginCancelled = true)
            .show();
    }

    private void fetchCodexModelsAndPick(AiProviderProfile profile, boolean sessionMode) {
        setStatus("Loading Codex models…", false);
        new Thread(() -> {
            try {
                String token = mProviderConfig.getProviderToken("openai-codex");
                List<String> models = AiModelCatalog.fetch(profile, ProviderLogin.CODEX_BASE_URL, token,
                    ProviderLogin.codexAccountId(token));
                runOnUiThread(() -> showModelListDialog(profile, models));
            } catch (Exception e) {
                final String message = e.getMessage() == null ? e.toString() : e.getMessage();
                runOnUiThread(() -> {
                    setStatus("Codex model list failed: " + message, true);
                    showManualModelDialog(profile);
                });
            }
        }, "codex-models").start();
    }

    // ------------------------------------------------------------------
    // Generic device-code login (xAI, Nous, Copilot) — RFC 8628 port
    // ------------------------------------------------------------------

    private interface DeviceFlow {
        /** Returns {user_code, verification_uri?, device_code, interval?}. */
        JSONObject start() throws Exception;

        /** Returns {status: "pending"|"done", tokens?: {...}}. */
        JSONObject poll(JSONObject start) throws Exception;

        /** Persist credentials for the provider. */
        void finish(JSONObject tokens) throws Exception;
    }

    private DeviceFlow deviceFlowFor(String providerId) {
        switch (providerId) {
            case "xai":
                return new DeviceFlow() {
                    @Override public JSONObject start() throws Exception {
                        java.util.Map<String, String> form = new java.util.LinkedHashMap<>();
                        form.put("client_id", ProviderLogin.XAI_CLIENT_ID);
                        form.put("scope", ProviderLogin.XAI_SCOPE);
                        return ProviderLogin.deviceCodeRequest("https://auth.x.ai/oauth2/device/code", form);
                    }
                    @Override public JSONObject poll(JSONObject start) throws Exception {
                        java.util.Map<String, String> form = new java.util.LinkedHashMap<>();
                        form.put("grant_type", "urn:ietf:params:oauth:grant-type:device_code");
                        form.put("device_code", start.optString("device_code"));
                        form.put("client_id", ProviderLogin.XAI_CLIENT_ID);
                        return ProviderLogin.deviceCodePoll(ProviderLogin.XAI_TOKEN_URL, form);
                    }
                    @Override public void finish(JSONObject tokens) throws Exception {
                        storeProviderLogin("xai", tokens);
                    }
                };
            case "nous":
                return new DeviceFlow() {
                    @Override public JSONObject start() throws Exception { return ProviderLogin.nousDeviceCode(); }
                    @Override public JSONObject poll(JSONObject start) throws Exception {
                        return ProviderLogin.nousPoll(start.optString("device_code"));
                    }
                    @Override public void finish(JSONObject tokens) throws Exception {
                        storeProviderLogin("nous", tokens);
                    }
                };
            case "github-copilot":
                return new DeviceFlow() {
                    @Override public JSONObject start() throws Exception {
                        java.util.Map<String, String> form = new java.util.LinkedHashMap<>();
                        form.put("client_id", ProviderLogin.COPILOT_CLIENT_ID);
                        form.put("scope", "read:user");
                        return ProviderLogin.deviceCodeRequest("https://github.com/login/device/code", form);
                    }
                    @Override public JSONObject poll(JSONObject start) throws Exception {
                        java.util.Map<String, String> form = new java.util.LinkedHashMap<>();
                        form.put("grant_type", "urn:ietf:params:oauth:grant-type:device_code");
                        form.put("device_code", start.optString("device_code"));
                        form.put("client_id", ProviderLogin.COPILOT_CLIENT_ID);
                        return ProviderLogin.deviceCodePoll("https://github.com/login/oauth/access_token", form);
                    }
                    @Override public void finish(JSONObject tokens) throws Exception {
                        // The GitHub token alone can't call the model API —
                        // exchange it for the short-lived Copilot proxy JWT.
                        JSONObject jwt = ProviderLogin.copilotExchangeJwt(tokens.optString("access_token", ""));
                        mProviderConfig.setProviderToken("github-copilot", jwt.optString("token", ""));
                        mProviderConfig.setProviderRefresh("github-copilot", tokens.optString("access_token", ""));
                        JSONObject state = new JSONObject()
                            .put("expires_at", jwt.optLong("expires_at", 0))
                            .put("obtained_at", System.currentTimeMillis());
                        mProviderConfig.setProviderState("github-copilot", state.toString());
                    }
                };
            default:
                throw new IllegalArgumentException("No device flow for " + providerId);
        }
    }

    private void storeProviderLogin(String providerId, JSONObject tokens) throws Exception {
        String access = tokens.optString("access_token", "");
        if (TextUtils.isEmpty(access)) throw new Exception("Login returned no access token.");
        mProviderConfig.setProviderToken(providerId, access);
        String refresh = tokens.optString("refresh_token", "");
        if (!TextUtils.isEmpty(refresh)) mProviderConfig.setProviderRefresh(providerId, refresh);
        JSONObject state = new JSONObject().put("obtained_at", System.currentTimeMillis());
        mProviderConfig.setProviderState(providerId, state.toString());
    }

    private void showDeviceCodeLogin(AiProviderProfile profile, boolean sessionMode) {
        final DeviceFlow flow = deviceFlowFor(profile.id);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(10), dp(22), 0);

        TextView status = new TextView(this);
        status.setText("Start the sign-in, then approve khabeer in your browser.");
        status.setTextColor(color(R.color.ai_text));
        status.setTextSize(13);
        box.addView(status);

        TextView codeView = new TextView(this);
        codeView.setTypeface(Typeface.MONOSPACE);
        codeView.setTextSize(24);
        codeView.setLetterSpacing(0.15f);
        codeView.setTextColor(color(R.color.ai_accent));
        codeView.setPadding(0, dp(14), 0, dp(4));
        codeView.setVisibility(View.GONE);
        box.addView(codeView);

        TextView uriView = new TextView(this);
        uriView.setTextColor(color(R.color.ai_text_muted));
        uriView.setTextSize(11);
        uriView.setVisibility(View.GONE);
        box.addView(uriView);

        MaterialButton openBrowser = new MaterialButton(this);
        openBrowser.setText("Open the authorization page");
        openBrowser.setAllCaps(false);
        openBrowser.setTextColor(0xFFFFFFFF);
        openBrowser.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color(R.color.ai_accent)));
        openBrowser.setCornerRadius(dp(10));
        openBrowser.setEnabled(false);
        openBrowser.setAlpha(0.5f);
        LinearLayout.LayoutParams browserLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        browserLp.topMargin = dp(12);
        openBrowser.setLayoutParams(browserLp);
        final String[] verificationUri = {null};
        openBrowser.setOnClickListener(v -> {
            String url = verificationUri[0];
            if (TextUtils.isEmpty(url)) return;
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e) {
                showError("No browser available.");
            }
        });
        box.addView(openBrowser);

        MaterialButton start = new MaterialButton(this);
        start.setText("Sign in with " + profile.name);
        start.setAllCaps(false);
        start.setTextColor(color(R.color.ai_text));
        start.setStrokeColor(android.content.res.ColorStateList.valueOf(color(R.color.ai_border)));
        start.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color(R.color.ai_surface)));
        start.setCornerRadius(dp(10));
        LinearLayout.LayoutParams startLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        startLp.topMargin = dp(8);
        start.setLayoutParams(startLp);
        box.addView(start);

        final androidx.appcompat.app.AlertDialog[] dialogHolder = new androidx.appcompat.app.AlertDialog[1];
        start.setOnClickListener(v -> {
            start.setEnabled(false);
            start.setText("Waiting for authorization…");
            status.setText("Requesting a device code…");
            new Thread(() -> {
                try {
                    JSONObject info = flow.start();
                    String userCode = info.optString("user_code", "");
                    String uri = info.optString("verification_uri_complete",
                        info.optString("verification_uri", info.optString("verification_url", "")));
                    int interval = Math.max(2, info.optInt("interval", 5));
                    runOnUiThread(() -> {
                        codeView.setText(userCode);
                        codeView.setVisibility(View.VISIBLE);
                        if (!TextUtils.isEmpty(uri)) {
                            verificationUri[0] = uri;
                            uriView.setText("Open  " + uri + "  and enter the code above.");
                            uriView.setVisibility(View.VISIBLE);
                            openBrowser.setEnabled(true);
                            openBrowser.setAlpha(1f);
                        }
                        status.setText("Approve khabeer in the browser to finish signing in.");
                    });
                    long deadline = System.currentTimeMillis() + 15 * 60_000L;
                    while (System.currentTimeMillis() < deadline) {
                        try { Thread.sleep(interval * 1000L); } catch (InterruptedException ie) { return; }
                        JSONObject poll = flow.poll(info);
                        String pollStatus = poll.optString("status", "pending");
                        if ("pending".equals(pollStatus)) continue;
                        if ("slow_down".equals(pollStatus)) { interval += 5; continue; }
                        if (!"done".equals(pollStatus))
                            throw new Exception("Sign-in did not complete (" + pollStatus + ").");
                        flow.finish(poll.optJSONObject("tokens"));
                        runOnUiThread(() -> {
                            if (dialogHolder[0] != null && dialogHolder[0].isShowing()) dialogHolder[0].dismiss();
                            finishProviderLogin(profile, sessionMode);
                        });
                        return;
                    }
                    runOnUiThread(() -> {
                        status.setText("Timed out waiting for authorization — try again.");
                        status.setTextColor(color(R.color.ai_warning));
                        start.setEnabled(true);
                        start.setText("Sign in with " + profile.name);
                    });
                } catch (Exception e) {
                    final String message = e.getMessage() == null ? e.toString() : e.getMessage();
                    runOnUiThread(() -> {
                        status.setText("Sign-in failed: " + message);
                        status.setTextColor(color(R.color.ai_warning));
                        start.setEnabled(true);
                        start.setText("Sign in with " + profile.name);
                    });
                }
            }, "device-login-" + profile.id).start();
        });

        dialogHolder[0] = new MaterialAlertDialogBuilder(this)
            .setTitle(profile.name + " sign-in")
            .setView(box)
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /** After a successful login: bind the session (session mode), open chat,
     *  and offer the model picker. */
    private void finishProviderLogin(AiProviderProfile profile, boolean sessionMode) {
        if (sessionMode) {
            mRuntimeService.setSessionProvider(profile.id);
            showChatPage();
        } else {
            selectProvider(profile, false);
            showChatPage();
        }
        setStatus("Signed in to " + profile.name + ".", false);
        fetchProviderModelsAndPick(profile);
    }

    private void fetchProviderModelsAndPick(AiProviderProfile profile) {
        new Thread(() -> {
            try {
                List<String> models = AiModelCatalog.fetch(profile, mProviderConfig.getBaseUrl(profile),
                    mProviderConfig.resolveCredential(profile));
                runOnUiThread(() -> showModelListDialog(profile, models));
            } catch (Exception ignored) {
                // Model listing is best-effort; the manual entry dialog covers the rest.
            }
        }, "provider-models").start();
    }

    // ------------------------------------------------------------------
    // Claude Pro/Max PKCE sign-in (paste-back code#state — the reference
    // flow's redirect target is a desktop console page, so no loopback)
    // ------------------------------------------------------------------

    private void showAnthropicPkceLogin(AiProviderProfile profile, boolean sessionMode) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(10), dp(22), 0);

        TextView status = new TextView(this);
        status.setText("1. Open the sign-in page and approve Claude.\n2. Copy the code shown at the end (it looks like ABcd…#tr-s…).\n3. Paste it below.");
        status.setTextColor(color(R.color.ai_text));
        status.setTextSize(13);
        box.addView(status);

        EditText codeInput = new EditText(this);
        codeInput.setSingleLine(true);
        codeInput.setHint("code#state");
        codeInput.setInputType(InputType.TYPE_CLASS_TEXT);
        LinearLayout.LayoutParams codeLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        codeLp.topMargin = dp(12);
        codeInput.setLayoutParams(codeLp);
        box.addView(codeInput);

        MaterialButton open = new MaterialButton(this);
        open.setText("Open claude.ai sign-in");
        open.setAllCaps(false);
        open.setTextColor(0xFFFFFFFF);
        open.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color(R.color.ai_accent)));
        open.setCornerRadius(dp(10));
        LinearLayout.LayoutParams openLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        openLp.topMargin = dp(10);
        open.setLayoutParams(openLp);
        final String[] authorizeUrl = {null};
        open.setOnClickListener(v -> {
            if (authorizeUrl[0] == null) return;
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(authorizeUrl[0])).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e) {
                showError("No browser available.");
            }
        });
        box.addView(open);

        final androidx.appcompat.app.AlertDialog[] dialogHolder = new androidx.appcompat.app.AlertDialog[1];
        final String verifier = base64UrlRandom(32);
        final String state = base64UrlRandom(16);
        final String challenge;
        try {
            challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception e) {
            showError("PKCE setup failed: " + e.getMessage());
            return;
        }
        authorizeUrl[0] = ProviderLogin.anthropicAuthorizeUrl(challenge, state);

        dialogHolder[0] = new MaterialAlertDialogBuilder(this)
            .setTitle("Claude sign-in")
            .setView(box)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Finish sign-in", (dialog, which) -> {
                String pasted = codeInput.getText().toString().trim();
                String code = pasted.contains("#") ? pasted.substring(0, pasted.indexOf('#')) : pasted;
                String pastedState = pasted.contains("#") ? pasted.substring(pasted.indexOf('#') + 1) : "";
                if (TextUtils.isEmpty(code)) {
                    showError("Paste the code from the browser first.");
                    return;
                }
                new Thread(() -> {
                    try {
                        if (!TextUtils.isEmpty(pastedState) && !pastedState.equals(state))
                            throw new Exception("state mismatch — start over.");
                        JSONObject tokens = ProviderLogin.anthropicExchange(code, state, verifier);
                        storeProviderLogin("anthropic", tokens);
                        runOnUiThread(() -> {
                            if (dialogHolder[0] != null && dialogHolder[0].isShowing()) dialogHolder[0].dismiss();
                            finishProviderLogin(profile, sessionMode);
                        });
                    } catch (Exception e) {
                        final String message = e.getMessage() == null ? e.toString() : e.getMessage();
                        runOnUiThread(() -> showError("Claude sign-in failed: " + message));
                    }
                }, "anthropic-pkce").start();
            })
            .show();
    }

    // ------------------------------------------------------------------
    // Qwen OAuth: the reference implementation reuses the Qwen CLI's
    // credential file; on mobile we accept a pasted credential JSON.
    // ------------------------------------------------------------------

    private void showQwenPasteDialog(AiProviderProfile profile, boolean sessionMode) {
        EditText paste = new EditText(this);
        paste.setSingleLine(false);
        paste.setMinLines(3);
        paste.setHint("{\"access_token\": \"…\", \"refresh_token\": \"…\"}");
        new MaterialAlertDialogBuilder(this)
            .setTitle("Qwen OAuth credentials")
            .setMessage("Sign in with 'qwen auth qwen-oauth' on a computer, then paste the contents of ~/.qwen/oauth_creds.json here.")
            .setView(paste)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Save", (dialog, which) -> {
                try {
                    JSONObject creds = new JSONObject(paste.getText().toString().trim());
                    String access = creds.optString("access_token", "");
                    if (TextUtils.isEmpty(access)) throw new Exception("No access_token in the pasted JSON.");
                    storeProviderLogin("qwen-oauth", creds);
                    finishProviderLogin(profile, sessionMode);
                } catch (Exception e) {
                    showError("Invalid credentials: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
                }
            })
            .show();
    }

    private void showModelListDialog(AiProviderProfile profile, List<String> models) {        ArrayList<String> items = new ArrayList<>();
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
        // Session-scoped /model switch (khabeer _persist_model_switch_to_session):
        // the model lives on the session row so resume restores this choice.
        // Provider config is only the default for NEW sessions; mutating it
        // while a session is active made one session's model bleed into
        // other sessions that shared the same provider.
        if (mRuntimeService != null && mHasNativeSession) {
            mRuntimeService.setSessionModel(cleanModel);
        } else {
            mProviderConfig.setModel(profile, cleanModel);
        }
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
        TermuxSession session = mTermuxService.createTermuxSession(null, null, null, workspace, false, "khabeer shell");
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
        String path = MobileKhabeerToolExecutor.normalizeWorkspace(mWorkspaceInput.getText().toString());
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
        if (path == null || MobileKhabeerToolExecutor.normalizeWorkspace(path) == null) {
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
        // A FAILED turn must not orphan its session: the transcript is intact
        // and the next message retries in place. Only an explicit new-session
        // action or archiving ends continuability.
        mHasNativeSession = run != null && !run.archived;
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
            && !method.contains("requestApproval") && !method.startsWith("session/") && !method.startsWith("memory/")) return;
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
        } else if ("turn/toolsDegraded".equals(method)) {
            setStatus("Model rejected function calling — continuing as plain chat this turn.", true);
        } else if ("memory/contextWarning".equals(method)) {
            showError("Context is " + payload.optString("usage_percent", "?") + "% full. Compact this session from Memory soon.");
        } else if ("memory/contextAutoCompact".equals(method)) {
            setStatus("Context " + payload.optString("usage_percent", "?") + "% full — compacting automatically…", true);
        } else if ("memory/contextAutoCompactDone".equals(method)) {
            Toast.makeText(this, "Session compacted (now " + payload.optString("usage_percent", "?") + "% context). Memory files stay authoritative.", Toast.LENGTH_LONG).show();
            setStatus("Session compacted.", false);
        } else if ("memory/reviewApplied".equals(method)) {
            String target = payload.optString("target", "memory");
            if (payload.optBoolean("staged")) {
                setStatus("Memory review staged writes for approval (" + target + ").", false);
            } else if ("verbose".equals(payload.optString("mode"))) {
                addSystemMessage("Memory updated (" + target + "):\n" + payload.optString("preview", ""));
            } else {
                Toast.makeText(this, "Memory updated (" + target + ")", Toast.LENGTH_SHORT).show();
            }
        } else if ("memory/contextUsage".equals(method)) {
            // Tracked for the Memory page readiness card; rendered on show.
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
        String label;
        if ("skills_list".equals(name) || "skill_view".equals(name) || "skill_manage".equals(name)) {
            label = name + (TextUtils.isEmpty(command) ? "" : " · " + command);
        } else if (name.startsWith(AiMcpRegistry.TOOL_PREFIX)) {
            label = "MCP · " + (TextUtils.isEmpty(command) ? name : command);
        } else {
            label = "Terminal · " + (TextUtils.isEmpty(command) ? name : oneLine(command, 96));
        }
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
        if (!archived) row.setOnLongClickListener(v -> { showSessionActions(run); return true; });
        return row;
    }

    private void showSessionActions(AiDatabase.RunRecord run) {
        new MaterialAlertDialogBuilder(this)
            .setTitle(run.title == null ? "Session" : run.title)
            .setItems(new String[]{"Compact session", "Share as markdown", "Save .md file", "Archive session"}, (dialog, which) -> {
                if (which == 0) confirmCompactSession(run);
                else if (which == 1) shareSessionMarkdown(run);
                else if (which == 2) saveSessionMarkdown(run);
                else confirmArchive(run);
            })
            .show();
    }

    private void confirmCompactSession(AiDatabase.RunRecord run) {
        if (mRuntimeService == null || !mRuntimeBound) {
            showError("Native runtime is still starting.");
            return;
        }
        AiProviderProfile profile = AiProviderProfile.find(run.harnessId);
        String model = TextUtils.isEmpty(run.modelOverride) ? run.lastResolvedModel : run.modelOverride;
        int active = mRuntimeService.countActiveMessages(run.id);
        String info = (profile == null ? run.harnessId : profile.name)
            + (TextUtils.isEmpty(model) ? "" : " · " + model)
            + "\n" + active + " active replay messages.";
        String ready = active < 30
            ? "\nToo small to compact usefully yet."
            : "\nOlder turns archive into one checkpoint; memory files stay authoritative.";
        new MaterialAlertDialogBuilder(this)
            .setTitle("Compact this session?")
            .setMessage(info + ready)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Compact", (dialog, which) -> mRuntimeService.compactSessionManually(run.id))
            .show();
    }

    private String sessionMarkdown(AiDatabase.RunRecord run) {
        if (mRuntimeService == null || run == null) return "";
        return mRuntimeService.exportSessionMarkdown(run.id);
    }

    private String exportFileName(AiDatabase.RunRecord run) {
        String base = run.title == null ? "session" : run.title.replaceAll("[^a-zA-Z0-9._-]+", "_");
        if (base.length() > 60) base = base.substring(0, 60);
        return base + "-" + run.id.substring(0, Math.min(8, run.id.length())) + ".md";
    }

    private void shareSessionMarkdown(AiDatabase.RunRecord run) {
        String md = sessionMarkdown(run);
        if (TextUtils.isEmpty(md)) {
            showError("Nothing to export in this session.");
            return;
        }
        try {
            android.content.Intent share = new android.content.Intent(android.content.Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(android.content.Intent.EXTRA_SUBJECT, run.title);
            share.putExtra(android.content.Intent.EXTRA_TEXT, md);
            startActivity(android.content.Intent.createChooser(share, "Share session"));
        } catch (Exception e) {
            saveSessionMarkdown(run);
        }
    }

    private void saveSessionMarkdown(AiDatabase.RunRecord run) {
        String md = sessionMarkdown(run);
        if (TextUtils.isEmpty(md)) {
            showError("Nothing to export in this session.");
            return;
        }
        try {
            java.io.File dir = new java.io.File(AiMemoryStore.dataRoot(), "exports");
            dir.mkdirs();
            java.io.File out = new java.io.File(dir, exportFileName(run));
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out, false)) {
                fos.write(md.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            Toast.makeText(this, "Saved " + out.getAbsolutePath(), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            showError("Export failed: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    private String sessionMeta(AiDatabase.RunRecord run, boolean archived) {
        StringBuilder sub = new StringBuilder();
        AiProviderProfile profile = AiProviderProfile.find(run.harnessId);
        sub.append(profile != null ? profile.name
            : (TextUtils.isEmpty(run.harnessId) ? "Agent" : run.harnessId));
        if (!TextUtils.isEmpty(run.lastResolvedModel)) sub.append(" · ").append(run.lastResolvedModel);
        sub.append(" · ").append(relativeTime(run.updatedAt));
        if (mRuntimeService != null) {
            try {
                JSONObject usage = mRuntimeService.getSessionUsage(run.id);
                if (usage.optInt("turns", 0) > 0) {
                    sub.append(" · ").append(formatTokens(usage.optLong("total_tokens", 0)));
                    if (usage.optInt("estimated_turns", 0) > 0
                        && usage.optInt("estimated_turns", 0) == usage.optInt("turns", 0)) sub.append("~");
                }
            } catch (Exception ignored) {}
        }
        if (archived) sub.append(" · archived");
        else if (run.state == AiRunStateMachine.State.FAILED) sub.append(" · failed");
        else if (run.state == AiRunStateMachine.State.CANCELED) sub.append(" · interrupted");
        else if (run.state == AiRunStateMachine.State.RUNNING
            || run.state == AiRunStateMachine.State.WAITING_APPROVAL
            || run.state == AiRunStateMachine.State.CONNECTING
            || run.state == AiRunStateMachine.State.STARTING) sub.append(" · active");
        return sub.toString();
    }

    private static String formatTokens(long total) {
        if (total < 1000) return total + " tok";
        if (total < 1_000_000) return String.format(java.util.Locale.US, "%.1fk tok", total / 1000.0);
        return String.format(java.util.Locale.US, "%.2fM tok", total / 1_000_000.0);
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
        mRuntimeService.resumeRun(runId);
        AiDatabase.RunRecord after = mRuntimeService.getActiveRun();
        if (after == null || !runId.equals(after.id)) return;
        applyRunControlsToUi(after);
        mCurrentRunId = after.id;
        rebuildTranscript(after.id);
        syncControlLabels();
        if (mDrawer != null) mDrawer.closeDrawer(findViewById(R.id.ai_drawer_panel));
        showChatPage();
    }

    /** Load provider/model labels from a session without touching global
     * provider defaults. Session switching is a view change, not a config
     * write. */
    private void applyRunControlsToUi(AiDatabase.RunRecord run) {
        if (run == null) return;
        AiProviderProfile runProfile = AiProviderProfile.find(run.harnessId);
        if (runProfile != null) {
            mSelectedProfile = runProfile;
            if (mSelectedProviderIcon != null) mSelectedProviderIcon.setImageResource(iconForProvider(runProfile.id));
            if (mSelectedProviderTitle != null) mSelectedProviderTitle.setText(runProfile.name);
            if (mSelectedProviderBody != null) mSelectedProviderBody.setText(runProfile.description);
            if (mEmptyChatHint instanceof TextView) ((TextView) mEmptyChatHint).setText(runProfile.mark);
            if (mChatTitle != null) mChatTitle.setText(runProfile.terminalOnly ? "Termux Shell" : runProfile.name);
        }
        if (!TextUtils.isEmpty(run.lastResolvedModel)) {
            mSelectedModel = run.lastResolvedModel;
        } else if (!TextUtils.isEmpty(run.modelOverride)) {
            mSelectedModel = run.modelOverride;
        } else if (runProfile != null) {
            mSelectedModel = runProfile.defaultModel;
        }
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
