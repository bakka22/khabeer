package com.termux.app;

import android.text.TextUtils;

import androidx.annotation.Nullable;

import com.termux.shared.termux.TermuxConstants;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * MCP client registry (Hermes mcp_tool.py port).
 *
 * Two transports, one protocol:
 * - Streamable HTTP: JSON-RPC over HttpURLConnection; the session is kept
 *   via the Mcp-Session-Id response header; responses arrive as plain JSON
 *   or SSE frames — both parsed.
 * - stdio: a local command spawned inside the Termux environment (the same
 *   env the terminal tool uses), speaking newline-delimited JSON-RPC over
 *   stdin/stdout. This is what makes npx/uvx/python MCP servers work
 *   on-device (Hermes runs the same servers on the desktop).
 *
 * Tools are exposed to the model under flattened `mcp__<server>__<tool>`
 * names (Hermes merges MCP tools directly into the main tool registry).
 *
 * Safety model carried over from Hermes: untrusted servers get user approval
 * per call (enforced by the service via the approval dialog); results and
 * errors are size-capped and secret-redacted before reaching the model; a
 * small circuit breaker stops hammering dead servers; name normalization
 * collisions fail closed (all colliding tools are skipped).
 */
public final class AiMcpRegistry {

    public static final String TOOL_PREFIX = "mcp__";
    public static final String PROTOCOL_VERSION = "2025-06-18";

    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int MAX_LIST_PAGES = 20;
    private static final int MAX_RESULT_CHARS = 200_000;
    private static final int MAX_DESCRIPTION_CHARS = 500;
    private static final int BREAKER_THRESHOLD = 3;
    private static final long BREAKER_COOLDOWN_MS = 60_000;
    private static final long PROBE_TTL_MS = 5 * 60_000L;

    /** A tool as exposed to the model. */
    public static final class ToolDef {
        public String serverName;    // raw DB name
        public String toolName;      // raw tool name on the server
        public String registryName;  // mcp__<sanitized server>__<sanitized tool>
        public String description = "";
        public JSONObject inputSchema = new JSONObject();
    }

    private final Object mLock = new Object();
    private final AiProviderConfig mConfig;
    private final Map<String, McpTransport> mTransports = new HashMap<>();
    private final Map<String, List<ToolDef>> mToolCache = new HashMap<>();
    private final Map<String, Long> mLastProbe = new HashMap<>();
    private final Map<String, long[]> mBreaker = new HashMap<>();

    public AiMcpRegistry(AiProviderConfig config) {
        mConfig = config;
    }

    /** One live connection to an MCP server (JSON-RPC peer). */
    private interface McpTransport {
        JSONObject request(String method, @Nullable JSONObject params, int timeoutMs) throws Exception;

        void notify(String method) throws Exception;

        void close();
    }

    // ------------------------------------------------------------------
    // Model-facing tool list
    // ------------------------------------------------------------------

    /** Tools for the model's tools array: enabled servers, sanitized flat
     * names, collision fail-closed. Uses the cached discovery state; probes
     * refresh it in the background (late binding, Hermes-style). */
    public List<ToolDef> toolsForModel(AiDatabase db) {
        List<ToolDef> out = new ArrayList<>();
        Map<String, String> owners = new HashMap<>();
        Set<String> skipped = new HashSet<>();
        for (AiDatabase.McpServerRecord server : db.getMcpServers()) {
            if (!server.enabled) continue;
            List<ToolDef> cached;
            synchronized (mLock) { cached = mToolCache.get(server.name); }
            if (cached == null) continue;
            for (ToolDef def : cached) {
                String raw = server.name + "/" + def.toolName;
                if (skipped.contains(def.registryName)) continue;
                String previous = owners.get(def.registryName);
                if (previous != null && !previous.equals(raw)) {
                    // Lossy normalization collision: refuse all offenders.
                    skipped.add(def.registryName);
                    owners.remove(def.registryName);
                    out.removeIf(t -> t.registryName.equals(def.registryName));
                    continue;
                }
                owners.put(def.registryName, raw);
                out.add(def);
            }
        }
        return out;
    }

    @Nullable
    public ToolDef findTool(String registryName) {
        synchronized (mLock) {
            for (List<ToolDef> defs : mToolCache.values()) {
                for (ToolDef def : defs) {
                    if (def.registryName.equals(registryName)) return def;
                }
            }
        }
        return null;
    }

    public static String sanitize(String raw) {
        return raw == null ? "" : raw.replaceAll("[^A-Za-z0-9_]", "_");
    }

    public void dropServer(String name) {
        McpTransport transport;
        synchronized (mLock) {
            transport = mTransports.remove(name);
            mToolCache.remove(name);
            mLastProbe.remove(name);
            mBreaker.remove(name);
        }
        if (transport != null) transport.close();
    }

    public boolean hasFreshTools(String name) {
        synchronized (mLock) {
            Long at = mLastProbe.get(name);
            return at != null && System.currentTimeMillis() - at < PROBE_TTL_MS && mToolCache.containsKey(name);
        }
    }

