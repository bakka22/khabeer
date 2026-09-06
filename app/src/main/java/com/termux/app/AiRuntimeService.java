package com.termux.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import com.termux.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Persistent native khabeer-style runtime. Talks to model APIs directly and exposes Termux as tools. */
public final class AiRuntimeService extends Service {

    public interface Listener {
        void onRunChanged(AiDatabase.RunRecord run);

        void onProtocolEvent(String runId, String method, JSONObject payload);

        void onAuthenticationUrl(String runId, String loginId, String url);

        void onDeviceCode(String runId, String loginId, String url, String code);

        void onRuntimeError(String runId, String message);
    }

    public final class LocalBinder extends android.os.Binder {
        public AiRuntimeService getService() {
            return AiRuntimeService.this;
        }
    }

    public enum BusyInputMode { QUEUE, STEER, INTERRUPT }

    private static final int NOTIFICATION_ID = 2401;
    private static final String CHANNEL_ID = "termux_ai_runtime";
    private static final String CHANNEL_APPROVAL = "khabeer_approval";
    private static final String CHANNEL_DONE = "khabeer_done";
    private static final int NOTIF_APPROVAL_BASE = 2500;
    private static final int NOTIF_DONE_BASE = 2600;
    public static final String ACTION_APPROVAL_ALLOW = "com.termux.app.AI_APPROVAL_ALLOW";
    public static final String ACTION_APPROVAL_DENY = "com.termux.app.AI_APPROVAL_DENY";
    public static final String EXTRA_REQUEST_ID = "com.termux.app.extra.APPROVAL_REQUEST_ID";
    public static final String EXTRA_RUN_ID = "com.termux.app.extra.RUN_ID";
    private volatile boolean mUiVisible = false;
    private final java.util.Set<Integer> mSessionNotifIds = new java.util.HashSet<>();
    private static final int MAX_MODEL_STEPS = 80;
    /** Bounded child turns (Hermes leaf discipline): fewer steps than a
     * full session turn, plus a wall-clock timeout in runDelegateTask. */
    private static final int SUBAGENT_MAX_STEPS = 20;
    private static final int SUBAGENT_DEFAULT_TIMEOUT_SECONDS = 300;
    private static final int SUBAGENT_MAX_TIMEOUT_SECONDS = 1800;

    private static int turnMaxSteps(RunContext c) {
        if (c != null && c.maxSteps > 0) return c.maxSteps;
        return MAX_MODEL_STEPS;
    }
    private static final int MODEL_CONNECT_TIMEOUT_MS = 30000;
    private static final int MODEL_READ_TIMEOUT_MS = 10 * 60 * 1000;
    private static final int DEFAULT_TOOL_TIMEOUT_SECONDS = 300;
    private static final int BUSY_QUEUE_MAX_PENDING = 8;
    private static final long APPROVAL_AUTO_DENY_MS = 120_000;
    private static final long AUTO_CONTINUE_FRESHNESS_MS = 3600_000L;

    private final IBinder mBinder = new LocalBinder();
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final List<Listener> mListeners = new ArrayList<>();
    private final Map<Long, PendingApproval> mPendingApprovals = new HashMap<>();
    private final Map<String, KhabeerSessionState> mSessions = new HashMap<>();
    /** Sessions already warned at the 90% threshold (reset on compact/new session). */
    private final HashSet<String> mContextWarnedSessions = new HashSet<>();
    /** True on threads running a delegated child turn: model callbacks stay
     * local (no UI broadcast, no parent-transcript writes, approvals
     * auto-deny, no review/title side effects). Nesting is forbidden. */
    private final ThreadLocal<Boolean> mQuietTurn = new ThreadLocal<>();

    private AiDatabase mDatabase;
    private MobileKhabeerToolExecutor mToolExecutor;
    private AiProviderConfig mProviderConfig;
    private AiMcpRegistry mMcpRegistry;
    /** Live sessions: each runs its own turns on its own worker thread, with
     * its own state machine and transcript — parallel sessions at once. */
    private final Map<String, RunContext> mRuns = new HashMap<>();
    /** The session the UI is currently looking at; background sessions keep
     * streaming independently. */
    private RunContext mViewed;
    /** The session whose turn is executing on this (worker) thread. */
    private final ThreadLocal<RunContext> mTurnContext = new ThreadLocal<>();
    private long mNextRequestId = 1;
    private BusyInputMode mBusyMode = BusyInputMode.INTERRUPT;
    private final Object mQueueLock = new Object();

    private KhabeerSessionState sessionState(String key) {
        KhabeerSessionState s = mSessions.get(key);
        if (s == null) { s = new KhabeerSessionState(); mSessions.put(key, s); }
        return s;
    }


    /** Live sessions: each runs its own turns on its own worker thread, with
     * its own state machine, transcript and interrupt flags — parallel
     * sessions at once (khabeer: every session is self-contained). */
    private static final class RunContext {
        final AiDatabase.RunRecord record;
        AiRunStateMachine stateMachine = new AiRunStateMachine();
        Thread worker;
        JSONArray chatMessages;
        String previousResponseId;
        volatile boolean stopRequested;
        volatile boolean lastTurnInterrupted;
        String steerText;
        String providerId;
        String baseUrl;
        String apiKey;
        String model;
        String effort;
        String approvalPolicy;
        /** Per-turn token accumulation (Hermes _last_turn_usage/session
         * counters, durable via turn_usage at turn end). */
        long turnPromptTokens;
        long turnCompletionTokens;
        boolean turnUsageReal;
        boolean turnUsageFlushed;
        /** Ledger attribution override: child turns flush to the parent. */
        String usageSessionId;
        /** Per-turn step budget override (subagents run bounded). 0 = default. */
        int maxSteps;
        /** Per-session task list (Hermes TodoStore, durable via runs.todo_json). */
        final AiTodoStore todos = new AiTodoStore();
        /** Image refs for the turn being built (vision port): consumed once
         * by appendUserTurnToHistory, then cleared. Refs only — bytes load
         * from the attachments dir at wire time. */
        JSONArray pendingImages;
        /** skill_view repeat-view dedup: "name|file" -> "mtime:size" (khabeer
         * repeat-view dedup — unchanged re-reads return a stub, not content). */
        final HashMap<String, String> skillViewCache = new HashMap<>();

        RunContext(AiDatabase.RunRecord record) { this.record = record; }
    }

    private RunContext ctx() {
        RunContext turn = mTurnContext.get();
        return turn != null ? turn : mViewed;
    }

    /** Bring a persisted run into the live map so it can be viewed/resumed. */
    private RunContext adoptRun(AiDatabase.RunRecord record) {
        RunContext existing = mRuns.get(record.id);
        if (existing != null) return existing;
        RunContext ctx = new RunContext(record);
        try { if (record.chatMessagesJson != null) ctx.chatMessages = new JSONArray(record.chatMessagesJson); } catch (Exception ignored) {}
        try {
            if (!TextUtils.isEmpty(record.todoJson)) ctx.todos.restore(new JSONObject(record.todoJson).optJSONArray("todos"));
        } catch (Exception ignored) {}
        ctx.previousResponseId = record.previousResponseId;
        mRuns.put(record.id, ctx);
        return ctx;
    }

    private void persistTodos(RunContext ctx) {
        if (ctx == null || ctx.record == null) return;
        try {
            ctx.record.todoJson = ctx.todos.snapshot().toString();
            persistRun(ctx);
        } catch (Exception ignored) {}
    }

    private boolean isTerminal(RunContext ctx) {
        if (ctx == null) return false;
        AiRunStateMachine.State state = ctx.stateMachine.getState();
        return state == AiRunStateMachine.State.COMPLETED
            || state == AiRunStateMachine.State.FAILED
            || state == AiRunStateMachine.State.CANCELED;
    }

    private void transition(RunContext ctx, AiRunStateMachine.State next) {
        if (ctx == null) return;
        try {
            ctx.stateMachine.transition(next);
            ctx.record.state = next;
            persistRun(ctx);
        } catch (IllegalStateException e) {
            notifyError(ctx.record.id, e.getMessage());
        }
    }

    private void persistRun(RunContext ctx) {
        if (ctx == null) return;
        ctx.record.updatedAt = System.currentTimeMillis();
        mDatabase.saveRun(ctx.record);
        AiDatabase.RunRecord copy = copyRun(ctx.record);
        mHandler.post(() -> {
            for (Listener listener : new ArrayList<>(mListeners)) listener.onRunChanged(copy);
        });
    }

    private void clearActiveTurn(RunContext ctx) {
        if (ctx == null) return;
        // Terminal choke for every turn end (complete, fail, cancel,
        // interrupt): the ledger row is written exactly once per turn.
        flushTurnUsage(ctx, false);
        try {
            KhabeerSessionState ss = sessionState(ctx.record.sessionKey == null ? ctx.record.id : ctx.record.sessionKey);
            mDatabase.clearTurnLease(ctx.record.sessionKey, ss.persistent.runGeneration);
            ctx.record.activeTurnToken = null;
            persistRun(ctx);
        } catch (Exception ignored) {}
    }

    private void markActiveTurn(RunContext ctx) {
        if (ctx == null) return;
        try {
            String token = UUID.randomUUID().toString();
            ctx.record.activeTurnToken = token;
            ctx.record.activeTurnStartedAt = System.currentTimeMillis();
            ctx.record.resumePending = false;
            KhabeerSessionState ss = sessionState(ctx.record.sessionKey == null ? ctx.record.id : ctx.record.sessionKey);
            mDatabase.markTurnLease(ctx.record.sessionKey, token, ss.persistent.runGeneration);
            persistRun(ctx);
        } catch (Exception ignored) {}
    }

    private void failRun(RunContext ctx, @Nullable String message) {
        if (ctx == null) return;
        flushTurnUsage(ctx, true);
        ctx.record.lastError = message == null ? "Unknown native agent runtime failure." : message;
        try { ctx.stateMachine.transition(AiRunStateMachine.State.FAILED); } catch (Exception ignored) {}
        ctx.record.state = AiRunStateMachine.State.FAILED;
        persistRun(ctx);
        notifyError(ctx.record.id, ctx.record.lastError);
        postCompletionNotification(ctx, true, ctx.record.lastError);
    }

    /** Adds one provider usage object to the turn ledger. Accepts OpenAI
     * (prompt/completion_tokens) and Anthropic/Responses (input/output)
     * shapes; missing shapes are ignored (estimate covers the turn). */
    private void noteUsage(@Nullable JSONObject usage) {
        RunContext c = ctx();
        if (c == null || usage == null) return;
        long prompt = usage.optLong("prompt_tokens", usage.optLong("input_tokens", 0));
        long completion = usage.optLong("completion_tokens", usage.optLong("output_tokens", 0));
        if (prompt <= 0 && completion <= 0) return;
        c.turnPromptTokens += prompt;
        c.turnCompletionTokens += completion;
        c.turnUsageReal = true;
    }

    private void resetTurnUsage(RunContext ctx) {
        if (ctx == null) return;
        ctx.turnPromptTokens = 0;
        ctx.turnCompletionTokens = 0;
        ctx.turnUsageReal = false;
        ctx.turnUsageFlushed = false;
    }

    /** Persists the turn ledger row: exact tokens when the provider reported
     * usage, otherwise replay chars/4 flagged estimated. Failed turns flush
     * only when real usage exists (estimates of dead turns are noise). */
    private void flushTurnUsage(RunContext ctx, boolean failed) {
        if (ctx == null || ctx.record == null || ctx.turnUsageFlushed) return;
        ctx.turnUsageFlushed = true;
        try {
            if (failed && !ctx.turnUsageReal) return;
            long prompt = ctx.turnPromptTokens;
            long completion = ctx.turnCompletionTokens;
            boolean estimated = !ctx.turnUsageReal;
            if (estimated) {
                long chars = 0;
                if (ctx.chatMessages != null) {
                    for (int i = 0; i < ctx.chatMessages.length(); i++) {
                        JSONObject msg = ctx.chatMessages.optJSONObject(i);
                        if (msg != null) chars += msg.optString("content", "").length();
                    }
                }
                prompt = 0;
                completion = chars / 4;
            }
            String ledgerSession = ctx.usageSessionId != null ? ctx.usageSessionId : ctx.record.id;
            mDatabase.appendTurnUsage(ledgerSession,
                ctx.record.lastResolvedModel == null ? "" : ctx.record.lastResolvedModel,
                prompt, completion, estimated);
        } catch (Exception ignored) {}
    }

    public JSONObject getSessionUsage(String sessionId) {
        if (mDatabase == null || TextUtils.isEmpty(sessionId)) return new JSONObject();
        return mDatabase.getSessionUsage(sessionId);
    }

    public int countActiveMessages(String sessionId) {
        if (mDatabase == null || TextUtils.isEmpty(sessionId)) return 0;
        return mDatabase.countActiveMessages(sessionId);
    }

    public String exportSessionMarkdown(String sessionId) {
        if (mDatabase == null || TextUtils.isEmpty(sessionId)) return "";
        return mDatabase.buildSessionMarkdown(sessionId);
    }

    public List<AiDatabase.RunRecord> getSubagentRuns() {
        List<AiDatabase.RunRecord> out = new ArrayList<>();
        if (mDatabase == null) return out;
        for (AiDatabase.RunRecord record : mDatabase.getSubagentRuns(30)) out.add(copyRun(record));
        return out;
    }

    public JSONObject getSubagentStats() {
        if (mDatabase == null) return new JSONObject();
        return mDatabase.getSubagentStats();
    }

    private void completeRun(RunContext ctx) {
        if (ctx == null) return;
        // A later success clears the sticky failure: sessions must not wear
        // an old error after they recover.
        ctx.record.lastError = null;
        flushTurnUsage(ctx, false);
        try { ctx.stateMachine.transition(AiRunStateMachine.State.COMPLETED); } catch (Exception ignored) {}
        ctx.record.state = AiRunStateMachine.State.COMPLETED;
        ctx.record.hygieneFailureStreak = 0;
        clearActiveTurn(ctx);
        persistRun(ctx);
        if (ctx.record.sessionKey != null) sessionState(ctx.record.sessionKey).clearTurn();
        if (isQuiet()) return;
        if (!"ai".equals(ctx.record.titleSource)) generateSessionTitleAsync(ctx.record.id);
        maybeRunMemoryReview(ctx);
        postCompletionNotification(ctx, false, null);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mDatabase = new AiDatabase(this);
        mProviderConfig = new AiProviderConfig(this);
        mToolExecutor = new MobileKhabeerToolExecutor(this);
        mMcpRegistry = new AiMcpRegistry(mProviderConfig);
        // One-time khabeer home migration runs BEFORE ensureDefaults: the
        // legacy rename only fires when the target does not exist yet, and
        // seeding first would strand .termuxAI/.katheer data forever.
        // It must also precede MCP discovery so stored stdio paths are
        // rewritten before any server is spawned.
        AiSkillRegistry.migrateKhabeerHome();
        AiMemoryStore.ensureDefaults();
        // Runtime-loadable providers/tools: rebuild the provider overlay and
        // sync plugin MCP servers (file + DB ops only, no model).
        AiPluginRegistry.refreshOverlay(mDatabase);
        // Deterministic library janitor when due (file ops only, no model).
        AiSkillCurator.maybeRun(mProviderConfig);
        mDatabase.rewriteMcpServerDataRoot(".termuxAI", ".khabeer");
        mDatabase.rewriteMcpServerDataRoot(".katheer", ".khabeer");
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        // Seed bundled skills into $HOME/.khabeer/skills (existing files win).
        Thread seeder = new Thread(() -> AiSkillRegistry.seedFromAssets(this), "skill-seeder");
        seeder.setDaemon(true);
        seeder.start();
        // Late-binding MCP discovery (khabeer mcp_startup): never blocks a
        // turn; discovered tools appear in the model's array on the next one.
        mMcpRegistry.probeStaleAsync(mDatabase);
        try { mDatabase.recoverInterruptedTurns(); } catch (Exception ignored) {}
        try { mDatabase.dedupeAssistantMessages(); } catch (Exception ignored) {}
        restoreLatestActiveRun();
    }

private void restoreLatestActiveRun() {
        try {
            AiDatabase.RunRecord r = mDatabase.getLatestActiveRun();
            if (r == null) {
                java.util.List<AiDatabase.RunRecord> recent = mDatabase.getSessions(1);
                if (recent.isEmpty()) recent = mDatabase.getArchivedSessions(1);
                if (!recent.isEmpty()) r = recent.get(0);
            }
            if (r != null && r.chatMessagesJson != null) {
                boolean isFailed = r.state == AiRunStateMachine.State.FAILED;
                if (!isFailed) {
                    RunContext ctx = adoptRun(r);
                    mViewed = ctx;
                    // A run persisted as RUNNING/WAITING_APPROVAL has no live
                    // worker in this process — it died with the old process.
                    // Show it honestly as interrupted; sendPrompt still
                    // continues it with full context.
                    if (!isTerminal(ctx)) {
                        r.state = AiRunStateMachine.State.CANCELED;
                        ctx.record.state = AiRunStateMachine.State.CANCELED;
                        persistRun(ctx);
                    }
                    if (r.activeTurnToken != null
                        && System.currentTimeMillis() - r.updatedAt < AUTO_CONTINUE_FRESHNESS_MS)
                        mDatabase.setResumePending(r.id, true);
                }
            }
        } catch (Exception ignored) {}
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_APPROVAL_ALLOW.equals(action) || ACTION_APPROVAL_DENY.equals(action)) {
                long reqId = intent.getLongExtra(EXTRA_REQUEST_ID, -1);
                if (reqId >= 0) {
                    JSONObject result = new JSONObject();
                    try { result.put("approved", ACTION_APPROVAL_ALLOW.equals(action)); } catch (Exception ignored) {}
                    respondToRequest(reqId, result);
                }
                return START_STICKY;
            }
        }
        return START_STICKY;
    }

    public void setUiVisible(boolean visible) {
        mUiVisible = visible;
        if (visible) { clearSessionNotifications(); return; }
        for (Map.Entry<Long, PendingApproval> entry : new java.util.ArrayList<>(mPendingApprovals.entrySet())) {
            PendingApproval pending = entry.getValue();
            if (pending != null) postApprovalNotification(entry.getKey(), pending.runId, pending.command, pending.workspace);
        }
    }

    public void clearSessionNotifications() {
        try {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager == null) return;
            synchronized (mSessionNotifIds) {
                for (int id : new java.util.ArrayList<>(mSessionNotifIds)) {
                    try { manager.cancel(id); } catch (Exception ignored) {}
                }
                mSessionNotifIds.clear();
            }
        } catch (Exception ignored) {}
    }

    /** Payload of a still-pending approval, or null once answered/timed out.
     * Lets the UI re-show the dialog when returning from background. */
    public JSONObject getPendingApprovalPayload(long id) {
        PendingApproval approval = mPendingApprovals.get(id);
        return approval == null ? null : approval.payload;
    }

    public void cancelApprovalNotification(long requestId) {
        try {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager == null) return;
            int id = approvalNotifId(requestId);
            try { manager.cancel(id); } catch (Exception ignored) {}
            synchronized (mSessionNotifIds) { mSessionNotifIds.remove(id); }
        } catch (Exception ignored) {}
    }

    private static int approvalNotifId(long requestId) {
        return NOTIF_APPROVAL_BASE + (int) (Math.abs(requestId) % 800);
    }

    private static int doneNotifId(String runId) {
        return NOTIF_DONE_BASE + (runId == null ? 0 : Math.abs(runId.hashCode()) % 800);
    }

    private boolean shouldNotify() {
        return !mUiVisible;
    }

    private android.app.PendingIntent openSessionIntent(String runId, int code) {
        Intent open = new Intent(this, AiActivity.class);
        open.setAction("com.termux.app.OPEN_SESSION");
        if (runId != null) open.putExtra(AiActivity.EXTRA_OPEN_RUN_ID, runId);
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= android.app.PendingIntent.FLAG_IMMUTABLE;
        return android.app.PendingIntent.getActivity(this, code, open, flags);
    }

    private void postApprovalNotification(long requestId, String runId, String command, String workspace) {
        if (!shouldNotify()) return;
        try {
            String cmd = command == null ? "" : command;
            if (cmd.length() > 200) cmd = cmd.substring(0, 200) + "…";
            android.app.PendingIntent open = openSessionIntent(runId, 1000 + approvalNotifId(requestId));
            Intent allow = new Intent(this, AiRuntimeService.class);
            allow.setAction(ACTION_APPROVAL_ALLOW);
            allow.putExtra(EXTRA_REQUEST_ID, requestId);
            Intent deny = new Intent(this, AiRuntimeService.class);
            deny.setAction(ACTION_APPROVAL_DENY);
            deny.putExtra(EXTRA_REQUEST_ID, requestId);
            int flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= android.app.PendingIntent.FLAG_IMMUTABLE;
            android.app.PendingIntent allowPi = android.app.PendingIntent.getService(this, (int) requestId * 2 + 1, allow, flags);
            android.app.PendingIntent denyPi = android.app.PendingIntent.getService(this, (int) requestId * 2 + 2, deny, flags);
            Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_APPROVAL)
                : new Notification.Builder(this);
            builder.setSmallIcon(R.drawable.ic_khabeer_notification)
                .setContentTitle("Approval needed")
                .setContentText(cmd.isEmpty() ? "The model wants to run a command." : cmd)
                .setStyle(new Notification.BigTextStyle().bigText(
                    (cmd.isEmpty() ? "The model wants to run a command." : cmd)
                    + (workspace == null || workspace.isEmpty() ? "" : "\n" + workspace)))
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Allow", allowPi).build())
                .addAction(new Notification.Action.Builder(null, "Deny", denyPi).build())
                .setAutoCancel(false)
                .setOngoing(true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) builder.setTimeoutAfter(APPROVAL_AUTO_DENY_MS);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager == null) return;
            int id = approvalNotifId(requestId);
            manager.notify(id, builder.build());
            synchronized (mSessionNotifIds) { mSessionNotifIds.add(id); }
        } catch (Exception ignored) {}
    }

    private void postCompletionNotification(RunContext ctx, boolean failed, @Nullable String error) {
        if (ctx == null || ctx.record == null) return;
        if (isQuiet()) return;
        if (!shouldNotify()) return;
        try {
            String runId = ctx.record.id;
            String title = ctx.record.title;
            if (TextUtils.isEmpty(title)) title = failed ? "Session failed" : "Session finished";
            String body;
            if (failed) body = error == null ? "The turn failed." : error;
            else {
                body = lastAssistantText(ctx.chatMessages);
                if (TextUtils.isEmpty(body)) body = "The model finished the turn.";
            }
            String shortBody = body.length() > 160 ? body.substring(0, 160) + "…" : body;
            Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_DONE)
                : new Notification.Builder(this);
            builder.setSmallIcon(R.drawable.ic_khabeer_notification)
                .setContentTitle(title)
                .setContentText(shortBody)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setContentIntent(openSessionIntent(runId, 2000 + doneNotifId(runId)))
                .setAutoCancel(true);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager == null) return;
            int id = doneNotifId(runId);
            manager.notify(id, builder.build());
            synchronized (mSessionNotifIds) { mSessionNotifIds.add(id); }
        } catch (Exception ignored) {}
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

public void addListener(Listener listener) {
        if (listener == null || mListeners.contains(listener)) return;
        mListeners.add(listener);
        if (mViewed != null) listener.onRunChanged(copyRun(mViewed.record));
    }

    public void removeListener(Listener listener) {
        mListeners.remove(listener);
    }

    @Nullable
 public AiDatabase.RunRecord getActiveRun() {
        return mViewed == null ? null : copyRun(mViewed.record);
    }

    public AiDatabase getDatabase() {
        return mDatabase;
    }

    private JSONArray mPendingImages;

    /** Stashes image refs for the next turn (clip-button attachments).
     * Consumed once by the next startAgent/sendPrompt, then cleared. */
    public void setPendingImages(@Nullable JSONArray images) {
        if (images == null || images.length() == 0) mPendingImages = null;
        else mPendingImages = images;
    }

    private JSONArray takePendingImages() {
        JSONArray out = mPendingImages;
        mPendingImages = null;
        return out;
    }

    public List<AiDatabase.RunRecord> getRecentRuns() {
        List<AiDatabase.RunRecord> result = new ArrayList<>();
        for (AiDatabase.RunRecord record : mDatabase.getRecentRuns(12)) result.add(copyRun(record));
        return result;
    }

    /** Drawer listing: live sessions only (archived + empty ghosts filtered out). */
    public List<AiDatabase.RunRecord> getSessions() {
        List<AiDatabase.RunRecord> result = new ArrayList<>();
        for (AiDatabase.RunRecord record : mDatabase.getSessions(10)) result.add(copyRun(record));
        return result;
    }

    public List<AiDatabase.RunRecord> getArchivedSessions() {
        List<AiDatabase.RunRecord> result = new ArrayList<>();
        for (AiDatabase.RunRecord record : mDatabase.getArchivedSessions(10)) result.add(copyRun(record));
        return result;
    }

    public JSONArray getTranscript(String runId) {
        return mDatabase.getTranscript(runId, 500);
    }

    /** Archive a session (soft hide, khabeer-style). Archiving the active run
     * stops its turn first and drops it as the current session. */
