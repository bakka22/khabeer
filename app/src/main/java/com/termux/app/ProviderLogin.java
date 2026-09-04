package com.termux.app;

import android.text.TextUtils;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Wire-level login flows for providers that don't take a plain API key
 * (port of the khabeer reference provider logins). HTTP-only, no UI —
 * the activity drives the steps and stores the results through
 * AiProviderConfig's Keystore-backed secrets.
 */
public final class ProviderLogin {

    private ProviderLogin() {}

    // ------------------------------------------------------------------
    // OpenAI Codex (ChatGPT subscription login) — device-code variant
    // where the authorization step returns the PKCE verifier.
    // ------------------------------------------------------------------

    public static final String CODEX_CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann";
    public static final String CODEX_ISSUER = "https://auth.openai.com";
    public static final String CODEX_DEVICE_URL = CODEX_ISSUER + "/codex/device";
    public static final String CODEX_TOKEN_URL = CODEX_ISSUER + "/oauth/token";
    public static final String CODEX_BASE_URL = "https://chatgpt.com/backend-api/codex";

    /** Step 1: request a device user code. */
    public static JSONObject codexRequestDeviceCode() throws Exception {
        JSONObject body = new JSONObject().put("client_id", CODEX_CLIENT_ID);
        JSONObject response = null;
        Exception last = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            try {
                response = httpPostJson(CODEX_ISSUER + "/api/accounts/deviceauth/usercode", body.toString(),
                    new String[]{"Content-Type", "application/json", "Accept", "application/json"});
                last = null;
                break;
            } catch (HttpError e) {
                if (e.code == 429) {
                    last = e;
                    long retryAfter = e.retryAfterMs > 0 ? e.retryAfterMs : (2L << attempt) * 1000;
                    Thread.sleep(Math.min(retryAfter, 60_000));
                } else {
                    throw e;
                }
            }
        }
        if (last != null) throw last;
        if (TextUtils.isEmpty(response.optString("user_code")) || TextUtils.isEmpty(response.optString("device_auth_id")))
            throw new Exception("Device code response missing user_code/device_auth_id");
        return response;
    }

    /**
     * Step 3: poll after the user typed the code in the browser.
     * Returns {authorized:false} while pending, or
     * {authorized:true, authorization_code, code_verifier}.
     */
    public static JSONObject codexPollDeviceCode(String deviceAuthId, String userCode) throws Exception {
        JSONObject body = new JSONObject()
            .put("device_auth_id", deviceAuthId)
            .put("user_code", userCode);
        try {
            JSONObject response = httpPostJson(CODEX_ISSUER + "/api/accounts/deviceauth/token", body.toString(),
                new String[]{"Content-Type", "application/json", "Accept", "application/json"});
            JSONObject out = new JSONObject()
                .put("authorized", !TextUtils.isEmpty(response.optString("authorization_code")))
                .put("authorization_code", response.optString("authorization_code", ""))
                .put("code_verifier", response.optString("code_verifier", ""));
            return out;
        } catch (HttpError e) {
            if (e.code == 403 || e.code == 404) return new JSONObject().put("authorized", false);
            throw e;
        }
    }

    /** Step 4: exchange the authorization code for the token pair. */
    public static JSONObject codexExchange(String authorizationCode, String codeVerifier) throws Exception {
        java.util.Map<String, String> form = new java.util.LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", authorizationCode);
        form.put("redirect_uri", CODEX_ISSUER + "/deviceauth/callback");
        form.put("client_id", CODEX_CLIENT_ID);
        form.put("code_verifier", codeVerifier);
        JSONObject tokens = httpPostForm(CODEX_TOKEN_URL, form,
            new String[]{"User-Agent", "khabeer/1.0"});
        if (TextUtils.isEmpty(tokens.optString("access_token")))
            throw new Exception("Token exchange returned no access_token");
        return tokens;
    }

    /** Refresh grant; rotates the refresh token when the server supplies one. */
    public static JSONObject codexRefresh(String refreshToken) throws Exception {
        java.util.Map<String, String> form = new java.util.LinkedHashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", refreshToken);
        form.put("client_id", CODEX_CLIENT_ID);
        JSONObject tokens = httpPostForm(CODEX_TOKEN_URL, form,
            new String[]{"User-Agent", "khabeer/1.0", "Accept", "application/json"});
        if (TextUtils.isEmpty(tokens.optString("access_token")))
            throw new Exception("Refresh returned no access_token");
        return tokens;
    }

    // ------------------------------------------------------------------
    // Generic RFC 8628 device-code flows (xAI, Nous, Copilot)
    // ------------------------------------------------------------------

    public static final String XAI_CLIENT_ID = "b1a00492-073a-47ea-816f-4c329264a828";
    public static final String XAI_SCOPE = "openid profile email offline_access grok-cli:access api:access";
    public static final String XAI_TOKEN_URL = "https://auth.x.ai/oauth2/token";
    public static final String XAI_BASE_URL = "https://api.x.ai/v1";

    public static final String NOUS_PORTAL = "https://portal.nousresearch.com";
    public static final String NOUS_INFERENCE = "https://inference-api.nousresearch.com/v1";

    public static final String COPILOT_CLIENT_ID = "Iv1.b507a08c87ecfe98";
    public static final String COPILOT_BASE_URL = "https://api.githubcopilot.com";

    /** Standard device-code request; returns the provider's raw JSON
     *  (device_code, user_code, verification_uri, interval, ...). */
    public static JSONObject deviceCodeRequest(String url, Map<String, String> form) throws Exception {
        JSONObject response = httpPostForm(url, form, new String[]{"Accept", "application/json"});
        if (TextUtils.isEmpty(response.optString("device_code")))
            throw new Exception("Device code response missing device_code");
        return response;
    }

    /**
     * Standard RFC 8628 token poll. Returns
     * {status: "pending"|"slow_down"|"done"|"expired"|"denied", tokens?: {...}}.
     */
    public static JSONObject deviceCodePoll(String tokenUrl, Map<String, String> form) throws Exception {
        java.util.Map<String, String> pollForm = new java.util.LinkedHashMap<>(form);
        if (!pollForm.containsKey("client_id") && form.containsKey("client_id")) pollForm.put("client_id", form.get("client_id"));
        try {
            JSONObject tokens = httpPostForm(tokenUrl, pollForm, new String[]{"Accept", "application/json"});
            if (!TextUtils.isEmpty(tokens.optString("access_token")))
                return new JSONObject().put("status", "done").put("tokens", tokens);
            String error = tokens.optString("error", "");
            return new JSONObject().put("status", error.isEmpty() ? "pending" : mapOAuthError(error));
        } catch (HttpError e) {
            if (e.code == 400 || e.code == 428) {
                String body = e.body == null ? "" : e.body;
                String error = "";
                try {
                    JSONObject parsed = new JSONObject(body);
                    error = parsed.optString("error", parsed.optJSONObject("error") == null ? ""
                        : parsed.optJSONObject("error").optString("code", ""));
                } catch (Exception ignored) {}
                return new JSONObject().put("status", mapOAuthError(error));
            }
            throw e;
        }
    }

    private static String mapOAuthError(String error) {
        switch (error) {
            case "authorization_pending": return "pending";
            case "slow_down": return "slow_down";
            case "expired_token": return "expired";
            case "access_denied": return "denied";
            default: return error.isEmpty() ? "pending" : error;
        }
    }

    // ------------------------------------------------------------------
    // Codex JWT helpers
    // ------------------------------------------------------------------

    /** chatgpt_account_id claim from the Codex access token (JWT). */
    public static String codexAccountId(String accessToken) {
        try {
            String[] parts = accessToken.split("\\.");
            if (parts.length < 2) return null;
            byte[] payload = android.util.Base64.decode(parts[1], android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
            JSONObject claims = new JSONObject(new String(payload, StandardCharsets.UTF_8));
            JSONObject auth = claims.optJSONObject("https://api.openai.com/auth");
            String id = auth == null ? null : auth.optString("chatgpt_account_id", null);
            return TextUtils.isEmpty(id) ? null : id;
        } catch (Exception e) {
            return null;
        }
    }

    /** JWT exp claim in epoch seconds, or 0 when absent/unparseable. */
    public static long jwtExpiryEpochSeconds(String accessToken) {
        try {
            String[] parts = accessToken.split("\\.");
            if (parts.length < 2) return 0;
            byte[] payload = android.util.Base64.decode(parts[1], android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
            return new JSONObject(new String(payload, StandardCharsets.UTF_8)).optLong("exp", 0);
        } catch (Exception e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------
    // Codex model catalog
    // ------------------------------------------------------------------

    public static JSONObject fetchCodexModels(String accessToken, String accountId) throws Exception {
        java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + accessToken);
        headers.put("Accept", "application/json");
        headers.put("User-Agent", "khabeer/1.0");
        if (!TextUtils.isEmpty(accountId)) headers.put("ChatGPT-Account-Id", accountId);
        return httpGetJson(CODEX_BASE_URL + "/models?client_version=1.0.0", headers);
    }

    // ------------------------------------------------------------------
    // Anthropic (Claude Pro/Max) — PKCE with paste-back code#state
    // ------------------------------------------------------------------

    public static final String ANTHROPIC_CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e";
    public static final String ANTHROPIC_AUTHORIZE_URL = "https://claude.ai/oauth/authorize";
    public static final String ANTHROPIC_REDIRECT_URI = "https://console.anthropic.com/oauth/code/callback";
    public static final String ANTHROPIC_TOKEN_URL = "https://platform.claude.com/v1/oauth/token";
    /** The token endpoint 429s any claude-code/ UA; axios passes. */
    public static final String ANTHROPIC_TOKEN_UA = "axios/1.7.9";

    public static String anthropicAuthorizeUrl(String codeChallenge, String state) {
        return ANTHROPIC_AUTHORIZE_URL
            + "?code=true&client_id=" + ANTHROPIC_CLIENT_ID
            + "&response_type=code"
            + "&redirect_uri=" + URLEncoder.encode(ANTHROPIC_REDIRECT_URI)
            + "&scope=" + URLEncoder.encode("org:create_api_key user:profile user:inference")
            + "&code_challenge=" + codeChallenge
            + "&code_challenge_method=S256"
            + "&state=" + state;
    }

    public static JSONObject anthropicExchange(String code, String state, String codeVerifier) throws Exception {
        JSONObject body = new JSONObject()
            .put("grant_type", "authorization_code")
            .put("client_id", ANTHROPIC_CLIENT_ID)
            .put("code", code)
            .put("state", state)
            .put("redirect_uri", ANTHROPIC_REDIRECT_URI)
            .put("code_verifier", codeVerifier);
        return httpPostJson(ANTHROPIC_TOKEN_URL, body.toString(),
            new String[]{"Content-Type", "application/json", "Accept", "application/json", "User-Agent", ANTHROPIC_TOKEN_UA});
    }

    public static JSONObject anthropicRefresh(String refreshToken) throws Exception {
        JSONObject body = new JSONObject()
            .put("grant_type", "refresh_token")
            .put("refresh_token", refreshToken)
            .put("client_id", ANTHROPIC_CLIENT_ID);
        return httpPostJson(ANTHROPIC_TOKEN_URL, body.toString(),
            new String[]{"Content-Type", "application/json", "Accept", "application/json", "User-Agent", ANTHROPIC_TOKEN_UA});
    }

    // ------------------------------------------------------------------
    // Nous Portal — device code (JSON bodies) + header-carrying refresh
    // ------------------------------------------------------------------

    public static final String NOUS_CLIENT_ID = "hermes-cli";
    public static final String NOUS_SCOPE = "inference:invoke";

    public static JSONObject nousDeviceCode() throws Exception {
        JSONObject body = new JSONObject().put("client_id", NOUS_CLIENT_ID).put("scope", NOUS_SCOPE);
        JSONObject response = httpPostJson(NOUS_PORTAL + "/api/oauth/device/code", body.toString(),
            new String[]{"Content-Type", "application/json", "Accept", "application/json"});
        if (TextUtils.isEmpty(response.optString("device_code")))
            throw new Exception("Nous device code response missing device_code");
        return response;
    }

    public static JSONObject nousPoll(String deviceCode) throws Exception {
        JSONObject body = new JSONObject()
            .put("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
            .put("client_id", NOUS_CLIENT_ID)
            .put("device_code", deviceCode);
        try {
            JSONObject tokens = httpPostJson(NOUS_PORTAL + "/api/oauth/token", body.toString(),
                new String[]{"Content-Type", "application/json", "Accept", "application/json"});
            if (!TextUtils.isEmpty(tokens.optString("access_token")))
                return new JSONObject().put("status", "done").put("tokens", tokens);
            return new JSONObject().put("status", "pending");
        } catch (HttpError e) {
            if (e.code == 400 || e.code == 403 || e.code == 404 || e.code == 428)
                return new JSONObject().put("status", "pending");
            throw e;
        }
    }

    public static JSONObject nousRefresh(String refreshToken) throws Exception {
        JSONObject body = new JSONObject().put("grant_type", "refresh_token").put("client_id", NOUS_CLIENT_ID);
        return httpPostJson(NOUS_PORTAL + "/api/oauth/token", body.toString(),
            new String[]{"Content-Type", "application/json", "Accept", "application/json", "x-nous-refresh-token", refreshToken});
    }

    // ------------------------------------------------------------------
    // GitHub Copilot — device code + internal proxy-token exchange
    // ------------------------------------------------------------------

    public static JSONObject copilotExchangeJwt(String githubToken) throws Exception {
        java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();
        headers.put("Authorization", "token " + githubToken);
        headers.put("Accept", "application/json");
        headers.put("User-Agent", "GitHubCopilotChat/0.26.7");
        headers.put("Editor-Version", "vscode/1.104.1");
        return httpGetJson("https://api.github.com/copilot_internal/v2/token", headers);
    }

    public static final String[] COPILOT_REQUEST_HEADERS = {
        "Editor-Version", "vscode/1.104.1",
        "Copilot-Integration-Id", "vscode-chat",
        "Openai-Intent", "conversation-edits",
        "x-initiator", "agent",
    };

    // ------------------------------------------------------------------
    // Tiny HTTP layer (mirrors AiMcpRegistry's helpers, no dependency)
    // ------------------------------------------------------------------

    public static final class HttpError extends Exception {
        public final int code;
        public final String body;
        public final long retryAfterMs;
        HttpError(int code, String body, long retryAfterMs) {
            super("HTTP " + code + (TextUtils.isEmpty(body) ? "" : ": " + AiMcpRegistry.redactStatic(
                body.length() > 200 ? body.substring(0, 199) + "…" : body)));
            this.code = code;
            this.body = body;
            this.retryAfterMs = retryAfterMs;
        }
    }

    public static JSONObject httpGetJson(String url, Map<String, String> headers) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(20_000);
            if (headers != null) for (Map.Entry<String, String> h : headers.entrySet())
                connection.setRequestProperty(h.getKey(), h.getValue());
            int code = connection.getResponseCode();
            String body = readStream(code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream());
            if (code < 200 || code >= 300) throw new HttpError(code, body, retryAfter(connection));
            return new JSONObject(body);
        } finally {
            connection.disconnect();
        }
    }

    public static JSONObject httpPostJson(String url, String json, String... headers) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(20_000);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            for (int i = 0; i + 1 < headers.length; i += 2)
                connection.setRequestProperty(headers[i], headers[i + 1]);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(json.getBytes(StandardCharsets.UTF_8));
            }
            int code = connection.getResponseCode();
            String body = readStream(code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream());
            if (code < 200 || code >= 300) throw new HttpError(code, body, retryAfter(connection));
            return new JSONObject(body);
        } finally {
            connection.disconnect();
        }
    }

    public static JSONObject httpPostForm(String url, Map<String, String> form, String... headers) throws Exception {
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> entry : form.entrySet()) {
            if (body.length() > 0) body.append('&');
            body.append(URLEncoder.encode(entry.getKey(), "UTF-8"))
                .append('=')
                .append(URLEncoder.encode(entry.getValue(), "UTF-8"));
        }
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(20_000);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            for (int i = 0; i + 1 < headers.length; i += 2)
                connection.setRequestProperty(headers[i], headers[i + 1]);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = connection.getResponseCode();
            String responseBody = readStream(code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream());
            if (code < 200 || code >= 300) throw new HttpError(code, responseBody, retryAfter(connection));
            try {
                return new JSONObject(responseBody);
            } catch (Exception e) {
                // Some token endpoints answer form-urlencoded despite Accept: json.
                JSONObject out = new JSONObject();
                for (String pair : responseBody.split("&")) {
                    int eq = pair.indexOf('=');
                    if (eq <= 0) continue;
                    out.put(java.net.URLDecoder.decode(pair.substring(0, eq), "UTF-8"),
                        java.net.URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
                }
                return out;
            }
        } finally {
            connection.disconnect();
        }
    }

    private static long retryAfter(HttpURLConnection connection) {
        String value = connection.getHeaderField("Retry-After");
        if (TextUtils.isEmpty(value)) return 0;
        try {
            return Long.parseLong(value.trim()) * 1000L;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String readStream(InputStream stream) throws Exception {
        if (stream == null) return "";
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) builder.append(line).append('\n');
        }
        return builder.toString();
    }
}