    // ------------------------------------------------------------------
    // Probing / discovery
    // ------------------------------------------------------------------

    /** Probe one server: fresh handshake + tools/list; persists status and
     * the tool cache to the DB. Returns an error message or null on success. */
    @Nullable
    public String probe(AiDatabase db, AiDatabase.McpServerRecord server) {
        try {
            McpTransport transport = ensureTransport(server);
            List<ToolDef> tools = listTools(transport, server.name);
            synchronized (mLock) {
                mToolCache.put(server.name, tools);
                mLastProbe.put(server.name, System.currentTimeMillis());
                mBreaker.remove(server.name);
            }
            db.setMcpServerStatus(server.name, "connected", toolsToJson(tools));
            return null;
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            dropTransport(server.name);
            db.setMcpServerStatus(server.name, "failed: " + truncate(message, 160), null);
            return message;
        }
    }

    /** Background probe of every enabled server that lacks fresh tools.
     * Never blocks the caller — tools appear in the model's array on the
     * next turn (Hermes late binding). */
    public void probeStaleAsync(AiDatabase db) {
        List<AiDatabase.McpServerRecord> pending = new ArrayList<>();
        for (AiDatabase.McpServerRecord server : db.getMcpServers()) {
            if (server.enabled && !hasFreshTools(server.name)) pending.add(server);
        }
        if (pending.isEmpty()) return;
        Thread thread = new Thread(() -> {
            for (AiDatabase.McpServerRecord server : pending) {
                AiDatabase.McpServerRecord fresh = db.getMcpServer(server.name);
                if (fresh == null || !fresh.enabled) continue;
                probe(db, fresh);
            }
        }, "mcp-discovery");
        thread.setDaemon(true);
        thread.start();
    }

    private String toolsToJson(List<ToolDef> tools) {
        JSONArray array = new JSONArray();
        for (ToolDef def : tools) {
            JSONObject row = new JSONObject();
            try {
                row.put("name", def.toolName);
                row.put("registryName", def.registryName);
                if (!TextUtils.isEmpty(def.description)) row.put("description", def.description);
                row.put("inputSchema", def.inputSchema);
            } catch (Exception ignored) {}
            array.put(row);
        }
        return array.toString();
    }

    // ------------------------------------------------------------------
    // Tool invocation
    // ------------------------------------------------------------------

    /** tools/call with a single transport-reconnect retry. Returns the
     * normalized result JSON string the model receives. Trust gating
     * (approval) is the service's job and happens before this call. */
    public String callTool(AiDatabase db, AiDatabase.McpServerRecord server,
                           String toolName, JSONObject args, int timeoutSeconds) {
        if (server == null) return toolError("Unknown MCP server for tool.");
        if (!server.enabled) return toolError("MCP server '" + server.name + "' is disabled.");
        if (breakerOpen(server.name)) {
            long remaining = (BREAKER_COOLDOWN_MS - breakerRemaining(server.name)) / 1000;
            return toolError("MCP server '" + server.name + "' is unreachable after repeated failures. "
                + "Auto-retry in ~" + Math.max(1, remaining) + "s. Do NOT retry immediately.");
        }
        try {
            String result = callToolOnce(server, toolName, args, timeoutSeconds);
            breakerSuccess(server.name);
            return result;
        } catch (McpSessionExpiredException e) {
            // Session died (HTTP 404) or the stdio process exited: rebuild
            // the transport and retry once (Hermes session-expired recovery).
            dropTransport(server.name);
            try {
                String result = callToolOnce(server, toolName, args, timeoutSeconds);
                breakerSuccess(server.name);
                return result;
            } catch (Exception e2) {
                breakerFailure(server.name);
                dropTransport(server.name);
                return toolError(redact(truncate("MCP call failed after reconnect: "
                    + (e2.getMessage() == null ? e2.toString() : e2.getMessage()), 400)));
            }
        } catch (Exception e) {
            breakerFailure(server.name);
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            return toolError(redact(truncate("MCP call failed: " + message, 400)));
        }
    }

    private String callToolOnce(AiDatabase.McpServerRecord server,
                                String toolName, JSONObject args, int timeoutSeconds) throws Exception {
        McpTransport transport = ensureTransport(server);
        JSONObject result = transport.request("tools/call", new JSONObject()
            .put("name", toolName)
            .put("arguments", args == null ? new JSONObject() : args),
            Math.max(5, timeoutSeconds) * 1000);
        return normalizeCallResult(result);
    }

    // ------------------------------------------------------------------
    // Transport lifecycle
    // ------------------------------------------------------------------

