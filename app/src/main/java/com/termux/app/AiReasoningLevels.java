package com.termux.app;

import android.text.TextUtils;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Live reasoning-effort levels per model (Hermes reasoning-metadata port).
 *
 * Most catalogs publish no effort vocabulary at all — only GitHub Copilot
 * advertises an explicit {@code capabilities.supports.reasoning_effort}
 * list, and OpenRouter-style catalogs advertise on/off via
 * {@code supported_parameters}. Everything else defers to the default
 * ladder, exactly like the reference (unknown capability → allow).
 */
public final class AiReasoningLevels {

    private AiReasoningLevels() {
    }

    /** Canonical app ladder, weakest first (Hermes clamp direction: never
     * silently escalate cost). */
    public static final List<String> DEFAULT_LEVELS =
        Collections.unmodifiableList(Arrays.asList("low", "medium", "high", "xhigh", "ultra"));

    public enum Status {
        /** No catalog metadata — show the default ladder. */
        DEFAULT,
        /** Catalog explicitly omits reasoning — hide the ladder. */
        UNSUPPORTED,
        /** Catalog publishes an explicit level list — show exactly it. */
        EXPLICIT
    }

    public static final class Result {
        public final Status status;
        public final List<String> levels;

        Result(Status status, List<String> levels) {
            this.status = status;
            this.levels = levels == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(levels));
        }
    }

    private static final long CACHE_TTL_MS = 60 * 60 * 1000L;
    private static final Map<String, CachedCatalog> sCache = new HashMap<>();

    private static final class CachedCatalog {
        final long expiresAt;
        final Map<String, JSONObject> entries;

        CachedCatalog(Map<String, JSONObject> entries) {
            this.expiresAt = System.currentTimeMillis() + CACHE_TTL_MS;
            this.entries = entries;
        }
    }

    /** Resolves levels for a model. Network call — never invoke on the UI
     * thread. Throws on fetch failure so callers can fall back silently. */
    public static Result resolve(AiProviderProfile profile, String model,
                                 String baseUrl, String apiKey) throws Exception {
        return resolve(profile, model, baseUrl, apiKey, null);
    }

    public static Result resolve(AiProviderProfile profile, String model,
                                 String baseUrl, String apiKey,
                                 @Nullable String codexAccountId) throws Exception {
        String key = (profile == null ? "" : profile.id) + "\n" + (baseUrl == null ? "" : baseUrl);
        Map<String, JSONObject> entries;
        synchronized (sCache) {
            CachedCatalog cached = sCache.get(key);
            if (cached != null && System.currentTimeMillis() < cached.expiresAt) {
                entries = cached.entries;
            } else {
                entries = AiModelCatalog.fetchCapabilities(profile, baseUrl, apiKey, codexAccountId);
                sCache.put(key, new CachedCatalog(entries));
            }
        }
        return interpret(entries, model);
    }

    /** Pure interpretation of cached/raw entries — unit-testable. */
    static Result interpret(@Nullable Map<String, JSONObject> entries, @Nullable String model) {
        if (entries == null || TextUtils.isEmpty(model)) return new Result(Status.DEFAULT, null);
        JSONObject entry = entries.get(model);
        if (entry == null) {
            for (Map.Entry<String, JSONObject> candidate : entries.entrySet()) {
                if (model.equalsIgnoreCase(candidate.getKey())) {
                    entry = candidate.getValue();
                    break;
                }
            }
        }
        if (entry == null) return new Result(Status.DEFAULT, null);

        JSONObject capabilities = entry.optJSONObject("capabilities");
        if (capabilities != null) {
            JSONObject supports = capabilities.optJSONObject("supports");
            if (supports != null) {
                JSONArray efforts = supports.optJSONArray("reasoning_effort");
                if (efforts != null) {
                    List<String> levels = new ArrayList<>();
                    for (int i = 0; i < efforts.length(); i++) {
                        String level = efforts.optString(i, "").trim().toLowerCase();
                        if (!level.isEmpty() && !levels.contains(level)) levels.add(level);
                    }
                    return new Result(Status.EXPLICIT, levels);
                }
            }
            return new Result(Status.UNSUPPORTED, null);
        }

        JSONArray supportedParams = entry.optJSONArray("supported_parameters");
        if (supportedParams != null) {
            for (int i = 0; i < supportedParams.length(); i++) {
                if ("reasoning".equals(supportedParams.optString(i))) {
                    return new Result(Status.DEFAULT, null);
                }
            }
            return new Result(Status.UNSUPPORTED, null);
        }
        return new Result(Status.DEFAULT, null);
    }

    /** Clamps a requested effort onto supported levels: verbatim when
     * supported, else nearest weaker level, else weakest. Unknown sets
     * pass through untouched. */
    static String clamp(String requested, List<String> supported) {
        if (TextUtils.isEmpty(requested) || supported == null || supported.isEmpty()) return requested;
        String clean = requested.trim().toLowerCase();
        for (String level : supported) {
            if (clean.equals(level.trim().toLowerCase())) return requested;
        }
        int requestedRank = DEFAULT_LEVELS.indexOf(clean);
        if (requestedRank < 0) return requested;
        String fallback = null;
        int fallbackRank = -1;
        for (String level : supported) {
            int rank = DEFAULT_LEVELS.indexOf(level.trim().toLowerCase());
            if (rank < 0) continue;
            if (rank <= requestedRank && rank > fallbackRank) {
                fallback = level;
                fallbackRank = rank;
            }
        }
        if (fallback != null) return fallback;
        int weakestRank = Integer.MAX_VALUE;
        for (String level : supported) {
            int rank = DEFAULT_LEVELS.indexOf(level.trim().toLowerCase());
            if (rank >= 0 && rank < weakestRank) {
                weakestRank = rank;
                fallback = level;
            }
        }
        return fallback == null ? requested : fallback;
    }
}
