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

    private static final int NOTIFICATION_ID = 2401;
    private static final String CHANNEL_ID = "termux_ai_runtime";
    private static final int MAX_MODEL_STEPS = 80;
    private static final int MODEL_CONNECT_TIMEOUT_MS = 30000;
    private static final int MODEL_READ_TIMEOUT_MS = 10 * 60 * 1000;
    private static final int DEFAULT_TOOL_TIMEOUT_SECONDS = 300;

    private final IBinder mBinder = new LocalBinder();
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final List<Listener> mListeners = new ArrayList<>();
    private final Map<Long, PendingApproval> mPendingApprovals = new HashMap<>();

    private AiDatabase mDatabase;
    private MobileHermesToolExecutor mToolExecutor;
    private AiDatabase.RunRecord mActiveRun;
    private AiRunStateMachine mStateMachine;
    private Thread mWorker;
    private volatile boolean mStopRequested;
    private long mNextRequestId = 1;
    private String mPreviousResponseId;
    private JSONArray mChatCompletionMessages;

    @Override
    public void onCreate() {
        super.onCreate();
        mDatabase = new AiDatabase(this);
        mToolExecutor = new MobileHermesToolExecutor(this);
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
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
        if (mActiveRun != null) listener.onRunChanged(copyRun(mActiveRun));
    }

    public void removeListener(Listener listener) {
        mListeners.remove(listener);
    }

    @Nullable
    public AiDatabase.RunRecord getActiveRun() {
        return copyRun(mActiveRun);
    }

    public List<AiDatabase.RunRecord> getRecentRuns() {
        List<AiDatabase.RunRecord> result = new ArrayList<>();
        for (AiDatabase.RunRecord record : mDatabase.getRecentRuns(12)) result.add(copyRun(record));
        return result;
    }

    public void resumeRun(String runId) {
        notifyError(runId, "Session restore for native mobile agent sessions is coming next. Start a new session for now.");
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

        stopActiveRun();
        mStopRequested = false;
        mPreviousResponseId = null;
        mChatCompletionMessages = null;
        mActiveRun = mDatabase.createRun(providerId == null ? "native-agent" : providerId, normalizedWorkspace);
        mStateMachine = new AiRunStateMachine();
        transition(AiRunStateMachine.State.STARTING);
        transition(AiRunStateMachine.State.CONNECTING);
        runTurn(providerId, baseUrl, apiKey, normalizedWorkspace, prompt, model, effort, approvalPolicy);
    }

    public void sendPrompt(String prompt, String providerId, String baseUrl, String apiKey, String model,
                           @Nullable String effort, @Nullable String approvalPolicy) {
        if (mActiveRun == null) {
            notifyError(null, "No active native agent session.");
            return;
        }
        mStopRequested = false;
        runTurn(providerId, baseUrl, apiKey, mActiveRun.workspace, prompt, model, effort, approvalPolicy);
    }

    public void stopActiveRun() {
        mStopRequested = true;
        for (PendingApproval approval : new ArrayList<>(mPendingApprovals.values())) approval.cancel();
        mPendingApprovals.clear();
        if (mWorker != null) mWorker.interrupt();
        if (mActiveRun != null && mStateMachine != null && !isTerminalState()) {
            try {
                mStateMachine.transition(AiRunStateMachine.State.CANCELED);
            } catch (IllegalStateException ignored) {
            }
            mActiveRun.state = AiRunStateMachine.State.CANCELED;
            persistRun();
        }
    }

    public void interruptActiveRun() {
        stopActiveRun();
    }

    public void loginWithChatGpt() {
        notifyError(mActiveRun == null ? null : mActiveRun.id,
            "ChatGPT OAuth is not wired into the native runtime yet. Use provider API keys in configuration for this slice.");
    }

    public void loginWithDeviceCode() {
        notifyError(mActiveRun == null ? null : mActiveRun.id,
            "Device-code login is not wired into the native runtime yet. Use provider API keys in configuration for this slice.");
    }

    public void loginWithApiKey(String apiKey) {
        notifyError(mActiveRun == null ? null : mActiveRun.id,
            "API keys are saved from the provider configuration screen now.");
    }

    public void logout() {
        notifyError(mActiveRun == null ? null : mActiveRun.id,
            "Provider logout will clear saved credentials from configuration in the next slice.");
    }

    public void respondToRequest(long id, JSONObject result) {
        PendingApproval approval = mPendingApprovals.remove(id);
        if (approval == null) {
            notifyError(mActiveRun == null ? null : mActiveRun.id, "This approval request is no longer active.");
            return;
        }
        approval.answer(result != null && result.optBoolean("approved", false));
        if (mStateMachine != null && mStateMachine.getState() == AiRunStateMachine.State.WAITING_APPROVAL)
            transition(AiRunStateMachine.State.RUNNING);
    }

    @Override
    public void onDestroy() {
        stopActiveRun();
        if (mDatabase != null) mDatabase.close();
        super.onDestroy();
    }

    private void runTurn(String providerId, String baseUrl, String apiKey, String workspace, String prompt,
                         String model, @Nullable String effort, @Nullable String approvalPolicy) {
        if (mWorker != null) mWorker.interrupt();
        mWorker = new Thread(() -> executeTurn(providerId, baseUrl, apiKey, workspace,
            prompt == null ? "" : prompt, model, effort, approvalPolicy), "mobile-hermes-runtime");
        mWorker.start();
    }

    private void executeTurn(String providerId, String baseUrl, String apiKey, String workspace, String prompt,
                             String model, @Nullable String effort, @Nullable String approvalPolicy) {
        if (mActiveRun == null) return;
        emit("turn/started", new JSONObject());
        transition(AiRunStateMachine.State.RUNNING);
        emit("item/agentMessage/delta", json("text", "Thinking…"));

        JSONArray input = new JSONArray();
        input.put(json("role", "user", "content", prompt));

        try {
            if (usesChatCompletions(providerId)) {
                executeChatCompletionsTurn(providerId, baseUrl, apiKey, workspace, prompt, model, approvalPolicy);
                return;
            }
            for (int step = 0; step < MAX_MODEL_STEPS && !mStopRequested; step++) {
                JSONObject response = callResponsesApi(providerId, baseUrl, apiKey, model, effort, input);
                mPreviousResponseId = response.optString("id", mPreviousResponseId);
                JSONArray outputs = response.optJSONArray("output");
                boolean hasToolCall = false;
                JSONArray nextInput = new JSONArray();

                if (outputs != null) {
                    for (int i = 0; i < outputs.length(); i++) {
                        JSONObject item = outputs.optJSONObject(i);
                        if (item == null) continue;
                        String type = item.optString("type");
                        if ("message".equals(type)) {
                            emitMessage(item);
                        } else if (isReasoningType(type)) {
                            emitReasoningItem(item);
                        } else if ("function_call".equals(type)) {
                            hasToolCall = true;
                            JSONObject toolResult = executeToolCall(item, workspace, approvalPolicy);
                            nextInput.put(toolResult);
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
                input = nextInput;
            }

            if (!mStopRequested) failRun("The native agent reached its tool-step limit before finishing.");
        } catch (Exception e) {
            if (!mStopRequested) failRun(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private void executeChatCompletionsTurn(String providerId, String baseUrl, String apiKey, String workspace,
                                            String prompt, String model, @Nullable String approvalPolicy) throws Exception {
        if (mChatCompletionMessages == null)
            mChatCompletionMessages = new JSONArray().put(json("role", "system", "content", systemInstructions()));
        mChatCompletionMessages.put(json("role", "user", "content", prompt));

        for (int step = 0; step < MAX_MODEL_STEPS && !mStopRequested; step++) {
            JSONObject response = callChatCompletionsApi(providerId, baseUrl, apiKey, model, mChatCompletionMessages);
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
            mChatCompletionMessages.put(message);

            if (!hasToolCalls) {
                completeRun();
                return;
            }

            for (int i = 0; i < toolCalls.length(); i++) {
                JSONObject toolCall = toolCalls.optJSONObject(i);
                if (toolCall == null) continue;
                JSONObject toolResult = executeChatToolCall(toolCall, workspace, approvalPolicy);
                mChatCompletionMessages.put(toolResult);
            }
        }

        if (!mStopRequested) failRun("The chat-completions agent reached its tool-step limit before finishing.");
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
            while ((rawLine = reader.readLine()) != null && !mStopRequested) {
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
        if (!TextUtils.isEmpty(mPreviousResponseId)) body.put("previous_response_id", mPreviousResponseId);
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
        if (mActiveRun == null) return false;
        long id = mNextRequestId++;
        PendingApproval approval = new PendingApproval();
        mPendingApprovals.put(id, approval);
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
        return approval.await();
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
        if (mStateMachine == null || mActiveRun == null) return;
        try {
            mStateMachine.transition(next);
            mActiveRun.state = next;
            persistRun();
        } catch (IllegalStateException e) {
            notifyError(mActiveRun.id, e.getMessage());
        }
    }

    private void completeRun() {
        if (mActiveRun == null) return;
        try {
            mStateMachine.transition(AiRunStateMachine.State.COMPLETED);
        } catch (Exception ignored) {
        }
        mActiveRun.state = AiRunStateMachine.State.COMPLETED;
        persistRun();
    }

    private void persistRun() {
        if (mActiveRun == null) return;
        mActiveRun.updatedAt = System.currentTimeMillis();
        mDatabase.saveRun(mActiveRun);
        AiDatabase.RunRecord copy = copyRun(mActiveRun);
        mHandler.post(() -> {
            for (Listener listener : new ArrayList<>(mListeners)) listener.onRunChanged(copy);
        });
    }

    private void failRun(@Nullable String message) {
        if (mActiveRun == null) return;
        mActiveRun.lastError = message == null ? "Unknown native agent runtime failure." : message;
        try {
            if (mStateMachine != null) mStateMachine.transition(AiRunStateMachine.State.FAILED);
        } catch (Exception ignored) {
        }
        mActiveRun.state = AiRunStateMachine.State.FAILED;
        persistRun();
        notifyError(mActiveRun.id, mActiveRun.lastError);
    }

    private void emit(String method, JSONObject payload) {
        if (mActiveRun == null) return;
        mDatabase.appendEvent(mActiveRun.id, method, payload.toString());
        String runId = mActiveRun.id;
        mHandler.post(() -> {
            for (Listener listener : new ArrayList<>(mListeners)) listener.onProtocolEvent(runId, method, payload);
        });
    }

    private void notifyError(@Nullable String runId, String message) {
        mHandler.post(() -> {
            for (Listener listener : new ArrayList<>(mListeners))
                listener.onRuntimeError(runId, message == null ? "Unknown runtime error." : message);
        });
    }

    private boolean isTerminalState() {
        if (mStateMachine == null) return false;
        AiRunStateMachine.State state = mStateMachine.getState();
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

    private static final class PendingApproval {
        private final CountDownLatch latch = new CountDownLatch(1);
        private volatile boolean approved;

        boolean await() throws InterruptedException {
            latch.await(10, TimeUnit.MINUTES);
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