    /** Get (or create + handshake) the live transport for a server. */
    private McpTransport ensureTransport(AiDatabase.McpServerRecord server) throws Exception {
        synchronized (mLock) {
            McpTransport existing = mTransports.get(server.name);
            if (existing != null) return existing;
        }
        McpTransport created = "stdio".equals(server.transport)
            ? new StdioTransport(server)
            : new HttpTransport(server, mConfig == null ? null : mConfig.getMcpServerToken(server.name), mConfig);
        try {
            JSONObject init = created.request("initialize", new JSONObject()
                .put("protocolVersion", PROTOCOL_VERSION)
                .put("capabilities", new JSONObject())
                .put("clientInfo", new JSONObject().put("name", "termux-ai").put("version", "1.0")),
                Math.min(30, Math.max(10, server.timeoutSeconds)) * 1000);
            if (init == null) throw new IllegalStateException("Server returned no initialize result.");
            created.notify("notifications/initialized");
        } catch (Exception e) {
            created.close();
            throw e;
        }
        synchronized (mLock) {
            McpTransport existing = mTransports.putIfAbsent(server.name, created);
            if (existing != null) {
                created.close(); // another thread won the race
                return existing;
            }
            return created;
        }
    }

    private void dropTransport(String name) {
        McpTransport transport;
        synchronized (mLock) {
            transport = mTransports.remove(name);
        }
        if (transport != null) transport.close();
    }

    // ------------------------------------------------------------------
    // HTTP transport (Streamable HTTP)
    // ------------------------------------------------------------------

    private static final class HttpTransport implements McpTransport {
        private final AiDatabase.McpServerRecord mServer;
        private final AiProviderConfig mConfig;
        private String mToken;
        private String mSessionId;
        private int mNextRequestId = 1;

        HttpTransport(AiDatabase.McpServerRecord server, String token, AiProviderConfig config) {
            mServer = server;
            mToken = token;
            mConfig = config;
        }

        /** True when this server authenticates with a bearer credential. */
        private boolean hasBearer() {
            return ("header".equals(mServer.authType) || "oauth".equals(mServer.authType))
                && !TextUtils.isEmpty(mToken);
        }

        @Override
        public JSONObject request(String method, @Nullable JSONObject params, int timeoutMs) throws Exception {
            try {
                return doRequest(method, params, timeoutMs);
            } catch (McpAuthRequiredException e) {
                // One automatic refresh + retry for OAuth servers; header
                // auth tokens are static, so they surface immediately.
                if (!"oauth".equals(mServer.authType)) throw e;
                refreshAccessToken();
                return doRequest(method, params, timeoutMs);
            }
        }

        private JSONObject doRequest(String method, @Nullable JSONObject params, int timeoutMs) throws Exception {
            if (TextUtils.isEmpty(mServer.url)) throw new IllegalStateException("MCP server URL is missing.");
            int id;
            synchronized (this) { id = ++mNextRequestId; }
            HttpURLConnection connection = (HttpURLConnection) new URL(mServer.url.trim()).openConnection();
            try {
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(Math.max(5_000, timeoutMs));
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setRequestProperty("Accept", "application/json, text/event-stream");
                connection.setRequestProperty("MCP-Protocol-Version", PROTOCOL_VERSION);
                if (mSessionId != null) connection.setRequestProperty("Mcp-Session-Id", mSessionId);
                if (hasBearer()) connection.setRequestProperty("Authorization", "Bearer " + mToken);
                JSONObject body = new JSONObject()
                    .put("jsonrpc", "2.0")
                    .put("id", id)
                    .put("method", method);
                if (params != null) body.put("params", params);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }

                int code = connection.getResponseCode();
                if (code == 404 && mSessionId != null) {
                    // Streamable HTTP: 404 on a session id = session expired.
                    throw new McpSessionExpiredException();
                }
                if (code == 401 || code == 403) {
                    throw new McpAuthRequiredException("HTTP " + code + ": authentication required or rejected."
                        + ("oauth".equals(mServer.authType)
                            ? " OAuth refresh will be attempted once; if it fails, sign in again."
                            : " Check the server's auth token. Do NOT retry until fixed."));
                }
                InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
                String text = readFully(stream);
                if (code < 200 || code >= 300) {
                    throw new IllegalStateException("HTTP " + code + ": " + AiMcpRegistry.redactStatic(truncateStatic(text, 200)));
                }
                String responseSession = connection.getHeaderField("Mcp-Session-Id");
                if (responseSession != null && !responseSession.trim().isEmpty()) mSessionId = responseSession.trim();
                if (TextUtils.isEmpty(text.trim())) return null;
                String contentType = connection.getContentType();
                if (contentType != null && contentType.toLowerCase().contains("text/event-stream")) {
                    return AiMcpRegistry.parseSseResponse(text, id);
                }
                return AiMcpRegistry.parseJsonRpcResponse(text, id);
            } finally {
                connection.disconnect();
            }
        }