public void archiveRun(String runId, boolean archived) {
        AiDatabase.RunRecord target = mDatabase.getRun(runId);
        if (target == null) return;
        RunContext live = mRuns.get(runId);
        if (archived && live != null) {
            stopRun(live);
            mRuns.remove(runId);
            if (mViewed == live) {
                mViewed = null;
                for (Listener listener : new ArrayList<>(mListeners)) listener.onRunChanged(null);
            }
        }
        mDatabase.setRunArchived(runId, archived);
    }

    /** Permanently deletes a session: stops any live turn, drops it as the
     * current session, removes its attachments dir, and wipes its database
     * rows. Irreversible — callers must confirm first. */
    public void deleteRun(String runId) {
        if (runId == null) return;
        RunContext live = mRuns.get(runId);
        if (live != null) {
            stopRun(live);
            mRuns.remove(runId);
            if (mViewed == live) {
                mViewed = null;
                for (Listener listener : new ArrayList<>(mListeners)) listener.onRunChanged(null);
            }
        }
        mDatabase.deleteRun(runId);
        try {
            File attachments = AiAttachments.sessionDir(runId);
            if (attachments.isDirectory()) deleteRecursive(attachments);
        } catch (Exception ignored) {}
    }

    private static void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.isDirectory() ? file.listFiles() : null;
        if (children != null) for (File child : children) deleteRecursive(child);
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    /** Resume a persisted session: restore its transcript into the runtime so
     * the next prompt continues that conversation. Busy turns must be stopped
     * first — switching mid-turn would scramble history (khabeer claims the
     * active session before any switch). */
public void resumeRun(String runId) {
        AiDatabase.RunRecord r = mDatabase.getRun(runId);
        if (r == null) { notifyError(runId, "Session not found: " + runId); return; }
        boolean fresh = mRuns.get(runId) == null;
        if (r.archived) mDatabase.setRunArchived(runId, false);
        RunContext ctx = adoptRun(r);
        if (fresh && !isTerminal(ctx)) {
            // The persisted run has no live worker here — previous process
            // died mid-turn. Show interrupted; the next prompt continues it.
            r.state = AiRunStateMachine.State.CANCELED;
            ctx.record.state = AiRunStateMachine.State.CANCELED;
        }
        mViewed = ctx;
        mDatabase.setResumePending(runId, false);
        persistRun(ctx);
        emit("session/resumed", json("sessionId", runId));
        for (Listener listener : new ArrayList<>(mListeners)) listener.onRunChanged(copyRun(r));
    }

    /** Forks a session into an independent child and opens it (Hermes
     * /branch). The parent is preserved untouched; a live parent turn is
     * stopped first so history cannot scramble mid-copy. Returns the
     * child, or null when there is nothing to fork. */
    public AiDatabase.RunRecord branchRun(String parentId, @Nullable String name) {
        if (TextUtils.isEmpty(parentId)) return null;
        RunContext live = mRuns.get(parentId);
        if (live != null && live.worker != null && live.worker.isAlive()) stopRun(live);
        AiDatabase.RunRecord child = mDatabase.branchSession(parentId, name);
        if (child == null) return null;
        resumeRun(child.id);
        return copyRun(mDatabase.getRun(child.id));
    }

    /** Starts a true session boundary: stops any turn and drops the current
     * run entirely so the next prompt creates a fresh session (khabeer
     * session_reset). stopActiveRun alone keeps the run as current. */
    public void newSession() {
        // Parallel sessions: leave every live run untouched — this only moves
        // the UI focus off the current session (khabeer session boundary).
        mViewed = null;
        mHandler.post(() -> {
            for (Listener listener : new ArrayList<>(mListeners)) listener.onRunChanged(null);
        });
    }

    public void setBusyMode(BusyInputMode mode) { if (mode != null) mBusyMode = mode; }
    public BusyInputMode getBusyMode() { return mBusyMode; }

public boolean steerActiveTurn(String text) {
        RunContext c = mViewed;
        if (c == null || c.worker == null || !c.worker.isAlive()) return false;
        if (c.stateMachine.getState() == AiRunStateMachine.State.WAITING_APPROVAL) return false;
        KhabeerSessionState s = sessionState(c.record.sessionKey == null ? c.record.id : c.record.sessionKey);
        s.conversation.sidecarNotes.add(text);
        c.steerText = text;
        emit("turn/steered", json("text", text));
        return true;
    }

public void startAgent(String providerId, String baseUrl, String apiKey, String workspace, String prompt,
                           String model, @Nullable String effort, @Nullable String approvalPolicy) {
        startAgent(providerId, baseUrl, apiKey, workspace, prompt, model, effort, approvalPolicy, null);
    }

    /** Route-aware start (F1 fix): OpenCode sessions pin their route on the
     * row at creation so the FIRST turn already uses the route endpoint +
     * key, not the profile-level leftovers. */
public void startAgent(String providerId, String baseUrl, String apiKey, String workspace, String prompt,
                           String model, @Nullable String effort, @Nullable String approvalPolicy, @Nullable String route) {
        String normalizedWorkspace = MobileKhabeerToolExecutor.normalizeWorkspace(workspace);
        if (normalizedWorkspace == null) {
            notifyError(null, "Choose a valid project folder first.");
            return;
        }
        AiProviderProfile profile = AiProviderProfile.find(providerId);
        if ((profile == null || profile.apiKeyAuth) && TextUtils.isEmpty(apiKey)) {
            notifyError(null, "Add an API key for this provider before starting a chat.");
            return;
        }
        if (TextUtils.isEmpty(baseUrl) || TextUtils.isEmpty(model)) {
            notifyError(null, "Provider endpoint and model are required.");
            return;
        }
        if ("opencode".equals(providerId) && !TextUtils.isEmpty(route)) {
            baseUrl = AiProviderConfig.ocRouteUrl(route);
            String routeKey = mProviderConfig.getOpenCodeRouteKey(route);
            if (!TextUtils.isEmpty(routeKey)) apiKey = routeKey;
            if (TextUtils.isEmpty(model)) model = mProviderConfig.getOpenCodeRouteModel(route);
        }

        // Parallel sessions: a new session starts immediately without touching
        // any other live session — they keep streaming in the background.
        String sessionKey = AiDatabase.buildSessionKey(providerId == null ? "native-agent" : providerId, normalizedWorkspace);
        AiDatabase.RunRecord record = mDatabase.createRun(providerId == null ? "native-agent" : providerId, normalizedWorkspace, sessionKey);
        record.modelOverride = model;
        record.lastResolvedModel = model;
        if ("opencode".equals(providerId) && !TextUtils.isEmpty(route)) record.route = route;
        mProviderConfig.setLastUsed(record.harnessId, model, record.route);
        RunContext ctx = adoptRun(record);
        ctx.pendingImages = AiAttachments.rehome(takePendingImages(), record.id);
        mViewed = ctx;
        KhabeerSessionState ss = sessionState(sessionKey);
        ss.persistent.bumpGeneration();
        transition(ctx, AiRunStateMachine.State.STARTING);
        transition(ctx, AiRunStateMachine.State.CONNECTING);
        markActiveTurn(ctx);
        runTurn(ctx, providerId, baseUrl, apiKey, normalizedWorkspace, prompt, model, effort, approvalPolicy);
    }

private boolean handleBusyInput(String prompt, String providerId, String baseUrl, String apiKey, String model, String effort, String approvalPolicy) {
        RunContext c = mViewed;
        if (c == null) return false;
        if (mBusyMode == BusyInputMode.QUEUE) {
            KhabeerSessionState ss = sessionState(c.record.sessionKey == null ? c.record.id : c.record.sessionKey);
            synchronized (mQueueLock) {
                if (ss.conversation.queuedEvents.size() >= BUSY_QUEUE_MAX_PENDING) {
                    notifyError(c.record.id, "Queue full (" + BUSY_QUEUE_MAX_PENDING + "). Wait for current turn to finish.");
                    return true;
                }
                ss.conversation.queuedEvents.add(prompt);
            }
            emit("turn/queued", json("text", prompt));
            return true;
        } else if (mBusyMode == BusyInputMode.STEER) {
            if (steerActiveTurn(prompt)) return true;
        }
        return false;
    }

private void drainQueueIfNeeded(RunContext ctx) {
        if (ctx == null) return;
        KhabeerSessionState ss = sessionState(ctx.record.sessionKey == null ? ctx.record.id : ctx.record.sessionKey);
        String next = null;
        synchronized (mQueueLock) {
            if (!ss.conversation.queuedEvents.isEmpty()) next = ss.conversation.queuedEvents.remove(0);
        }
        if (next != null) {
            String p = next;
            mHandler.postDelayed(() -> {
                RunContext live = mRuns.get(ctx.record.id);
                if (live != null) runTurn(live, live.record.harnessId, null, null, live.record.workspace, p, live.record.modelOverride, null, null);
            }, 200);
        }
    }

    private void markActiveTurn() {
        if (ctx() == null) return;
        try {
            String token = UUID.randomUUID().toString();
            ctx().record.activeTurnToken = token;
            ctx().record.activeTurnStartedAt = System.currentTimeMillis();
            ctx().record.resumePending = false;
            KhabeerSessionState ss = sessionState(ctx().record.sessionKey == null ? ctx().record.id : ctx().record.sessionKey);
            mDatabase.markTurnLease(ctx().record.sessionKey, token, ss.persistent.runGeneration);
            persistRun();
        } catch (Exception ignored) {}
    }

    private void clearActiveTurn() {
        if (ctx() == null) return;
        flushTurnUsage(ctx(), false);
        try {
            KhabeerSessionState ss = sessionState(ctx().record.sessionKey == null ? ctx().record.id : ctx().record.sessionKey);
            mDatabase.clearTurnLease(ctx().record.sessionKey, ss.persistent.runGeneration);
            ctx().record.activeTurnToken = null;
            persistRun();
        } catch (Exception ignored) {}
    }

