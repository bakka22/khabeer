package com.termux.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.text.TextUtils;
import android.util.Base64;

import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Stores khabeer-mobile provider settings. Secrets are encrypted with Android Keystore. */
public final class AiProviderConfig {

    private static final String PREFS = "mobile_hermes_provider_config";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "mobile_hermes_provider_key";
    private static final String CIPHER = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final String KEY_SELECTED_PROVIDER = "selected_provider";
    private static final String KEY_MODEL = "model";
    private static final String KEY_BASE_URL = "base_url";
    private static final String KEY_SECRET_PREFIX = "secret_";
    private static final String KEY_MEMORY_ENABLED = "memory_enabled";
    private static final String KEY_USER_MEMORY_ENABLED = "user_memory_enabled";
    private static final String KEY_MEMORY_WRITE_APPROVAL = "memory_write_approval";
    private static final String KEY_MEMORY_NUDGE_ENABLED = "memory_nudge_enabled";
    private static final String KEY_MEMORY_NUDGE_INTERVAL = "memory_nudge_interval";
    private static final String KEY_MEMORY_WARN_PCT = "memory_warn_pct";
    private static final String KEY_MEMORY_AUTO_PCT = "memory_auto_pct";
    private static final String KEY_MEMORY_NOTIFY_MODE = "memory_notify_mode";
    private static final String KEY_LAST_PROVIDER = "last_provider_id";
    private static final String KEY_LAST_MODEL = "last_model";
    private static final String KEY_LAST_ROUTE = "last_route";
    private static final String KEY_SUBAGENT_MAX_STEPS = "subagent_max_steps";
    private static final String KEY_SUBAGENT_TIMEOUT = "subagent_timeout_seconds";

    private final SharedPreferences mPrefs;