        /** Use the stored refresh token to mint a fresh access token. */
        private void refreshAccessToken() throws Exception {
            JSONObject oauth = TextUtils.isEmpty(mServer.oauthJson) ? null : new JSONObject(mServer.oauthJson);
            String refreshToken = mConfig == null ? null : mConfig.getMcpServerRefresh(mServer.name);
            if (oauth == null || TextUtils.isEmpty(oauth.optString("token_endpoint")) || TextUtils.isEmpty(refreshToken))
                throw new McpAuthRequiredException("OAuth session expired and no refresh token is stored — sign in again.");
            Map<String, String> form = new java.util.LinkedHashMap<>();
            form.put("grant_type", "refresh_token");
            form.put("refresh_token", refreshToken);
            form.put("client_id", oauth.optString("client_id", ""));
            JSONObject tokens = httpPostForm(oauth.optString("token_endpoint"), form);
            String access = tokens.optString("access_token", "");
            if (TextUtils.isEmpty(access))
                throw new McpAuthRequiredException("OAuth refresh rejected: " + AiMcpRegistry.redactStatic(truncateStatic(tokens.toString(), 160)) + " — sign in again.");
            mToken = access;
            if (mConfig != null) {
                mConfig.setMcpServerToken(mServer.name, access);
                String rotated = tokens.optString("refresh_token", "");
                if (!TextUtils.isEmpty(rotated)) mConfig.setMcpServerRefresh(mServer.name, rotated);
            }
        }

