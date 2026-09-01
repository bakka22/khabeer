package com.termux.app;

import android.text.TextUtils;

import androidx.annotation.Nullable;

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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MCP client registry (Hermes mcp_tool.py port, Streamable HTTP slice).
 *
 * JSON-RPC 2.0 over HttpURLConnection: initialize handshake (session kept via
 * the Mcp-Session-Id header), notifications/initialized, paginated tools/list,
 * tools/call. Responses arrive either as plain JSON or as an SSE stream —
 * both are handled. Tools are exposed to the model under flattened
 * `mcp__<server>__<tool>` names (Hermes merges MCP tools directly into the
 * main tool registry; there is no wrapper tool).
 *
 * Safety model carried over from Hermes: untrusted servers get user approval
 * per write-capable call (enforced by the service via the approval dialog);
 * results and errors are size-capped and secret-redacted before reaching the
 * model; a small circuit breaker stops hammering dead servers; name
 * normalization collisions fail closed (all colliding tools are skipped).
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

    private static final class ServerSession {
        String sessionId;
        int nextRequestId = 1;
        long connectedAt;
    }

    private final Object mLock = new Object();
    private final AiProviderConfig mConfig;
    private final Map<String, ServerSession> mSessions = new HashMap<>();
    private final Map<String, List<ToolDef>> mToolCache = new HashMap<>();
    private final Map<String, Long> mLastProbe = new HashMap<>();
    private final Map<String, long[]> mBreaker = new HashMap<>();

    public AiMcpRegistry(AiProviderConfig config) {
        mConfig = config;
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
        synchronized (mLock) {
            mSessions.remove(name);
            mToolCache.remove(name);
            mLastProbe.remove(name);
            mBreaker.remove(name);
        }
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

    /** Probe one server: fresh initialize + tools/list; persists status and
     * the tool cache to the DB. Returns an error message or null on success. */
    @Nullable
    public String probe(AiDatabase db, AiDatabase.McpServerRecord server) {
        try {
            ServerSession session = new ServerSession();
            JSONObject initResult = rpc(server, session, null, "initialize", new JSONObject()
                .put("protocolVersion", PROTOCOL_VERSION)
                .put("capabilities", new JSONObject())
                .put("clientInfo", new JSONObject().put("name", "termux-ai").put("version", "1.0")),
                Math.min(30, Math.max(10, server.timeoutSeconds)) * 1000);
            if (initResult == null) return "Server returned no initialize result.";
            session.sessionId = session.sessionId; // header captured inside rpc()
            notifyInitialized(server, session);
            List<ToolDef> tools = listTools(server, session);
            synchronized (mLock) {
                mSessions.put(server.name, session);
                mToolCache.put(server.name, tools);
                mLastProbe.put(server.name, System.currentTimeMillis());
                mBreaker.remove(server.name);
            }
            db.setMcpServerStatus(server.name, "connected", toolsToJson(tools));
            return null;
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            synchronized (mLock) {
                mSessions.remove(server.name);
                mToolCache.remove(server.name);
                mLastProbe.remove(server.name);
            }
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

    /** tools/call with a single session-expiry retry. Returns the normalized
     * result JSON string the model receives. Trust gating (approval) is the
     * service's job and happens before this call. */
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
            String result = callToolOnce(db, server, toolName, args, timeoutSeconds);
            breakerSuccess(server.name);
            return result;
        } catch (McpSessionExpiredException e) {
            // Re-initialize once and retry (Hermes session-expired recovery).
            synchronized (mLock) { mSessions.remove(server.name); }
            try {
                String result = callToolOnce(db, server, toolName, args, timeoutSeconds);
                breakerSuccess(server.name);
                return result;
            } catch (Exception e2) {
                breakerFailure(server.name);
                return toolError(redact(truncate("MCP call failed after session reconnect: "
                    + (e2.getMessage() == null ? e2.toString() : e2.getMessage()), 400)));
            }
        } catch (Exception e) {
            breakerFailure(server.name);
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            return toolError(redact(truncate("MCP call failed: " + message, 400)));
        }
    }

    private String callToolOnce(AiDatabase db, AiDatabase.McpServerRecord server,
                                String toolName, JSONObject args, int timeoutSeconds) throws Exception {
        ServerSession session = ensureSession(server);
        JSONObject result = rpc(server, session, null, "tools/call", new JSONObject()
            .put("name", toolName)
            .put("arguments", args == null ? new JSONObject() : args),
            Math.max(5, timeoutSeconds) * 1000);
        return normalizeCallResult(result);
    }

    private ServerSession ensureSession(AiDatabase.McpServerRecord server) throws Exception {
        synchronized (mLock) {
            ServerSession existing = mSessions.get(server.name);
            if (existing != null) return existing;
        }
        ServerSession session = new ServerSession();
        JSONObject result = rpc(server, session, null, "initialize", new JSONObject()
            .put("protocolVersion", PROTOCOL_VERSION)
            .put("capabilities", new JSONObject())
            .put("clientInfo", new JSONObject().put("name", "termux-ai").put("version", "1.0")),
            Math.min(30, Math.max(10, server.timeoutSeconds)) * 1000);
        if (result == null) throw new IllegalStateException("Server returned no initialize result.");
        notifyInitialized(server, session);
        synchronized (mLock) { mSessions.put(server.name, session); }
        return session;
    }

    private void notifyInitialized(AiDatabase.McpServerRecord server, ServerSession session) {
        try {
            rpc(server, session, "notifications/initialized", null, null, 10_000);
        } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------------
    // JSON-RPC over Streamable HTTP
    // ------------------------------------------------------------------

    @Nullable
    private JSONObject rpc(AiDatabase.McpServerRecord server, ServerSession session,
                           @Nullable String notification, @Nullable String method, @Nullable JSONObject params,
                           int timeoutMs) throws Exception {
        if (TextUtils.isEmpty(server.url)) throw new IllegalStateException("MCP server URL is missing.");
        JSONObject body = new JSONObject().put("jsonrpc", "2.0");
        boolean isNotification = notification != null;
        int id = 0;
        if (isNotification) {
            body.put("method", notification);
        } else {
            synchronized (mLock) { id = session.nextRequestId++; }
            body.put("id", id);
            body.put("method", method);
            if (params != null) body.put("params", params);
        }

        HttpURLConnection connection = (HttpURLConnection) new URL(server.url.trim()).openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(Math.max(5_000, timeoutMs));
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json, text/event-stream");
            connection.setRequestProperty("MCP-Protocol-Version", PROTOCOL_VERSION);
            if (session.sessionId != null) connection.setRequestProperty("Mcp-Session-Id", session.sessionId);
            if ("header".equals(server.authType)) {
                String token = mConfig == null ? null : mConfig.getMcpServerToken(server.name);
                if (!TextUtils.isEmpty(token)) connection.setRequestProperty("Authorization", "Bearer " + token);
            }
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }

            int code = connection.getResponseCode();
            if (isNotification) {
                if (code >= 200 && code < 300) return null;
                throw new IllegalStateException("Notification rejected with HTTP " + code);
            }
            if (code == 404 && session.sessionId != null) {
                // Streamable HTTP: 404 on a session id = session expired.
                throw new McpSessionExpiredException();
            }
            if (code == 401 || code == 403) {
                throw new IllegalStateException("HTTP " + code + ": authentication required or rejected. "
                    + "Check the server's auth token. Do NOT retry until fixed.");
            }
            InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
            String text = readFully(stream);
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("HTTP " + code + ": " + redact(truncate(text, 200)));
            }
            String responseSession = connection.getHeaderField("Mcp-Session-Id");
            if (responseSession != null && !responseSession.trim().isEmpty()) session.sessionId = responseSession.trim();
            if (TextUtils.isEmpty(text.trim())) return null;
            String contentType = connection.getContentType();
            if (contentType != null && contentType.toLowerCase().contains("text/event-stream")) {
                return parseSseResponse(text, id);
            }
            return parseJsonRpcResponse(text, id);
        } finally {
            connection.disconnect();
        }
    }

    private List<ToolDef> listTools(AiDatabase.McpServerRecord server, ServerSession session) throws Exception {
        List<ToolDef> out = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < MAX_LIST_PAGES; page++) {
            JSONObject params = new JSONObject();
            if (cursor != null) params.put("cursor", cursor);
            JSONObject result = rpc(server, session, null, "tools/list", params, 30_000);
            if (result == null) break;
            JSONArray tools = result.optJSONArray("tools");
            if (tools != null) {
                for (int i = 0; i < tools.length(); i++) {
                    JSONObject tool = tools.optJSONObject(i);
                    if (tool == null) continue;
                    String name = tool.optString("name", "");
                    if (TextUtils.isEmpty(name)) continue;
                    ToolDef def = new ToolDef();
                    def.serverName = server.name;
                    def.toolName = name;
                    def.registryName = TOOL_PREFIX + sanitize(server.name) + "__" + sanitize(name);
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

    /** JSON or SSE data block → JSON-RPC response object (matched by id). */
    private JSONObject parseJsonRpcResponse(String text, int expectedId) throws Exception {
        String trimmed = text.trim();
        JSONObject object;
        try {
            object = new JSONObject(trimmed);
        } catch (Exception e) {
            throw new IllegalStateException("Non-JSON MCP response: " + truncate(redact(trimmed), 200));
        }
        if (object.has("error")) {
            JSONObject error = object.optJSONObject("error");
            throw new IllegalStateException("JSON-RPC " + error.optInt("code", 0) + ": "
                + redact(truncate(error.optString("message", "unknown error"), 200)));
        }
        if (object.has("result")) return object.optJSONObject("result");
        // A notification or unrelated frame — nothing usable.
        return null;
    }

    @Nullable
    private JSONObject parseSseResponse(String text, int expectedId) throws Exception {
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
                        + redact(truncate(error.optString("message", "unknown error"), 200)));
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

    // ------------------------------------------------------------------
    // Result normalization (Hermes block handling, trimmed to phase 2)
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
            for (java.util.Iterator<String> keys = raw.keys(); keys.hasNext(); ) {
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

    private String readFully(InputStream stream) throws Exception {
        if (stream == null) return "";
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) builder.append(line).append('\n');
        }
        return builder.toString();
    }

    private static final class McpSessionExpiredException extends Exception {
        McpSessionExpiredException() { super("MCP session expired"); }
    }
}
