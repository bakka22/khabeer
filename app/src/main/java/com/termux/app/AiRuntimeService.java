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
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Persistent native katheer-style runtime. Talks to model APIs directly and exposes Termux as tools. */
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
    private static final int MAX_MODEL_STEPS = 80;
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
    private final Map<String, KatheerSessionState> mSessions = new HashMap<>();

    private AiDatabase mDatabase;
    private MobileKatheerToolExecutor mToolExecutor;
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

    private KatheerSessionState sessionState(String key) {
        KatheerSessionState s = mSessions.get(key);
        if (s == null) { s = new KatheerSessionState(); mSessions.put(key, s); }
        return s;
    }


    /** Live sessions: each runs its own turns on its own worker thread, with
     * its own state machine, transcript and interrupt flags — parallel
     * sessions at once (katheer: every session is self-contained). */
    private static final class RunContext {
        final AiDatabase.RunRecord record;
        AiRunStateMachine stateMachine = new AiRunStateMachine();
        Thread worker;
        JSONArray chatMessages;
        String previousResponseId;
        volatile boolean stopRequested;
        volatile boolean lastTurnInterrupted;
        String steerText;
        /** skill_view repeat-view dedup: "name|file" -> "mtime:size" (katheer
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
        ctx.previousResponseId = record.previousResponseId;
        mRuns.put(record.id, ctx);
        return ctx;
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
        try {
            KatheerSessionState ss = sessionState(ctx.record.sessionKey == null ? ctx.record.id : ctx.record.sessionKey);
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
            KatheerSessionState ss = sessionState(ctx.record.sessionKey == null ? ctx.record.id : ctx.record.sessionKey);
            mDatabase.markTurnLease(ctx.record.sessionKey, token, ss.persistent.runGeneration);
            persistRun(ctx);
        } catch (Exception ignored) {}
    }

    private void failRun(RunContext ctx, @Nullable String message) {
        if (ctx == null) return;
        ctx.record.lastError = message == null ? "Unknown native agent runtime failure." : message;
        try { ctx.stateMachine.transition(AiRunStateMachine.State.FAILED); } catch (Exception ignored) {}
        ctx.record.state = AiRunStateMachine.State.FAILED;
        persistRun(ctx);
        notifyError(ctx.record.id, ctx.record.lastError);
    }

    private void completeRun(RunContext ctx) {
        if (ctx == null) return;
        try { ctx.stateMachine.transition(AiRunStateMachine.State.COMPLETED); } catch (Exception ignored) {}
        ctx.record.state = AiRunStateMachine.State.COMPLETED;
        ctx.record.hygieneFailureStreak = 0;
        clearActiveTurn(ctx);
        persistRun(ctx);
        if (ctx.record.sessionKey != null) sessionState(ctx.record.sessionKey).clearTurn();
        if (!"ai".equals(ctx.record.titleSource)) generateSessionTitleAsync(ctx.record.id);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mDatabase = new AiDatabase(this);
        mProviderConfig = new AiProviderConfig(this);
        mToolExecutor = new MobileKatheerToolExecutor(this);
        mMcpRegistry = new AiMcpRegistry(mProviderConfig);
        // One-time katheer home migration; must precede MCP discovery so
        // stored stdio paths are rewritten before any server is spawned.
        AiSkillRegistry.migrateKatheerHome();
        mDatabase.rewriteMcpServerDataRoot(".termuxAI", ".katheer");
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        // Seed bundled skills into $HOME/.katheer/skills (existing files win).
        Thread seeder = new Thread(() -> AiSkillRegistry.seedFromAssets(this), "skill-seeder");
        seeder.setDaemon(true);
        seeder.start();
        // Late-binding MCP discovery (katheer mcp_startup): never blocks a
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
        return START_STICKY;
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

    /** Archive a session (soft hide, katheer-style). Archiving the active run
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

    /** Resume a persisted session: restore its transcript into the runtime so
     * the next prompt continues that conversation. Busy turns must be stopped
     * first — switching mid-turn would scramble history (katheer claims the
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

    /** Starts a true session boundary: stops any turn and drops the current
     * run entirely so the next prompt creates a fresh session (katheer
     * session_reset). stopActiveRun alone keeps the run as current. */
public void newSession() {
        // Parallel sessions: leave every live run untouched — this only moves
        // the UI focus off the current session (katheer session boundary).
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
        KatheerSessionState s = sessionState(c.record.sessionKey == null ? c.record.id : c.record.sessionKey);
        s.conversation.sidecarNotes.add(text);
        c.steerText = text;
        emit("turn/steered", json("text", text));
        return true;
    }

public void startAgent(String providerId, String baseUrl, String apiKey, String workspace, String prompt,
                           String model, @Nullable String effort, @Nullable String approvalPolicy) {
        String normalizedWorkspace = MobileKatheerToolExecutor.normalizeWorkspace(workspace);
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

        // Parallel sessions: a new session starts immediately without touching
        // any other live session — they keep streaming in the background.
        String sessionKey = AiDatabase.buildSessionKey(providerId == null ? "native-agent" : providerId, normalizedWorkspace);
        AiDatabase.RunRecord record = mDatabase.createRun(providerId == null ? "native-agent" : providerId, normalizedWorkspace, sessionKey);
        record.modelOverride = model;
        record.lastResolvedModel = model;
        RunContext ctx = adoptRun(record);
        mViewed = ctx;
        KatheerSessionState ss = sessionState(sessionKey);
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
            KatheerSessionState ss = sessionState(c.record.sessionKey == null ? c.record.id : c.record.sessionKey);
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
        KatheerSessionState ss = sessionState(ctx.record.sessionKey == null ? ctx.record.id : ctx.record.sessionKey);
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
            KatheerSessionState ss = sessionState(ctx().record.sessionKey == null ? ctx().record.id : ctx().record.sessionKey);
            mDatabase.markTurnLease(ctx().record.sessionKey, token, ss.persistent.runGeneration);
            persistRun();
        } catch (Exception ignored) {}
    }

    private void clearActiveTurn() {
        if (ctx() == null) return;
        try {
            KatheerSessionState ss = sessionState(ctx().record.sessionKey == null ? ctx().record.id : ctx().record.sessionKey);
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
        // Session-authoritative resolution (katheer _restore_session_model):
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
            KatheerInterruptManager.setInterrupt(true, c.worker.getId(), "steer");
        }
        // Let the interrupted worker unwind so it can persist its partial reply
        // before we build the next turn (mirrors katheer' orderly interrupt drain).
        if (c.worker != null && c.worker.isAlive()) {
            try { c.worker.join(3000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        c.stopRequested = false;
        c.steerText = null;
        prompt = applyInterruptScaffold(c, prompt);
        persistRun(c);
        runTurn(c, providerId, baseUrl, apiKey, c.record.workspace, prompt, model, effort, approvalPolicy);
    }

    /** Session-scoped /model switch (katheer _persist_model_switch_to_session):
     * the model lives on the session row so resume restores it. */
public void setSessionModel(String model) {
        RunContext c = mViewed;
        if (c == null || TextUtils.isEmpty(model)) return;
        c.record.modelOverride = model;
        c.record.lastResolvedModel = model;
        persistRun(c);
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
        emit("session/route", json("sessionId", c.record.id, "route", route));
    }

    /** Session-scoped provider switch (katheer model_config.gateway_runtime):
     * the session keeps its transcript but subsequent turns run on the new
     * provider; the model resets to that provider's default. */
public void setSessionProvider(String providerId) {
        RunContext c = mViewed;
        if (c == null || TextUtils.isEmpty(providerId)) return;
        AiProviderProfile profile = AiProviderProfile.find(providerId);
        if (profile == null || profile.terminalOnly || !profile.implemented) return;
        c.record.harnessId = profile.id;
        c.record.modelOverride = mProviderConfig.getModel(profile);
        if (TextUtils.isEmpty(c.record.modelOverride)) c.record.modelOverride = profile.defaultModel;
        c.record.lastResolvedModel = c.record.modelOverride;
        persistRun(c);
        emit("session/provider", json("sessionId", c.record.id, "provider", profile.id));
    }

    /**
     * katheer-style interrupt checkpoint: when the previous turn was cut off
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
        if (ctx.worker != null) KatheerInterruptManager.setInterrupt(true, ctx.worker.getId(), "stop");
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
        KatheerInterruptManager.setInterrupt(true, c.worker.getId(), "interrupt");
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
        if (ctx.worker != null) ctx.worker.interrupt();
        String tag = ctx.record.id == null ? "runtime" : ctx.record.id.substring(0, Math.min(8, ctx.record.id.length()));
        ctx.worker = new Thread(() -> executeTurn(ctx, providerId, baseUrl, apiKey, workspace,
            prompt == null ? "" : prompt, model, effort, approvalPolicy), "katheer-" + tag);
        ctx.worker.start();
    }

    private void executeTurn(RunContext ctx, String providerId, String baseUrl, String apiKey, String workspace, String prompt,
                         String model, @Nullable String effort, @Nullable String approvalPolicy) {
        mTurnContext.set(ctx);
        KatheerInterruptManager.clearCurrentThread();
        KatheerInterruptManager.setInterrupt(false, Thread.currentThread().getId(), null);
        // A continuation after CANCELED needs a fresh machine (FSM has no CANCELED->RUNNING).
        if (ctx() == null || ctx().stateMachine.getState() == AiRunStateMachine.State.CANCELED
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
            appendUserTurnToHistory(ctx, prompt);
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
            if (ANTHROPIC_MESSAGES_PROVIDERS.contains(providerId)) {
                executeAnthropicTurn(ctx, providerId, baseUrl, apiKey, workspace, prompt, model, approvalPolicy);
                return;
            }
            for (int step = 0; step < MAX_MODEL_STEPS && !ctx().stopRequested && !KatheerInterruptManager.isInterrupted(); step++) {
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

        if (!ctx().stopRequested && !KatheerInterruptManager.isInterrupted()) failRun("The native agent reached its tool-step limit before finishing.");
            else if (KatheerInterruptManager.isInterrupted()) { emit("turn/interrupted", json("reason", KatheerInterruptManager.getReason())); ctx().lastTurnInterrupted = true; clearActiveTurn(); }
            else { ctx().lastTurnInterrupted = true; clearActiveTurn(); }
            drainQueueIfNeeded(ctx);
        } catch (Exception e) {
            if (!ctx().stopRequested && !KatheerInterruptManager.isInterrupted()) {
                if (isNetworkError(e)) { transitionWithFallback(AiRunStateMachine.State.DISCONNECTED); failRun(e.getMessage() == null ? e.toString() : e.getMessage()); }
                else failRun(e.getMessage() == null ? e.toString() : e.getMessage());
            } else { emit("turn/interrupted", json("reason", "cancel")); ctx().lastTurnInterrupted = true; clearActiveTurn(); }
        } finally { KatheerInterruptManager.clearCurrentThread(); mTurnContext.remove(); }
    }

    /** Single owner for user-turn mutation. Provider adapters must only
     * render this durable chat transcript into their API dialect; they must
     * not append the same user text again. */
    private void appendUserTurnToHistory(RunContext ctx, String prompt) throws Exception {
        if (ctx == null) throw new IllegalStateException("No active run context.");
        mDatabase.appendMessage(ctx.record.id, "user", prompt);
        refreshSystemMessage();
        sanitizeReplayHistory();
        if (ctx.chatMessages == null) ctx.chatMessages = new JSONArray();
        ctx.chatMessages.put(json("role", "user", "content", prompt));
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

    /** Convert the chat-format history into Responses-API input items (full replay, katheer-style). */
    private JSONArray toResponsesInput(JSONArray messages) throws Exception {
        JSONArray input = new JSONArray();
        for (int i = 0; i < messages.length(); i++) {
            JSONObject m = messages.optJSONObject(i);
            if (m == null) continue;
            String role = m.optString("role");
            if ("system".equals(role)) continue;
            if ("user".equals(role)) {
                input.put(new JSONObject().put("role", "user").put("content", m.optString("content", "")));
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
            for (int step = 0; step < MAX_MODEL_STEPS && !ctx().stopRequested && !KatheerInterruptManager.isInterrupted(); step++) {
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
            if (!ctx().stopRequested && !KatheerInterruptManager.isInterrupted()) failRun("The Codex agent reached its tool-step limit before finishing.");
            else { ctx().lastTurnInterrupted = true; clearActiveTurn(); }
            drainQueueIfNeeded(ctx);
        } catch (Exception e) {
            if (!ctx().stopRequested && !KatheerInterruptManager.isInterrupted()) failRun(e.getMessage() == null ? e.toString() : e.getMessage());
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
                    long backoff = KatheerRetry.jitteredBackoff(attempt, 1000, 8000);
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
        connection.setRequestProperty("originator", "katheer-agent");
        connection.setRequestProperty("User-Agent", "KatheerAgent/1.0");
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
                input.put(new JSONObject().put("role", "user")
                    .put("content", new JSONArray().put(new JSONObject().put("type", "input_text").put("text", m.optString("content", "")))));
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
            for (int step = 0; step < MAX_MODEL_STEPS && !ctx().stopRequested && !KatheerInterruptManager.isInterrupted(); step++) {
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
            if (!ctx().stopRequested && !KatheerInterruptManager.isInterrupted())
                failRun("The Anthropic-dialect agent reached its tool-step limit before finishing.");
            else { ctx().lastTurnInterrupted = true; clearActiveTurn(); }
            drainQueueIfNeeded(ctx);
        } catch (Exception e) {
            if (!ctx().stopRequested && !KatheerInterruptManager.isInterrupted()) failRun(e.getMessage() == null ? e.toString() : e.getMessage());
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
        }
        try (OutputStream output = connection.getOutputStream()) {
            output.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        int code = connection.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
        String text = readFully(stream);
        if (code < 200 || code >= 300)
            throw new IllegalStateException("Provider request failed with HTTP " + code + ": " + text);
        return new JSONObject(text);
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
                out.put(new JSONObject().put("role", "user")
                    .put("content", new JSONArray().put(new JSONObject().put("type", "text").put("text", m.optString("content", "")))));
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
                    long backoff = KatheerRetry.jitteredBackoff(attempt, 1000, 8000);
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

        for (int step = 0; step < MAX_MODEL_STEPS && !ctx().stopRequested && !KatheerInterruptManager.isInterrupted(); step++) {
            JSONObject response = callChatCompletionsApi(providerId, baseUrl, apiKey, model, ctx().chatMessages);
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

        if (!ctx().stopRequested && !KatheerInterruptManager.isInterrupted()) failRun("The chat-completions agent reached its tool-step limit before finishing.");
        else { ctx().lastTurnInterrupted = true; clearActiveTurn(); }
        drainQueueIfNeeded(ctx);
    }

    /**
     * katheer-style replay cleanup (agent/replay_cleanup.py): a turn killed
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

    private JSONObject callChatCompletionsApi(String providerId, String baseUrl, String apiKey, String model,
                                              JSONArray messages) throws Exception {
        JSONObject body = new JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("tools", modelTools(true))
            .put("tool_choice", "auto")
            .put("stream", true);

        HttpURLConnection connection = (HttpURLConnection) new URL(chatCompletionsUrl(baseUrl)).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(MODEL_CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(MODEL_READ_TIMEOUT_MS);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "text/event-stream");
        if (!TextUtils.isEmpty(apiKey)) connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        connection.setRequestProperty("X-Title", "Termux katheer");
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
        if (code < 200 || code >= 300) return callChatCompletionsApiBuffered(baseUrl, apiKey, model, messages);
        if (text.trim().startsWith("data:")) return readChatCompletionsStream(text);
        return new JSONObject(text);
    }

    private JSONObject callChatCompletionsApiBuffered(String baseUrl, String apiKey, String model,
                                                      JSONArray messages) throws Exception {
        JSONObject body = new JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("tools", modelTools(true))
            .put("tool_choice", "auto");

        HttpURLConnection connection = (HttpURLConnection) new URL(chatCompletionsUrl(baseUrl)).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(MODEL_CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(MODEL_READ_TIMEOUT_MS);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        if (!TextUtils.isEmpty(apiKey)) connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        connection.setRequestProperty("X-Title", "Termux katheer");
        try (OutputStream output = connection.getOutputStream()) {
            output.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }

        int code = connection.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
        String text = readFully(stream);
        if (code < 200 || code >= 300)
            throw new IllegalStateException("Provider request failed with HTTP " + code + ": " + text);
        return new JSONObject(text);
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
        return CHAT_COMPLETIONS_PROVIDERS.contains(providerId);
    }

    /** Providers that speak OpenAI chat completions (katheer dialect table:
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
            connection.setRequestProperty("HTTP-Referer", "https://termux.local/katheer");
            connection.setRequestProperty("X-Title", "Termux katheer");
        }
        try (OutputStream output = connection.getOutputStream()) {
            output.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }

        int code = connection.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
        String text = readFully(stream);
        if (code < 200 || code >= 300)
            throw new IllegalStateException("Provider request failed with HTTP " + code + ": " + text);
        return new JSONObject(text);
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

    /** Every tool the model can call: terminal + skills + MCP tools (katheer
     * merges all tools flat into one array — no wrapper tool). */
    private JSONArray modelTools(boolean chatShape) throws Exception {
        JSONArray tools = new JSONArray();
        tools.put(chatShape ? terminalToolForChat() : terminalTool());
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
        return tools;
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
                + "every touched skill back. Ops: create (full SKILL.md; lands in $HOME/.katheer/skills/; must precede that "
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

        String output;
        if (isSkillsTool(name)) {
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

        String output;
        if (isSkillsTool(name)) {
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
                return functionOutput(callId, output);
            }

            output = mToolExecutor.runCommand(workspace, command, timeout);
            emit("item/commandExecution/outputDelta", json("text", output + "\n", "command", command));
        }
        return functionOutput(callId, output);
    }

    private boolean isSkillsTool(String name) {
        return "skills_list".equals(name) || "skill_view".equals(name) || "skill_manage".equals(name);
    }

    /** skills_list/skill_view run locally (no approval, no shell): read-only
     * filesystem access. skill_manage MUTATES the skills root, so it gates
     * through the approval dialog before any write (katheer write-gate). */
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
     * MCP tool dispatch (katheer mcp tool handlers). Untrusted servers gate
     * through the approval dialog BEFORE any network call — fail-closed, the
     * same trust model katheer applies to write-capable tools.
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
        KatheerInterruptManager.clearCurrentThread();
        long id = mNextRequestId++;
        PendingApproval approval = new PendingApproval(ctx().record.id);
        mPendingApprovals.put(id, approval);
        KatheerSessionState ss = sessionState(ctx().record.sessionKey == null ? ctx().record.id : ctx().record.sessionKey);
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
        emit("terminal/requestApproval", payload);
        boolean ans = approval.await();
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
        sb.append("You are a native mobile agent running inside Termux on Android. ")
          .append("Use the terminal tool deliberately, explain what you are doing, and prefer small inspect-before-change steps. ")
          .append("Never claim a command succeeded unless the terminal output confirms it.");
        String skills = AiSkillRegistry.promptSection();
        if (!TextUtils.isEmpty(skills)) sb.append("\n\n").append(skills);
        return sb.toString();
    }

    /**
     * Rebuilds the system message at the start of every turn: new skills,
     * toggles and provider changes must reach EXISTING sessions too — the
     * persisted transcript carries a frozen copy otherwise (katheer rebuilds
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
                first.put("content", instructions);
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

    private void completeRun() {
        if (ctx() == null) return;
        try {
            ctx().stateMachine.transition(AiRunStateMachine.State.COMPLETED);
        } catch (Exception ignored) {
        }
        ctx().record.state = AiRunStateMachine.State.COMPLETED;
        ctx().record.hygieneFailureStreak = 0;
        clearActiveTurn();
        persistRun();
        if (ctx().record.sessionKey != null) sessionState(ctx().record.sessionKey).clearTurn();
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
        if (ctx() == null) return;
        ctx().record.lastError = message == null ? "Unknown native agent runtime failure." : message;
        try {
            if (ctx() != null) ctx().stateMachine.transition(AiRunStateMachine.State.FAILED);
        } catch (Exception ignored) {
        }
        ctx().record.state = AiRunStateMachine.State.FAILED;
        persistRun();
        notifyError(ctx().record.id, ctx().record.lastError);
    }

    private void emit(String method, JSONObject payload) {
        if (ctx() == null) return;
        mDatabase.appendEvent(ctx().record.id, method, payload.toString());
        String runId = ctx().record.id;
        mHandler.post(() -> {
            for (Listener listener : new ArrayList<>(mListeners)) listener.onProtocolEvent(runId, method, payload);
        });
        if (method.equals("item/agentMessage/delta") && ctx().record != null) {
            try { mDatabase.appendMessage(ctx().record.id, "assistant", payload.optString("text")); } catch (Exception ignored) {}
        }
    }

    private void notifyError(@Nullable String runId, String message) {
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
            .setSmallIcon(R.drawable.ic_service_notification)
            .setContentTitle(getString(R.string.ai_app_title))
            .setContentText(getString(R.string.ai_runtime_active))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
            getString(R.string.ai_app_title), NotificationManager.IMPORTANCE_LOW));
    }

    private final java.util.concurrent.atomic.AtomicBoolean mTitleBusy = new java.util.concurrent.atomic.AtomicBoolean(false);

    /** AI session titles (katheer title_generator): a one-shot model call that
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
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = connection.getResponseCode();
            android.util.Log.d("AiTitles", "http " + code + " model=" + model + " for " + runId.substring(0, 8));
            if (code == 429) return false; // rate limited: caller retries
            if (code < 200 || code >= 300) return false;

            JSONObject response = new JSONObject(readFully(connection.getInputStream()));
            JSONArray choices = response.optJSONArray("choices");
            JSONObject choice = choices == null ? null : choices.optJSONObject(0);
            JSONObject message = choice == null ? null : choice.optJSONObject("message");
            String title = message == null ? "" : message.optString("content", "");
            if (TextUtils.isEmpty(title) || "null".equals(title))
                title = message.optString("reasoning_content", "");
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