        @Override
        public void notify(String method) throws Exception {
            if (TextUtils.isEmpty(mServer.url)) throw new IllegalStateException("MCP server URL is missing.");
            JSONObject body = new JSONObject().put("jsonrpc", "2.0").put("method", method);
            HttpURLConnection connection = (HttpURLConnection) new URL(mServer.url.trim()).openConnection();
            try {
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(10_000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setRequestProperty("Accept", "application/json, text/event-stream");
                connection.setRequestProperty("MCP-Protocol-Version", PROTOCOL_VERSION);
                if (mSessionId != null) connection.setRequestProperty("Mcp-Session-Id", mSessionId);
                if (hasBearer()) connection.setRequestProperty("Authorization", "Bearer " + mToken);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
                int code = connection.getResponseCode();
                if (code < 200 || code >= 300) throw new IllegalStateException("Notification rejected with HTTP " + code);
            } finally {
                connection.disconnect();
            }
        }

        @Override
        public void close() {
            // Stateless per request — nothing to tear down.
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

        private String truncate(String text, int max) {
            if (text == null) return "";
            return text.length() <= max ? text : text.substring(0, max - 1) + "…";
        }
    }

    // ------------------------------------------------------------------
    // stdio transport: local command inside the Termux environment
    // ------------------------------------------------------------------

    /**
     * Spawns the server command with the same environment the terminal tool
     * uses ($PREFIX/bin on PATH, Termux HOME) and speaks newline-delimited
     * JSON-RPC over its stdin/stdout (the MCP stdio framing). stderr is
     * drained to a bounded tail buffer surfaced in error messages. A dead
     * process surfaces as McpSessionExpiredException so the caller rebuilds
     * the transport and respawns the server once.
     */
    private final class StdioTransport implements McpTransport {
        private final Process mProcess;
        private final BufferedWriter mWriter;
        private final Object mWriteLock = new Object();
        private final Map<Integer, PendingCall> mPending = new ConcurrentHashMap<>();
        private final StringBuilder mStderrTail = new StringBuilder();
        private volatile boolean mClosed;
        private int mNextRequestId = 1;

        StdioTransport(AiDatabase.McpServerRecord server) throws Exception {
            List<String> command = buildCommand(server);
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(new File(TermuxConstants.TERMUX_HOME_DIR_PATH));
            Map<String, String> env = builder.environment();
            env.put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
            env.put("PREFIX", TermuxConstants.TERMUX_PREFIX_DIR_PATH);
            env.put("TMPDIR", TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/tmp");
            env.put("TERM", "xterm-256color");
            env.put("PATH", TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + ":"
                + TermuxConstants.TERMUX_HOME_DIR_PATH + "/.local/bin:"
                + env.getOrDefault("PATH", ""));
            mProcess = builder.start();
            mWriter = new BufferedWriter(new OutputStreamWriter(mProcess.getOutputStream(), StandardCharsets.UTF_8));

            Thread reader = new Thread(this::readLoop, "mcp-stdio-reader-" + sanitize(server.name));
            reader.setDaemon(true);
            reader.start();
            Thread stderr = new Thread(() -> drainStderr(mProcess), "mcp-stdio-stderr-" + sanitize(server.name));
            stderr.setDaemon(true);
            stderr.start();
        }

        private List<String> buildCommand(AiDatabase.McpServerRecord server) throws Exception {
            String command = server.command == null ? "" : server.command.trim();
            if (TextUtils.isEmpty(command)) throw new IllegalStateException("stdio server has no command.");
            List<String> out = new ArrayList<>();
            out.add(resolveCommand(command));
            if (!TextUtils.isEmpty(server.argsJson)) {
                try {
                    JSONArray args = new JSONArray(server.argsJson);
                    for (int i = 0; i < args.length(); i++) {
                        String arg = args.optString(i, "");
                        if (!arg.isEmpty()) out.add(arg);
                    }
                } catch (Exception ignored) {}
            }
            return out;
        }

        /** Hermes _resolve_stdio_command, trimmed: absolute paths pass
         * through; bare names resolve against $PREFIX/bin and ~/.local/bin. */
        private String resolveCommand(String command) {
            if (command.contains("/")) return command;
            String[] candidates = {
                TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/" + command,
                TermuxConstants.TERMUX_HOME_DIR_PATH + "/.local/bin/" + command
            };
            for (String candidate : candidates) {
                File file = new File(candidate);
                if (file.isFile() && file.canExecute()) return candidate;
            }
            return command; // let ProcessBuilder produce a clear failure
        }

        private void readLoop() {
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(mProcess.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while (!mClosed && (line = reader.readLine()) != null) {
                    dispatch(line);
                }
            } catch (Exception ignored) {
            } finally {
                failAllPending("MCP server process exited."
                    + (mStderrTail.length() > 0 ? " stderr: " + truncate(mStderrTail.toString(), 200) : ""));
            }
        }

        private void dispatch(String line) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || mClosed) return;
            try {
                JSONObject frame = new JSONObject(trimmed);
                if (!frame.has("id")) return; // server-initiated notification: ignored in phase 3
                PendingCall call = mPending.remove(frame.optInt("id", -1));
                if (call == null) return;
                if (frame.has("error")) {
                    JSONObject error = frame.optJSONObject("error");
                    call.error = "JSON-RPC " + error.optInt("code", 0) + ": "
                        + redact(truncate(error.optString("message", "unknown error"), 200));
                } else {
                    call.result = frame.optJSONObject("result");
                }
                call.latch.countDown();
            } catch (Exception ignored) {
                // Not JSON — tolerate noise on stdout.
            }
        }

        private void drainStderr(Process process) {
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (mStderrTail) {
                        if (mStderrTail.length() > 4096) mStderrTail.delete(0, 2048);
                        mStderrTail.append(line).append('\n');
                    }
                }
            } catch (Exception ignored) {}
        }

        private void failAllPending(String message) {
            for (PendingCall call : mPending.values()) {
                call.error = message;
                call.latch.countDown();
            }
            mPending.clear();
        }

        @Override
        public JSONObject request(String method, @Nullable JSONObject params, int timeoutMs) throws Exception {
            if (mClosed || !mProcess.isAlive()) throw new McpSessionExpiredException();
            int id;
            synchronized (this) { id = ++mNextRequestId; }
            JSONObject body = new JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put("method", method);
            if (params != null) body.put("params", params);

            PendingCall call = new PendingCall();
            mPending.put(id, call);
            try {
                try {
                    synchronized (mWriteLock) {
                        if (mClosed || !mProcess.isAlive()) throw new McpSessionExpiredException();
                        mWriter.write(body.toString());
                        mWriter.write("\n");
                        mWriter.flush();
                    }
                } catch (Exception e) {
                    throw new McpSessionExpiredException();
                }
                if (!call.latch.await(Math.max(5_000, timeoutMs), TimeUnit.MILLISECONDS)) {
                    mPending.remove(id);
                    throw new IllegalStateException("MCP stdio call timed out after " + (timeoutMs / 1000) + "s"
                        + (mProcess.isAlive() ? "" : " (process exited)"));
                }
                if (call.error != null) throw new IllegalStateException(call.error);
                return call.result;
            } finally {
                mPending.remove(id);
            }
        }

        @Override
        public void notify(String method) throws Exception {
            if (mClosed || !mProcess.isAlive()) return;
            JSONObject body = new JSONObject().put("jsonrpc", "2.0").put("method", method);
            synchronized (mWriteLock) {
                mWriter.write(body.toString());
                mWriter.write("\n");
                mWriter.flush();
            }
        }

        @Override
        public void close() {
            if (mClosed) return;
            mClosed = true;
            failAllPending("MCP server transport closed.");
            mProcess.destroy();
            try {
                if (!mProcess.waitFor(2, TimeUnit.SECONDS)) mProcess.destroyForcibly();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                mProcess.destroyForcibly();
            }
        }
    }

    private static final class PendingCall {
        final CountDownLatch latch = new CountDownLatch(1);
        volatile JSONObject result;
        volatile String error;
    }

    // ------------------------------------------------------------------
    // Tool discovery over a transport
    // ------------------------------------------------------------------

    private List<ToolDef> listTools(McpTransport transport, String serverName) throws Exception {
        List<ToolDef> out = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < MAX_LIST_PAGES; page++) {
            JSONObject params = new JSONObject();
            if (cursor != null) params.put("cursor", cursor);
            JSONObject result = transport.request("tools/list", params, 30_000);
            if (result == null) break;
            JSONArray tools = result.optJSONArray("tools");
            if (tools != null) {
                for (int i = 0; i < tools.length(); i++) {
                    JSONObject tool = tools.optJSONObject(i);
                    if (tool == null) continue;
                    String name = tool.optString("name", "");
                    if (TextUtils.isEmpty(name)) continue;
                    ToolDef def = new ToolDef();
                    def.serverName = serverName;
                    def.toolName = name;
                    def.registryName = TOOL_PREFIX + sanitize(serverName) + "__" + sanitize(name);
                    def.description = truncate(tool.optString("description", "MCP tool."), MAX_DESCRIPTION_CHARS);
                    def.inputSchema = normalizeSchema(tool.optJSONObject("inputSchema"));
                    out.add(def);
                }
            }
            cursor = result.optString("nextCursor", "");
            if (TextUtils.isEmpty(cursor) || "null".equals(cursor)) break;
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Result normalization (Hermes block handling, trimmed)
    // ------------------------------------------------------------------

    private String normalizeCallResult(@Nullable JSONObject result) throws Exception {
        if (result == null) return toolError("MCP server returned an empty result.");
        StringBuilder text = new StringBuilder();
        JSONArray content = result.optJSONArray("content");
        if (content != null) {
            for (int i = 0; i < content.length(); i++) {
                JSONObject block = content.optJSONObject(i);
                if (block == null) continue;
                String type = block.optString("type", "");
                if ("text".equals(type)) {
                    if (text.length() > 0) text.append('\n');
                    text.append(block.optString("text", ""));
                } else if ("resource_link".equals(type)) {
                    if (text.length() > 0) text.append('\n');
                    text.append("[resource] ").append(block.optString("uri", "")).append(' ')
                        .append(block.optString("name", ""));
                } else {
                    if (text.length() > 0) text.append('\n');
                    text.append("[").append(type.isEmpty() ? "unknown" : type).append(" content block] ");
                }
            }
        }
        JSONObject structured = result.optJSONObject("structuredContent");
        String body;
        if (text.length() > 0) {
            body = text.toString();
            if (structured != null) body += "\n[structuredContent] " + structured.toString();
        } else if (structured != null) {
            body = structured.toString();
        } else {
            body = result.optBoolean("isError") ? "" : "(empty result)";
        }
        body = redact(truncate(body, MAX_RESULT_CHARS));
        if (result.optBoolean("isError") && !body.isEmpty()) {
            return toolError(body);
        }
        return new JSONObject().put("result", body).toString();
    }

    private String toolError(String message) {
        try {
            return new JSONObject().put("error", message).toString();
        } catch (Exception e) {
            return "{\"error\":\"MCP tool error\"}";
        }
    }

    // ------------------------------------------------------------------
    // Circuit breaker (per server, Hermes-shaped: 3 fails → 60s open)
    // ------------------------------------------------------------------

    private long[] breaker(String name) {
        return mBreaker.computeIfAbsent(name, k -> new long[2]);
    }

    private boolean breakerOpen(String name) {
        synchronized (mLock) {
            long[] state = breaker(name);
            return state[0] >= BREAKER_THRESHOLD
                && System.currentTimeMillis() - state[1] < BREAKER_COOLDOWN_MS;
        }
    }

    private long breakerRemaining(String name) {
        synchronized (mLock) {
            long[] state = breaker(name);
            long elapsed = System.currentTimeMillis() - state[1];
            return Math.max(0, BREAKER_COOLDOWN_MS - elapsed);
        }
    }

    private void breakerFailure(String name) {
        synchronized (mLock) {
            long[] state = breaker(name);
            if (state[0] >= BREAKER_THRESHOLD
                && System.currentTimeMillis() - state[1] >= BREAKER_COOLDOWN_MS) {
                state[0] = 0; // cooldown elapsed: fresh count
            }
            state[0]++;
            if (state[0] >= BREAKER_THRESHOLD) state[1] = System.currentTimeMillis();
        }
    }

    private void breakerSuccess(String name) {
        synchronized (mLock) { mBreaker.remove(name); }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Schema normalization for provider compatibility (Hermes
     * _normalize_mcp_input_schema, trimmed): force object type, ensure
     * properties exists, prune required to existing properties. */
    private JSONObject normalizeSchema(@Nullable JSONObject raw) {
        JSONObject out = new JSONObject();
        if (raw == null) {
            try { out.put("type", "object").put("properties", new JSONObject()); } catch (Exception ignored) {}
            return out;
        }
        try {
            for (Iterator<String> keys = raw.keys(); keys.hasNext(); ) {
                String key = keys.next();
                out.put(key, raw.opt(key));
            }
            String type = out.optString("type", "object");
            if ("null".equals(type) || TextUtils.isEmpty(type)) type = "object";
            out.put("type", type);
            if (!out.has("properties") || !(out.opt("properties") instanceof JSONObject)) {
                out.put("properties", new JSONObject());
            }
            JSONArray required = out.optJSONArray("required");
            if (required != null) {
                JSONObject properties = out.optJSONObject("properties");
                JSONArray pruned = new JSONArray();
                for (int i = 0; i < required.length(); i++) {
                    String name = required.optString(i, "");
                    if (!TextUtils.isEmpty(name) && properties != null && properties.has(name)) pruned.put(name);
                }
                out.put("required", pruned);
            }
        } catch (Exception ignored) {}
        return out;
    }

    private String redact(String text) {
        return redactStatic(text);
    }

    static String redactStatic(String text) {
        if (text == null) return "";
        return text
            .replaceAll("(?i)(bearer\\s+)[A-Za-z0-9._\\-]+", "$1[REDACTED]")
            .replaceAll("\\b(sk-[A-Za-z0-9_]{8,}|ghp_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})", "[REDACTED]")
            .replaceAll("(?i)(\"?(?:token|api_?key|secret|password)\"?(?:\\s*[:=]\\s*)\"?)([^\"\\s,}&]{4,})", "$1[REDACTED]");
    }

    private String truncate(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    /** JSON or SSE data block → JSON-RPC response object (matched by id). */
    @Nullable
    static JSONObject parseJsonRpcResponse(String text, int expectedId) throws Exception {
        String trimmed = text.trim();
        JSONObject object;
        try {
            object = new JSONObject(trimmed);
        } catch (Exception e) {
            throw new IllegalStateException("Non-JSON MCP response: " + truncateStatic(redactStatic(trimmed), 200));
        }
        if (object.has("error")) {
            JSONObject error = object.optJSONObject("error");
            throw new IllegalStateException("JSON-RPC " + error.optInt("code", 0) + ": "
                + redactStatic(truncateStatic(error.optString("message", "unknown error"), 200)));
        }
        if (object.has("result")) return object.optJSONObject("result");
        return null;
    }

    @Nullable
    static JSONObject parseSseResponse(String text, int expectedId) {
        for (String line : text.split("\\r?\\n")) {
            line = line.trim();
            if (!line.startsWith("data:")) continue;
            String data = line.substring(5).trim();
            if (data.isEmpty() || "[DONE]".equals(data)) continue;
            try {
                JSONObject frame = new JSONObject(data);
                if (frame.has("error")) {
                    JSONObject error = frame.optJSONObject("error");
                    throw new IllegalStateException("JSON-RPC " + error.optInt("code", 0) + ": "
                        + redactStatic(truncateStatic(error.optString("message", "unknown error"), 200)));
                }
                if (!frame.has("result")) continue;
                if (frame.has("id") && frame.optInt("id", -1) != expectedId) continue;
                return frame.optJSONObject("result");
            } catch (IllegalStateException e) {
                throw e;
            } catch (Exception ignored) {
                // not a JSON-RPC frame; keep scanning
            }
        }
        return null;
    }

    private static String truncateStatic(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    private static final class McpSessionExpiredException extends Exception {
        McpSessionExpiredException() { super("MCP transport expired"); }
    }

    /** 401/403 from a server — OAuth callers may retry once after a refresh. */
    private static final class McpAuthRequiredException extends Exception {
        McpAuthRequiredException(String message) { super(message); }
    }

    // ------------------------------------------------------------------
    // OAuth 2.0 Authorization Code + PKCE (Hermes mcp_oauth port)
    // Static helpers used by the UI sign-in flow; the transport only
    // consumes the stored tokens.
    // ------------------------------------------------------------------

    /**
     * Discover OAuth metadata for an MCP server URL: protected-resource
     * metadata first (to find the authorization server), then authorization-
     * server metadata. Falls back to treating the server's origin as the AS.
     * Returns {authorization_endpoint, token_endpoint, registration_endpoint?,
     * scopes_supported?, resource?}.
     */
    public static JSONObject discoverOAuth(String serverUrl) throws Exception {
        java.net.URI uri = new java.net.URI(serverUrl.trim());
        String origin = uri.getScheme() + "://" + uri.getRawAuthority();
        String asBase = origin;
        try {
            JSONObject prm = httpGetJson(origin + "/.well-known/oauth-protected-resource");
            JSONArray servers = prm.optJSONArray("authorization_servers");
            if (servers != null && servers.length() > 0) {
                String candidate = servers.optString(0, "");
                if (!TextUtils.isEmpty(candidate)) asBase = candidate.endsWith("/") ? candidate.substring(0, candidate.length() - 1) : candidate;
            }
        } catch (Exception ignored) {
            // No protected-resource metadata — origin is the AS (common).
        }
        JSONObject meta;
        try {
            meta = httpGetJson(asBase + "/.well-known/oauth-authorization-server");
        } catch (Exception ignored) {
            meta = httpGetJson(asBase + "/.well-known/openid-configuration");
        }
        if (TextUtils.isEmpty(meta.optString("authorization_endpoint")) || TextUtils.isEmpty(meta.optString("token_endpoint")))
            throw new IllegalStateException("Authorization server metadata is missing authorization/token endpoints.");
        meta.put("resource", serverUrl.trim());
        return meta;
    }

    /** Dynamic client registration; returns the issued client_id. */
    public static String registerClient(JSONObject meta, String redirectUri, String serverName) throws Exception {
        String endpoint = meta.optString("registration_endpoint", "");
        if (TextUtils.isEmpty(endpoint))
            throw new IllegalStateException("Server offers no dynamic client registration. Configure a static client_id instead.");
        JSONObject body = new JSONObject()
            .put("client_name", "Termux AI (" + serverName + ")")
            .put("redirect_uris", new JSONArray().put(redirectUri))
            .put("grant_types", new JSONArray().put("authorization_code").put("refresh_token"))
            .put("response_types", new JSONArray().put("code"))
            .put("token_endpoint_auth_method", "none");
        JSONObject response = httpPostJson(endpoint, body.toString());
        String clientId = response.optString("client_id", "");
        if (TextUtils.isEmpty(clientId)) throw new IllegalStateException("Registration response had no client_id.");
        return clientId;
    }

    /** Authorization-code exchange; returns the token response JSON. */
    public static JSONObject exchangeAuthorizationCode(JSONObject meta, String clientId, String code,
                                                       String redirectUri, String codeVerifier) throws Exception {
        Map<String, String> form = new java.util.LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", redirectUri);
        form.put("client_id", clientId);
        form.put("code_verifier", codeVerifier);
        return httpPostForm(meta.optString("token_endpoint"), form);
    }

    /** Refresh-token grant; returns the token response JSON. */
    public static JSONObject refreshAccessTokenStatic(JSONObject meta, String clientId, String refreshToken) throws Exception {
        Map<String, String> form = new java.util.LinkedHashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", refreshToken);
        form.put("client_id", clientId);
        return httpPostForm(meta.optString("token_endpoint"), form);
    }

    /** Build the browser authorization URL (PKCE S256). */
    public static String buildAuthorizationUrl(JSONObject meta, String clientId, String redirectUri,
                                               String state, String codeChallenge, String resource) throws Exception {
        StringBuilder url = new StringBuilder(meta.optString("authorization_endpoint"))
            .append("?response_type=code")
            .append("&client_id=").append(java.net.URLEncoder.encode(clientId, "UTF-8"))
            .append("&redirect_uri=").append(java.net.URLEncoder.encode(redirectUri, "UTF-8"))
            .append("&state=").append(java.net.URLEncoder.encode(state, "UTF-8"))
            .append("&code_challenge=").append(java.net.URLEncoder.encode(codeChallenge, "UTF-8"))
            .append("&code_challenge_method=S256");
        JSONArray scopes = meta.optJSONArray("scopes_supported");
        if (scopes != null && scopes.length() > 0) {
            StringBuilder scope = new StringBuilder();
            for (int i = 0; i < scopes.length(); i++) {
                if (i > 0) scope.append(' ');
                scope.append(scopes.optString(i));
            }
            url.append("&scope=").append(java.net.URLEncoder.encode(scope.toString(), "UTF-8"));
        }
        if (!TextUtils.isEmpty(resource)) url.append("&resource=").append(java.net.URLEncoder.encode(resource, "UTF-8"));
        return url.toString();
    }

    private static JSONObject httpGetJson(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(15_000);
            connection.setRequestProperty("Accept", "application/json");
            int code = connection.getResponseCode();
            String text = readStreamStatic(code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream());
            if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code + " from " + url);
            return new JSONObject(text);
        } finally {
            connection.disconnect();
        }
    }

    private static JSONObject httpPostJson(String url, String json) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(20_000);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            try (OutputStream output = connection.getOutputStream()) {
                output.write(json.getBytes(StandardCharsets.UTF_8));
            }
            int code = connection.getResponseCode();
            String text = readStreamStatic(code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream());
            if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code + ": " + redactStatic(truncateStatic(text, 200)));
            return new JSONObject(text);
        } finally {
            connection.disconnect();
        }
    }

    private static JSONObject httpPostForm(String url, Map<String, String> form) throws Exception {
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> entry : form.entrySet()) {
            if (body.length() > 0) body.append('&');
            body.append(java.net.URLEncoder.encode(entry.getKey(), "UTF-8"))
                .append('=')
                .append(java.net.URLEncoder.encode(entry.getValue(), "UTF-8"));
        }
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(20_000);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            connection.setRequestProperty("Accept", "application/json");
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = connection.getResponseCode();
            String text = readStreamStatic(code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream());
            if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code + ": " + redactStatic(truncateStatic(text, 200)));
            try {
                return new JSONObject(text);
            } catch (Exception e) {
                return formToJson(text);
            }
        } finally {
            connection.disconnect();
        }
    }

    /** Some token endpoints answer form-urlencoded despite Accept: json. */
    private static JSONObject formToJson(String text) throws Exception {
        JSONObject out = new JSONObject();
        for (String pair : text.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            out.put(java.net.URLDecoder.decode(pair.substring(0, eq), "UTF-8"),
                java.net.URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
        }
        return out;
    }

    private static String readStreamStatic(InputStream stream) throws Exception {
        if (stream == null) return "";
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) builder.append(line).append('\n');
        }
        return builder.toString();
    }
}