    public AiProviderConfig(Context context) {
        mPrefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public String getSelectedProviderId() {
        return mPrefs.getString(KEY_SELECTED_PROVIDER, AiProviderProfile.firstAgentProfile().id);
    }

    public void setSelectedProviderId(String providerId) {
        if (TextUtils.isEmpty(providerId)) return;
        mPrefs.edit().putString(KEY_SELECTED_PROVIDER, providerId).apply();
    }

    public String getModel(AiProviderProfile profile) {
        if (profile == null) return "";
        return mPrefs.getString(KEY_MODEL + "_" + profile.id, profile.defaultModel);
    }

    public void setModel(AiProviderProfile profile, String model) {
        if (profile == null) return;
        mPrefs.edit().putString(KEY_MODEL + "_" + profile.id,
            model == null ? "" : model.trim()).apply();
    }

    public String getBaseUrl(AiProviderProfile profile) {
        if (profile == null) return "";
        return mPrefs.getString(KEY_BASE_URL + "_" + profile.id, profile.defaultBaseUrl);
    }

    public void setBaseUrl(AiProviderProfile profile, String baseUrl) {
        if (profile == null) return;
        mPrefs.edit().putString(KEY_BASE_URL + "_" + profile.id,
            baseUrl == null ? "" : baseUrl.trim()).apply();
    }

    // ---- Hermes-style built-in memory settings ----

    public boolean isMemoryEnabled() {
        return mPrefs.getBoolean(KEY_MEMORY_ENABLED, true);
    }

    public void setMemoryEnabled(boolean enabled) {
        mPrefs.edit().putBoolean(KEY_MEMORY_ENABLED, enabled).apply();
    }

    public boolean isUserMemoryEnabled() {
        return mPrefs.getBoolean(KEY_USER_MEMORY_ENABLED, true);
    }

    public void setUserMemoryEnabled(boolean enabled) {
        mPrefs.edit().putBoolean(KEY_USER_MEMORY_ENABLED, enabled).apply();
    }

    public boolean isAnyBuiltInMemoryEnabled() {
        return isMemoryEnabled() || isUserMemoryEnabled();
    }

    public boolean isMemoryWriteApprovalEnabled() {
        return mPrefs.getBoolean(KEY_MEMORY_WRITE_APPROVAL, false);
    }

    public void setMemoryWriteApprovalEnabled(boolean enabled) {
        mPrefs.edit().putBoolean(KEY_MEMORY_WRITE_APPROVAL, enabled).apply();
    }

    public boolean isMemoryNudgeEnabled() {
        return mPrefs.getBoolean(KEY_MEMORY_NUDGE_ENABLED, true);
    }

    public void setMemoryNudgeEnabled(boolean enabled) {
        mPrefs.edit().putBoolean(KEY_MEMORY_NUDGE_ENABLED, enabled).apply();
    }

    public int getMemoryNudgeInterval() {
        return Math.max(1, mPrefs.getInt(KEY_MEMORY_NUDGE_INTERVAL, 10));
    }

    public void setMemoryNudgeInterval(int interval) {
        mPrefs.edit().putInt(KEY_MEMORY_NUDGE_INTERVAL, Math.max(1, interval)).apply();
    }

    /** Context-window gating (§5.1): warn at this replay usage %, compact
     * automatically at the auto threshold. Manual compaction anytime. */
    public int getMemoryWarnPct() {
        return Math.max(1, Math.min(99, mPrefs.getInt(KEY_MEMORY_WARN_PCT, 90)));
    }

    public void setMemoryWarnPct(int pct) {
        mPrefs.edit().putInt(KEY_MEMORY_WARN_PCT, Math.max(1, Math.min(99, pct))).apply();
    }

    public int getMemoryAutoPct() {
        return Math.max(1, Math.min(100, mPrefs.getInt(KEY_MEMORY_AUTO_PCT, 95)));
    }

    public void setMemoryAutoPct(int pct) {
        mPrefs.edit().putInt(KEY_MEMORY_AUTO_PCT, Math.max(1, Math.min(100, pct))).apply();
    }

    /** Background-review surfacing: off | on ("Memory updated") | verbose (previews). */
    public String getMemoryNotifyMode() {
        String mode = mPrefs.getString(KEY_MEMORY_NOTIFY_MODE, "on");
        if ("off".equals(mode) || "verbose".equals(mode)) return mode;
        return "on";
    }

    public void setMemoryNotifyMode(String mode) {
        if (!"off".equals(mode) && !"verbose".equals(mode)) mode = "on";
        mPrefs.edit().putString(KEY_MEMORY_NOTIFY_MODE, mode).apply();
    }

    public boolean hasApiKey(AiProviderProfile profile) {
        return !TextUtils.isEmpty(getApiKey(profile));
    }

    @Nullable
    public String getApiKey(AiProviderProfile profile) {
        if (profile == null) return null;
        String encoded = mPrefs.getString(KEY_SECRET_PREFIX + profile.id, "");
        if (TextUtils.isEmpty(encoded)) return null;
        try {
            String[] parts = encoded.split(":", 2);
            if (parts.length != 2) return null;
            byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
            byte[] encrypted = Base64.decode(parts[1], Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            String value = new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
            return TextUtils.isEmpty(value) ? null : value;
        } catch (Exception e) {
            return null;
        }
    }

    public void setApiKey(AiProviderProfile profile, String apiKey) {
        if (profile == null) return;
        String clean = apiKey == null ? "" : apiKey.trim();
        SharedPreferences.Editor editor = mPrefs.edit();
        if (clean.isEmpty()) {
            editor.remove(KEY_SECRET_PREFIX + profile.id);
        } else {
            try {
                Cipher cipher = Cipher.getInstance(CIPHER);
                cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey());
                byte[] encrypted = cipher.doFinal(clean.getBytes(StandardCharsets.UTF_8));
                String encoded = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)
                    + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP);
                editor.putString(KEY_SECRET_PREFIX + profile.id, encoded);
            } catch (Exception e) {
                return;
            }
        }
        editor.apply();
    }

    private SecretKey getOrCreateSecretKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build());
        return generator.generateKey();
    }

    // ---- OpenCode routes: Free / Zen / Go each keep their own identity ----

    public static final String OC_ROUTE_FREE = "free";
    public static final String OC_ROUTE_ZEN = "zen";
    public static final String OC_ROUTE_GO = "go";

    private static final String KEY_OC_ROUTE_KEY = "oc_route_key_";
    private static final String KEY_OC_ROUTE_MODEL = "oc_route_model_";
    private static final String KEY_OC_SELECTED_ROUTE = "oc_selected_route";

    public static String ocRouteUrl(String route) {
        return OC_ROUTE_GO.equals(route) ? "https://opencode.ai/zen/go/v1" : "https://opencode.ai/zen/v1";
    }

    public static String ocRouteDefaultModel(String route) {
        if (OC_ROUTE_GO.equals(route)) return "glm-5";
        if (OC_ROUTE_ZEN.equals(route)) return "x-preview";
        return "big-pickle";
    }

    public static String ocRouteLabel(String route) {
        if (OC_ROUTE_GO.equals(route)) return "Go";
        if (OC_ROUTE_ZEN.equals(route)) return "Zen";
        return "Free";
    }

    public static boolean ocRouteNeedsKey(String route) {
        return OC_ROUTE_ZEN.equals(route) || OC_ROUTE_GO.equals(route);
    }

    public String getOpenCodeSelectedRoute() {
        String route = mPrefs.getString(KEY_OC_SELECTED_ROUTE, OC_ROUTE_FREE);
        return TextUtils.isEmpty(route) ? OC_ROUTE_FREE : route;
    }

    public void setOpenCodeSelectedRoute(String route) {
        if (TextUtils.isEmpty(route)) return;
        mPrefs.edit().putString(KEY_OC_SELECTED_ROUTE, route).apply();
    }

    public String getOpenCodeRouteKey(String route) {
        return getSecret(KEY_OC_ROUTE_KEY + (route == null ? OC_ROUTE_FREE : route));
    }

    public void setOpenCodeRouteKey(String route, String key) {
        setSecret(KEY_OC_ROUTE_KEY + (route == null ? OC_ROUTE_FREE : route), key);
    }

    public String getOpenCodeRouteModel(String route) {
        return mPrefs.getString(KEY_OC_ROUTE_MODEL + (route == null ? OC_ROUTE_FREE : route),
            ocRouteDefaultModel(route));
    }

    // ---- New-session flow: last used + readiness ----

    public void setLastUsed(String providerId, String model, String route) {
        mPrefs.edit().putString(KEY_LAST_PROVIDER, providerId == null ? "" : providerId)
            .putString(KEY_LAST_MODEL, model == null ? "" : model)
            .putString(KEY_LAST_ROUTE, route == null ? "" : route).apply();
    }

    public String getLastProviderId() {
        return mPrefs.getString(KEY_LAST_PROVIDER, "");
    }

    public String getLastModel() {
        return mPrefs.getString(KEY_LAST_MODEL, "");
    }

    public String getLastRoute() {
        return mPrefs.getString(KEY_LAST_ROUTE, "");
    }

    /** Subagent turn bounds (More → Subagents). Clamped sane ranges. */
    public int getSubagentMaxSteps() {
        return Math.max(5, Math.min(80, mPrefs.getInt(KEY_SUBAGENT_MAX_STEPS, 20)));
    }

    public void setSubagentMaxSteps(int steps) {
        mPrefs.edit().putInt(KEY_SUBAGENT_MAX_STEPS, Math.max(5, Math.min(80, steps))).apply();
    }

    public int getSubagentTimeoutSeconds() {
        return Math.max(60, Math.min(1800, mPrefs.getInt(KEY_SUBAGENT_TIMEOUT, 300)));
    }

    public void setSubagentTimeoutSeconds(int seconds) {
        mPrefs.edit().putInt(KEY_SUBAGENT_TIMEOUT, Math.max(60, Math.min(1800, seconds))).apply();
    }

    /** An OpenCode route is usable when it needs no key (Free) or its key is set. */
    public boolean isRouteConfigured(String route) {
        if (TextUtils.isEmpty(route)) route = OC_ROUTE_FREE;
        if (!ocRouteNeedsKey(route)) return true;
        return !TextUtils.isEmpty(getOpenCodeRouteKey(route));
    }

    /** A provider is offerable for new sessions: implemented, chat-capable,
     * and credentialed (key, login, or keyless with an endpoint). */
    public boolean isProviderConfigured(AiProviderProfile profile) {
        if (profile == null || profile.terminalOnly || !profile.implemented) return false;
        if ("opencode".equals(profile.id)) return isRouteConfigured(getOpenCodeSelectedRoute());
        if (profile.apiKeyAuth) return hasApiKey(profile) || hasProviderLogin(profile.id);
        if (profile.oauthAuth) return hasProviderLogin(profile.id);
        return !TextUtils.isEmpty(getBaseUrl(profile));
    }

    public String configuredSummary(AiProviderProfile profile) {
        if (profile == null) return "";
        if (profile.terminalOnly) return "Raw terminal — no agent chat.";
        if (!profile.implemented) return "Coming next.";
        if ("opencode".equals(profile.id)) {
            String route = getOpenCodeSelectedRoute();
            if (!isRouteConfigured(route))
                return "OpenCode " + ocRouteLabel(route) + " needs an API key.";
            return "✓ " + ocRouteLabel(route) + " · " + getOpenCodeRouteModel(route);
        }
        if (!isProviderConfigured(profile)) {
            if (profile.apiKeyAuth && profile.oauthAuth) return "Needs an API key or sign-in.";
            if (profile.apiKeyAuth) return "Needs an API key.";
            if (profile.oauthAuth) return "Sign-in required.";
            return "Needs an endpoint.";
        }
        String model = getModel(profile);
        return "✓ " + (TextUtils.isEmpty(model) ? profile.defaultModel : model);
    }

    public void setOpenCodeRouteModel(String route, String model) {
        mPrefs.edit().putString(KEY_OC_ROUTE_MODEL + (route == null ? OC_ROUTE_FREE : route),
            model == null ? "" : model.trim()).apply();
    }

    // ---- MCP server auth tokens (header auth), Keystore-encrypted ----

    private static final String KEY_MCP_TOKEN = "mcp_token_";

    public String getMcpServerToken(String serverName) {
        return getSecret(KEY_MCP_TOKEN + (serverName == null ? "" : serverName));
    }

    public void setMcpServerToken(String serverName, String token) {
        setSecret(KEY_MCP_TOKEN + (serverName == null ? "" : serverName), token);
    }

    /** OAuth refresh tokens live under their own key so an access-token
     * update never touches the long-lived credential. */
    public String getMcpServerRefresh(String serverName) {
        return getSecret("mcp_refresh_" + (serverName == null ? "" : serverName));
    }

    public void setMcpServerRefresh(String serverName, String token) {
        setSecret("mcp_refresh_" + (serverName == null ? "" : serverName), token);
    }

    // ---- Provider login tokens (device-code / OAuth providers), Keystore-encrypted ----

    public String getProviderToken(String providerId) {
        return getSecret("ptoken_" + (providerId == null ? "" : providerId));
    }

    public void setProviderToken(String providerId, String token) {
        setSecret("ptoken_" + (providerId == null ? "" : providerId), token);
    }

    public String getProviderRefresh(String providerId) {
        return getSecret("prefresh_" + (providerId == null ? "" : providerId));
    }

    public void setProviderRefresh(String providerId, String token) {
        setSecret("prefresh_" + (providerId == null ? "" : providerId), token);
    }

    /** Free-form login state JSON (account id, region, endpoints, expiry...). */
    public String getProviderState(String providerId) {
        return getSecret("pstate_" + (providerId == null ? "" : providerId));
    }

    public void setProviderState(String providerId, String json) {
        setSecret("pstate_" + (providerId == null ? "" : providerId), json);
    }

    /** API key or provider-login token, whichever is present. */
    public String resolveCredential(AiProviderProfile profile) {
        String key = getApiKey(profile);
        if (!TextUtils.isEmpty(key)) return key;
        return getProviderToken(profile.id);
    }

    public boolean hasProviderLogin(String providerId) {
        return !TextUtils.isEmpty(getProviderToken(providerId));
    }

    public void clearProviderLogin(String providerId) {
        setProviderToken(providerId, null);
        setProviderRefresh(providerId, null);
        setProviderState(providerId, null);
    }

    private String getSecret(String storageKey) {
        String encoded = mPrefs.getString(storageKey, "");
        if (TextUtils.isEmpty(encoded)) return null;
        try {
            String[] parts = encoded.split(":", 2);
            if (parts.length != 2) return null;
            byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
            byte[] encrypted = Base64.decode(parts[1], Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            String value = new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
            return TextUtils.isEmpty(value) ? null : value;
        } catch (Exception e) {
            return null;
        }
    }

    private void setSecret(String storageKey, String value) {
        String clean = value == null ? "" : value.trim();
        SharedPreferences.Editor editor = mPrefs.edit();
        if (clean.isEmpty()) {
            editor.remove(storageKey);
        } else {
            try {
                Cipher cipher = Cipher.getInstance(CIPHER);
                cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey());
                byte[] encrypted = cipher.doFinal(clean.getBytes(StandardCharsets.UTF_8));
                editor.putString(storageKey, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)
                    + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP));
            } catch (Exception e) {
                return;
            }
        }
        editor.apply();
    }
}