public void sendPrompt(String prompt, @Nullable String effort, @Nullable String approvalPolicy) {
        RunContext c = mViewed;
        if (c == null) {
            notifyError(null, "No active native agent session.");
            return;
        }
        if (refuseIfCompacting(c.record.id)) return;
        // Session-authoritative resolution (khabeer _restore_session_model):
        // provider, model and credentials come from the session row and the
        // provider registry, never from ambient UI state — sessions carry
        // their own provider identity.
        AiProviderProfile profile = AiProviderProfile.find(c.record.harnessId);
        if (profile == null) {
            notifyError(c.record.id, "Session provider is not available.");
            return;
        }
        String model = TextUtils.isEmpty(c.record.modelOverride)
            ? mProviderConfig.getModel(profile) : c.record.modelOverride;
        if (TextUtils.isEmpty(model)) model = profile.defaultModel;
        c.record.lastResolvedModel = model;
        String baseUrl = mProviderConfig.getBaseUrl(profile);
        String apiKey = mProviderConfig.resolveCredential(profile);
        String providerId = profile.id;
        if ("opencode".equals(providerId)) {
            // Session-scoped route: each session carries which OpenCode route
            // (Free / Zen / Go) it belongs to.
            String route = TextUtils.isEmpty(c.record.route)
                ? mProviderConfig.getOpenCodeSelectedRoute() : c.record.route;
            baseUrl = AiProviderConfig.ocRouteUrl(route);
            apiKey = mProviderConfig.getOpenCodeRouteKey(route);
            if (TextUtils.isEmpty(c.record.modelOverride))
                model = mProviderConfig.getOpenCodeRouteModel(route);
            c.record.lastResolvedModel = model;
            c.record.route = route;
        }

        if (!isTerminal(c) && c.worker != null && c.worker.isAlive()) {
            if (handleBusyInput(prompt, providerId, baseUrl, apiKey, model, effort, approvalPolicy)) return;
            c.stopRequested = true;
            try {
                c.stateMachine.transition(AiRunStateMachine.State.INTERRUPTING);
                c.record.state = AiRunStateMachine.State.INTERRUPTING;
                persistRun(c);
            } catch (Exception ignored) {}
            KhabeerInterruptManager.setInterrupt(true, c.worker.getId(), "steer");
        }
        // Let the interrupted worker unwind so it can persist its partial reply
        // before we build the next turn (mirrors khabeer' orderly interrupt drain).
        if (c.worker != null && c.worker.isAlive()) {
            try { c.worker.join(3000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        c.stopRequested = false;
        c.steerText = null;
        prompt = applyInterruptScaffold(c, prompt);
        JSONArray pending = takePendingImages();
        if (pending != null && pending.length() > 0) c.pendingImages = pending;
        persistRun(c);
        runTurn(c, providerId, baseUrl, apiKey, c.record.workspace, prompt, model, effort, approvalPolicy);
    }

    /** Session-authoritative credential resolution shared by sendPrompt,
     * retry, and compaction: provider, model, and credentials come from the
     * session row and the registry, never from ambient UI state. Returns
     * {providerId, baseUrl, apiKey, model} or null (error already shown). */
    private String[] sessionCredentials(RunContext c) {
        if (c == null || c.record == null) {
            notifyError(null, "No active native agent session.");
            return null;
        }
        AiProviderProfile profile = AiProviderProfile.find(c.record.harnessId);
        if (profile == null) {
            notifyError(c.record.id, "Session provider is not available.");
            return null;
        }
        String model = TextUtils.isEmpty(c.record.modelOverride)
            ? mProviderConfig.getModel(profile) : c.record.modelOverride;
        if (TextUtils.isEmpty(model)) model = profile.defaultModel;
        String baseUrl = mProviderConfig.getBaseUrl(profile);
        String apiKey = mProviderConfig.resolveCredential(profile);
        if ("opencode".equals(profile.id)) {
            String route = TextUtils.isEmpty(c.record.route)
                ? mProviderConfig.getOpenCodeSelectedRoute() : c.record.route;
            baseUrl = AiProviderConfig.ocRouteUrl(route);
            String routeKey = mProviderConfig.getOpenCodeRouteKey(route);
            if (!TextUtils.isEmpty(routeKey)) apiKey = routeKey;
            if (TextUtils.isEmpty(c.record.modelOverride))
                model = mProviderConfig.getOpenCodeRouteModel(route);
            c.record.route = route;
        }
        c.record.lastResolvedModel = model;
        return new String[]{profile.id, baseUrl, apiKey, model};
    }

    /** /retry: re-send the last user turn in place. Fails closed when the
     * turn carried attachments (no replay protocol for them). The user row
     * is reused — no duplicate is appended. */
    public void retryLastTurn() {
        RunContext c = mViewed;
        if (c == null || c.record == null) {
            notifyError(null, "No active native agent session.");
            return;
        }
        if (refuseIfCompacting(c.record.id)) return;
        if (c.worker != null && c.worker.isAlive()) {
            notifyError(c.record.id, "Wait for the current model turn to finish before retrying.");
            return;
        }
        String[] creds = sessionCredentials(c);
        if (creds == null) return;
        JSONObject last;
        try {
            last = mDatabase.lastActiveUserMessage(c.record.id);
        } catch (Exception e) {
            notifyError(c.record.id, "Could not read the transcript: " + e.getMessage());
            return;
        }
        if (!last.optBoolean("found")) {
            notifyError(c.record.id, "No user message to retry in this session.");
            return;
        }
        String text = last.optString("content", "");
        if (TextUtils.isEmpty(text.trim())) {
            notifyError(c.record.id, "No user message to retry in this session.");
            return;
        }
        if (text.contains("\n\nAttached files:")) {
            notifyError(c.record.id, "That turn had attached files, which cannot be replayed. Send it again manually.");
            return;
        }
        int cleared = mDatabase.clearAfterMessage(c.record.id, last.optLong("id"));
        if (cleared < 0) {
            notifyError(c.record.id, "Could not reset the failed turn. Send your message again.");
            return;
        }
        rebuildReplayFromDatabase(c);
        persistRun(c);
        try {
            mDatabase.appendEvent(c.record.id, "memory/turnRetried",
                new JSONObject().put("cleared_messages", cleared).toString());
        } catch (Exception ignored) {}
        emit("memory/turnRetried", json("cleared_messages", String.valueOf(cleared)));
        runTurn(c, creds[0], creds[1], creds[2], c.record.workspace, text, creds[3],
            c.effort, c.approvalPolicy, false);
    }

    /** /undo [N]: back up N user turns. Returns the DB result; the activity
     * echoes the removed text so it can be copied, edited, and resent. */
    public JSONObject undoTurns(int n) {
        JSONObject out = new JSONObject();
        RunContext c = mViewed;
        if (c == null || c.record == null) {
            try { out.put("success", false).put("error", "No active native agent session."); } catch (Exception ignored) {}
            return out;
        }
        if (refuseIfCompacting(c.record.id)) {
            try { out.put("success", false).put("error", "Compaction is running on this session — wait for it to finish."); } catch (Exception ignored) {}
            return out;
        }
        if (c.worker != null && c.worker.isAlive()) {
            try { out.put("success", false).put("error", "Wait for the current model turn to finish before undoing."); } catch (Exception ignored) {}
            return out;
        }
        JSONObject result = mDatabase.rewindSession(c.record.id, n);
        if (!result.optBoolean("success")) return result;
        rebuildReplayFromDatabase(c);
        persistRun(c);
        try {
            mDatabase.appendEvent(c.record.id, "memory/turnUndone", result.toString());
        } catch (Exception ignored) {}
        emit("memory/turnUndone", json("turns_undone", String.valueOf(result.optInt("turns_undone"))));
        return result;
    }

    /** Session-scoped /model switch (khabeer _persist_model_switch_to_session):
     * the model lives on the session row so resume restores it. */
public void setSessionModel(String model) {
        RunContext c = mViewed;
        if (c == null || TextUtils.isEmpty(model)) return;
        c.record.modelOverride = model;
        c.record.lastResolvedModel = model;
        persistRun(c);
        mProviderConfig.setLastUsed(c.record.harnessId, model, c.record.route);
        emit("session/model", json("sessionId", c.record.id, "model", model));
    }

    /** Session-scoped OpenCode route switch (Free / Zen / Go). */
    public void setSessionRoute(String route) {
        RunContext c = mViewed;
        if (c == null || TextUtils.isEmpty(route)) return;
        c.record.route = route;
        c.record.modelOverride = mProviderConfig.getOpenCodeRouteModel(route);
        c.record.lastResolvedModel = c.record.modelOverride;
        persistRun(c);
        mProviderConfig.setLastUsed(c.record.harnessId, c.record.modelOverride, route);
        emit("session/route", json("sessionId", c.record.id, "route", route));
    }

    /** Session-scoped provider switch (khabeer model_config.gateway_runtime):
     * the session keeps its transcript but subsequent turns run on the new
     * provider; the model resets to that provider's default. */
public void setSessionProvider(String providerId) {
        RunContext c = mViewed;
        if (c == null || TextUtils.isEmpty(providerId)) return;
        AiProviderProfile profile = AiProviderProfile.find(providerId);
        if (profile == null || profile.terminalOnly || !profile.implemented) return;
        c.record.harnessId = profile.id;
        if ("opencode".equals(profile.id)) {
            // F3 fix: an OpenCode session runs its route's model, not the
            // profile-level leftover. Prefer the session's pinned route.
            String route = TextUtils.isEmpty(c.record.route)
                ? mProviderConfig.getOpenCodeSelectedRoute() : c.record.route;
            c.record.route = route;
            c.record.modelOverride = mProviderConfig.getOpenCodeRouteModel(route);
        } else {
            // F5 fix: a stale OpenCode route must not resurrect later.
            c.record.route = null;
            c.record.modelOverride = mProviderConfig.getModel(profile);
        }
        if (TextUtils.isEmpty(c.record.modelOverride)) c.record.modelOverride = profile.defaultModel;
        c.record.lastResolvedModel = c.record.modelOverride;
        persistRun(c);
        mProviderConfig.setLastUsed(c.record.harnessId, c.record.modelOverride, c.record.route);
        emit("session/provider", json("sessionId", c.record.id, "provider", profile.id));
    }

    /** Manual-only context compaction. This mirrors Hermes' structured
     * checkpoint flow while keeping MEMORY.md / USER.md authoritative and
     * never mutating a live turn underneath the model. */
public void compactCurrentSessionManually() {
        compactSessionManually(null);
    }

    /** Manual compaction for any session (Sessions list entry point).
     * Null selects the viewed session. Compaction archives transcript rows
     * in the database, so it works even when the session is not live. */
    public void compactSessionManually(@Nullable String targetRunId) {
        RunContext c = targetRunId == null ? mViewed : mRuns.get(targetRunId);
        if (c == null || c.record == null) {
            AiDatabase.RunRecord stored = targetRunId == null ? null : mDatabase.getRun(targetRunId);
            if (stored == null) {
                notifyError(null, "No active session to compact.");
                return;
            }
            c = adoptRun(stored);
        }
        if (c.worker != null && c.worker.isAlive()) {
            notifyError(c.record.id, "Wait for the current model turn to finish before compacting this session.");
            return;
        }
        AiProviderProfile profile = AiProviderProfile.find(c.record.harnessId);
        if (profile == null) {
            notifyError(c.record.id, "Session provider is not available.");
            return;
        }
        String providerId = profile.id;
        String baseUrl = mProviderConfig.getBaseUrl(profile);
        String apiKey = mProviderConfig.resolveCredential(profile);
        String model = TextUtils.isEmpty(c.record.modelOverride) ? mProviderConfig.getModel(profile) : c.record.modelOverride;
        if (TextUtils.isEmpty(model)) model = profile.defaultModel;
        boolean needsKey = profile.apiKeyAuth;
        if ("opencode".equals(providerId)) {
            String route = TextUtils.isEmpty(c.record.route) ? mProviderConfig.getOpenCodeSelectedRoute() : c.record.route;
            baseUrl = AiProviderConfig.ocRouteUrl(route);
            String routeKey = mProviderConfig.getOpenCodeRouteKey(route);
            if (!TextUtils.isEmpty(routeKey)) apiKey = routeKey;
            if (TextUtils.isEmpty(c.record.modelOverride)) model = mProviderConfig.getOpenCodeRouteModel(route);
            // Keyless routes (Free) compact without credentials.
            needsKey = AiProviderConfig.ocRouteNeedsKey(route);
        }
        if (TextUtils.isEmpty(baseUrl) || TextUtils.isEmpty(model)) {
            notifyError(c.record.id, "Provider endpoint and model are required before compaction.");
            return;
        }
        if (needsKey && TextUtils.isEmpty(apiKey)) {
            notifyError(c.record.id, "Add credentials for this provider before compaction.");
            return;
        }
        final RunContext target = c;
        final String runId = c.record.id;
        final String fProviderId = providerId;
        final String fBaseUrl = baseUrl;
        final String fApiKey = apiKey;
        final String fModel = model;
        final JSONArray transcript = mDatabase.getHistoricalTranscript(runId, 1000);
        if (transcript.length() < 30) {
            notifyError(runId, "This session is still small; compaction needs a longer transcript to be useful.");
            return;
        }
        emitForRun(runId, "memory/compactionStarted", new JSONObject());
        new Thread(() -> {
            try {
                performCompactionBlocking(target, fProviderId, fBaseUrl, fApiKey, fModel);
                persistRun(target);
                mContextWarnedSessions.remove(runId);
                mHandler.post(() -> {
                    for (Listener listener : new ArrayList<>(mListeners)) listener.onRunChanged(copyRun(target.record));
                });
            } catch (Exception e) {
                notifyError(runId, e.getMessage() == null ? "Manual compaction failed." : e.getMessage());
            }
        }, "khabeer-manual-compaction").start();
    }

    /** Shared compaction core for manual (§5) and 95% automatic (§5.1)
     * passes: structured summary → archive → replay rebuild. Runs on the
     * caller's thread; manual callers must wrap it in a worker thread. */
    private JSONObject performCompactionBlocking(RunContext target, String providerId, String baseUrl,
                                                 String apiKey, String model) throws Exception {
        String runId = target.record.id;
        if (mCompactingRunId != null && !mCompactingRunId.equals(runId)) {
            throw new IllegalStateException("Another session is compacting right now.");
        }
        mCompactingRunId = runId;
        try {
            JSONArray transcript = mDatabase.getHistoricalTranscript(runId, 1000);
            if (transcript.length() < 30) throw new IllegalStateException("This session is still small; compaction needs a longer transcript to be useful.");
            progress(runId, "started", transcript.length() + " transcript messages. Asking " + model + " for a checkpoint…");
            String summary = callBackgroundCompactionSummary(providerId, baseUrl, apiKey, model, runId, transcript);
            if (TextUtils.isEmpty(summary)) throw new IllegalStateException("The provider returned an empty compaction summary.");
            if (!summary.startsWith("[CONTEXT COMPACTION")) summary = compactionPrefix() + "\n\n" + summary.trim();
            progress(runId, "summarized", "Checkpoint written (" + summary.length() + " chars). Archiving older turns…");
            JSONObject result = mDatabase.compactSession(runId, summary, 20);
            if (!result.optBoolean("success")) throw new IllegalStateException(result.optString("error", "Compaction failed."));
            progress(runId, "archived", result.optInt("archived_messages", 0) + " messages archived, "
                + result.optInt("protected_tail", 0) + " kept. Rebuilding replay…");
            rebuildReplayFromDatabase(target);
            mContextWarnedSessions.remove(runId);
            try {
                result.put("phase", "done");
                emitForRun(runId, "memory/compactionComplete", result);
            } catch (Exception ignored) {}
            return result;
        } catch (Exception e) {
            try {
                emitForRun(runId, "memory/compactionFailed",
                    new JSONObject().put("error", e.getMessage() == null ? "Compaction failed." : e.getMessage()));
            } catch (Exception ignored) {}
            throw e;
        } finally {
            if (runId.equals(mCompactingRunId)) mCompactingRunId = null;
        }
    }

    private void progress(String runId, String phase, String detail) {
        try {
            emitForRun(runId, "memory/compactionProgress",
                new JSONObject().put("phase", phase).put("detail", detail));
        } catch (Exception ignored) {}
    }

    /** Model context-window estimate in tokens (mobile multi-provider
     * heuristic; Hermes reads the model's own context_length). */
    private static int estimateContextWindowTokens(String model) {
        String m = model == null ? "" : model.toLowerCase(Locale.US);
        if (m.contains("gemini") || m.contains("gpt-4.1")) return 1_000_000;
        if (m.contains("gpt-5")) return 400_000;
        if (m.contains("claude") || m.contains("o1") || m.contains("o3") || m.contains("o4")) return 200_000;
        if (m.contains("gpt-4o") || m.contains("gpt-4") || m.contains("glm") || m.contains("grok")) return 128_000;
        if (m.contains("deepseek")) return 64_000;
        if (m.contains("qwen") || m.contains("llama") || m.contains("mistral") || m.contains("mixtral")) return 32_768;
        return 128_000;
    }

    /** Replay usage % = replay chars/4 vs the model window. Emitted every
     * turn so the Memory page readiness card stays live. */
    private int contextUsagePercent(RunContext ctx, String model) {
        int window = Math.max(4096, estimateContextWindowTokens(model));
        long chars = 0;
        try {
            if (ctx != null && ctx.chatMessages != null) {
                for (int i = 0; i < ctx.chatMessages.length(); i++) {
                    JSONObject msg = ctx.chatMessages.optJSONObject(i);
                    if (msg != null) chars += msg.optString("content", "").length();
                }
            }
        } catch (Exception ignored) {}
        return (int) Math.min(100, (chars / 4 * 100L) / window);
    }

    /** §5.1 gate, called after the user turn is appended and before the
     * model call is built: warn at 90%, single auto-compact at 95%.
     * Returns false when the turn must not proceed. */
    private boolean enforceContextGate(RunContext ctx, String providerId, String baseUrl, String apiKey, String model) {
        if (ctx == null || ctx.record == null || mProviderConfig == null) return true;
        String runId = ctx.record.id;
        int pct = contextUsagePercent(ctx, model);
        int warnAt = mProviderConfig.getMemoryWarnPct();
        int autoAt = Math.max(warnAt, mProviderConfig.getMemoryAutoPct());
        emit("memory/contextUsage", json("usage_percent", String.valueOf(pct)));
        try {
            mDatabase.appendEvent(runId, "memory/contextUsage",
                new JSONObject().put("usage_percent", pct).put("model", model == null ? "" : model).toString());
        } catch (Exception ignored) {}
        if (pct < warnAt) return true;
        if (pct < autoAt) {
            if (!mContextWarnedSessions.contains(runId)) {
                mContextWarnedSessions.add(runId);
                emit("memory/contextWarning", json("usage_percent", String.valueOf(pct)));
                notifyError(runId, "Context is " + pct + "% full. Compact this session from Memory soon — turns continue for now.");
            }
            return true;
        }
        emit("memory/contextAutoCompact", json("usage_percent", String.valueOf(pct)));
        try {
            mDatabase.appendEvent(runId, "memory/contextAutoCompact",
                new JSONObject().put("usage_percent", pct).toString());
        } catch (Exception ignored) {}
        try {
            performCompactionBlocking(ctx, providerId, baseUrl, apiKey, model);
            emit("memory/contextAutoCompactDone", json("usage_percent",
                String.valueOf(contextUsagePercent(ctx, model))));
            return true;
        } catch (Exception e) {
            failRun("Context is " + pct + "% full and automatic compaction failed (" +
                (e.getMessage() == null ? "unknown error" : e.getMessage()) +
                "). Start a new session to continue.");
            return false;
        }
    }

    private void rebuildReplayFromDatabase(RunContext ctx) {
        if (ctx == null || ctx.record == null) return;
        try {
            String system = systemInstructions();
            String todos = ctx.todos.formatForInjection();
            if (!TextUtils.isEmpty(todos)) system += "\n\n" + todos;
            JSONArray rebuilt = new JSONArray().put(json("role", "system", "content", system));
            JSONArray rows = mDatabase.getTranscript(ctx.record.id, 1000);
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) continue;
                String role = row.optString("role", "user");
                if (!"assistant".equals(role) && !"user".equals(role) && !"tool".equals(role)) role = "user";
                JSONObject rebuiltRow = json("role", role, "content", row.optString("content", ""));
                JSONArray images = row.optJSONArray("images");
                if ("user".equals(role) && images != null && images.length() > 0) {
                    rebuiltRow.put("images", images);
                }
                rebuilt.put(rebuiltRow);
            }
            ctx.chatMessages = rebuilt;
            ctx.previousResponseId = null;
            ctx.record.previousResponseId = null;
            ctx.record.chatMessagesJson = rebuilt.toString();
        } catch (Exception e) {
            notifyError(ctx.record.id, "Compaction completed, but replay rebuild failed: " + e.getMessage());
        }
    }

    /**
     * khabeer-style interrupt checkpoint: when the previous turn was cut off
     * mid-response, wrap the user's follow-up in a scaffold that tells the
     * model its own reply was interrupted and shows the visible text it had
     * produced, so "continue" has full context.
     */
private String applyInterruptScaffold(RunContext ctx, String prompt) {
        if (ctx == null || !ctx.lastTurnInterrupted) return prompt;
        ctx.lastTurnInterrupted = false;
        StringBuilder sb = new StringBuilder();
        sb.append("[Context from the interrupted assistant response]\n");
        sb.append("[This response was interrupted by a user correction.]\n");
        sb.append("The previous turn was cut off mid-task. The task is NOT finished.\n\n");
        sb.append(prompt == null ? "" : prompt);
        return sb.toString();
    }

public void stopActiveRun() {
        stopRun(mViewed);
    }

    private void stopRun(RunContext ctx) {
        if (ctx == null) return;
        ctx.stopRequested = true;
        if (ctx.worker != null) KhabeerInterruptManager.setInterrupt(true, ctx.worker.getId(), "stop");
        cancelApprovalsFor(ctx.record.id);
        if (ctx.worker != null) ctx.worker.interrupt();
        if (!isTerminal(ctx)) {
            try {
                if (ctx.stateMachine.getState() == AiRunStateMachine.State.RUNNING || ctx.stateMachine.getState() == AiRunStateMachine.State.WAITING_APPROVAL)
                    ctx.stateMachine.transition(AiRunStateMachine.State.INTERRUPTING);
                ctx.stateMachine.transition(AiRunStateMachine.State.CANCELED);
            } catch (IllegalStateException ignored) {
                try { ctx.stateMachine.transition(AiRunStateMachine.State.CANCELED); } catch (Exception ignored2) {}
            }
            ctx.record.state = AiRunStateMachine.State.CANCELED;
            clearActiveTurn(ctx);
            persistRun(ctx);
            if (ctx.record.sessionKey != null) sessionState(ctx.record.sessionKey).clearTurn();
        }
        synchronized (mQueueLock) {
            if (ctx.record.sessionKey != null) sessionState(ctx.record.sessionKey).conversation.queuedEvents.clear();
        }
    }

public void interruptActiveRun() {
        RunContext c = mViewed;
        if (c == null || c.worker == null) { stopActiveRun(); return; }
        c.stopRequested = true;
        KhabeerInterruptManager.setInterrupt(true, c.worker.getId(), "interrupt");
        try { c.stateMachine.transition(AiRunStateMachine.State.INTERRUPTING); c.record.state = AiRunStateMachine.State.INTERRUPTING; persistRun(c); } catch (Exception ignored) {}
        emit("turn/interrupted", json("reason", "user"));
    }

    public void loginWithChatGpt() {
        notifyError(ctx() == null ? null : ctx().record.id,
            "ChatGPT OAuth is not wired into the native runtime yet. Use provider API keys in configuration for this slice.");
    }

    public void loginWithDeviceCode() {
        notifyError(ctx() == null ? null : ctx().record.id,
            "Device-code login is not wired into the native runtime yet. Use provider API keys in configuration for this slice.");
    }

    public void loginWithApiKey(String apiKey) {
        notifyError(ctx() == null ? null : ctx().record.id,
            "API keys are saved from the provider configuration screen now.");
    }

    public void logout() {
        notifyError(ctx() == null ? null : ctx().record.id,
            "Provider logout will clear saved credentials from configuration in the next slice.");
    }

public void respondToRequest(long id, JSONObject result) {
        PendingApproval approval = mPendingApprovals.remove(id);
        cancelApprovalNotification(id);
        if (approval == null) return; // already answered or auto-denied by timeout
        approval.answer(result != null && result.optBoolean("approved", false));
        RunContext ctx = approval.runId == null ? null : mRuns.get(approval.runId);
        if (ctx != null && ctx.stateMachine.getState() == AiRunStateMachine.State.WAITING_APPROVAL)
            transition(ctx, AiRunStateMachine.State.RUNNING);
    }

    @Override
public void onDestroy() {
        for (RunContext ctx : new ArrayList<>(mRuns.values())) stopRun(ctx);
        mRuns.clear();
        mViewed = null;
        if (mDatabase != null) mDatabase.close();
        super.onDestroy();
    }

private void runTurn(RunContext ctx, String providerId, String baseUrl, String apiKey, String workspace, String prompt,
                         String model, @Nullable String effort, @Nullable String approvalPolicy) {
        runTurn(ctx, providerId, baseUrl, apiKey, workspace, prompt, model, effort, approvalPolicy, true);
    }

    /** @param appendHistory false replays an existing user row (/retry)
     * instead of persisting a duplicate. */
private void runTurn(RunContext ctx, String providerId, String baseUrl, String apiKey, String workspace, String prompt,
                         String model, @Nullable String effort, @Nullable String approvalPolicy, boolean appendHistory) {
        if (ctx.worker != null) ctx.worker.interrupt();
        String tag = ctx.record.id == null ? "runtime" : ctx.record.id.substring(0, Math.min(8, ctx.record.id.length()));
        ctx.worker = new Thread(() -> executeTurn(ctx, providerId, baseUrl, apiKey, workspace,
            prompt == null ? "" : prompt, model, effort, approvalPolicy, appendHistory), "khabeer-" + tag);
        ctx.worker.start();
    }

    private void executeTurn(RunContext ctx, String providerId, String baseUrl, String apiKey, String workspace, String prompt,
                         String model, @Nullable String effort, @Nullable String approvalPolicy) {
        executeTurn(ctx, providerId, baseUrl, apiKey, workspace, prompt, model, effort, approvalPolicy, true);
    }

    private void executeTurn(RunContext ctx, String providerId, String baseUrl, String apiKey, String workspace, String prompt,
                         String model, @Nullable String effort, @Nullable String approvalPolicy, boolean appendHistory) {
        mTurnContext.set(ctx);
        ctx.providerId = providerId;
        ctx.baseUrl = baseUrl;
        ctx.apiKey = apiKey;
        ctx.model = model;
        ctx.effort = effort;
        ctx.approvalPolicy = approvalPolicy;
        KhabeerInterruptManager.clearCurrentThread();
        KhabeerInterruptManager.setInterrupt(false, Thread.currentThread().getId(), null);
        // A continuation after CANCELED/FAILED needs a fresh machine (the FSM
        // has no CANCELED->RUNNING or FAILED->RUNNING edge). Retrying in the
        // same session preserves the transcript instead of orphaning it.
        if (ctx() == null || ctx().stateMachine.getState() == AiRunStateMachine.State.CANCELED
            || ctx().stateMachine.getState() == AiRunStateMachine.State.FAILED
            || ctx().stateMachine.getState() == AiRunStateMachine.State.CREATED) {
            ctx().stateMachine = new AiRunStateMachine();
            try { ctx().stateMachine.transition(AiRunStateMachine.State.STARTING); ctx().stateMachine.transition(AiRunStateMachine.State.CONNECTING); } catch (Exception ignored) {}
            ctx().record.state = AiRunStateMachine.State.STARTING;
        }
        if (ctx().steerText != null) prompt = "[steered] " + ctx().steerText + "\n\n" + prompt;
        ctx().steerText = null;
        emit("turn/started", new JSONObject());
        transition(AiRunStateMachine.State.RUNNING);

        JSONArray input;
        try {
            if (appendHistory) appendUserTurnToHistory(ctx, prompt);
            else {
                AiMemoryStore.resetTurnFailureBudget();
                resetTurnUsage(ctx);
                refreshSystemMessage();
                sanitizeReplayHistory();
                saveChatHistory(ctx);
            }
            if (!enforceContextGate(ctx, providerId, baseUrl, apiKey, model)) return;
            input = toResponsesInput(ctx.chatMessages);
        } catch (Exception e) {
            input = new JSONArray();
            input.put(json("role", "user", "content", prompt));
        }
        ctx().previousResponseId = null;

        try {
            if (usesChatCompletions(providerId)) {
                executeChatCompletionsTurn(ctx, providerId, baseUrl, apiKey, workspace, prompt, model, approvalPolicy);
                return;
            }
            if ("openai-codex".equals(providerId)) {
                executeCodexTurn(ctx, workspace, prompt, model, effort, approvalPolicy);
                return;
            }
            if (usesAnthropicMessages(providerId)) {
                executeAnthropicTurn(ctx, providerId, baseUrl, apiKey, workspace, prompt, model, approvalPolicy);
                return;
            }
            for (int step = 0; step < turnMaxSteps(ctx()) && !ctx().stopRequested && !KhabeerInterruptManager.isInterrupted(); step++) {
                JSONObject response = callResponsesApiWithRetry(providerId, baseUrl, apiKey, model, effort, input);
                JSONArray outputs = response.optJSONArray("output");
                boolean hasToolCall = false;

                if (outputs != null) {
                    for (int i = 0; i < outputs.length(); i++) {
                        JSONObject item = outputs.optJSONObject(i);
                        if (item == null) continue;
                        String type = item.optString("type");
                        if ("message".equals(type)) {
                            emitMessage(item);
                            String text = extractMessageText(item);
                            if (!TextUtils.isEmpty(text)) {
                                try { ctx().chatMessages.put(new JSONObject().put("role", "assistant").put("content", text)); } catch (Exception ignored) {}
                            }
                        } else if (isReasoningType(type)) {
                            emitReasoningItem(item);
                        } else if ("function_call".equals(type)) {
                            hasToolCall = true;
                            try {
                                JSONObject fn = new JSONObject()
                                    .put("role", "assistant")
                                    .put("tool_calls", new JSONArray().put(new JSONObject()
                                        .put("id", item.optString("call_id", item.optString("id")))
                                        .put("type", "function")
                                        .put("function", new JSONObject()
                                            .put("name", item.optString("name"))
                                            .put("arguments", item.optString("arguments", "{}")))));
                                ctx().chatMessages.put(fn);
                                if (ctx() != null) { ctx().record.chatMessagesJson = ctx().chatMessages.toString(); persistRun(); }
                            } catch (Exception ignored) {}
                            JSONObject toolResult = executeToolCall(item, workspace, approvalPolicy);
                            String output = toolResult.optString("output", "");
                            try {
                                ctx().chatMessages.put(new JSONObject()
                                    .put("role", "tool")
                                    .put("tool_call_id", item.optString("call_id", item.optString("id")))
                                    .put("content", output));
                                if (ctx() != null) { ctx().record.chatMessagesJson = ctx().chatMessages.toString(); persistRun(); }
                            } catch (Exception ignored) {}
                            input.put(toolResult);
                        }
                    }
                } else {
                    String text = response.optString("output_text", "");
                    if (!TextUtils.isEmpty(text)) emit("item/agentMessage/delta", json("text", text));
                }

                if (!hasToolCall) {
                    completeRun();
                    return;
                }
            }

        if (!ctx().stopRequested && !KhabeerInterruptManager.isInterrupted()) failRun("The native agent reached its tool-step limit before finishing.");
            else if (KhabeerInterruptManager.isInterrupted()) { emit("turn/interrupted", json("reason", KhabeerInterruptManager.getReason())); ctx().lastTurnInterrupted = true; clearActiveTurn(); }
            else { ctx().lastTurnInterrupted = true; clearActiveTurn(); }
            drainQueueIfNeeded(ctx);
        } catch (Exception e) {
            if (!ctx().stopRequested && !KhabeerInterruptManager.isInterrupted()) {
                if (isNetworkError(e)) { transitionWithFallback(AiRunStateMachine.State.DISCONNECTED); failRun(e.getMessage() == null ? e.toString() : e.getMessage()); }
                else failRun(e.getMessage() == null ? e.toString() : e.getMessage());
            } else { emit("turn/interrupted", json("reason", "cancel")); ctx().lastTurnInterrupted = true; clearActiveTurn(); }
        } finally { KhabeerInterruptManager.clearCurrentThread(); mTurnContext.remove(); }
    }

    /** Single owner for user-turn mutation. Provider adapters must only
     * render this durable chat transcript into their API dialect; they must
     * not append the same user text again. */
    private void appendUserTurnToHistory(RunContext ctx, String prompt) throws Exception {
        if (ctx == null) throw new IllegalStateException("No active run context.");
        AiMemoryStore.resetTurnFailureBudget();
        resetTurnUsage(ctx);
        JSONArray images = ctx.pendingImages;
        ctx.pendingImages = null;
        if (images != null && images.length() == 0) images = null;
        mDatabase.appendMessage(ctx.record.id, "user", prompt, images);
        refreshSystemMessage();
        sanitizeReplayHistory();
        if (ctx.chatMessages == null) ctx.chatMessages = new JSONArray();
        JSONObject userMessage = json("role", "user", "content", prompt);
        if (images != null) userMessage.put("images", images);
        ctx.chatMessages.put(userMessage);
        saveChatHistory(ctx);
    }

    /** Rebuild per-turn system/context metadata without changing the user
     * transcript. This keeps each provider dialect read-only over history. */
    private void refreshReplayHistory(RunContext ctx) {
        if (ctx == null) return;
        refreshSystemMessage();
        sanitizeReplayHistory();
        saveChatHistory(ctx);
    }

    private void saveChatHistory(RunContext ctx) {
        if (ctx == null || ctx.chatMessages == null) return;
        ctx.record.chatMessagesJson = ctx.chatMessages.toString();
        persistRun(ctx);
    }

    private String extractMessageText(JSONObject item) {
        JSONArray content = item.optJSONArray("content");
        if (content == null) return "";
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < content.length(); i++) {
            JSONObject part = content.optJSONObject(i);
            if (part == null) continue;
            String type = part.optString("type");
            if ("output_text".equals(type) || "text".equals(type)) builder.append(part.optString("text"));
        }
        return builder.toString();
    }

    /** Native image parts for a user message (vision port). Hermes shapes:
     * Chat {@code image_url}, Responses/Codex {@code input_image} (user
     * messages only — assistant image parts brick replay), Anthropic image
     * blocks. Returns null when the message carries no usable images, so
     * callers keep the plain string shape. Missing files degrade to a
     * placeholder line instead of failing the turn. */
    private JSONArray userContentParts(String text, JSONObject m, String style) {
        JSONArray images = m == null ? null : m.optJSONArray("images");
        if (images == null || images.length() == 0) return null;
        StringBuilder body = new StringBuilder(text == null ? "" : text);
        JSONArray imageParts = new JSONArray();
        try {
            for (int i = 0; i < images.length(); i++) {
                JSONObject img = images.optJSONObject(i);
                if (img == null) continue;
                String dataUrl = AiAttachments.dataUrl(img);
                if (dataUrl == null) {
                    body.append("\n[image unavailable: ").append(img.optString("name", "?")).append("]");
                    continue;
                }
                if ("anthropic".equals(style)) {
                    int comma = dataUrl.indexOf(',');
                    imageParts.put(new JSONObject().put("type", "image")
                        .put("source", new JSONObject().put("type", "base64")
                            .put("media_type", img.optString("mime", "image/jpeg"))
                            .put("data", comma < 0 ? dataUrl : dataUrl.substring(comma + 1))));
                } else if ("chat".equals(style)) {
                    imageParts.put(new JSONObject().put("type", "image_url")
                        .put("image_url", new JSONObject().put("url", dataUrl)));
                } else {
                    imageParts.put(new JSONObject().put("type", "input_image").put("image_url", dataUrl));
                }
            }
            String baseText = text == null ? "" : text;
            if (imageParts.length() == 0 && body.toString().equals(baseText)) return null;
            String finalText = body.toString();
            if (TextUtils.isEmpty(finalText)) finalText = "(see attached images)";
            JSONArray parts = new JSONArray();
            if ("anthropic".equals(style) || "chat".equals(style)) {
                parts.put(new JSONObject().put("type", "text").put("text", finalText));
            } else {
                parts.put(new JSONObject().put("type", "input_text").put("text", finalText));
            }
            for (int i = 0; i < imageParts.length(); i++) parts.put(imageParts.opt(i));
            return parts;
        } catch (Exception e) {
            return null;
        }
    }

    /** Chat-completions wire history: user messages with images become
     * content arrays; everything else passes through untouched. */
    private JSONArray toChatWireMessages(JSONArray messages) {
        JSONArray out = new JSONArray();
        if (messages == null) return out;
        for (int i = 0; i < messages.length(); i++) {
            JSONObject m = messages.optJSONObject(i);
            if (m == null) continue;
            if ("user".equals(m.optString("role"))) {
                JSONArray parts = userContentParts(m.optString("content", ""), m, "chat");
                if (parts != null) {
                    try {
                        out.put(new JSONObject().put("role", "user").put("content", parts));
                        continue;
                    } catch (Exception ignored) {}
                }
            }
            out.put(m);
        }
        return out;
    }

    /** Convert the chat-format history into Responses-API input items (full replay, khabeer-style). */
    private JSONArray toResponsesInput(JSONArray messages) throws Exception {
        JSONArray input = new JSONArray();
        for (int i = 0; i < messages.length(); i++) {
            JSONObject m = messages.optJSONObject(i);
            if (m == null) continue;
            String role = m.optString("role");
            if ("system".equals(role)) continue;
            if ("user".equals(role)) {
                JSONArray parts = userContentParts(m.optString("content", ""), m, "responses");
                input.put(parts == null
                    ? new JSONObject().put("role", "user").put("content", m.optString("content", ""))
                    : new JSONObject().put("role", "user").put("content", parts));
            } else if ("assistant".equals(role)) {
                JSONArray tcs = m.optJSONArray("tool_calls");
                if (tcs != null && tcs.length() > 0) {
                    String c = m.optString("content", "");
                    if (!TextUtils.isEmpty(c)) input.put(new JSONObject().put("role", "assistant").put("content", c));
                    for (int k = 0; k < tcs.length(); k++) {
                        JSONObject tc = tcs.optJSONObject(k);
                        JSONObject fn = tc == null ? null : tc.optJSONObject("function");
                        if (fn == null) continue;
                        input.put(new JSONObject()
                            .put("type", "function_call")
                            .put("call_id", tc.optString("id"))
                            .put("name", fn.optString("name"))
                            .put("arguments", fn.optString("arguments", "{}")));
                    }
                } else {
                    input.put(new JSONObject().put("role", "assistant").put("content", m.optString("content", "")));
                }
            } else if ("tool".equals(role)) {
                input.put(new JSONObject()
                    .put("type", "function_call_output")
                    .put("call_id", m.optString("tool_call_id"))
                    .put("output", m.optString("content", "")));
            }
        }
        return input;
    }

    private boolean isNetworkError(Exception e) {
        String m = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.US);
        return m.contains("timeout") || m.contains("unable to resolve") || m.contains("failed to connect") || m.contains("network");
    }

    private void transitionWithFallback(AiRunStateMachine.State target) {
        try { transition(target); } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------------
    // Codex (ChatGPT subscription) runtime — Responses API over the
    // chatgpt.com backend with device-code login tokens.
    // ------------------------------------------------------------------

    private static final long CODEX_REFRESH_SKEW_MS = 120_000;
    private final Object mCodexAuthLock = new Object();

    /** Raised on 401/403 from the Codex backend so the caller can force one
     *  refresh+retry before surfacing the error (mirrors the reference
     *  one-retry-per-turn rule). */
    private static final class CodexAuthRequired extends Exception {
        final String staleToken;
        CodexAuthRequired(String staleToken, String message) { super(message); this.staleToken = staleToken; }
    }

    private void executeCodexTurn(RunContext ctx, String workspace, String prompt, String model,
                                  @Nullable String effort, @Nullable String approvalPolicy) throws Exception {
        JSONArray input;
        try {
            refreshReplayHistory(ctx);
            input = toCodexResponsesInput(ctx.chatMessages);
        } catch (Exception e) {
            input = new JSONArray();
            input.put(new JSONObject().put("role", "user")
                .put("content", new JSONArray().put(new JSONObject().put("type", "input_text").put("text", prompt))));
        }

        try {
            for (int step = 0; step < turnMaxSteps(ctx()) && !ctx().stopRequested && !KhabeerInterruptManager.isInterrupted(); step++) {
                JSONObject response = callCodexApiWithRetry(model, effort, input);
                JSONArray outputs = response.optJSONArray("output");
                boolean hasToolCall = false;
                if (outputs != null) {
                    for (int i = 0; i < outputs.length(); i++) {
                        JSONObject item = outputs.optJSONObject(i);
                        if (item == null) continue;
                        String type = item.optString("type");
                        if ("message".equals(type)) {
                            emitMessage(item);
                            String text = extractMessageText(item);
                            if (!TextUtils.isEmpty(text)) {
                                try {
                                    JSONObject assistantMessage = new JSONObject().put("role", "assistant").put("content", text);
                                    captureCodexReplayItems(outputs, assistantMessage);
                                    ctx().chatMessages.put(assistantMessage);
                                    if (ctx() != null) { ctx().record.chatMessagesJson = ctx().chatMessages.toString(); persistRun(); }
                                } catch (Exception ignored) {}
                            }
                        } else if (isReasoningType(type)) {
                            emitReasoningItem(item);
                        } else if ("function_call".equals(type)) {
                            hasToolCall = true;
                            try {
                                ctx().chatMessages.put(new JSONObject()
                                    .put("role", "assistant")
                                    .put("tool_calls", new JSONArray().put(new JSONObject()
                                        .put("id", item.optString("call_id", item.optString("id")))
                                        .put("type", "function")
                                        .put("function", new JSONObject()
                                            .put("name", item.optString("name"))
                                            .put("arguments", item.optString("arguments", "{}"))))));
                                if (ctx() != null) { ctx().record.chatMessagesJson = ctx().chatMessages.toString(); persistRun(); }
                            } catch (Exception ignored) {}
                            JSONObject toolResult = executeToolCall(item, workspace, approvalPolicy);
                            String output = toolResult.optString("output", "");
                            try {
                                ctx().chatMessages.put(new JSONObject()
                                    .put("role", "tool")
                                    .put("tool_call_id", item.optString("call_id", item.optString("id")))
                                    .put("content", output));
                                if (ctx() != null) { ctx().record.chatMessagesJson = ctx().chatMessages.toString(); persistRun(); }
                            } catch (Exception ignored) {}
                            // The ChatGPT Codex backend requires the model's
                            // function_call item echoed before its output
                            // (openai.com tolerates the omission; chatgpt.com
                            // answers 400 "No tool call found for function
                            // call output").
                            JSONObject echo = new JSONObject()
                                .put("type", "function_call")
                                .put("call_id", item.optString("call_id", item.optString("id")))
                                .put("name", item.optString("name"))
                                .put("arguments", item.optString("arguments", "{}"));
                            input.put(echo);
                            input.put(toolResult);
                        }
                    }
                }
                if (!hasToolCall) {
                    completeRun();
                    return;
                }
            }
            if (!ctx().stopRequested && !KhabeerInterruptManager.isInterrupted()) failRun("The Codex agent reached its tool-step limit before finishing.");
            else { ctx().lastTurnInterrupted = true; clearActiveTurn(); }
            drainQueueIfNeeded(ctx);
        } catch (Exception e) {
            if (!ctx().stopRequested && !KhabeerInterruptManager.isInterrupted()) failRun(e.getMessage() == null ? e.toString() : e.getMessage());
            else { emit("turn/interrupted", json("reason", "cancel")); ctx().lastTurnInterrupted = true; clearActiveTurn(); }
        }
    }

    private JSONObject callCodexApiWithRetry(String model, @Nullable String effort, JSONArray input) throws Exception {
        int authRetries = 0;
        int maxRetries = 3;
        for (int attempt = 0; ; attempt++) {
            try {
                return callCodexApi(model, effort, input);
            } catch (CodexAuthRequired e) {
                if (authRetries++ > 0) throw new IllegalStateException(e.getMessage());
                codexForceRefresh(e.staleToken);
                attempt--; // auth retry doesn't consume a backoff attempt
                continue;
            } catch (Exception e) {
                String msg = e.getMessage() == null ? "" : e.getMessage();
                boolean retryable = msg.contains("HTTP 429") || msg.contains("HTTP 5");
                if (attempt < maxRetries && retryable && !ctx().stopRequested) {
                    long backoff = KhabeerRetry.jitteredBackoff(attempt, 1000, 8000);
                    try { Thread.sleep(backoff); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw ie; }
                    continue;
                }
                throw e;
            }
        }
    }

    private JSONObject callCodexApi(String model, @Nullable String effort, JSONArray input) throws Exception {
        JSONObject creds = codexCredentials();
        String token = creds.getString("token");
        String accountId = creds.optString("accountId", null);

        JSONObject body = new JSONObject()
            .put("model", model)
            .put("instructions", systemInstructions())
            .put("input", input)
            .put("store", false)
            .put("stream", true);
        JSONArray tools = modelTools(false);
        if (tools.length() > 0) {
            body.put("tools", tools).put("tool_choice", "auto").put("parallel_tool_calls", true);
        }
        if (!TextUtils.isEmpty(effort)) {
            body.put("reasoning", new JSONObject().put("effort", codexEffort(effort)).put("summary", "auto"));
        } else {
            // gpt-5.6 Codex models are reasoning models — always send a
            // valid effort level (the backend 500s on missing reasoning
            // for some sessions; "medium" matches the reference default).
            body.put("reasoning", new JSONObject().put("effort", "medium").put("summary", "auto"));
        }
        body.put("include", new JSONArray().put("reasoning.encrypted_content"));

        HttpURLConnection connection = (HttpURLConnection) new URL(ProviderLogin.CODEX_BASE_URL + "/responses").openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(MODEL_CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(MODEL_READ_TIMEOUT_MS);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "text/event-stream");
        connection.setRequestProperty("Authorization", "Bearer " + token);
        connection.setRequestProperty("originator", "khabeer-agent");
        connection.setRequestProperty("User-Agent", "KhabeerAgent/1.0");
        connection.setRequestProperty("x-client-request-id", java.util.UUID.randomUUID().toString());
        if (!TextUtils.isEmpty(accountId)) connection.setRequestProperty("ChatGPT-Account-Id", accountId);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        int code = connection.getResponseCode();
        if (code == 401 || code == 403) {
            String error = readFully(connection.getErrorStream());
            throw new CodexAuthRequired(token, "ChatGPT rejected the session token (HTTP " + code + ")");
        }
        InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
        String text = readFully(stream);
        if (code < 200 || code >= 300)
            throw new IllegalStateException("Codex request failed with HTTP " + code + ": " + text);
        return parseCodexStream(text);
    }

    /** Background calls (review, compaction, titles) for the Codex backend:
     * the generic Responses shape 404s there, so this speaks the Codex
     * dialect directly — non-streaming, no tools, account headers. */
    private String callBackgroundCodex(String model, String prompt, String system) throws Exception {
        JSONObject creds = codexCredentials();
        String token = creds.getString("token");
        String accountId = creds.optString("accountId", null);
        JSONArray input = new JSONArray()
            .put(new JSONObject().put("role", "user")
                .put("content", new JSONArray().put(new JSONObject()
                    .put("type", "input_text").put("text", prompt))));
        JSONObject body = new JSONObject()
            .put("model", model)
            .put("instructions", system)
            .put("input", input)
            .put("store", false)
            .put("stream", true)
            .put("reasoning", new JSONObject().put("effort", "low").put("summary", "auto"));
        HttpURLConnection connection = (HttpURLConnection) new URL(ProviderLogin.CODEX_BASE_URL + "/responses").openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(MODEL_CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(MODEL_READ_TIMEOUT_MS);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "text/event-stream");
        connection.setRequestProperty("Authorization", "Bearer " + token);
        connection.setRequestProperty("originator", "khabeer-agent");
        connection.setRequestProperty("User-Agent", "KhabeerAgent/1.0");
        connection.setRequestProperty("x-client-request-id", java.util.UUID.randomUUID().toString());
        if (!TextUtils.isEmpty(accountId)) connection.setRequestProperty("ChatGPT-Account-Id", accountId);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        int code = connection.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
        String text = readFully(stream);
        if (code < 200 || code >= 300)
            throw new IllegalStateException("Background Codex request failed with HTTP " + code + ": " + text);
        JSONObject parsed = parseCodexStream(text);
        JSONArray outputItems = parsed.optJSONArray("output");
        StringBuilder out = new StringBuilder();
        if (outputItems != null) {
            for (int i = 0; i < outputItems.length(); i++) {
                JSONObject item = outputItems.optJSONObject(i);
                if (item == null || !"message".equals(item.optString("type"))) continue;
                JSONArray content = item.optJSONArray("content");
                if (content == null) continue;
                for (int j = 0; j < content.length(); j++) {
                    JSONObject part = content.optJSONObject(j);
                    if (part == null) continue;
                    String t = part.optString("text", "");
                    if (!TextUtils.isEmpty(t)) {
                        if (out.length() > 0) out.append("\n");
                        out.append(t);
                    }
                }
            }
        }
        return out.toString();
    }

    /** Assembles the final Responses output array from the SSE event stream:
     *  output_item.done events carry the full items; the terminal
     *  response.completed event is preferred when it carries a non-empty
     *  output (backend occasionally nulls it, hence the fallback). */
    private JSONObject parseCodexStream(String sse) throws Exception {
        java.util.SortedMap<Integer, JSONObject> items = new java.util.TreeMap<>();
        JSONArray completedOutput = null;
        String failure = null;
        String[] lines = sse.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (!line.startsWith("data:")) continue;
            String payload = line.substring(5).trim();
            if (payload.isEmpty() || "[DONE]".equals(payload)) continue;
            JSONObject event;
            try { event = new JSONObject(payload); } catch (Exception e) { continue; }
            String type = event.optString("type", "");
            switch (type) {
                case "response.output_item.done": {
                    JSONObject item = event.optJSONObject("item");
                    if (item != null) items.put(event.optInt("output_index", items.size()), item);
                    break;
                }
                case "response.completed":
                case "response.incomplete": {
                    JSONObject response = event.optJSONObject("response");
                    JSONArray output = response == null ? null : response.optJSONArray("output");
                    if (output != null && output.length() > 0) completedOutput = output;
                    if (response != null) noteUsage(response.optJSONObject("usage"));
                    break;
                }
                case "response.failed": {
                    JSONObject response = event.optJSONObject("response");
                    JSONObject error = response == null ? null : response.optJSONObject("error");
                    failure = error == null ? "Codex request failed" : error.optString("message", "Codex request failed");
                    break;
                }
                case "error": {
                    JSONObject error = event.optJSONObject("error");
                    failure = error == null ? "Codex stream error" : error.optString("message", "Codex stream error");
                    break;
                }
                default:
                    break;
            }
        }
        if (failure != null) throw new IllegalStateException(failure);
        JSONArray output = new JSONArray();
        if (completedOutput != null) {
            for (int i = 0; i < completedOutput.length(); i++) output.put(completedOutput.opt(i));
        } else {
            for (JSONObject item : items.values()) output.put(item);
        }
        return new JSONObject().put("output", output);
    }

    /** Current Codex bearer token, refreshed when the JWT is inside the
     *  120s expiry skew. Returns {token, accountId}. */
    private JSONObject codexCredentials() throws Exception {
        String token = mProviderConfig.getProviderToken("openai-codex");
        if (TextUtils.isEmpty(token))
            throw new IllegalStateException("Not signed in to ChatGPT. Sign in from the provider page first.");
        long expMs = ProviderLogin.jwtExpiryEpochSeconds(token) * 1000L;
        if (expMs == 0 || System.currentTimeMillis() > expMs - CODEX_REFRESH_SKEW_MS) {
            token = codexForceRefresh(token);
        }
        return new JSONObject().put("token", token)
            .put("accountId", ProviderLogin.codexAccountId(token) == null ? "" : ProviderLogin.codexAccountId(token));
    }

    /** Refresh under a lock; returns the fresh access token. 429 = quota
     *  (credentials stay), terminal errors clear the login and ask for a
     *  new sign-in. */
    private String codexForceRefresh(String currentToken) throws Exception {
        synchronized (mCodexAuthLock) {
            String token = mProviderConfig.getProviderToken("openai-codex");
            long expMs = TextUtils.isEmpty(token) ? 0 : ProviderLogin.jwtExpiryEpochSeconds(token) * 1000L;
            if (!TextUtils.isEmpty(token) && !token.equals(currentToken)
                && expMs > System.currentTimeMillis() + CODEX_REFRESH_SKEW_MS) {
                return token; // another thread already refreshed
            }
            String refresh = mProviderConfig.getProviderRefresh("openai-codex");
            if (TextUtils.isEmpty(refresh))
                throw new IllegalStateException("ChatGPT session expired — sign in again from the provider page.");
            try {
                JSONObject tokens = ProviderLogin.codexRefresh(refresh);
                String access = tokens.optString("access_token", "");
                if (TextUtils.isEmpty(access)) throw new IllegalStateException("ChatGPT refresh returned no access_token.");
                mProviderConfig.setProviderToken("openai-codex", access);
                String rotated = tokens.optString("refresh_token", "");
                if (!TextUtils.isEmpty(rotated)) mProviderConfig.setProviderRefresh("openai-codex", rotated);
                return access;
            } catch (ProviderLogin.HttpError e) {
                if (e.code == 429) throw new IllegalStateException("ChatGPT is rate limiting token refresh — try again shortly.");
                mProviderConfig.setProviderToken("openai-codex", null);
                mProviderConfig.setProviderRefresh("openai-codex", null);
                throw new IllegalStateException("ChatGPT session expired — sign in again from the provider page.");
            }
        }
    }

    /** Map the app's effort vocabulary onto the Codex backend's. */
    private static String codexEffort(String effort) {
        if ("ultra".equals(effort)) return "xhigh";
        if ("auto".equals(effort) || TextUtils.isEmpty(effort)) return "medium";
        return effort;
    }

    /**
     * Codex-strict conversion of chat history into Responses input items
     * (port of the reference codex_responses_adapter): user/assistant text
     * uses typed content parts, assistant items replay captured reasoning
     * (encrypted_content) and message items for cache hits, tool calls
     * become function_call/function_call_output items.
     */
    private JSONArray toCodexResponsesInput(JSONArray messages) throws Exception {
        JSONArray input = new JSONArray();
        for (int i = 0; i < messages.length(); i++) {
            JSONObject m = messages.optJSONObject(i);
            if (m == null) continue;
            String role = m.optString("role");
            if ("system".equals(role)) continue;
            if ("user".equals(role)) {
                JSONArray parts = userContentParts(m.optString("content", ""), m, "responses");
                if (parts == null) {
                    parts = new JSONArray().put(new JSONObject().put("type", "input_text")
                        .put("text", m.optString("content", "")));
                }
                input.put(new JSONObject().put("role", "user").put("content", parts));
            } else if ("assistant".equals(role)) {
                JSONArray reasoning = m.optJSONArray("codex_reasoning_items");
                if (reasoning != null) {
                    for (int k = 0; k < reasoning.length(); k++) {
                        JSONObject ri = reasoning.optJSONObject(k);
                        if (ri != null && !TextUtils.isEmpty(ri.optString("encrypted_content"))) {
                            JSONObject replay = new JSONObject();
                            java.util.Iterator<String> keys = ri.keys();
                            while (keys.hasNext()) {
                                String key = keys.next();
                                if (!"id".equals(key) && !"_issuer_kind".equals(key)) replay.put(key, ri.opt(key));
                            }
                            input.put(replay);
                        }
                    }
                }
                JSONArray messageItems = m.optJSONArray("codex_message_items");
                if (messageItems != null && messageItems.length() > 0) {
                    for (int k = 0; k < messageItems.length(); k++) {
                        JSONObject mi = messageItems.optJSONObject(k);
                        if (mi == null || !"message".equals(mi.optString("type"))) continue;
                        JSONArray parts = new JSONArray();
                        JSONArray content = mi.optJSONArray("content");
                        if (content != null) {
                            for (int p = 0; p < content.length(); p++) {
                                JSONObject part = content.optJSONObject(p);
                                if (part == null) continue;
                                String type = part.optString("type", "");
                                if ("output_text".equals(type) || "text".equals(type))
                                    parts.put(new JSONObject().put("type", "output_text").put("text", part.optString("text", "")));
                            }
                        }
                        if (parts.length() == 0) continue;
                        input.put(new JSONObject()
                            .put("type", "message")
                            .put("role", "assistant")
                            .put("status", "completed")
                            .put("content", parts));
                    }
                } else {
                    JSONArray tcs = m.optJSONArray("tool_calls");
                    if (tcs != null && tcs.length() > 0) {
                        String c = m.optString("content", "");
                        if (!TextUtils.isEmpty(c)) {
                            input.put(new JSONObject().put("type", "message").put("role", "assistant")
                                .put("status", "completed")
                                .put("content", new JSONArray().put(new JSONObject().put("type", "output_text").put("text", c))));
                        }
                        for (int k = 0; k < tcs.length(); k++) {
                            JSONObject tc = tcs.optJSONObject(k);
                            JSONObject fn = tc == null ? null : tc.optJSONObject("function");
                            if (fn == null) continue;
                            input.put(new JSONObject()
                                .put("type", "function_call")
                                .put("call_id", tc.optString("id"))
                                .put("name", fn.optString("name"))
                                .put("arguments", fn.optString("arguments", "{}")));
                        }
                    } else {
                        String text = m.optString("content", "");
                        if (TextUtils.isEmpty(text)) continue; // empty output_text parts 400 on the Codex backend
                        input.put(new JSONObject().put("type", "message").put("role", "assistant")
                            .put("status", "completed")
                            .put("content", new JSONArray().put(new JSONObject().put("type", "output_text").put("text", text))));
                    }
                }
            } else if ("tool".equals(role)) {
                input.put(new JSONObject()
                    .put("type", "function_call_output")
                    .put("call_id", m.optString("tool_call_id"))
                    .put("output", m.optString("content", "")));
            }
        }
        return input;
    }

    /** Collects reasoning/message items worth replaying on the next turn. */
    private void captureCodexReplayItems(JSONArray outputs, JSONObject assistantMessage) {
        try {
            if (outputs == null || assistantMessage == null) return;
            JSONArray reasoning = null;
            JSONArray messageItems = null;
            for (int i = 0; i < outputs.length(); i++) {
                JSONObject item = outputs.optJSONObject(i);
                if (item == null) continue;
                String type = item.optString("type");
                if (isReasoningType(type) && !TextUtils.isEmpty(item.optString("encrypted_content", ""))) {
                    if (reasoning == null) reasoning = new JSONArray();
                    reasoning.put(item);
                } else if ("message".equals(type) && "assistant".equals(item.optString("role"))) {
                    if (messageItems == null) messageItems = new JSONArray();
                    messageItems.put(item);
                }
            }
            if (reasoning != null) assistantMessage.put("codex_reasoning_items", reasoning);
            if (messageItems != null) assistantMessage.put("codex_message_items", messageItems);
        } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------------
    // Anthropic Messages dialect (api.anthropic.com, MiniMax, Kimi /coding,
    // Tencent TokenPlan). Buffered requests; tools via tool_use/tool_result.
    // ------------------------------------------------------------------

    private void executeAnthropicTurn(RunContext ctx, String providerId, String baseUrl, String apiKey,
                                      String workspace, String prompt, String model,
                                      @Nullable String approvalPolicy) throws Exception {
        refreshReplayHistory(ctx);

        try {
            for (int step = 0; step < turnMaxSteps(ctx()) && !ctx().stopRequested && !KhabeerInterruptManager.isInterrupted(); step++) {
                JSONObject response = callAnthropicApi(providerId, baseUrl, apiKey, model, ctx().chatMessages);
                JSONArray content = response.optJSONArray("content");
                StringBuilder textBuilder = new StringBuilder();
                JSONArray toolCalls = new JSONArray();
                if (content != null) {
                    for (int i = 0; i < content.length(); i++) {
                        JSONObject block = content.optJSONObject(i);
                        if (block == null) continue;
                        String type = block.optString("type");
                        if ("text".equals(type)) {
                            textBuilder.append(block.optString("text", ""));
                        } else if ("tool_use".equals(type)) {
                            JSONObject input = block.optJSONObject("input");
                            toolCalls.put(new JSONObject()
                                .put("id", block.optString("id"))
                                .put("type", "function")
                                .put("function", new JSONObject()
                                    .put("name", block.optString("name"))
                                    .put("arguments", input == null ? "{}" : input.toString())));
                        }
                    }
                }
                String text = textBuilder.toString();
                boolean hasToolCalls = toolCalls.length() > 0;
                if (!TextUtils.isEmpty(text)) {
                    emit("item/agentMessage/delta", json("text", text));
                }
                JSONObject assistantMessage = new JSONObject().put("role", "assistant").put("content", text);
                if (hasToolCalls) assistantMessage.put("tool_calls", toolCalls);
                ctx().chatMessages.put(assistantMessage);
                if (ctx() != null) { ctx().record.chatMessagesJson = ctx().chatMessages.toString(); persistRun(); }

                if (!hasToolCalls) {
                    completeRun();
                    clearActiveTurn();
                    drainQueueIfNeeded(ctx);
                    return;
                }
                for (int i = 0; i < toolCalls.length(); i++) {
                    JSONObject toolCall = toolCalls.optJSONObject(i);
                    if (toolCall == null) continue;
                    JSONObject toolResult = executeChatToolCall(toolCall, workspace, approvalPolicy);
                    ctx().chatMessages.put(toolResult);
                    if (ctx() != null) { ctx().record.chatMessagesJson = ctx().chatMessages.toString(); persistRun(); }
                }
            }
            if (!ctx().stopRequested && !KhabeerInterruptManager.isInterrupted())
                failRun("The Anthropic-dialect agent reached its tool-step limit before finishing.");
            else { ctx().lastTurnInterrupted = true; clearActiveTurn(); }
            drainQueueIfNeeded(ctx);
        } catch (Exception e) {
            if (!ctx().stopRequested && !KhabeerInterruptManager.isInterrupted()) failRun(e.getMessage() == null ? e.toString() : e.getMessage());
            else { emit("turn/interrupted", json("reason", "cancel")); ctx().lastTurnInterrupted = true; clearActiveTurn(); }
        }
    }

    /** OAuth tokens (sk-ant-oat…, JWTs, cc-…) authenticate with Bearer and
     *  the OAuth beta headers; API keys use x-api-key (reference rule). */
    private static boolean isAnthropicOAuthToken(String key) {
        if (TextUtils.isEmpty(key)) return false;
        if (key.startsWith("sk-ant-api")) return false;
        return key.startsWith("sk-ant-") || key.startsWith("cc-") || key.startsWith("eyJ");
    }

    private String anthropicMessagesUrl(String baseUrl) {
        String clean = baseUrl == null ? "" : baseUrl.trim();
        if (clean.endsWith("/messages")) return clean;
        if (clean.endsWith("/")) clean = clean.substring(0, clean.length() - 1);
        return clean + "/v1/messages";
    }

    private JSONObject callAnthropicApi(String providerId, String baseUrl, String apiKey, String model,
                                        JSONArray messages) throws Exception {
        boolean oauth = isAnthropicOAuthToken(apiKey);
        JSONObject body = new JSONObject()
            .put("model", model)
            .put("max_tokens", 8192)
            .put("stream", false)
            .put("system", systemInstructions())
            .put("messages", toAnthropicMessages(messages));
        JSONArray flatTools = modelTools(false);
        JSONArray tools = new JSONArray();
        for (int i = 0; i < flatTools.length(); i++) {
            JSONObject tool = flatTools.optJSONObject(i);
            if (tool == null) continue;
            tools.put(new JSONObject()
                .put("name", tool.optString("name"))
                .put("description", tool.optString("description"))
                .put("input_schema", tool.optJSONObject("parameters")));
        }
        if (tools.length() > 0) {
            body.put("tools", tools).put("tool_choice", new JSONObject().put("type", "auto"));
        }

        HttpURLConnection connection = (HttpURLConnection) new URL(anthropicMessagesUrl(baseUrl)).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(MODEL_CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(MODEL_READ_TIMEOUT_MS);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "application/json");
        if (oauth) {
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            connection.setRequestProperty("anthropic-beta", "claude-code-20250219,oauth-2025-04-20");
            connection.setRequestProperty("User-Agent", "claude-code/2.1.74 (external, cli)");
            connection.setRequestProperty("x-app", "cli");
        } else {
            connection.setRequestProperty("x-api-key", apiKey == null ? "" : apiKey);
            connection.setRequestProperty("anthropic-version", "2023-06-01");
            applyOpenCodeSessionHeader(connection, anthropicMessagesUrl(baseUrl), turnSessionId(ctx()));
        }
        try (OutputStream output = connection.getOutputStream()) {
            output.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        int code = connection.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
        String text = readFully(stream);
        if (code < 200 || code >= 300)
            throw new IllegalStateException("Provider request failed with HTTP " + code + ": " + text);
        JSONObject parsed = new JSONObject(text);
        noteUsage(parsed.optJSONObject("usage"));
        return parsed;
    }

    /** Chat-style history → Anthropic messages; tool_calls become tool_use
     *  blocks and tool results become user tool_result blocks. */
    private JSONArray toAnthropicMessages(JSONArray messages) throws Exception {
        JSONArray out = new JSONArray();
        for (int i = 0; i < messages.length(); i++) {
            JSONObject m = messages.optJSONObject(i);
            if (m == null) continue;
            String role = m.optString("role");
            if ("system".equals(role)) continue;
            if ("user".equals(role)) {
                JSONArray parts = userContentParts(m.optString("content", ""), m, "anthropic");
                if (parts == null) {
                    parts = new JSONArray().put(new JSONObject().put("type", "text")
                        .put("text", m.optString("content", "")));
                }
                out.put(new JSONObject().put("role", "user").put("content", parts));
            } else if ("assistant".equals(role)) {
                JSONArray blocks = new JSONArray();
                String c = m.optString("content", "");
                if (!TextUtils.isEmpty(c)) blocks.put(new JSONObject().put("type", "text").put("text", c));
                JSONArray tcs = m.optJSONArray("tool_calls");
                if (tcs != null) {
                    for (int k = 0; k < tcs.length(); k++) {
                        JSONObject tc = tcs.optJSONObject(k);
                        JSONObject fn = tc == null ? null : tc.optJSONObject("function");
                        if (fn == null) continue;
                        JSONObject input;
                        try { input = new JSONObject(fn.optString("arguments", "{}")); } catch (Exception e) { input = new JSONObject(); }
                        blocks.put(new JSONObject()
                            .put("type", "tool_use")
                            .put("id", tc.optString("id"))
                            .put("name", fn.optString("name"))
                            .put("input", input));
                    }
                }
                if (blocks.length() > 0) out.put(new JSONObject().put("role", "assistant").put("content", blocks));
            } else if ("tool".equals(role)) {
                out.put(new JSONObject().put("role", "user")
                    .put("content", new JSONArray().put(new JSONObject()
                        .put("type", "tool_result")
                        .put("tool_use_id", m.optString("tool_call_id"))
                        .put("content", m.optString("content", "")))));
            }
        }
        return out;
    }

    private JSONObject callResponsesApiWithRetry(String providerId, String baseUrl, String apiKey, String model, @Nullable String effort, JSONArray input) throws Exception {        int maxRetries = 3;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try { return callResponsesApi(providerId, baseUrl, apiKey, model, effort, input); }
            catch (Exception e) {
                String msg = e.getMessage() == null ? "" : e.getMessage();
                boolean retryable = msg.contains("HTTP 429") || msg.contains("HTTP 5");
                if (attempt < maxRetries && retryable && !ctx().stopRequested) {
                    long backoff = KhabeerRetry.jitteredBackoff(attempt, 1000, 8000);
                    try { Thread.sleep(backoff); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw ie; }
                    continue;
                }
                throw e;
            }
        }
        throw new IllegalStateException("retry exhausted");
    }

    private void executeChatCompletionsTurn(RunContext ctx, String providerId, String baseUrl, String apiKey, String workspace,
                                            String prompt, String model, @Nullable String approvalPolicy) throws Exception {
        if (ctx().chatMessages == null)
            ctx().chatMessages = new JSONArray();
        refreshReplayHistory(ctx);

        for (int step = 0; step < turnMaxSteps(ctx()) && !ctx().stopRequested && !KhabeerInterruptManager.isInterrupted(); step++) {
            JSONObject response = callChatCompletionsApiWithRetry(providerId, baseUrl, apiKey, model, toChatWireMessages(ctx().chatMessages));
            JSONArray choices = response.optJSONArray("choices");
            JSONObject choice = choices == null ? null : choices.optJSONObject(0);
            JSONObject message = choice == null ? null : choice.optJSONObject("message");
            if (message == null) {
                failRun("OpenCode returned no assistant message.");
                return;
            }

            emitReasoningFromChatMessage(message);
            String content = message.optString("content", "");
            JSONArray toolCalls = message.optJSONArray("tool_calls");
            boolean hasToolCalls = toolCalls != null && toolCalls.length() > 0;
            if (!TextUtils.isEmpty(content)) {
                if (hasToolCalls) emit("item/reasoning/delta", json("text", content));
                else emit("item/agentMessage/delta", json("text", content));
            }
            ctx().chatMessages.put(message);
            if (ctx() != null) { ctx().record.chatMessagesJson = ctx().chatMessages.toString(); persistRun(); }

            if (!hasToolCalls) {
                // The full reply is already persisted exactly once by emit()
                // (item/agentMessage/delta) — appending it here as well made
                // every chat reply appear twice after a transcript rebuild.
                completeRun();
                clearActiveTurn();
                drainQueueIfNeeded(ctx);
                return;
            }

            for (int i = 0; i < toolCalls.length(); i++) {
                JSONObject toolCall = toolCalls.optJSONObject(i);
                if (toolCall == null) continue;
                JSONObject toolResult = executeChatToolCall(toolCall, workspace, approvalPolicy);
                ctx().chatMessages.put(toolResult);
                if (ctx() != null) { ctx().record.chatMessagesJson = ctx().chatMessages.toString(); persistRun(); }
            }
        }

        if (!ctx().stopRequested && !KhabeerInterruptManager.isInterrupted()) failRun("The chat-completions agent reached its tool-step limit before finishing.");
        else { ctx().lastTurnInterrupted = true; clearActiveTurn(); }
        drainQueueIfNeeded(ctx);
    }

    /**
     * khabeer-style replay cleanup (agent/replay_cleanup.py): a turn killed
     * mid-tool-loop can leave a trailing assistant(tool_calls) with NO tool
     * answers. Replaying that dangling tail makes the model re-issue the call
     * or lose the plot. Synthesize orphan-recovery tool results so the model
     * knows the tool "may have executed; effect UNKNOWN".
     */
    private void sanitizeReplayHistory() {
        if (ctx().chatMessages == null) return;
        JSONArray cleaned = new JSONArray();
        int n = ctx().chatMessages.length();
        for (int i = 0; i < n; i++) {
            JSONObject msg = ctx().chatMessages.optJSONObject(i);
            if (msg == null) continue;
            String role = msg.optString("role", "");
            if ("assistant".equals(role) && msg.optJSONArray("tool_calls") != null) {
                cleaned.put(msg);
                JSONArray toolCalls = msg.optJSONArray("tool_calls");
                boolean anyAnswer = false;
                for (int j = i + 1; j < n; j++) {
                    JSONObject nxt = ctx().chatMessages.optJSONObject(j);
                    if (nxt == null || !"tool".equals(nxt.optString("role"))) break;
                    anyAnswer = true;
                }
                if (!anyAnswer && toolCalls != null) {
                    for (int k = 0; k < toolCalls.length(); k++) {
                        JSONObject tc = toolCalls.optJSONObject(k);
                        if (tc == null) continue;
                        try {
                            cleaned.put(new JSONObject()
                                .put("role", "tool")
                                .put("tool_call_id", tc.optString("id"))
                                .put("content", "[Orphan recovery: this tool may have executed before the interruption; its effect is UNKNOWN. Inspect current state before retrying.]"));
                        } catch (Exception ignored) {}
                    }
                }
                continue;
            }
            cleaned.put(msg);
        }
        ctx().chatMessages = cleaned;
    }

    /** Chat-completions request body. Some free-tier models 500 on function
     * calling, so the tools block must be omittable (last-resort plain
     * retry) — never send an empty tools array with tool_choice. */
    static JSONObject chatCompletionsBody(String model, JSONArray messages, @Nullable JSONArray tools) throws Exception {
        JSONObject body = new JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("stream", true);
        if (tools != null && tools.length() > 0) {
            body.put("tools", tools).put("tool_choice", "auto");
        }
        return body;
    }

    /** Retried chat-completions call (429 + 5xx, like the other dialects).
     * If 5xx persists with tools attached, one final attempt goes out as a
     * plain completion: a reply without tool use beats a dead turn on
     * models whose function-calling path is broken. */
    private JSONObject callChatCompletionsApiWithRetry(String providerId, String baseUrl, String apiKey, String model,
                                                       JSONArray messages) throws Exception {
        int maxRetries = 3;
        Exception last = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return callChatCompletionsApi(providerId, baseUrl, apiKey, model, messages, true);
            } catch (Exception e) {
                last = e;
                String msg = e.getMessage() == null ? "" : e.getMessage();
                boolean retryable = msg.contains("HTTP 429") || msg.contains("HTTP 5");
                if (attempt < maxRetries && retryable && ctx() != null && !ctx().stopRequested) {
                    long backoff = KhabeerRetry.jitteredBackoff(attempt, 1000, 8000);
                    try { Thread.sleep(backoff); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw ie; }
                    continue;
                }
                break;
            }
        }
        String lastMsg = last == null || last.getMessage() == null ? "" : last.getMessage();
        if (lastMsg.contains("HTTP 5") && ctx() != null && !ctx().stopRequested) {
            emit("turn/toolsDegraded", json("reason", "function-calling rejected; retrying as plain completion"));
            try {
                return callChatCompletionsApi(providerId, baseUrl, apiKey, model, messages, false);
            } catch (Exception plainFailed) {
                throw last;
            }
        }
        if (last != null) throw last;
        throw new IllegalStateException("chat completions retry exhausted");
    }

    private JSONObject callChatCompletionsApi(String providerId, String baseUrl, String apiKey, String model,
                                              JSONArray messages, boolean withTools) throws Exception {
        JSONObject body = chatCompletionsBody(model, messages, withTools ? modelTools(true) : null);

        HttpURLConnection connection = (HttpURLConnection) new URL(chatCompletionsUrl(baseUrl)).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(MODEL_CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(MODEL_READ_TIMEOUT_MS);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "text/event-stream");
        if (!TextUtils.isEmpty(apiKey)) connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        connection.setRequestProperty("X-Title", "Termux khabeer");
        applyOpenCodeSessionHeader(connection, chatCompletionsUrl(baseUrl), turnSessionId(ctx()));
        if ("github-copilot".equals(providerId)) {
            for (int i = 0; i < ProviderLogin.COPILOT_REQUEST_HEADERS.length; i += 2)
                connection.setRequestProperty(ProviderLogin.COPILOT_REQUEST_HEADERS[i], ProviderLogin.COPILOT_REQUEST_HEADERS[i + 1]);
        }
        try (OutputStream output = connection.getOutputStream()) {
            output.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }

        int code = connection.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
        String contentType = connection.getContentType();
        if (code >= 200 && code < 300 && contentType != null
            && contentType.toLowerCase(Locale.US).contains("text/event-stream"))
            return readChatCompletionsStream(stream);
        String text = readFully(stream);
        if (code < 200 || code >= 300) return callChatCompletionsApiBuffered(baseUrl, apiKey, model, messages, withTools);
        if (text.trim().startsWith("data:")) return readChatCompletionsStream(text);
        return new JSONObject(text);
    }

    private JSONObject callChatCompletionsApiBuffered(String baseUrl, String apiKey, String model,
                                                      JSONArray messages, boolean withTools) throws Exception {
        JSONObject body = chatCompletionsBody(model, messages, withTools ? modelTools(true) : null);
        body.remove("stream");

        HttpURLConnection connection = (HttpURLConnection) new URL(chatCompletionsUrl(baseUrl)).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(MODEL_CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(MODEL_READ_TIMEOUT_MS);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        if (!TextUtils.isEmpty(apiKey)) connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        connection.setRequestProperty("X-Title", "Termux khabeer");
        applyOpenCodeSessionHeader(connection, chatCompletionsUrl(baseUrl), turnSessionId(ctx()));
        try (OutputStream output = connection.getOutputStream()) {
            output.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }

        int code = connection.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
        String text = readFully(stream);
        if (code < 200 || code >= 300)
            throw new IllegalStateException("Provider request failed with HTTP " + code + ": " + text);
        JSONObject parsed = new JSONObject(text);
        noteUsage(parsed.optJSONObject("usage"));
        return parsed;
    }

    private JSONObject readChatCompletionsStream(InputStream stream) throws Exception {
        JSONObject message = new JSONObject().put("role", "assistant");
        StringBuilder content = new StringBuilder();
        JSONArray toolCalls = new JSONArray();
        StringBuilder finishReason = new StringBuilder();
        StringBuilder pendingReasoning = new StringBuilder();
        long[] lastReasoningEmit = new long[]{System.currentTimeMillis()};

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String rawLine;
            while ((rawLine = reader.readLine()) != null && !ctx().stopRequested) {
                consumeChatCompletionsStreamLine(rawLine, content, toolCalls, finishReason, pendingReasoning, lastReasoningEmit);
            }
        }
        flushPendingReasoning(pendingReasoning);

        if (content.length() > 0) message.put("content", content.toString());
        if (toolCalls.length() > 0) message.put("tool_calls", toolCalls);
        return new JSONObject()
            .put("choices", new JSONArray().put(new JSONObject()
                .put("finish_reason", finishReason.length() == 0 ? JSONObject.NULL : finishReason.toString())
                .put("message", message)));
    }

    private JSONObject readChatCompletionsStream(String text) throws Exception {
        JSONObject message = new JSONObject().put("role", "assistant");
        StringBuilder content = new StringBuilder();
        JSONArray toolCalls = new JSONArray();
        StringBuilder finishReason = new StringBuilder();
        StringBuilder pendingReasoning = new StringBuilder();
        long[] lastReasoningEmit = new long[]{0};

        String[] lines = text.split("\\r?\\n");
        for (String rawLine : lines) {
            if ("[DONE]".equals(rawLine.trim())) break;
            consumeChatCompletionsStreamLine(rawLine, content, toolCalls, finishReason, pendingReasoning, lastReasoningEmit);
        }
        flushPendingReasoning(pendingReasoning);

        if (content.length() > 0) message.put("content", content.toString());
        if (toolCalls.length() > 0) message.put("tool_calls", toolCalls);
        return new JSONObject()
            .put("choices", new JSONArray().put(new JSONObject()
                .put("finish_reason", finishReason.length() == 0 ? JSONObject.NULL : finishReason.toString())
                .put("message", message)));
    }

    private void consumeChatCompletionsStreamLine(String rawLine, StringBuilder content, JSONArray toolCalls,
                                                  StringBuilder finishReason, StringBuilder pendingReasoning,
                                                  long[] lastReasoningEmit) throws Exception {
        String line = rawLine.trim();
        if (!line.startsWith("data:")) return;
        String data = line.substring(5).trim();
        if ("[DONE]".equals(data) || TextUtils.isEmpty(data)) return;

        JSONObject chunk = new JSONObject(data);
        noteUsage(chunk.optJSONObject("usage"));
        JSONArray choices = chunk.optJSONArray("choices");
        JSONObject choice = choices == null ? null : choices.optJSONObject(0);
        if (choice == null) return;
        String reason = choice.optString("finish_reason", "");
        if (!TextUtils.isEmpty(reason)) {
            finishReason.setLength(0);
            finishReason.append(reason);
        }
        JSONObject delta = choice.optJSONObject("delta");
        if (delta == null) return;

        String reasoning = reasoningTextFrom(delta);
        if (!TextUtils.isEmpty(reasoning)) {
            pendingReasoning.append(reasoning);
            long now = System.currentTimeMillis();
            if (now - lastReasoningEmit[0] >= 250) {
                flushPendingReasoning(pendingReasoning);
                lastReasoningEmit[0] = now;
            }
        }

        String piece = optCleanString(delta, "content");
        if (!TextUtils.isEmpty(piece)) content.append(piece);

        JSONArray toolDeltas = delta.optJSONArray("tool_calls");
        if (toolDeltas != null) {
            for (int i = 0; i < toolDeltas.length(); i++) {
                JSONObject toolDelta = toolDeltas.optJSONObject(i);
                if (toolDelta == null) continue;
                mergeToolCallDelta(toolCalls, toolDelta);
            }
        }
    }

    private void flushPendingReasoning(StringBuilder pendingReasoning) {
        if (pendingReasoning == null || pendingReasoning.length() == 0) return;
        emit("item/reasoning/delta", json("text", pendingReasoning.toString()));
        pendingReasoning.setLength(0);
    }

    private void mergeToolCallDelta(JSONArray toolCalls, JSONObject delta) throws Exception {
        int index = delta.optInt("index", toolCalls.length());
        JSONObject toolCall = toolCalls.optJSONObject(index);
        if (toolCall == null) {
            toolCall = new JSONObject().put("type", "function");
            toolCalls.put(index, toolCall);
        }
        String id = optCleanString(delta, "id");
        if (!TextUtils.isEmpty(id)) toolCall.put("id", id);
        String type = optCleanString(delta, "type");
        if (!TextUtils.isEmpty(type)) toolCall.put("type", type);

        JSONObject functionDelta = delta.optJSONObject("function");
        if (functionDelta == null) return;
        JSONObject function = toolCall.optJSONObject("function");
        if (function == null) {
            function = new JSONObject();
            toolCall.put("function", function);
        }
        String name = optCleanString(functionDelta, "name");
        if (!TextUtils.isEmpty(name)) function.put("name", name);
        String arguments = optCleanString(functionDelta, "arguments");
        if (!TextUtils.isEmpty(arguments))
            function.put("arguments", function.optString("arguments", "") + arguments);
    }

    private String optCleanString(JSONObject object, String key) {
        if (object == null || TextUtils.isEmpty(key) || !object.has(key) || object.isNull(key)) return "";
        Object value = object.opt(key);
        if (value == null || JSONObject.NULL.equals(value)) return "";
        String text = String.valueOf(value);
        return "null".equals(text) ? "" : text;
    }

    private String reasoningTextFrom(JSONObject object) {
        if (object == null) return "";
        StringBuilder reasoning = new StringBuilder();
        appendReasoningValue(reasoning, cleanValue(object, "reasoning_content"));
        appendReasoningValue(reasoning, cleanValue(object, "reasoning"));
        appendReasoningValue(reasoning, cleanValue(object, "thinking"));
        appendReasoningValue(reasoning, cleanValue(object, "summary"));
        return reasoning.toString();
    }

    private boolean usesChatCompletions(String providerId) {
        AiProviderProfile profile = AiProviderProfile.find(providerId);
        if (profile != null && profile.dialect != null) return "chat".equals(profile.dialect);
        return CHAT_COMPLETIONS_PROVIDERS.contains(providerId);
    }

    /** Anthropic Messages dialect, honoring runtime-loaded profiles. */
    private boolean usesAnthropicMessages(String providerId) {
        AiProviderProfile profile = AiProviderProfile.find(providerId);
        if (profile != null && profile.dialect != null) return "anthropic".equals(profile.dialect);
        return ANTHROPIC_MESSAGES_PROVIDERS.contains(providerId);
    }

    /** Providers that speak OpenAI chat completions (khabeer dialect table:
     * most aggregators and OpenAI-compatible clouds; openai/openrouter/xai
     * speak Responses, anthropic-family speak Messages). */
    private static final java.util.Set<String> CHAT_COMPLETIONS_PROVIDERS = new java.util.HashSet<>(java.util.Arrays.asList(
        "opencode", "deepseek", "gemini", "zai", "alibaba", "huggingface", "nvidia",
        "ollama-cloud", "fireworks", "novita", "ai-gateway", "kilocode", "gmi",
        "arcee", "xiaomi", "tencent-tokenhub", "lmstudio", "nous", "github-copilot"));

    /** Providers speaking the native Anthropic Messages dialect. */
    private static final java.util.Set<String> ANTHROPIC_MESSAGES_PROVIDERS = new java.util.HashSet<>(java.util.Arrays.asList(
        "anthropic", "minimax", "tencent-tokenplan", "kimi-coding"));

    private String chatCompletionsUrl(String baseUrl) {
        String clean = baseUrl == null ? "" : baseUrl.trim();
        if (clean.endsWith("/chat/completions")) return clean;
        if (clean.endsWith("/")) clean = clean.substring(0, clean.length() - 1);
        return clean + "/chat/completions";
    }

    /** OpenCode Go (and Zen, same infra) requires x-opencode-session: one
     * stable ID per conversation — the session/run id. Missing headers may
     * error from 2026-09-06. Gated by URL so Free/Zen/Go all carry it and
     * every other provider is untouched. */
    private static void applyOpenCodeSessionHeader(HttpURLConnection c, String url, @Nullable String sessionId) {
        if (c == null || TextUtils.isEmpty(sessionId)) return;
        String u = url == null ? "" : url;
        if (u.contains("opencode.ai/zen")) {
            c.setRequestProperty("x-opencode-session", sessionId);
        }
    }

    private static String turnSessionId(RunContext c) {
        if (c == null || c.record == null || TextUtils.isEmpty(c.record.id)) return null;
        return c.record.id;
    }

    private JSONObject callResponsesApi(String providerId, String baseUrl, String apiKey, String model,
                                        @Nullable String effort, JSONArray input) throws Exception {
        JSONObject body = new JSONObject()
            .put("model", model)
            .put("instructions", systemInstructions())
            .put("input", input)
            .put("tools", modelTools(false))
            .put("tool_choice", "auto");
        if (!TextUtils.isEmpty(effort)) body.put("reasoning", new JSONObject().put("effort", effort));

        HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(MODEL_CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(MODEL_READ_TIMEOUT_MS);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        if ("openrouter".equals(providerId)) {
            connection.setRequestProperty("HTTP-Referer", "https://termux.local/khabeer");
            connection.setRequestProperty("X-Title", "Termux khabeer");
        }
        applyOpenCodeSessionHeader(connection, baseUrl, turnSessionId(ctx()));
        try (OutputStream output = connection.getOutputStream()) {
            output.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }

        int code = connection.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
        String text = readFully(stream);
        if (code < 200 || code >= 300)
            throw new IllegalStateException("Provider request failed with HTTP " + code + ": " + text);
        JSONObject parsed = new JSONObject(text);
        noteUsage(parsed.optJSONObject("usage"));
        return parsed;
    }

    private JSONObject terminalTool() throws Exception {
        return new JSONObject()
            .put("type", "function")
            .put("name", "terminal")
            .put("description", "Run one shell command in the selected Termux project folder. Use this for file inspection, builds, tests, package commands, and local automation.")
            .put("parameters", new JSONObject()
                .put("type", "object")
                .put("additionalProperties", false)
                .put("properties", new JSONObject()
                    .put("command", new JSONObject()
                        .put("type", "string")
                        .put("description", "The shell command to run."))
                    .put("timeout_seconds", new JSONObject()
                        .put("type", "integer")
                        .put("description", "Timeout from 1 to 1200 seconds.")))
                .put("required", new JSONArray().put("command")));
    }

    private JSONObject terminalToolForChat() throws Exception {
        return chatShape(terminalTool());
    }

    /** Wraps a flat Responses-style tool schema into the chat-completions nested shape. */
    private JSONObject chatShape(JSONObject flat) throws Exception {
        return new JSONObject()
            .put("type", "function")
            .put("function", new JSONObject()
                .put("name", flat.getString("name"))
                .put("description", flat.getString("description"))
                .put("parameters", flat.getJSONObject("parameters")));
    }

    /** Every tool the model can call: terminal + skills + MCP tools (khabeer
     * merges all tools flat into one array — no wrapper tool). */
    private JSONArray modelTools(boolean chatShape) throws Exception {
        JSONArray tools = new JSONArray();
        tools.put(chatShape ? terminalToolForChat() : terminalTool());
        if (mProviderConfig == null || mProviderConfig.isAnyBuiltInMemoryEnabled())
            tools.put(chatShape ? chatShape(memoryTool()) : memoryTool());
        tools.put(chatShape ? chatShape(sessionSearchTool()) : sessionSearchTool());
        tools.put(chatShape ? chatShape(delegateTool()) : delegateTool());
        tools.put(chatShape ? chatShape(todoTool()) : todoTool());
        if (mProviderConfig == null || mProviderConfig.isWebEnabled())
            tools.put(chatShape ? chatShape(webTool()) : webTool());
        if (AiSkillRegistry.promptSection() != null) {
            tools.put(chatShape ? chatShape(skillsListTool()) : skillsListTool());
            tools.put(chatShape ? chatShape(skillViewTool()) : skillViewTool());
            tools.put(chatShape ? chatShape(skillManageTool()) : skillManageTool());
        }
        if (mMcpRegistry != null) {
            for (AiMcpRegistry.ToolDef def : mMcpRegistry.toolsForModel(mDatabase)) {
                JSONObject flat = new JSONObject()
                    .put("type", "function")
                    .put("name", def.registryName)
                    .put("description", TextUtils.isEmpty(def.description) ? "MCP tool." : def.description)
                    .put("parameters", def.inputSchema);
                tools.put(chatShape ? chatShape(flat) : flat);
            }
        }
        if (isQuiet()) return filterBlockedTools(tools);
        return tools;
    }

    /** Tools a delegated child may use (Hermes DELEGATE_BLOCKED_TOOLS,
     * trimmed for mobile): everything except memory writes, skill
     * management, and further delegation. Terminal stays but its approvals
     * auto-deny inside child turns. modelTools applies this automatically
     * on quiet (child) threads. */
    static final java.util.Set<String> SUBAGENT_BLOCKED_TOOLS =
        new java.util.HashSet<>(java.util.Arrays.asList("delegate_task", "memory", "skill_manage"));

    static JSONArray filterBlockedTools(JSONArray tools) throws Exception {
        JSONArray out = new JSONArray();
        if (tools == null) return out;
        for (int i = 0; i < tools.length(); i++) {
            JSONObject tool = tools.optJSONObject(i);
            if (tool == null) continue;
            String name = tool.optString("name");
            if (TextUtils.isEmpty(name)) {
                JSONObject fn = tool.optJSONObject("function");
                if (fn != null) name = fn.optString("name");
            }
            if (!SUBAGENT_BLOCKED_TOOLS.contains(name)) out.put(tool);
        }
        return out;
    }

    private JSONObject delegateTool() throws Exception {
        return new JSONObject()
            .put("type", "function")
            .put("name", "delegate_task")
            .put("description", "Hand a self-contained chunk of work to a subagent that runs with the same provider and model. "
                + "Use for parallelizable research, exploration, or bounded multi-step jobs while you continue with other work items. "
                + "The subagent sees the frozen memory snapshot and session history search but CANNOT write memory, manage skills, delegate further, "
                + "or ask the user anything (terminal commands auto-deny). Give a precise goal plus all needed context — it cannot ask follow-ups. "
                + "Returns the subagent's final result text. Prefer one delegate call per independent workstream.")
            .put("parameters", new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                    .put("task", new JSONObject()
                        .put("type", "string")
                        .put("description", "The precise goal for the subagent, stated as a complete assignment."))
                    .put("context", new JSONObject()
                        .put("type", "string")
                        .put("description", "Background the subagent needs: file paths, decisions so far, constraints. Optional."))
                    .put("timeout_seconds", new JSONObject()
                        .put("type", "integer")
                        .put("description", "Hard timeout for the child turn. Optional, default 300, max 1800.")))
                .put("required", new JSONArray().put("task")));
    }

    private JSONObject todoTool() throws Exception {
        return new JSONObject()
            .put("type", "function")
            .put("name", "todowrite")
            .put("description", "Track multi-step work as a structured task list. "
                + "Send the full list each time; items merge by id (omit id for new items). "
                + "Exactly one item should be in_progress at a time; mark completed/cancelled as you go. "
                + "Use short task descriptions; nesting via parent ids for subtasks. "
                + "The active list survives compaction and resume — completed work is never re-injected.")
            .put("parameters", new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                    .put("todos", new JSONObject()
                        .put("type", "array")
                        .put("description", "Full task list: [{id?, content, status: pending|in_progress|completed|cancelled, parent?}].")
                        .put("items", new JSONObject()
                            .put("type", "object")
                            .put("properties", new JSONObject()
                                .put("id", new JSONObject().put("type", "string"))
                                .put("content", new JSONObject().put("type", "string"))
                                .put("status", new JSONObject().put("type", "string"))
                                .put("parent", new JSONObject().put("type", "string"))))))
                .put("required", new JSONArray().put("todos")));
    }

    private boolean isTodoTool(String name) {
        return "todowrite".equals(name);
    }

    /** todowrite dispatch: merge into the session store, persist the
     * snapshot, and echo the list back so the model stays grounded. */
    private String runTodoTool(JSONObject args) {
        RunContext c = ctx();
        if (c == null || c.record == null) return todoError("No active session.");
        try {
            JSONArray todos = args == null ? null : args.optJSONArray("todos");
            if (todos == null) return todoError("todowrite needs a todos array.");
            JSONObject snapshot = c.todos.write(todos);
            persistTodos(c);
            snapshot.put("success", true);
            snapshot.put("message", "Task list updated.");
            return snapshot.toString();
        } catch (Exception e) {
            return todoError(e.getMessage() == null ? "todo write failed" : e.getMessage());
        }
    }

    private static String todoError(String message) {
        try {
            return new JSONObject().put("success", false)
                .put("error", message == null ? "todo error" : message).toString();
        } catch (Exception e) {
            return "{\"success\":false,\"error\":\"todo error\"}";
        }
    }

    private JSONObject webTool() throws Exception {
        return new JSONObject()
            .put("type", "function")
            .put("name", "web")
            .put("description", "Search the web and fetch pages. "
                + "action='search' needs query (optional limit, default 5). "
                + "action='fetch' needs url and returns the page as text. "
                + "Private-network targets and blocklisted hosts are refused. "
                + "Prefer search to discover URLs, then fetch the best hits. "
                + "Quote what you use.")
            .put("parameters", new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                    .put("action", new JSONObject().put("type", "string")
                        .put("description", "search | fetch"))
                    .put("query", new JSONObject().put("type", "string"))
                    .put("url", new JSONObject().put("type", "string"))
                    .put("limit", new JSONObject().put("type", "integer")))
                .put("required", new JSONArray().put("action")));
    }

    private boolean isWebTool(String name) {
        return "web".equals(name);
    }

    /** web dispatch: search via the configured backend ladder, fetch
     * behind the fetch policy. Runs on the worker thread (network I/O). */
    private String runWebTool(JSONObject args) {
        try {
            if (mProviderConfig != null && !mProviderConfig.isWebEnabled()) {
                return webError("Web access is disabled (More → Web access).");
            }
            String action = args == null ? "" : args.optString("action", "").trim().toLowerCase(Locale.US);
            String blocked = mProviderConfig == null ? "" : mProviderConfig.getWebBlockedHosts();
            JSONObject result;
            if ("search".equals(action)) {
                String backend = mProviderConfig == null ? "auto" : mProviderConfig.getWebSearchBackend();
                String searxng = mProviderConfig == null ? "" : mProviderConfig.getSearxngUrl();
                result = AiWebTools.search(args.optString("query", ""),
                    args.optInt("limit", 5), backend, searxng, blocked);
            } else if ("fetch".equals(action)) {
                result = AiWebTools.fetch(args == null ? "" : args.optString("url", ""), blocked);
            } else {
                return webError("web needs action='search' or action='fetch'.");
            }
            return result.toString();
        } catch (Exception e) {
            return webError(e.getMessage() == null ? "web tool failed" : e.getMessage());
        }
    }

    private static String webError(String message) {
        try {
            return new JSONObject().put("success", false)
                .put("error", message == null ? "web error" : message).toString();
        } catch (Exception e) {
            return "{\"success\":false,\"error\":\"web error\"}";
        }
    }

    private JSONObject memoryTool() throws Exception {
        return new JSONObject()
            .put("type", "function")
            .put("name", "memory")
            .put("description", "Save durable facts to persistent memory that survive across sessions. "
                + "Use target='user' for user preferences/profile, target='memory' for environment/project/workflow notes. "
                + "Make compact high-signal entries. Skip trivial facts, temporary task progress, raw data dumps, and things easily rediscovered. "
                + "Reusable procedures belong in skills. Use session_search for past conversation/task history. "
                + "Prefer one operations array when consolidating or changing multiple entries; it applies atomically against the final char budget. "
                + "SOUL.md is not a memory target: it is user-owned identity/persona context edited from the Memory page.")
            .put("parameters", new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                    .put("target", new JSONObject()
                        .put("type", "string")
                        .put("enum", enabledMemoryTargets())
                        .put("description", enabledMemoryTargetDescription()))
                    .put("action", new JSONObject()
                        .put("type", "string")
                        .put("enum", new JSONArray().put("add").put("replace").put("remove"))
                        .put("description", "Single-operation shape. Omit when using operations."))
                    .put("content", new JSONObject()
                        .put("type", "string")
                        .put("description", "Entry content for add/replace. Alias: new_text."))
                    .put("new_text", new JSONObject()
                        .put("type", "string")
                        .put("description", "Alias for content."))
                    .put("old_text", new JSONObject()
                        .put("type", "string")
                        .put("description", "Short unique substring identifying the entry for replace/remove."))
                    .put("operations", new JSONObject()
                        .put("type", "array")
                        .put("description", "Atomic batch of {action, content/new_text, old_text?} operations.")
                        .put("items", new JSONObject()
                            .put("type", "object")
                            .put("properties", new JSONObject()
                                .put("action", new JSONObject().put("type", "string").put("enum", new JSONArray().put("add").put("replace").put("remove")))
                                .put("content", new JSONObject().put("type", "string"))
                                .put("new_text", new JSONObject().put("type", "string"))
                                .put("old_text", new JSONObject().put("type", "string")))
                            .put("required", new JSONArray().put("action")))))
                .put("required", new JSONArray().put("target")));
    }

    private JSONObject sessionSearchTool() throws Exception {
        return new JSONObject()
            .put("type", "function")
            .put("name", "session_search")
            .put("description", "Search, browse, read, or scroll durable past chat sessions. "
                + "Use when the user references something from a previous conversation, asks what happened before, "
                + "or when old task context may save them from repeating themselves. Zero LLM calls: returns stored transcript rows. "
                + "Modes are inferred from args: query=discovery, session_id=read, session_id+around_message_id=scroll, no args=browse.")
            .put("parameters", new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                    .put("query", new JSONObject()
                        .put("type", "string")
                        .put("description", "Search text for discovery mode."))
                    .put("session_id", new JSONObject()
                        .put("type", "string")
                        .put("description", "Session to read or scroll."))
                    .put("around_message_id", new JSONObject()
                        .put("type", "integer")
                        .put("description", "Message id anchor for scroll mode."))
                    .put("window", new JSONObject()
                        .put("type", "integer")
                        .put("description", "Messages before/after anchor, default 5, max 20."))
                    .put("limit", new JSONObject()
                        .put("type", "integer")
                        .put("description", "Max sessions/results, default 8, max 20.")))
                .put("required", new JSONArray()));
    }

    private JSONObject skillsListTool() throws Exception {
        return new JSONObject()
            .put("type", "function")
            .put("name", "skills_list")
            .put("description", "List available skills (name + description). Use skill_view(name) to load full content.")
            .put("parameters", new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                    .put("category", new JSONObject()
                        .put("type", "string")
                        .put("description", "Optional category filter to narrow results")))
                .put("required", new JSONArray()));
    }

    private JSONObject skillViewTool() throws Exception {
        return new JSONObject()
            .put("type", "function")
            .put("name", "skill_view")
            .put("description", "Skills allow for loading information about specific tasks and workflows, as well as scripts and templates. Load a skill's full content or access its linked files (references, templates, scripts). First call returns SKILL.md content plus a 'linked_files' object showing available references/templates/scripts. To access those, call again with file_path.")
            .put("parameters", new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                    .put("name", new JSONObject()
                        .put("type", "string")
                        .put("description", "The skill name (use skills_list to see available skills)."))
                    .put("file_path", new JSONObject()
                        .put("type", "string")
                        .put("description", "OPTIONAL: Path to a linked file within the skill (e.g., 'references/api.md', 'templates/config.yaml', 'scripts/validate.py'). Omit to get the main SKILL.md content.")))
                .put("required", new JSONArray().put("name")));
    }

    private JSONObject skillManageTool() throws Exception {
        return new JSONObject()
            .put("type", "function")
            .put("name", "skill_manage")
            .put("description", "Create, update, or delete skills — your procedural memory for recurring task types. "
                + "The call is an operations array (a single edit is a list of one); it applies atomically — any failure rolls "
                + "every touched skill back. Ops: create (full SKILL.md; lands in $HOME/.khabeer/skills/; must precede that "
                + "skill's other ops), patch (targeted old_string/new_string fix — preferred; content alone REPLACES the whole "
                + "file, read it via skill_view() first), write_file/remove_file (supporting files), delete (sole op only). "
                + "Every write asks the user for approval first. Keep the description's first 57 chars a self-contained "
                + "trigger: 'Use when <trigger>. <one-line behavior>.'")
            .put("parameters", new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                    .put("operations", new JSONObject()
                        .put("type", "array")
                        .put("description", "Ordered ops; each names its target skill.")
                        .put("items", new JSONObject()
                            .put("type", "object")
                            .put("properties", new JSONObject()
                                .put("name", new JSONObject()
                                    .put("type", "string")
                                    .put("description", "Skill name (lowercase, hyphens/underscores, max 64 chars); an existing skill's name unless creating."))
                                .put("action", new JSONObject()
                                    .put("type", "string")
                                    .put("enum", new JSONArray().put("create").put("patch").put("delete").put("write_file").put("remove_file")))
                                .put("content", new JSONObject()
                                    .put("type", "string")
                                    .put("description", "Full SKILL.md text (YAML frontmatter + markdown body) for create, or a full rewrite on patch."))
                                .put("category", new JSONObject()
                                    .put("type", "string")
                                    .put("description", "Optional category subdir for create (e.g. 'devops')."))
                                .put("old_string", new JSONObject()
                                    .put("type", "string")
                                    .put("description", "Text to find (patch; exact match, like the patch tool)."))
                                .put("new_string", new JSONObject()
                                    .put("type", "string")
                                    .put("description", "Replacement (patch); empty string deletes the match."))
                                .put("replace_all", new JSONObject()
                                    .put("type", "boolean")
                                    .put("description", "patch: replace all occurrences (default false)."))
                                .put("file_path", new JSONObject()
                                    .put("type", "string")
                                    .put("description", "Path RELATIVE to the skill's own directory, e.g. 'references/api.md' — no leading slash, never absolute. write_file/remove_file: required; first segment references/, templates/, scripts/, or assets/. patch: optional (default SKILL.md)."))
                                .put("file_content", new JSONObject()
                                    .put("type", "string")
                                    .put("description", "Content for write_file.")))
                            .put("required", new JSONArray().put("name").put("action")))))
                .put("required", new JSONArray().put("operations")));
    }

    private JSONObject executeChatToolCall(JSONObject toolCall, String workspace,
                                           @Nullable String approvalPolicy) throws Exception {
        String callId = toolCall.optString("id");
        JSONObject function = toolCall.optJSONObject("function");
        String name = function == null ? "" : function.optString("name");
        JSONObject args = parseObject(function == null ? "{}" : function.optString("arguments", "{}"));
        String command = args.optString("command", "");
        int timeout = args.optInt("timeout_seconds", DEFAULT_TOOL_TIMEOUT_SECONDS);
        persistToolTranscript("tool_call", callId, name, args, null);

        String output;
        if (isMemoryTool(name)) {
            output = runMemoryTool(args);
        } else if (isSessionSearchTool(name)) {
            output = runSessionSearchTool(args);
        } else if (isDelegateTool(name)) {
            emit("tool/callStarted", json("name", name, "command", "delegate_task"));
            output = runDelegateTask(args, workspace);
            emit("item/commandExecution/outputDelta", json("text", output + "\n", "command", "delegate_task"));
        } else if (isTodoTool(name)) {
            emit("tool/callStarted", json("name", name, "command", "todowrite"));
            output = runTodoTool(args);
            emit("item/commandExecution/outputDelta", json("text", memoryToolSummary(output) + "\n", "command", "todowrite"));
        } else if (isWebTool(name)) {
            emit("tool/callStarted", json("name", name, "command", "web"));
            output = runWebTool(args);
            emit("item/commandExecution/outputDelta", json("text", memoryToolSummary(output) + "\n", "command", "web"));
        } else if (isSkillsTool(name)) {
            output = runSkillsTool(name, args, approvalPolicy);
        } else if (name != null && name.startsWith(AiMcpRegistry.TOOL_PREFIX)) {
            output = runMcpToolCall(name, args, approvalPolicy);
        } else if (!"terminal".equals(name)) {
            output = new JSONObject().put("error", "Unknown tool: " + name).toString();
        } else {
            emit("tool/callStarted", json("name", name, "command", command));
            emit("item/commandExecution/outputDelta", json("text", "$ " + command + "\n", "command", command));
            boolean approved = "never".equals(approvalPolicy) || requestApproval(command, workspace);
            output = approved
                ? mToolExecutor.runCommand(workspace, command, timeout)
                : new JSONObject().put("error", "User denied terminal command.").toString();
            emit("item/commandExecution/outputDelta", json("text", output + "\n", "command", command));
        }

        persistToolTranscript("tool_result", callId, name, args, output);
        return new JSONObject()
            .put("role", "tool")
            .put("tool_call_id", callId)
            .put("content", output);
    }

    private JSONObject executeToolCall(JSONObject item, String workspace, @Nullable String approvalPolicy) throws Exception {
        String callId = item.optString("call_id", item.optString("id"));
        String name = item.optString("name");
        JSONObject args = parseObject(item.optString("arguments", "{}"));
        String command = args.optString("command", "");
        int timeout = args.optInt("timeout_seconds", DEFAULT_TOOL_TIMEOUT_SECONDS);
        persistToolTranscript("tool_call", callId, name, args, null);

        String output;
        if (isMemoryTool(name)) {
            output = runMemoryTool(args);
        } else if (isSessionSearchTool(name)) {
            output = runSessionSearchTool(args);
        } else if (isDelegateTool(name)) {
            emit("tool/callStarted", json("name", name, "command", "delegate_task"));
            output = runDelegateTask(args, workspace);
            emit("item/commandExecution/outputDelta", json("text", output + "\n", "command", "delegate_task"));
        } else if (isTodoTool(name)) {
            emit("tool/callStarted", json("name", name, "command", "todowrite"));
            output = runTodoTool(args);
            emit("item/commandExecution/outputDelta", json("text", memoryToolSummary(output) + "\n", "command", "todowrite"));
        } else if (isWebTool(name)) {
            emit("tool/callStarted", json("name", name, "command", "web"));
            output = runWebTool(args);
            emit("item/commandExecution/outputDelta", json("text", memoryToolSummary(output) + "\n", "command", "web"));
        } else if (isSkillsTool(name)) {
            output = runSkillsTool(name, args, approvalPolicy);
        } else if (name != null && name.startsWith(AiMcpRegistry.TOOL_PREFIX)) {
            output = runMcpToolCall(name, args, approvalPolicy);
        } else if (!"terminal".equals(name)) {
            output = new JSONObject().put("error", "Unknown tool: " + name).toString();
        } else {
            emit("tool/callStarted", json("name", name, "command", command));
            emit("item/commandExecution/outputDelta",
                json("text", "$ " + command + "\n", "command", command));

            boolean approved = "never".equals(approvalPolicy) || requestApproval(command, workspace);
            if (!approved) {
                output = new JSONObject().put("error", "User denied terminal command.").toString();
                emit("item/commandExecution/outputDelta", json("text", output + "\n", "command", command));
                persistToolTranscript("tool_result", callId, name, args, output);
                return functionOutput(callId, output);
            }

            output = mToolExecutor.runCommand(workspace, command, timeout);
            emit("item/commandExecution/outputDelta", json("text", output + "\n", "command", command));
        }
        persistToolTranscript("tool_result", callId, name, args, output);
        return functionOutput(callId, output);
    }

    private void persistToolTranscript(String phase, String callId, String name, JSONObject args, @Nullable String output) {
        RunContext c = ctx();
        if (c == null || c.record == null || TextUtils.isEmpty(c.record.id)) return;
        try {
            String safeName = TextUtils.isEmpty(name) ? "unknown_tool" : name;
            StringBuilder content = new StringBuilder();
            content.append("[").append(phase).append("] ").append(safeName);
            if (!TextUtils.isEmpty(callId)) content.append(" #").append(callId);
            if (args != null && args.length() > 0) content.append("\narguments: ").append(boundForTranscript(args.toString(), 4000));
            if (output != null) content.append("\noutput: ").append(boundForTranscript(output, 12000));
            JSONObject api = new JSONObject()
                .put("phase", phase)
                .put("tool", safeName)
                .put("call_id", callId == null ? "" : callId)
                .put("arguments", args == null ? new JSONObject() : args);
            if (output != null) api.put("output", output);
            mDatabase.appendToolTranscript(c.record.id, content.toString(), api.toString());
        } catch (Exception ignored) {}
    }

    private String boundForTranscript(String text, int limit) {
        if (text == null) return "";
        if (text.length() <= limit) return text;
        return text.substring(0, Math.max(0, limit - 80)) + "\n[truncated " + (text.length() - limit) + " chars in searchable transcript; full live tool result was passed to the model]";
    }

    private boolean isSkillsTool(String name) {
        return "skills_list".equals(name) || "skill_view".equals(name) || "skill_manage".equals(name);
    }

    private boolean isMemoryTool(String name) {
        return "memory".equals(name);
    }

    private boolean isDelegateTool(String name) {
        return "delegate_task".equals(name);
    }

    /** Child-turn system prompt: same frozen memory snapshot for context,
     * but an explicit subagent contract overriding the parent guidance
     * (no user contact, no memory/skill writes, no further delegation,
     * terminal approvals auto-deny — put everything in the final text). */
    private String childInstructions() {
        boolean memoryEnabled = mProviderConfig == null || mProviderConfig.isMemoryEnabled();
        boolean userEnabled = mProviderConfig == null || mProviderConfig.isUserMemoryEnabled();
        return AiMemoryStore.systemPromptSnapshot(memoryEnabled, userEnabled)
            + "\n\nYou are a khabeer subagent running inside a parent agent's tool call. Rules: "
            + "you cannot contact the user and no one will see anything except your final reply text, so be complete and self-contained; "
            + "quote file paths, commands, and outputs verbatim. "
            + "You have no memory tool and no skill management — durable findings belong in your final text, not in any store. "
            + "You cannot delegate further. Terminal commands run without approval prompts: read-only inspection is safe, "
            + "but any write-capable command is auto-denied, so verify by reading instead. "
            + "Use session_search for past conversation context when needed. End with a concise result section.";
    }

    /** Runs one bounded child turn on a dedicated thread and returns its
     * result as the tool output string. Same provider/model/credentials as
     * the parent turn; transcript persists under a hidden subagent session
     * row; token usage attributes to the parent ledger. */
    private String runDelegateTask(JSONObject args, String workspace) throws Exception {
        if (isQuiet()) {
            return new JSONObject().put("error", "Subagents cannot delegate further. Do the work directly.").toString();
        }
        RunContext parent = ctx();
        if (parent == null || parent.record == null) {
            return new JSONObject().put("error", "No active session to delegate from.").toString();
        }
        String goal = args == null ? "" : args.optString("task", args.optString("goal", "")).trim();
        if (TextUtils.isEmpty(goal)) {
            return new JSONObject().put("error", "delegate_task needs a task: a complete assignment with goal and context.").toString();
        }
        String context = args == null ? "" : args.optString("context", "");
        int configuredTimeout = mProviderConfig == null ? SUBAGENT_DEFAULT_TIMEOUT_SECONDS
            : mProviderConfig.getSubagentTimeoutSeconds();
        int configuredSteps = mProviderConfig == null ? SUBAGENT_MAX_STEPS
            : mProviderConfig.getSubagentMaxSteps();
        int timeoutSec = args == null ? configuredTimeout
            : args.optInt("timeout_seconds", configuredTimeout);
        timeoutSec = Math.max(60, Math.min(SUBAGENT_MAX_TIMEOUT_SECONDS, timeoutSec));
        String providerId = parent.providerId != null ? parent.providerId : parent.record.harnessId;
        String baseUrl = parent.baseUrl;
        String apiKey = parent.apiKey;
        String model = parent.model != null ? parent.model : parent.record.lastResolvedModel;
        if (TextUtils.isEmpty(baseUrl) || TextUtils.isEmpty(model)) {
            String[] creds = sessionCredentials(parent);
            if (creds == null) return new JSONObject().put("error", "Session provider is not available.").toString();
            providerId = creds[0];
            baseUrl = creds[1];
            apiKey = creds[2];
            model = creds[3];
        }
        final String fProviderId = providerId;
        final String fBaseUrl = baseUrl;
        final String fApiKey = apiKey;
        final String fModel = model;
        final String assignment = TextUtils.isEmpty(context.trim()) ? goal : goal + "\n\nContext:\n" + context.trim();

        AiDatabase.RunRecord child = mDatabase.createRun(
            parent.record.harnessId == null ? "native-agent" : parent.record.harnessId,
            workspace, "subagent:" + parent.record.id);
        child.parentSessionId = parent.record.id;
        child.source = "subagent";
        child.modelOverride = model;
        child.lastResolvedModel = model;
        child.sessionKey = "subagent:" + child.id;
        mDatabase.saveRun(child);
        final RunContext childCtx = new RunContext(child);
        childCtx.providerId = fProviderId;
        childCtx.baseUrl = fBaseUrl;
        childCtx.apiKey = fApiKey;
        childCtx.model = fModel;
        childCtx.usageSessionId = parent.record.id;
        childCtx.maxSteps = configuredSteps;
        try {
            childCtx.chatMessages = new JSONArray()
                .put(json("role", "system", "content", childInstructions()))
                .put(json("role", "user", "content", assignment));
            childCtx.record.chatMessagesJson = childCtx.chatMessages.toString();
        } catch (Exception e) {
            return new JSONObject().put("error", "Could not start the subagent: " + e.getMessage()).toString();
        }
        mRuns.put(child.id, childCtx);

        final long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
        Thread worker = new Thread(() -> {
            RunContext prev = mTurnContext.get();
            mTurnContext.set(childCtx);
            mQuietTurn.set(true);
            KhabeerInterruptManager.clearCurrentThread();
            try {
                executeTurn(childCtx, fProviderId, fBaseUrl, fApiKey, workspace,
                    assignment, fModel, null, null, false);
            } catch (Exception e) {
                try { failRun(childCtx, e.getMessage() == null ? e.toString() : e.getMessage()); } catch (Exception ignored) {}
            } finally {
                mQuietTurn.remove();
                mTurnContext.set(prev);
                KhabeerInterruptManager.clearCurrentThread();
            }
        }, "khabeer-subagent");
        worker.start();
        boolean timedOut = false;
        boolean parentStopped = false;
        while (worker.isAlive()) {
            if (parent.stopRequested || KhabeerInterruptManager.isInterrupted()) {
                parentStopped = true;
                KhabeerInterruptManager.setInterrupt(true, worker.getId(), "parent-stopped");
                try { worker.join(10000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                break;
            }
            if (System.currentTimeMillis() > deadline) {
                timedOut = true;
                KhabeerInterruptManager.setInterrupt(true, worker.getId(), "subagent-timeout");
                try { worker.join(15000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                break;
            }
            try { worker.join(1000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
        }
        if (worker.isAlive()) {
            try { failRun(childCtx, "Subagent turn did not stop after interrupt; result unavailable."); } catch (Exception ignored) {}
        } else if (timedOut && !isTerminalState(childCtx)) {
            try { failRun(childCtx, "Subagent timed out after " + timeoutSec + "s."); } catch (Exception ignored) {}
        }
        persistChildTranscript(childCtx);
        persistRun(childCtx);
        mRuns.remove(child.id);
        KhabeerInterruptManager.clearCurrentThread();

        String result = lastAssistantText(childCtx.chatMessages);
        long used = childCtx.turnPromptTokens + childCtx.turnCompletionTokens;
        StringBuilder out = new StringBuilder();
        if (TextUtils.isEmpty(result.trim())) {
            out.append(parentStopped ? "Subagent stopped with the parent turn."
                : timedOut ? "Subagent timed out with no text result."
                : "Subagent finished with no text result.");
        } else {
            out.append(result.trim());
        }
        if (used > 0) out.append("\n\n[subagent used ").append(used).append(" tokens]");
        if (timedOut) out.append(" [timed out]");
        String finalResult = out.toString();
        if (finalResult.length() > 16000) {
            finalResult = finalResult.substring(0, 16000)
                + "\n\n[subagent result truncated to 16000 chars; full transcript is in the hidden subagent session]";
        }
        return finalResult;
    }

    private boolean isTerminalState(RunContext c) {
        if (c == null) return true;
        AiRunStateMachine.State s = c.stateMachine.getState();
        return s == AiRunStateMachine.State.COMPLETED || s == AiRunStateMachine.State.FAILED
            || s == AiRunStateMachine.State.CANCELED;
    }

    private void persistChildTranscript(RunContext childCtx) {
        if (childCtx == null || childCtx.record == null || childCtx.chatMessages == null) return;
        try {
            for (int i = 0; i < childCtx.chatMessages.length(); i++) {
                JSONObject msg = childCtx.chatMessages.optJSONObject(i);
                if (msg == null) continue;
                String role = msg.optString("role", "");
                if ("system".equals(role)) continue;
                if ("tool".equals(role)) {
                    mDatabase.appendToolTranscript(childCtx.record.id,
                        msg.optString("content", ""), msg.optString("content", ""));
                } else if ("user".equals(role) || "assistant".equals(role)) {
                    String content = msg.optString("content", "");
                    JSONArray calls = msg.optJSONArray("tool_calls");
                    if (TextUtils.isEmpty(content) && calls != null) content = "[tool calls: " + calls.length() + "]";
                    mDatabase.appendMessage(childCtx.record.id, role, content);
                }
            }
        } catch (Exception ignored) {}
    }

    private String lastAssistantText(JSONArray chatMessages) {
        if (chatMessages == null) return "";
        for (int i = chatMessages.length() - 1; i >= 0; i--) {
            JSONObject msg = chatMessages.optJSONObject(i);
            if (msg != null && "assistant".equals(msg.optString("role"))) {
                String text = msg.optString("content", "");
                if (!TextUtils.isEmpty(text.trim())) return text;
            }
        }
        return "";
    }

    private JSONArray enabledMemoryTargets() {
        JSONArray targets = new JSONArray();
        if (mProviderConfig == null || mProviderConfig.isMemoryEnabled()) targets.put("memory");
        if (mProviderConfig == null || mProviderConfig.isUserMemoryEnabled()) targets.put("user");
        return targets;
    }

    private String enabledMemoryTargetDescription() {
        boolean mem = mProviderConfig == null || mProviderConfig.isMemoryEnabled();
        boolean user = mProviderConfig == null || mProviderConfig.isUserMemoryEnabled();
        if (mem && user) return "memory=agent notes/environment/workflow lessons, user=user profile/preferences.";
        if (mem) return "Only memory is enabled: agent notes/environment/workflow lessons.";
        if (user) return "Only user is enabled: user profile/preferences.";
        return "Built-in memory is disabled.";
    }

    private boolean isSessionSearchTool(String name) {
        return "session_search".equals(name);
    }

    private String runMemoryTool(JSONObject args) {
        String target = args == null ? "memory" : args.optString("target", "memory");
        String action = args == null ? "" : args.optString("action", args.has("operations") ? "batch" : "");
        String displayName = "memory " + action + " → " + target;
        emit("tool/callStarted", json("name", "memory", "command", displayName));
        try {
            if (mProviderConfig != null && mProviderConfig.isMemoryWriteApprovalEnabled()) {
                String staged = AiMemoryStore.stageWrite(args == null ? new JSONObject() : args, "foreground").toString();
                emit("item/commandExecution/outputDelta", json("text", memoryToolSummary(staged), "command", displayName));
                return staged;
            }
            boolean memoryEnabled = mProviderConfig == null || mProviderConfig.isMemoryEnabled();
            boolean userEnabled = mProviderConfig == null || mProviderConfig.isUserMemoryEnabled();
            String output = AiMemoryStore.tool(args, memoryEnabled, userEnabled);
            emit("item/commandExecution/outputDelta", json("text", memoryToolSummary(output), "command", displayName));
            return output;
        } catch (Exception e) {
            String output = skillsToolError("memory tool failed: " + e.getMessage());
            emit("item/commandExecution/outputDelta", json("text", memoryToolSummary(output), "command", displayName));
            return output;
        }
    }

    private String runSessionSearchTool(JSONObject args) {
        String displayName = "session_search";
        if (args != null && !TextUtils.isEmpty(args.optString("query", ""))) displayName += " “" + args.optString("query") + "”";
        emit("tool/callStarted", json("name", "session_search", "command", displayName));
        try {
            String current = ctx() == null || ctx().record == null ? null : ctx().record.id;
            String output = mDatabase.sessionSearch(args == null ? new JSONObject() : args, current).toString();
            emit("item/commandExecution/outputDelta", json("text", sessionSearchSummary(output), "command", displayName));
            return output;
        } catch (Exception e) {
            String output = skillsToolError("session_search failed: " + e.getMessage());
            emit("item/commandExecution/outputDelta", json("text", "error → " + e.getMessage(), "command", displayName));
            return output;
        }
    }

    private String sessionSearchSummary(String output) {
        try {
            JSONObject o = new JSONObject(output);
            if (!o.optBoolean("success")) return "error → " + o.optString("error", "search failed");
            if (o.has("results")) return "found " + o.optJSONArray("results").length() + " session(s)";
            if (o.has("sessions")) return "listed " + o.optJSONArray("sessions").length() + " session(s)";
            if (o.has("messages")) return "loaded " + o.optJSONArray("messages").length() + " message(s)";
            return "session recall complete";
        } catch (Exception e) {
            return "session recall complete";
        }
    }

    private String memoryToolSummary(String output) {
        try {
            JSONObject o = new JSONObject(output);
            if (!o.optBoolean("success")) return "error → " + o.optString("error", "memory write failed");
            return o.optString("message", "memory saved") + " · " + o.optString("usage", "");
        } catch (Exception e) {
            return "memory updated";
        }
    }

    /** skills_list/skill_view run locally (no approval, no shell): read-only
     * filesystem access. skill_manage MUTATES the skills root, so it gates
     * through the approval dialog before any write (khabeer write-gate). */
    private String runSkillsTool(String name, JSONObject args, @Nullable String approvalPolicy) {
        String displayName;
        if ("skill_view".equals(name)) displayName = args.optString("name", "skill_view");
        else if ("skill_manage".equals(name)) displayName = AiSkillRegistry.manageGist(manageOpOf(args));
        else displayName = "skills_list";
        emit("tool/callStarted", json("name", name, "command", displayName));
        String output;
        try {
            if ("skill_manage".equals(name)) {
                if (!"never".equals(approvalPolicy)) {
                    String gist = TextUtils.isEmpty(displayName) ? "skill_manage" : displayName;
                    boolean approved = requestApproval(gist, AiSkillRegistry.skillsRoot().getAbsolutePath());
                    if (!approved) {
                        output = skillsToolError("User denied this skill write. Ask the user to approve it in the dialog, or describe the change instead.");
                        emit("item/commandExecution/outputDelta", json("text", skillsToolSummary(name, output), "command", displayName));
                        return output;
                    }
                }
                output = AiSkillRegistry.manageTool(args);
            } else if ("skills_list".equals(name)) {
                String category = args.optString("category", "");
                output = AiSkillRegistry.listTool(TextUtils.isEmpty(category) ? null : category);
            } else {
                String filePath = args.has("file_path") && !args.isNull("file_path")
                    ? args.optString("file_path") : null;
                output = AiSkillRegistry.viewTool(args.optString("name", ""),
                    filePath, ctx() == null ? null : ctx().skillViewCache);
            }
        } catch (Exception e) {
            output = skillsToolError("skill tool failed: " + e.getMessage());
        }
        emit("item/commandExecution/outputDelta", json("text", skillsToolSummary(name, output), "command", displayName));
        return output;
    }

    /** Normalize the two call shapes into a single op for the approval gist. */
    private JSONObject manageOpOf(JSONObject args) {
        if (args.has("operations")) {
            JSONArray ops = args.optJSONArray("operations");
            if (ops != null && ops.length() > 0) return ops.optJSONObject(0);
            return null;
        }
        return args;
    }

    private String skillsToolError(String message) {
        try {
            return new JSONObject().put("success", false).put("error", message).toString();
        } catch (Exception e) {
            return "{\"success\":false,\"error\":\"skill tool error\"}";
        }
    }

    /**
     * MCP tool dispatch (khabeer mcp tool handlers). Untrusted servers gate
     * through the approval dialog BEFORE any network call — fail-closed, the
     * same trust model khabeer applies to write-capable tools.
     */
    private String runMcpToolCall(String registryName, JSONObject args, @Nullable String approvalPolicy) {
        AiMcpRegistry.ToolDef def = mMcpRegistry.findTool(registryName);
        if (def == null) {
            return skillsToolError("Unknown MCP tool: " + registryName
                + ". Re-test the server in Skills & extensions to refresh its tool list.");
        }
        AiDatabase.McpServerRecord server = mDatabase.getMcpServer(def.serverName);
        if (server == null) {
            return skillsToolError("MCP server '" + def.serverName + "' is not configured anymore.");
        }
        emit("tool/callStarted", json("name", registryName, "command", def.serverName + " → " + def.toolName));

        if (!"trusted".equals(server.trust) && !"never".equals(approvalPolicy)) {
            String summary = def.serverName + " → " + def.toolName + "\n" + truncateForApproval(args.toString());
            boolean approved;
            try {
                approved = requestApproval(summary, server.url);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return skillsToolError("MCP call interrupted.");
            }
            if (!approved) {
                String denied = skillsToolError("User denied the MCP tool call.");
                emit("item/commandExecution/outputDelta", json("text", "denied", "command", def.toolName));
                return denied;
            }
        }

        String output = mMcpRegistry.callTool(mDatabase, server, def.toolName, args, server.timeoutSeconds);
        emit("item/commandExecution/outputDelta", json("text", mcpResultSummary(output), "command", def.toolName));
        return output;
    }

    private String truncateForApproval(String text) {
        if (text == null) return "";
        return text.length() <= 300 ? text : text.substring(0, 299) + "…";
    }

    private String mcpResultSummary(String output) {
        try {
            JSONObject result = new JSONObject(output);
            if (result.has("error")) {
                String error = result.optString("error", "failed");
                return "error → " + (error.length() > 200 ? error.substring(0, 199) + "…" : error);
            }
            String body = result.optString("result", "");
            return "(" + body.length() + " chars)";
        } catch (Exception e) {
            return "done";
        }
    }

    /** UI: fire-and-forget probe of one server; status lands in the DB and
     * reaches the page via the mcp/status listener event. */
    public void testMcpServer(String name) {
        AiDatabase.McpServerRecord server = mDatabase.getMcpServer(name);
        if (server == null || mMcpRegistry == null) return;
        Thread thread = new Thread(() -> {
            String error = mMcpRegistry.probe(mDatabase, server);
            JSONObject payload = new JSONObject();
            try {
                payload.put("server", name);
                payload.put("ok", error == null);
                if (error != null) payload.put("error", error);
            } catch (Exception ignored) {}
            mHandler.post(() -> {
                for (Listener listener : new ArrayList<>(mListeners))
                    listener.onProtocolEvent(null, "mcp/status", payload);
            });
        }, "mcp-test");
        thread.setDaemon(true);
        thread.start();
    }

    /** UI: a server was added/edited/enabled — drop cached state and probe. */
    public void refreshMcpServer(String name) {
        if (mMcpRegistry == null) return;
        mMcpRegistry.dropServer(name);
        mMcpRegistry.probeStaleAsync(mDatabase);
    }

    /** UI: a server was removed — drop cached state. */
    public void onMcpServerDeleted(String name) {
        if (mMcpRegistry != null) mMcpRegistry.dropServer(name);
    }

    public java.util.List<AiDatabase.McpServerRecord> getMcpServers() {
        return mDatabase.getMcpServers();
    }

    @Nullable
    public AiDatabase.McpServerRecord getMcpServer(String name) {
        return mDatabase.getMcpServer(name);
    }

    public void saveMcpServer(AiDatabase.McpServerRecord record) {
        mDatabase.saveMcpServer(record);
    }

    public void deleteMcpServer(String name) {
        mDatabase.deleteMcpServer(name);
    }

    /** Short UI-facing summary — the full tool JSON goes to the model, not the bubble. */
    private String skillsToolSummary(String name, String output) {
        try {
            JSONObject result = new JSONObject(output);
            if (!result.optBoolean("success", false)) {
                String error = result.optString("error", "failed");
                return name + " → " + (error.length() > 200 ? error.substring(0, 199) + "…" : error);
            }
            if (result.optBoolean("dedup", false)) return name + " → unchanged (already in context)";
            if ("skills_list".equals(name)) return name + " → " + result.optInt("count", 0) + " skills";
            if ("skill_manage".equals(name)) {
                String msg = result.optString("message", "");
                int applied = result.optInt("applied", 0);
                if (applied > 0) return name + " → batch(" + applied + " ops) applied";
                return name + " → " + (TextUtils.isEmpty(msg) ? "done" : msg.replace('\n', ' ').trim());
            }
            String loaded = result.optString("name", "");
            String content = result.optString("content", "");
            return name + " → " + loaded + " (" + content.length() + " chars)";
        } catch (Exception e) {
            return name + " → done";
        }
    }

    private boolean requestApproval(String command, String workspace) throws InterruptedException {
        if (ctx() == null) return false;
        // Subagents cannot prompt the user (Hermes default: auto-deny).
        if (isQuiet()) return false;
        KhabeerInterruptManager.clearCurrentThread();
        long id = mNextRequestId++;
        PendingApproval approval = new PendingApproval(ctx().record.id);
        approval.command = command;
        approval.workspace = workspace;
        mPendingApprovals.put(id, approval);
        KhabeerSessionState ss = sessionState(ctx().record.sessionKey == null ? ctx().record.id : ctx().record.sessionKey);
        ss.persistent.pendingApproval = command;
        transition(AiRunStateMachine.State.WAITING_APPROVAL);
        JSONObject payload = new JSONObject();
        try {
            payload.put("_requestId", id);
            payload.put("_method", "terminal/requestApproval");
            payload.put("command", command);
            payload.put("cwd", workspace);
            payload.put("reason", "The model wants to run this command in Termux.");
        } catch (Exception ignored) {
        }
        approval.payload = payload;
        emit("terminal/requestApproval", payload);
        postApprovalNotification(id, ctx().record.id, command, workspace);
        boolean ans = approval.await();
        cancelApprovalNotification(id);
        ss.persistent.pendingApproval = null;
        return ans;
    }

    private JSONObject functionOutput(String callId, String output) throws Exception {
        return new JSONObject()
            .put("type", "function_call_output")
            .put("call_id", callId)
            .put("output", output == null ? "" : output);
    }

    private void emitMessage(JSONObject item) {
        JSONArray content = item.optJSONArray("content");
        if (content == null) return;
        StringBuilder builder = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        for (int i = 0; i < content.length(); i++) {
            JSONObject part = content.optJSONObject(i);
            if (part == null) continue;
            String type = part.optString("type");
            if ("output_text".equals(type) || "text".equals(type))
                builder.append(part.optString("text"));
            else if (isReasoningType(type)) {
                appendReasoningText(reasoning, part);
                appendReasoningValue(reasoning, part.opt("content"));
            }
        }
        appendReasoningText(reasoning, item);
        if (reasoning.length() > 0) emit("item/reasoning/delta", json("text", reasoning.toString()));
        if (builder.length() > 0) emit("item/agentMessage/delta", json("text", builder.toString()));
    }

    private void emitReasoningFromChatMessage(JSONObject message) {
        StringBuilder reasoning = new StringBuilder();
        appendReasoningText(reasoning, message);
        JSONArray details = message.optJSONArray("reasoning_details");
        if (details != null) {
            for (int i = 0; i < details.length(); i++) {
                JSONObject detail = details.optJSONObject(i);
                if (detail != null) {
                    appendReasoningText(reasoning, detail);
                    appendReasoningValue(reasoning, detail);
                }
                else appendReasoningValue(reasoning, details.opt(i));
            }
        }
        if (reasoning.length() > 0) emit("item/reasoning/delta", json("text", reasoning.toString()));
    }

    private void emitReasoningItem(JSONObject item) {
        StringBuilder reasoning = new StringBuilder();
        appendReasoningText(reasoning, item);
        appendReasoningValue(reasoning, item.opt("content"));
        if (reasoning.length() > 0) emit("item/reasoning/delta", json("text", reasoning.toString()));
    }

    private boolean isReasoningType(String type) {
        return "reasoning".equals(type)
            || "reasoning_text".equals(type)
            || "thinking".equals(type)
            || "thinking_text".equals(type)
            || "summary_text".equals(type);
    }

    private void appendReasoningText(StringBuilder builder, JSONObject object) {
        if (object == null) return;
        appendReasoningValue(builder, cleanValue(object, "reasoning_content"));
        appendReasoningValue(builder, cleanValue(object, "reasoning"));
        appendReasoningValue(builder, cleanValue(object, "thinking"));
        appendReasoningValue(builder, cleanValue(object, "summary"));
        appendReasoningValue(builder, cleanValue(object, "text"));
    }

    private Object cleanValue(JSONObject object, String key) {
        if (object == null || TextUtils.isEmpty(key) || !object.has(key) || object.isNull(key)) return null;
        return object.opt(key);
    }

    private void appendReasoningValue(StringBuilder builder, Object value) {
        if (value == null || JSONObject.NULL.equals(value)) return;
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) appendReasoningValue(builder, array.opt(i));
            return;
        }
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            appendReasoningValue(builder, object.opt("text"));
            appendReasoningValue(builder, object.opt("content"));
            appendReasoningValue(builder, object.opt("summary"));
            return;
        }
        String text = String.valueOf(value);
        if (TextUtils.isEmpty(text) || TextUtils.isEmpty(text.trim()) || "null".equals(text.trim())) return;
        if (builder.length() > 0) builder.append("\n");
        builder.append(text);
    }

    private JSONObject parseObject(String value) {
        try {
            return TextUtils.isEmpty(value) ? new JSONObject() : new JSONObject(value);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private String systemInstructions() {
        StringBuilder sb = new StringBuilder();
        boolean memoryEnabled = mProviderConfig == null || mProviderConfig.isMemoryEnabled();
        boolean userEnabled = mProviderConfig == null || mProviderConfig.isUserMemoryEnabled();
        sb.append(AiMemoryStore.systemPromptSnapshot(memoryEnabled, userEnabled));
        sb.append("\n\n")
          .append("You are running as a native mobile agent inside Termux on Android. ")
          .append("Use the terminal tool deliberately, explain what you are doing, and prefer small inspect-before-change steps. ")
          .append("Never claim a command succeeded unless the terminal output confirms it.");
        if (memoryEnabled || userEnabled) {
            sb.append("\n\n")
              .append("Memory rules: MEMORY.md and USER.md are curated persistent memory. ")
              .append("Save compact durable facts with the memory tool when the user states preferences, corrections, stable environment facts, or reusable workflow lessons. ")
              .append("Write declarative facts, not instructions to yourself. If memory is full, consolidate with one operations batch instead of looping. ")
              .append("Use session_search for prior conversation/task history, temporary progress, and anything that should not live in the tiny always-on memory files. ")
              .append("SOUL.md is slot-1 identity/persona context. It is user-owned and is not a memory-tool target.");
        } else {
            sb.append("\n\nMemory rules: built-in MEMORY.md/USER.md are disabled. Use session_search for durable conversation recall. SOUL.md remains identity/persona context.");
        }
        String skills = AiSkillRegistry.promptSection();
        if (!TextUtils.isEmpty(skills)) sb.append("\n\n").append(skills);
        return sb.toString();
    }

    /**
     * Rebuilds the system message at the start of every turn: new skills,
     * toggles and provider changes must reach EXISTING sessions too — the
     * persisted transcript carries a frozen copy otherwise (khabeer rebuilds
     * its prompt per turn behind a cache for the same reason).
     */
    private void refreshSystemMessage() {
        if (ctx() == null) return;
        try {
            String instructions = systemInstructions();
            JSONArray messages = ctx().chatMessages;
            if (messages == null) {
                ctx().chatMessages = new JSONArray().put(json("role", "system", "content", instructions));
                return;
            }
            JSONObject first = messages.optJSONObject(0);
            if (first != null && "system".equals(first.optString("role"))) {
                // Hermes-style prompt-cache behavior: SOUL.md, MEMORY.md and
                // USER.md are frozen into the session's initial system prompt.
                // Disk writes are durable immediately, but they do not mutate
                // the current conversation prefix mid-session.
                return;
            } else {
                JSONArray updated = new JSONArray().put(json("role", "system", "content", instructions));
                for (int i = 0; i < messages.length(); i++) updated.put(messages.opt(i));
                ctx().chatMessages = updated;
            }
        } catch (Exception ignored) {}
    }

    private JSONObject json(String key, String value) {
        JSONObject object = new JSONObject();
        try {
            object.put(key, value == null ? "" : value);
        } catch (Exception ignored) {
        }
        return object;
    }

    private JSONObject json(String key1, String value1, String key2, String value2) {
        JSONObject object = new JSONObject();
        try {
            object.put(key1, value1 == null ? "" : value1);
            object.put(key2, value2 == null ? "" : value2);
        } catch (Exception ignored) {
        }
        return object;
    }

    private String readFully(InputStream stream) throws Exception {
        if (stream == null) return "";
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) builder.append(line).append('\n');
        }
        return builder.toString();
    }

    private void maybeRunMemoryReview(RunContext source) {
        if (source == null || source.record == null || mProviderConfig == null) return;
        if (!mProviderConfig.isMemoryNudgeEnabled() || !mProviderConfig.isAnyBuiltInMemoryEnabled()) return;
        int userTurns = mDatabase.countAllUserMessages(source.record.id);
        int interval = mProviderConfig.getMemoryNudgeInterval();
        if (userTurns <= 0 || userTurns % interval != 0) return;

        final String runId = source.record.id;
        final String providerId = source.providerId;
        final String baseUrl = source.baseUrl;
        final String apiKey = source.apiKey;
        final String model = source.model;
        final JSONArray transcript = mDatabase.getTranscript(runId, 80);
        final boolean memoryApproval = mProviderConfig.isMemoryWriteApprovalEnabled();
        final boolean memoryEnabled = mProviderConfig.isMemoryEnabled();
        final boolean userEnabled = mProviderConfig.isUserMemoryEnabled();

        new Thread(() -> {
            try {
                if (TextUtils.isEmpty(providerId) || TextUtils.isEmpty(baseUrl) || TextUtils.isEmpty(model)) return;
                String review = callBackgroundMemoryReview(providerId, baseUrl, apiKey, model, transcript, memoryEnabled, userEnabled, runId);
                JSONObject parsed = parseReviewJson(review);
                JSONArray ops = parsed == null ? null : parsed.optJSONArray("operations");
                JSONArray skillOps = parsed == null ? null : parsed.optJSONArray("skill_operations");
                if ((ops == null || ops.length() == 0) && (skillOps == null || skillOps.length() == 0)) return;
                JSONObject byMemory = new JSONObject().put("target", AiMemoryStore.TARGET_MEMORY).put("operations", new JSONArray());
                JSONObject byUser = new JSONObject().put("target", AiMemoryStore.TARGET_USER).put("operations", new JSONArray());
                for (int i = 0; i < ops.length(); i++) {
                    JSONObject op = ops.optJSONObject(i);
                    if (op == null) continue;
                    String target = op.optString("target", AiMemoryStore.TARGET_MEMORY);
                    JSONObject clean = new JSONObject(op.toString());
                    clean.remove("target");
                    if (AiMemoryStore.TARGET_USER.equals(target)) byUser.getJSONArray("operations").put(clean);
                    else byMemory.getJSONArray("operations").put(clean);
                }
                applyReviewOps(runId, byMemory, memoryApproval, memoryEnabled, userEnabled);
                applyReviewOps(runId, byUser, memoryApproval, memoryEnabled, userEnabled);
                applyReviewSkillOps(runId, skillOps, memoryApproval);
            } catch (Exception e) {
                try { mDatabase.appendEvent(runId, "memory/reviewSkipped", new JSONObject().put("error", e.getMessage()).toString()); } catch (Exception ignored) {}
            }
        }, "khabeer-memory-review").start();
    }

    /** Skill side of the review nudge: staged for approval like memory
     * when the write gate is on, else applied through the ownership +
     * guard-scan path. Surfaced with the same notice event. */
    private void applyReviewSkillOps(String runId, JSONArray skillOps, boolean approval) throws Exception {
        if (skillOps == null || skillOps.length() == 0) return;
        JSONObject result;
        if (approval) {
            JSONArray stagedIds = new JSONArray();
            for (int i = 0; i < skillOps.length(); i++) {
                JSONObject op = skillOps.optJSONObject(i);
                if (op == null) continue;
                JSONObject staged = AiSkillRegistry.stageSkillWrite(op, "background_review");
                if (staged.optBoolean("success")) stagedIds.put(staged.optString("id"));
            }
            result = new JSONObject().put("success", true).put("done", true)
                .put("applied", 0).put("staged_ids", stagedIds)
                .put("message", "Staged " + stagedIds.length() + " skill change(s) for approval.");
        } else {
            result = AiSkillRegistry.applyReviewSkillOps(skillOps);
        }
        mDatabase.appendEvent(runId, "memory/reviewResult", result.toString());
        if (mProviderConfig == null || !result.optBoolean("success")) return;
        String mode = mProviderConfig.getMemoryNotifyMode();
        if ("off".equals(mode)) return;
        JSONArray names = new JSONArray();
        for (int i = 0; i < skillOps.length(); i++) {
            JSONObject op = skillOps.optJSONObject(i);
            if (op != null && !TextUtils.isEmpty(op.optString("name", "").trim())) names.put(op.optString("name", "").trim());
        }
        JSONObject notice = new JSONObject()
            .put("mode", mode)
            .put("target", "skill")
            .put("staged", approval)
            .put("skills", names)
            .put("usage", result.optString("message", ""));
        if ("verbose".equals(mode) && names.length() > 0) {
            notice.put("preview", "📝 " + names.join(", ").replace("\"", ""));
        }
        emit("memory/reviewApplied", notice);
    }

    private void applyReviewOps(String runId, JSONObject args, boolean approval, boolean memoryEnabled, boolean userEnabled) throws Exception {
        JSONArray ops = args.optJSONArray("operations");
        if (ops == null || ops.length() == 0) return;
        String result = approval
            ? AiMemoryStore.stageWrite(args, "background_review").toString()
            : AiMemoryStore.tool(args, memoryEnabled, userEnabled);
        mDatabase.appendEvent(runId, "memory/reviewResult", new JSONObject(result).toString());
        if (mProviderConfig == null || !new JSONObject(result).optBoolean("success")) return;
        String mode = mProviderConfig.getMemoryNotifyMode();
        if ("off".equals(mode)) return;
        JSONObject notice = new JSONObject()
            .put("mode", mode)
            .put("target", args.optString("target", "memory"))
            .put("staged", approval)
            .put("usage", new JSONObject(result).optString("usage", ""));
        if ("verbose".equals(mode)) {
            StringBuilder previews = new StringBuilder();
            for (int i = 0; i < Math.min(3, ops.length()); i++) {
                JSONObject op = ops.optJSONObject(i);
                if (op == null) continue;
                String action = op.optString("action", "add");
                String text = "remove".equals(action) ? op.optString("old_text", "") : op.optString("content", op.optString("new_text", ""));
                if (text.length() > ("remove".equals(action) ? 60 : 120)) text = text.substring(0, "remove".equals(action) ? 60 : 120) + "…";
                if (previews.length() > 0) previews.append("\n");
                previews.append(("add".equals(action) ? "➕ " : "remove".equals(action) ? "➖ " : "✏️ ")).append(text);
            }
            notice.put("preview", previews.toString());
        }
        emit("memory/reviewApplied", notice);
    }

    private String callBackgroundMemoryReview(String providerId, String baseUrl, String apiKey, String model, JSONArray transcript,
                                              boolean memoryEnabled, boolean userEnabled, @Nullable String sessionId) throws Exception {
        String prompt = "You are the khabeer background memory reviewer. Inspect the recent transcript and return ONLY JSON. " +
            "If nothing durable should be saved, return {\"operations\":[]}. " +
            "Otherwise return {\"operations\":[{\"target\":\"memory|user\",\"action\":\"add|replace|remove\",\"content\":\"...\",\"old_text\":\"...\"}]}. " +
            "Save user preferences/corrections/profile to user. Save stable environment/project/tool lessons to memory. " +
            "Skip task progress, transient paths, raw dumps, and procedures that belong in skills. Write declarative facts, not imperatives. " +
            "Enabled targets: memory=" + memoryEnabled + ", user=" + userEnabled + ". " +
            "Then review SKILLS: if the session surfaced a reusable procedure, technique, workaround, or pitfall a future session would benefit from, " +
            "return it too as \"skill_operations\":[{\"action\":\"create|patch|write_file\",\"name\":\"...\",\"content|old_string|new_string|file_path|file_content\":\"...\"}]. " +
            "Prefer patching an existing skill you saw loaded over creating narrow one-off skills; new names must be class-level, never error strings or session artifacts. " +
            "Never capture env failures, missing tools, transient errors, one-off narratives, or unvalidated guesses. " +
            "If no skill lesson emerged, use \"skill_operations\":[]. " +
            "Transcript JSON:\n" + transcript.toString();
        if ("openai-codex".equals(providerId)) return callBackgroundCodex(model, prompt, "Return strict JSON only.");
        if (ANTHROPIC_MESSAGES_PROVIDERS.contains(providerId)) return callBackgroundAnthropic(baseUrl, apiKey, model, prompt, 1024, "Return strict JSON only.", sessionId);
        if (usesChatCompletions(providerId)) return callBackgroundChatCompletions(providerId, baseUrl, apiKey, model, prompt, 1024, "Return strict JSON only.", sessionId);
        return callBackgroundResponses(baseUrl, apiKey, model, prompt, 1024, "Return strict JSON only.", sessionId);
    }

    private String callBackgroundCompactionSummary(String providerId, String baseUrl, String apiKey, String model,
                                                   String sessionId, JSONArray transcript) throws Exception {
        String prompt = "You are generating a manual Hermes-style context compaction checkpoint for khabeer mobile. " +
            "Return the final summary text only. Do not include JSON, markdown fences, commentary, or apologies.\n\n" +
            "The summary MUST begin exactly with this prefix:\n" + compactionPrefix() + "\n\n" +
            "Then include these sections exactly, in this order:\n" +
            "## Historical Task Snapshot\n" +
            "## Goal\n" +
            "## Constraints\n" +
            "## Completed Actions\n" +
            "## Active State\n" +
            "## Blocked\n" +
            "## Key Decisions\n" +
            "## Errors & Fixes\n" +
            "## Relevant Files\n" +
            "## Critical Context\n" +
            "## Context Recovery\n\n" +
            "Rules: be compact but concrete; quote security/user constraints verbatim when present; preserve the latest unfulfilled request verbatim where possible; " +
            "do not turn MEMORY.md or USER.md content into weaker advice; newest user reversals such as stop/undo/never mind override older work; " +
            "Context Recovery must include this exact callable hint: session_search(query='<keywords>', session_id='" + sessionId + "'), replacing <keywords> with high-signal terms.\n\n" +
            "Transcript JSON:\n" + transcript.toString();
        String system = "Return the final compaction summary text only.";
        if ("openai-codex".equals(providerId)) return callBackgroundCodex(model, prompt, system);
        if (ANTHROPIC_MESSAGES_PROVIDERS.contains(providerId)) return callBackgroundAnthropic(baseUrl, apiKey, model, prompt, 4096, system, sessionId);
        if (usesChatCompletions(providerId)) return callBackgroundChatCompletions(providerId, baseUrl, apiKey, model, prompt, 4096, system, sessionId);
        return callBackgroundResponses(baseUrl, apiKey, model, prompt, 4096, system, sessionId);
    }

    private String compactionPrefix() {
        return "[CONTEXT COMPACTION — REFERENCE ONLY]\n" +
            "Your persistent memory (MEMORY.md, USER.md) in the system prompt is ALWAYS authoritative — never deprioritize memory due to this compaction note.\n" +
            "If the newest user message reverses earlier work (\"stop\", \"undo\", \"never mind\"), obey the newest user message and end any in-flight work from this summary.";
    }

    private String callBackgroundChatCompletions(String providerId, String baseUrl, String apiKey, String model, String prompt) throws Exception {
        return callBackgroundChatCompletions(providerId, baseUrl, apiKey, model, prompt, 1024, "Return strict JSON only.");
    }

    private String callBackgroundChatCompletions(String providerId, String baseUrl, String apiKey, String model, String prompt, int maxTokens, String system) throws Exception {
        return callBackgroundChatCompletions(providerId, baseUrl, apiKey, model, prompt, maxTokens, system, null);
    }

    private String callBackgroundChatCompletions(String providerId, String baseUrl, String apiKey, String model, String prompt, int maxTokens, String system, @Nullable String sessionId) throws Exception {
        JSONArray messages = new JSONArray()
            .put(new JSONObject().put("role", "system").put("content", system))
            .put(new JSONObject().put("role", "user").put("content", prompt));
        JSONObject body = new JSONObject().put("model", model).put("messages", messages).put("stream", false);
        if (maxTokens > 0) body.put("max_tokens", maxTokens);
        String text = postJson(chatCompletionsUrl(baseUrl), body, apiKey, providerId, sessionId);
        JSONObject o = new JSONObject(text);
        JSONArray choices = o.optJSONArray("choices");
        JSONObject first = choices == null || choices.length() == 0 ? null : choices.optJSONObject(0);
        JSONObject msg = first == null ? null : first.optJSONObject("message");
        return msg == null ? text : msg.optString("content", text);
    }

    private String callBackgroundResponses(String baseUrl, String apiKey, String model, String prompt) throws Exception {
        return callBackgroundResponses(baseUrl, apiKey, model, prompt, 1024, "Return strict JSON only.");
    }

    private String callBackgroundResponses(String baseUrl, String apiKey, String model, String prompt, int maxOutputTokens, String system) throws Exception {
        return callBackgroundResponses(baseUrl, apiKey, model, prompt, maxOutputTokens, system, null);
    }

    private String callBackgroundResponses(String baseUrl, String apiKey, String model, String prompt, int maxOutputTokens, String system, @Nullable String sessionId) throws Exception {
        JSONObject body = new JSONObject()
            .put("model", model)
            .put("instructions", system)
            .put("input", prompt);
        if (maxOutputTokens > 0) body.put("max_output_tokens", maxOutputTokens);
        String text = postJson(baseUrl, body, apiKey, "", sessionId);
        JSONObject o = new JSONObject(text);
        String out = o.optString("output_text", "");
        return TextUtils.isEmpty(out) ? text : out;
    }

    private String callBackgroundAnthropic(String baseUrl, String apiKey, String model, String prompt) throws Exception {
        return callBackgroundAnthropic(baseUrl, apiKey, model, prompt, 1024, "Return strict JSON only.");
    }

    private String callBackgroundAnthropic(String baseUrl, String apiKey, String model, String prompt, int maxTokens, String system) throws Exception {
        return callBackgroundAnthropic(baseUrl, apiKey, model, prompt, maxTokens, system, null);
    }

    private String callBackgroundAnthropic(String baseUrl, String apiKey, String model, String prompt, int maxTokens, String system, @Nullable String sessionId) throws Exception {
        JSONObject body = new JSONObject()
            .put("model", model)
            .put("max_tokens", Math.max(1, maxTokens))
            .put("system", system)
            .put("messages", new JSONArray().put(new JSONObject()
                .put("role", "user")
                .put("content", prompt)));
        boolean oauth = isAnthropicOAuthToken(apiKey);
        HttpURLConnection c = openJsonConnection(anthropicMessagesUrl(baseUrl), oauth ? apiKey : "", "", sessionId);
        if (oauth) {
            c.setRequestProperty("anthropic-beta", "oauth-2025-04-20");
            c.setRequestProperty("Authorization", "Bearer " + apiKey);
        } else if (!TextUtils.isEmpty(apiKey)) {
            c.setRequestProperty("x-api-key", apiKey);
        }
        c.setRequestProperty("anthropic-version", "2023-06-01");
        try (OutputStream output = c.getOutputStream()) { output.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
        int code = c.getResponseCode();
        String text = readFully(code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream());
        if (code < 200 || code >= 300) throw new IllegalStateException("Background review failed HTTP " + code + ": " + text);
        JSONObject o = new JSONObject(text);
        JSONArray content = o.optJSONArray("content");
        if (content != null && content.length() > 0) return content.optJSONObject(0).optString("text", text);
        return text;
    }

    private String postJson(String url, JSONObject body, String apiKey, String providerId, @Nullable String sessionId) throws Exception {
        HttpURLConnection c = openJsonConnection(url, apiKey, providerId, sessionId);
        try (OutputStream output = c.getOutputStream()) { output.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
        int code = c.getResponseCode();
        String text = readFully(code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream());
        if (code < 200 || code >= 300) throw new IllegalStateException("Background review failed HTTP " + code + ": " + text);
        return text;
    }

    private HttpURLConnection openJsonConnection(String url, String apiKey, String providerId, @Nullable String sessionId) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(MODEL_CONNECT_TIMEOUT_MS);
        c.setReadTimeout(MODEL_READ_TIMEOUT_MS);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        if (!TextUtils.isEmpty(apiKey)) c.setRequestProperty("Authorization", "Bearer " + apiKey);
        c.setRequestProperty("X-Title", "Termux khabeer");
        applyOpenCodeSessionHeader(c, url, sessionId);
        if ("github-copilot".equals(providerId)) {
            for (int i = 0; i < ProviderLogin.COPILOT_REQUEST_HEADERS.length; i += 2)
                c.setRequestProperty(ProviderLogin.COPILOT_REQUEST_HEADERS[i], ProviderLogin.COPILOT_REQUEST_HEADERS[i + 1]);
        }
        return c;
    }

    private JSONObject parseReviewJson(String raw) {
        if (TextUtils.isEmpty(raw)) return null;
        String clean = raw.trim();
        int start = clean.indexOf('{');
        int end = clean.lastIndexOf('}');
        if (start >= 0 && end > start) clean = clean.substring(start, end + 1);
        try { return new JSONObject(clean); } catch (Exception e) { return null; }
    }

    private void transition(AiRunStateMachine.State next) {
        if (ctx() == null || ctx().record == null) return;
        try {
            ctx().stateMachine.transition(next);
            ctx().record.state = next;
            persistRun();
        } catch (IllegalStateException e) {
            notifyError(ctx().record.id, e.getMessage());
        }
    }

    /** All executors end here: single choke point for flush, error
     * clearing, titles, and the review nudge. The ctx overload holds the
     * logic; this keeps the call sites unchanged. */
    private void completeRun() {
        completeRun(ctx());
    }

    private void persistRun() {
        if (ctx() == null) return;
        ctx().record.updatedAt = System.currentTimeMillis();
        mDatabase.saveRun(ctx().record);
        AiDatabase.RunRecord copy = copyRun(ctx().record);
        mHandler.post(() -> {
            for (Listener listener : new ArrayList<>(mListeners)) listener.onRunChanged(copy);
        });
    }

    private void failRun(@Nullable String message) {
        failRun(ctx(), message);
    }

    private boolean isQuiet() {
        return Boolean.TRUE.equals(mQuietTurn.get());
    }

    /** Run-targeted broadcast for work that owns no turn context
     * (compaction): same event store as emit, always delivered. */
    private void emitForRun(String runId, String method, JSONObject payload) {
        if (TextUtils.isEmpty(runId) || payload == null) return;
        mDatabase.appendEvent(runId, method, payload.toString());
        try { payload.put("runId", runId); } catch (Exception ignored) {}
        mHandler.post(() -> {
            for (Listener listener : new ArrayList<>(mListeners)) listener.onProtocolEvent(runId, method, payload);
        });
    }

    /** Session id with an in-flight compaction, if any. New turns are
     * refused while set: interleaving a turn with archiving corrupts both. */
    private volatile String mCompactingRunId;

    public boolean isCompacting() {
        return mCompactingRunId != null;
    }

    /** Refuses turns on the compacting session only: archiving and
     * appending the same transcript concurrently corrupts both. Other
     * sessions are unaffected. */
    private boolean refuseIfCompacting(@Nullable String runId) {
        if (mCompactingRunId == null || runId == null || !mCompactingRunId.equals(runId)) return false;
        notifyError(runId, "Compaction is running on this session — wait for it to finish.");
        return true;
    }

    private void emit(String method, JSONObject payload) {
        if (ctx() == null) return;
        mDatabase.appendEvent(ctx().record.id, method, payload.toString());
        if (isQuiet()) return;
        String runId = ctx().record.id;
        mHandler.post(() -> {
            for (Listener listener : new ArrayList<>(mListeners)) listener.onProtocolEvent(runId, method, payload);
        });
        if (method.equals("item/agentMessage/delta") && ctx().record != null) {
            try { mDatabase.appendMessage(ctx().record.id, "assistant", payload.optString("text")); } catch (Exception ignored) {}
        }
    }

    private void notifyError(@Nullable String runId, String message) {
        if (isQuiet()) return;
        mHandler.post(() -> {
            for (Listener listener : new ArrayList<>(mListeners))
                listener.onRuntimeError(runId, message == null ? "Unknown runtime error." : message);
        });
    }

    private boolean isTerminalState() {
        if (ctx().stateMachine == null) return false;
        AiRunStateMachine.State state = ctx().stateMachine.getState();
        return state == AiRunStateMachine.State.COMPLETED
            || state == AiRunStateMachine.State.FAILED
            || state == AiRunStateMachine.State.CANCELED;
    }

    private AiDatabase.RunRecord copyRun(AiDatabase.RunRecord source) {
        if (source == null) return null;
        AiDatabase.RunRecord copy = new AiDatabase.RunRecord();
        copy.id = source.id;
        copy.harnessId = source.harnessId;
        copy.workspace = source.workspace;
        copy.threadId = source.threadId;
        copy.turnId = source.turnId;
        copy.state = source.state;
        copy.lastError = source.lastError;
        copy.updatedAt = source.updatedAt;
        copy.createdAt = source.createdAt;
        copy.sessionKey = source.sessionKey;
        copy.parentSessionId = source.parentSessionId;
        copy.previousResponseId = source.previousResponseId;
        copy.chatMessagesJson = source.chatMessagesJson;
        copy.resumePending = source.resumePending;
        copy.activeTurnToken = source.activeTurnToken;
        copy.activeTurnStartedAt = source.activeTurnStartedAt;
        copy.hygieneFailureStreak = source.hygieneFailureStreak;
        copy.compressionCooldownUntil = source.compressionCooldownUntil;
        copy.modelOverride = source.modelOverride;
        copy.lastResolvedModel = source.lastResolvedModel;
        copy.archived = source.archived;
        copy.title = source.title;
        copy.titleSource = source.titleSource;
        copy.route = source.route;
        copy.source = source.source;
        copy.todoJson = source.todoJson;
        return copy;
    }

    private Notification buildNotification() {
        Intent intent = new Intent(this, AiActivity.class);
        android.app.PendingIntent pendingIntent = android.app.PendingIntent.getActivity(this, 0, intent,
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? android.app.PendingIntent.FLAG_IMMUTABLE : 0);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(this, CHANNEL_ID)
            : new Notification.Builder(this);
        return builder
            .setSmallIcon(R.drawable.ic_khabeer_notification)
            .setContentTitle(getString(R.string.ai_app_title))
            .setContentText(getString(R.string.ai_runtime_active))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        manager.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
            getString(R.string.ai_app_title), NotificationManager.IMPORTANCE_LOW));
        manager.createNotificationChannel(new NotificationChannel(CHANNEL_APPROVAL,
            "Approvals", NotificationManager.IMPORTANCE_HIGH));
        manager.createNotificationChannel(new NotificationChannel(CHANNEL_DONE,
            "Finished sessions", NotificationManager.IMPORTANCE_DEFAULT));
    }

    private final java.util.concurrent.atomic.AtomicBoolean mTitleBusy = new java.util.concurrent.atomic.AtomicBoolean(false);

    /** AI session titles (khabeer title_generator): a one-shot model call that
     * names the session after its opening exchange. Message-derived titles
     * are regenerated; AI titles are never touched. */
    /** Synchronous single-session titler for the backfill queue. Returns
     * true when the session got (or already had) a final title. */
    private boolean generateSessionTitleSync(String runId) {
        try {
            AiDatabase.RunRecord record = mDatabase.getRun(runId);
            if (record == null) return true;
            if ("ai".equals(record.titleSource)) return true;
            if (!TextUtils.isEmpty(record.title)) return true; // message-derived: final
            AiProviderProfile profile = AiProviderProfile.find(record.harnessId);
            if (profile == null) return true;

            JSONArray history = mDatabase.getTranscript(runId, 4);
            String firstUser = null;
            for (int i = 0; i < history.length(); i++) {
                JSONObject row = history.optJSONObject(i);
                if (row != null && "user".equals(row.optString("role"))) { firstUser = row.optString("content"); break; }
            }
            if (TextUtils.isEmpty(firstUser)) return true; // nothing to name

            // Short opening message: it IS the title, no model call.
            if (firstUser.length() <= 50) {
                String direct = bound(firstUser);
                if (TextUtils.isEmpty(direct)) return true;
                saveTitle(runId, direct, "message");
                return true;
            }

            // Longer openings get summarized. Route to the free keyless
            // model first; fall back to the session's configured model.
            String baseUrl = mProviderConfig.getBaseUrl(profile);
            String apiKey = mProviderConfig.getApiKey(profile);
            String model = "big-pickle";
            if (TextUtils.isEmpty(model)) model = mProviderConfig.getModel(profile);
            if (TextUtils.isEmpty(model)) model = record.lastResolvedModel;
            if (TextUtils.isEmpty(model)) model = profile.defaultModel;

            JSONArray messages = new JSONArray()
                .put(json("role", "system", "content",
                    "You name chat sessions. Summarize the user's opening message as a title of 50 characters or less. Reply with the title only."))
                .put(json("role", "user", "content", bound(firstUser) + "\n\nTitle:"));

            JSONObject body = new JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("max_tokens", 200)
                .put("temperature", 0.3);
            HttpURLConnection connection = (HttpURLConnection) new URL(chatCompletionsUrl(baseUrl)).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(MODEL_CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(30000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            if (!TextUtils.isEmpty(apiKey)) connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            applyOpenCodeSessionHeader(connection, chatCompletionsUrl(baseUrl), runId);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = connection.getResponseCode();
            android.util.Log.d("AiTitles", "http " + code + " model=" + model + " for " + runId.substring(0, 8));
            if (code == 429) return false; // rate limited: caller retries
            if (code < 200 || code >= 300) return false;

            String title;
            if ("openai-codex".equals(profile.id)) {
                title = callBackgroundCodex(model,
                    bound(firstUser) + "\n\nTitle:",
                    "You name chat sessions. Summarize the user's opening message as a title of 50 characters or less. Reply with the title only.");
            } else {
                JSONObject response = new JSONObject(readFully(connection.getInputStream()));
                JSONArray choices = response.optJSONArray("choices");
                JSONObject choice = choices == null ? null : choices.optJSONObject(0);
                JSONObject message = choice == null ? null : choice.optJSONObject("message");
                title = message == null ? "" : message.optString("content", "");
                if (TextUtils.isEmpty(title) || "null".equals(title))
                    title = message == null ? "" : message.optString("reasoning_content", "");
            }
            title = title.replace("\"", "").replace("*", "").replace("#", "").trim();
            int sentenceEnd = title.indexOf(". ");
            if (sentenceEnd > 12) title = title.substring(0, sentenceEnd + 1);
            android.util.Log.d("AiTitles", "raw title=[" + title + "]");
            if (TextUtils.isEmpty(title)) return false;

            // Enforce the 50-character title budget.
            if (title.length() > 50) {
                String[] words = title.split("\\s+");
                StringBuilder cut = new StringBuilder();
                for (String w : words) {
                    if (cut.length() + w.length() + 1 > 47) break;
                    if (cut.length() > 0) cut.append(' ');
                    cut.append(w);
                }
                title = cut.length() > 0 ? cut.toString() : title.substring(0, 47);
            }
            if (TextUtils.isEmpty(title)) return false;

            saveTitle(runId, title, "ai");
            return true;
        } catch (Exception e) {
            android.util.Log.d("AiTitles", "titler failed for " + runId, e);
            return false;
        }
    }

    private void saveTitle(String runId, String title, String source) {
        AiDatabase.RunRecord fresh = mDatabase.getRun(runId);
        if (fresh == null || "ai".equals(fresh.titleSource)) return;
        fresh.title = title;
        fresh.titleSource = source;
        mDatabase.saveRun(fresh);
        // Checkpoint immediately: the UI (and any fresh process) must always
        // see titles — never leave them hostage to a stale WAL.
        try { mDatabase.getWritableDatabase().execSQL("PRAGMA wal_checkpoint(TRUNCATE)"); } catch (Exception ignored) {}
        AiDatabase.RunRecord copy = copyRun(fresh);
        mHandler.post(() -> {
            for (Listener listener : new ArrayList<>(mListeners)) listener.onRunChanged(copy);
        });
    }

    private void generateSessionTitleAsync(String runId) {
        if (!mTitleBusy.compareAndSet(false, true)) return;
        Thread t = new Thread(() -> {
            try { generateSessionTitleSync(runId); }
            finally { mTitleBusy.set(false); }
        }, "session-titler");
        t.setDaemon(true);
        t.start();
    }

    /** Backfill: name older sessions that only carry a first-message title. */
public void backfillSessionTitles() {
        // Heal the historical bug that stored the literal string "null" as an
        // AI title: those rows become untitled again and get a real name.
        try {
            mDatabase.getWritableDatabase().execSQL(
                "UPDATE runs SET title = NULL, title_source = NULL WHERE title_source = 'ai' AND (title = 'null' OR title IS NULL OR title LIKE '**%')");
        } catch (Exception ignored) {}
        if (!mTitleBusy.compareAndSet(false, true)) return;
        Thread t = new Thread(() -> {
            try {
                // Drain the whole queue: no stuck states, ever. Rate limits
                // back off and retry instead of dropping the session.
                for (int pass = 0; pass < 3; pass++) {
                    java.util.List<AiDatabase.RunRecord> pending = mDatabase.getUntitledSessions(10);
                    if (pending.isEmpty()) return;
                    for (AiDatabase.RunRecord run : pending) {
                        for (int attempt = 0; attempt < 2; attempt++) {
                            boolean done = generateSessionTitleSync(run.id);
                            if (done) break;
                            try { Thread.sleep(8000); } catch (InterruptedException ie) { return; }
                        }
                    }
                    if (mDatabase.getUntitledSessions(1).isEmpty()) return;
                    try { Thread.sleep(5000); } catch (InterruptedException ie) { return; }
                }
            } catch (Exception e) {
                android.util.Log.d("AiTitles", "backfill failed", e);
            } finally {
                mTitleBusy.set(false);
            }
        }, "session-titler");
        t.setDaemon(true);
        t.start();
    }

    private String bound(String text) {
        if (text == null) return "";
        String clean = text.replace('\n', ' ').trim();
        return clean.length() <= 400 ? clean : clean.substring(0, 399) + "…";
    }

    private void cancelApprovalsFor(String runId) {
        for (Long id : new ArrayList<>(mPendingApprovals.keySet())) {
            PendingApproval approval = mPendingApprovals.get(id);
            if (approval != null && runId.equals(approval.runId)) {
                approval.cancel();
                mPendingApprovals.remove(id);
            }
        }
    }

    private static final class PendingApproval {
        private final CountDownLatch latch = new CountDownLatch(1);
        private volatile boolean approved;
        final String runId;
        volatile String command;
        volatile String workspace;
        volatile JSONObject payload;

        PendingApproval(String runId) { this.runId = runId; }

        boolean await() throws InterruptedException {
            // Auto-deny backstop: if nobody answers (e.g. the UI died), the
            // turn resumes with approved=false instead of wedging forever.
            latch.await(APPROVAL_AUTO_DENY_MS, TimeUnit.MILLISECONDS);
            return approved;
        }

        void answer(boolean approved) {
            this.approved = approved;
            latch.countDown();
        }

        void cancel() {
            approved = false;
            latch.countDown();
        }
    }
}
