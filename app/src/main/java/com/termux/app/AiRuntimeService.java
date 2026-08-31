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

/** Persistent native Hermes-style runtime. Talks to model APIs directly and exposes Termux as tools. */
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
    private final Map<String, HermesSessionState> mSessions = new HashMap<>();

    private AiDatabase mDatabase;
    private MobileHermesToolExecutor mToolExecutor;
    private AiProviderConfig mProviderConfig;
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

    private HermesSessionState sessionState(String key) {
        HermesSessionState s = mSessions.get(key);
        if (s == null) { s = new HermesSessionState(); mSessions.put(key, s); }
        return s;
    }


    /** Live sessions: each runs its own turns on its own worker thread, with
     * its own state machine, transcript and interrupt flags — parallel
     * sessions at once (Hermes: every session is self-contained). */
    private static final class RunContext {
        final AiDatabase.RunRecord record;
        AiRunStateMachine stateMachine = new AiRunStateMachine();
        Thread worker;
        JSONArray chatMessages;
        String previousResponseId;
        volatile boolean stopRequested;
        volatile boolean lastTurnInterrupted;
        String steerText;

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
            HermesSessionState ss = sessionState(ctx.record.sessionKey == null ? ctx.record.id : ctx.record.sessionKey);
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
            HermesSessionState ss = sessionState(ctx.record.sessionKey == null ? ctx.record.id : ctx.record.sessionKey);
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
        mToolExecutor = new MobileHermesToolExecutor(this);
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        try { mDatabase.recoverInterruptedTurns(); } catch (Exception ignored) {}
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
                    boolean fresh = System.currentTimeMillis() - r.updatedAt < AUTO_CONTINUE_FRESHNESS_MS;
                    if (r.state == AiRunStateMachine.State.CANCELED || r.state == AiRunStateMachine.State.COMPLETED) {
                        try {
                            ctx.stateMachine.transition(AiRunStateMachine.State.STARTING);
                            ctx.stateMachine.transition(AiRunStateMachine.State.CONNECTING);
                            ctx.stateMachine.transition(AiRunStateMachine.State.RUNNING);
                            r.state = AiRunStateMachine.State.RUNNING;
                        } catch (Exception ignored) {}
                    }
                    if (fresh && r.activeTurnToken != null) mDatabase.setResumePending(r.id, true);
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

    /** Archive a session (soft hide, Hermes-style). Archiving the active run
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
     * first — switching mid-turn would scramble history (Hermes claims the
     * active session before any switch). */
public void resumeRun(String runId) {
        AiDatabase.RunRecord r = mDatabase.getRun(runId);
        if (r == null) { notifyError(runId, "Session not found: " + runId); return; }
        boolean fresh = mRuns.get(runId) == null;
        if (r.archived) mDatabase.setRunArchived(runId, false);
        RunContext ctx = adoptRun(r);
        if (fresh) {
            // Not live in this process: rebuild the session context so the
            // transcript and route come back exactly as persisted.
            try {
                ctx.stateMachine.transition(AiRunStateMachine.State.STARTING);
                ctx.stateMachine.transition(AiRunStateMachine.State.CONNECTING);
                ctx.stateMachine.transition(AiRunStateMachine.State.RUNNING);
            } catch (Exception ignored) {}
        }
        mViewed = ctx;
        mDatabase.setResumePending(runId, false);
        r.state = AiRunStateMachine.State.RUNNING;
        persistRun(ctx);
        emit("session/resumed", json("sessionId", runId));
        for (Listener listener : new ArrayList<>(mListeners)) listener.onRunChanged(copyRun(r));
    }

    /** Starts a true session boundary: stops any turn and drops the current
     * run entirely so the next prompt creates a fresh session (Hermes
     * session_reset). stopActiveRun alone keeps the run as current. */
public void newSession() {
        // Parallel sessions: leave every live run untouched — this only moves
        // the UI focus off the current session (Hermes session boundary).
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
        HermesSessionState s = sessionState(c.record.sessionKey == null ? c.record.id : c.record.sessionKey);
        s.conversation.sidecarNotes.add(text);
        c.steerText = text;
        emit("turn/steered", json("text", text));
        return true;
    }

public void startAgent(String providerId, String baseUrl, String apiKey, String workspace, String prompt,
                           String model, @Nullable String effort, @Nullable String approvalPolicy) {
        String normalizedWorkspace = MobileHermesToolExecutor.normalizeWorkspace(workspace);
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
        HermesSessionState ss = sessionState(sessionKey);
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
            HermesSessionState ss = sessionState(c.record.sessionKey == null ? c.record.id : c.record.sessionKey);
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
        HermesSessionState ss = sessionState(ctx.record.sessionKey == null ? ctx.record.id : ctx.record.sessionKey);
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
            HermesSessionState ss = sessionState(ctx().record.sessionKey == null ? ctx().record.id : ctx().record.sessionKey);
            mDatabase.markTurnLease(ctx().record.sessionKey, token, ss.persistent.runGeneration);
            persistRun();
        } catch (Exception ignored) {}
    }

    private void clearActiveTurn() {
        if (ctx() == null) return;
        try {
            HermesSessionState ss = sessionState(ctx().record.sessionKey == null ? ctx().record.id : ctx().record.sessionKey);
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
        // Session-authoritative resolution (Hermes _restore_session_model):
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
        String apiKey = mProviderConfig.getApiKey(profile);
        String providerId = profile.id;

        if (!isTerminal(c) && c.worker != null && c.worker.isAlive()) {
            if (handleBusyInput(prompt, providerId, baseUrl, apiKey, model, effort, approvalPolicy)) return;
            c.stopRequested = true;
            try {
                c.stateMachine.transition(AiRunStateMachine.State.INTERRUPTING);
                c.record.state = AiRunStateMachine.State.INTERRUPTING;
                persistRun(c);
            } catch (Exception ignored) {}
            HermesInterruptManager.setInterrupt(true, c.worker.getId(), "steer");
        }
        // Let the interrupted worker unwind so it can persist its partial reply
        // before we build the next turn (mirrors Hermes' orderly interrupt drain).
        if (c.worker != null && c.worker.isAlive()) {
            try { c.worker.join(3000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        c.stopRequested = false;
        c.steerText = null;
        prompt = applyInterruptScaffold(c, prompt);
        persistRun(c);
        runTurn(c, providerId, baseUrl, apiKey, c.record.workspace, prompt, model, effort, approvalPolicy);
    }

    /** Session-scoped /model switch (Hermes _persist_model_switch_to_session):
     * the model lives on the session row so resume restores it. */
public void setSessionModel(String model) {
        RunContext c = mViewed;
        if (c == null || TextUtils.isEmpty(model)) return;
        c.record.modelOverride = model;
        c.record.lastResolvedModel = model;
        persistRun(c);
        emit("session/model", json("sessionId", c.record.id, "model", model));
    }

    /** Session-scoped provider switch (Hermes model_config.gateway_runtime):
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
     * Hermes-style interrupt checkpoint: when the previous turn was cut off
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
        if (ctx.worker != null) HermesInterruptManager.setInterrupt(true, ctx.worker.getId(), "stop");
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
        HermesInterruptManager.setInterrupt(true, c.worker.getId(), "interrupt");
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
            prompt == null ? "" : prompt, model, effort, approvalPolicy), "mobile-hermes-" + tag);
        ctx.worker.start();
    }

    private void executeTurn(RunContext ctx, String providerId, String baseUrl, String apiKey, String workspace, String prompt,
                         String model, @Nullable String effort, @Nullable String approvalPolicy) {
        mTurnContext.set(ctx);
        HermesInterruptManager.clearCurrentThread();
        HermesInterruptManager.setInterrupt(false, Thread.currentThread().getId(), null);
        // A continuation after CANCELED needs a fresh machine (FSM has no CANCELED->RUNNING).
        if (ctx() == null || ctx().stateMachine.getState() == AiRunStateMachine.State.CANCELED) {
            ctx().stateMachine = new AiRunStateMachine();
            try { ctx().stateMachine.transition(AiRunStateMachine.State.STARTING); ctx().stateMachine.transition(AiRunStateMachine.State.CONNECTING); } catch (Exception ignored) {}
            ctx().record.state = AiRunStateMachine.State.STARTING;
        }
        if (ctx().steerText != null) prompt = "[steered] " + ctx().steerText + "\n\n" + prompt;
        ctx().steerText = null;
        emit("turn/started", new JSONObject());
        transition(AiRunStateMachine.State.RUNNING);
        emit("item/agentMessage/delta", json("text", "Thinking…"));
        if (ctx() != null) mDatabase.appendMessage(ctx().record.id, "user", prompt);

        JSONArray input;
        try {
            if (ctx().chatMessages == null)
                ctx().chatMessages = new JSONArray().put(json("role", "system", "content", systemInstructions()));
            sanitizeReplayHistory();
            ctx().chatMessages.put(json("role", "user", "content", prompt));
            if (ctx() != null) { ctx().record.chatMessagesJson = ctx().chatMessages.toString(); persistRun(); }
            input = toResponsesInput(ctx().chatMessages);
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
            for (int step = 0; step < MAX_MODEL_STEPS && !ctx().stopRequested && !HermesInterruptManager.isInterrupted(); step++) {
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

        if (!ctx().stopRequested && !HermesInterruptManager.isInterrupted()) failRun("The native agent reached its tool-step limit before finishing.");
            else if (HermesInterruptManager.isInterrupted()) { emit("turn/interrupted", json("reason", HermesInterruptManager.getReason())); ctx().lastTurnInterrupted = true; clearActiveTurn(); }
            else { ctx().lastTurnInterrupted = true; clearActiveTurn(); }
            drainQueueIfNeeded(ctx);
        } catch (Exception e) {
            if (!ctx().stopRequested && !HermesInterruptManager.isInterrupted()) {
                if (isNetworkError(e)) { transitionWithFallback(AiRunStateMachine.State.DISCONNECTED); failRun(e.getMessage() == null ? e.toString() : e.getMessage()); }
                else failRun(e.getMessage() == null ? e.toString() : e.getMessage());
            } else { emit("turn/interrupted", json("reason", "cancel")); ctx().lastTurnInterrupted = true; clearActiveTurn(); }
        } finally { HermesInterruptManager.clearCurrentThread(); mTurnContext.remove(); }
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

    /** Convert the chat-format history into Responses-API input items (full replay, Hermes-style). */
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

    private JSONObject callResponsesApiWithRetry(String providerId, String baseUrl, String apiKey, String model, @Nullable String effort, JSONArray input) throws Exception {
        int maxRetries = 3;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try { return callResponsesApi(providerId, baseUrl, apiKey, model, effort, input); }
            catch (Exception e) {
                String msg = e.getMessage() == null ? "" : e.getMessage();
                boolean retryable = msg.contains("HTTP 429") || msg.contains("HTTP 5");
                if (attempt < maxRetries && retryable && !ctx().stopRequested) {
                    long backoff = HermesRetry.jitteredBackoff(attempt, 1000, 8000);
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
            ctx().chatMessages = new JSONArray().put(json("role", "system", "content", systemInstructions()));
        sanitizeReplayHistory();
        ctx().chatMessages.put(json("role", "user", "content", prompt));
        if (ctx() != null) { ctx().record.chatMessagesJson = ctx().chatMessages.toString(); persistRun(); }

        for (int step = 0; step < MAX_MODEL_STEPS && !ctx().stopRequested && !HermesInterruptManager.isInterrupted(); step++) {
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
                if (ctx() != null) mDatabase.appendMessage(ctx().record.id, "assistant", content);
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

        if (!ctx().stopRequested && !HermesInterruptManager.isInterrupted()) failRun("The chat-completions agent reached its tool-step limit before finishing.");
        else { ctx().lastTurnInterrupted = true; clearActiveTurn(); }
        drainQueueIfNeeded(ctx);
    }

    /**
     * Hermes-style replay cleanup (agent/replay_cleanup.py): a turn killed
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
            .put("tools", new JSONArray().put(terminalToolForChat()))
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
        connection.setRequestProperty("X-Title", "Termux Mobile Hermes");
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
            .put("tools", new JSONArray().put(terminalToolForChat()))
            .put("tool_choice", "auto");

        HttpURLConnection connection = (HttpURLConnection) new URL(chatCompletionsUrl(baseUrl)).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(MODEL_CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(MODEL_READ_TIMEOUT_MS);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        if (!TextUtils.isEmpty(apiKey)) connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        connection.setRequestProperty("X-Title", "Termux Mobile Hermes");
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
        return "opencode".equals(providerId);
    }

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
            .put("tools", new JSONArray().put(terminalTool()))
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
            connection.setRequestProperty("HTTP-Referer", "https://termux.local/mobile-hermes");
            connection.setRequestProperty("X-Title", "Termux Mobile Hermes");
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
        return new JSONObject()
            .put("type", "function")
            .put("function", new JSONObject()
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
                    .put("required", new JSONArray().put("command"))));
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
        if (!"terminal".equals(name)) {
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

        if (!"terminal".equals(name)) {
            return functionOutput(callId, new JSONObject().put("error", "Unknown tool: " + name).toString());
        }

        emit("tool/callStarted", json("name", name, "command", command));
        emit("item/commandExecution/outputDelta",
            json("text", "$ " + command + "\n", "command", command));

        boolean approved = "never".equals(approvalPolicy) || requestApproval(command, workspace);
        if (!approved) {
            String output = new JSONObject().put("error", "User denied terminal command.").toString();
            emit("item/commandExecution/outputDelta", json("text", output + "\n", "command", command));
            return functionOutput(callId, output);
        }

        String output = mToolExecutor.runCommand(workspace, command, timeout);
        emit("item/commandExecution/outputDelta", json("text", output + "\n", "command", command));
        return functionOutput(callId, output);
    }

    private boolean requestApproval(String command, String workspace) throws InterruptedException {
        if (ctx() == null) return false;
        HermesInterruptManager.clearCurrentThread();
        long id = mNextRequestId++;
        PendingApproval approval = new PendingApproval(ctx().record.id);
        mPendingApprovals.put(id, approval);
        HermesSessionState ss = sessionState(ctx().record.sessionKey == null ? ctx().record.id : ctx().record.sessionKey);
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
        return "You are a native mobile agent running inside Termux on Android. "
            + "Use the terminal tool deliberately, explain what you are doing, and prefer small inspect-before-change steps. "
            + "Never claim a command succeeded unless the terminal output confirms it.";
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

    /** AI session titles (Hermes title_generator): a one-shot model call that
     * names the session after its opening exchange. Message-derived titles
     * are regenerated; AI titles are never touched. */
    private void generateSessionTitleAsync(String runId) {
        AiDatabase.RunRecord record = mDatabase.getRun(runId);
        if (record == null) return;
        if ("ai".equals(record.titleSource)) return;
        AiProviderProfile profile = AiProviderProfile.find(record.harnessId);
        if (profile == null) return;
        if (!mTitleBusy.compareAndSet(false, true)) return;
        android.util.Log.d("AiTitles", "generating for " + runId + " provider=" + profile.id);
        Thread t = new Thread(() -> {
            try {
                JSONArray history = mDatabase.getTranscript(runId, 4);
                String firstUser = null;
                for (int i = 0; i < history.length(); i++) {
                    JSONObject row = history.optJSONObject(i);
                    if (row != null && "user".equals(row.optString("role"))) { firstUser = row.optString("content"); break; }
                }
                if (TextUtils.isEmpty(firstUser)) return;

                // Short opening message: it IS the title, no model call.
                if (firstUser.length() <= 50) {
                    String direct = bound(firstUser);
                    if (TextUtils.isEmpty(direct)) return;
                    AiDatabase.RunRecord fresh = mDatabase.getRun(runId);
                    if (fresh == null || "ai".equals(fresh.titleSource)) return;
                    fresh.title = direct;
                    fresh.titleSource = "message";
                    mDatabase.saveRun(fresh);
                    AiDatabase.RunRecord copy = copyRun(fresh);
                    mHandler.post(() -> {
                        for (Listener listener : new ArrayList<>(mListeners)) listener.onRunChanged(copy);
                    });
                    return;
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
                android.util.Log.d("AiTitles", "http " + code + " model=" + model);
                if (code < 200 || code >= 300) return;
                JSONObject response = new JSONObject(readFully(connection.getInputStream()));
                JSONArray choices = response.optJSONArray("choices");
                JSONObject choice = choices == null ? null : choices.optJSONObject(0);
                JSONObject message = choice == null ? null : choice.optJSONObject("message");
                String title = message == null ? "" : message.optString("content", "");
                if (TextUtils.isEmpty(title) || "null".equals(title))
                    title = message.optString("reasoning_content", "");
                title = title.replace("\"", "").trim();
                android.util.Log.d("AiTitles", "raw title=[" + title + "]");
                if (TextUtils.isEmpty(title)) return;
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
                if (TextUtils.isEmpty(title)) return;

                AiDatabase.RunRecord fresh = mDatabase.getRun(runId);
                if (fresh == null || "ai".equals(fresh.titleSource)) return;
                fresh.title = title;
                fresh.titleSource = "ai";
                mDatabase.saveRun(fresh);
                AiDatabase.RunRecord copy = copyRun(fresh);
                mHandler.post(() -> {
                    for (Listener listener : new ArrayList<>(mListeners)) listener.onRunChanged(copy);
                });
            } catch (Exception e) {
                android.util.Log.d("AiTitles", "titler failed", e);
            } finally {
                mTitleBusy.set(false);
            }
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
                "UPDATE runs SET title = NULL, title_source = NULL WHERE title_source = 'ai' AND (title = 'null' OR title IS NULL)");
        } catch (Exception ignored) {}
        for (AiDatabase.RunRecord run : mDatabase.getUntitledSessions(10)) {
            generateSessionTitleAsync(run.id);
            return; // one at a time; the page refresh keeps pulling
        }
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
