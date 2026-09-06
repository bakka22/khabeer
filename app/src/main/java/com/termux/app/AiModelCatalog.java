package com.termux.app;

import android.text.TextUtils;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Fetches live model catalogs from provider APIs instead of relying on fake fixed lists. */
public final class AiModelCatalog {

    private AiModelCatalog() {
    }

    public static List<String> fetch(AiProviderProfile profile, String baseUrl, String apiKey) throws Exception {
        return fetch(profile, baseUrl, apiKey, null);
    }

    /** Codex model catalogs need the ChatGPT account header or the backend
     *  answers HTTP 200 with an empty model list. */
    public static List<String> fetch(AiProviderProfile profile, String baseUrl, String apiKey,
                                     @Nullable String codexAccountId) throws Exception {
        if (profile != null && "openai-codex".equals(profile.id)) {
            JSONObject catalog = ProviderLogin.fetchCodexModels(apiKey, codexAccountId);
            return parseCodexModels(catalog);
        }
        String url = modelsUrl(baseUrl);
        if (TextUtils.isEmpty(url)) throw new IllegalArgumentException("Provider has no model catalog URL.");

        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(45000);
        connection.setRequestProperty("Accept", "application/json");
        if (!TextUtils.isEmpty(apiKey)) connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        if (profile != null && "anthropic".equals(profile.id))
            connection.setRequestProperty("anthropic-version", "2023-06-01");

        int code = connection.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
        String text = readFully(stream);
        if (code < 200 || code >= 300)
            throw new IllegalStateException("Model catalog request failed with HTTP " + code + ": " + text);
        return parseModels(text);
    }

    public static String modelsUrl(String baseUrl) {
        String clean = baseUrl == null ? "" : baseUrl.trim();
        if (clean.isEmpty()) return "";
        if (clean.endsWith("/models")) return clean;
        String[] suffixes = new String[]{"/chat/completions", "/responses", "/messages"};
        for (String suffix : suffixes) {
            if (clean.endsWith(suffix)) clean = clean.substring(0, clean.length() - suffix.length());
        }
        if (clean.endsWith("/")) clean = clean.substring(0, clean.length() - 1);
        return clean + "/models";
    }

    /** Raw catalog entries keyed by model id (or Codex slug), for capability
     * probes like reasoning support. Same endpoints and auth as fetch(). */
    public static java.util.Map<String, JSONObject> fetchCapabilities(AiProviderProfile profile,
                                                                     String baseUrl, String apiKey) throws Exception {
        return fetchCapabilities(profile, baseUrl, apiKey, null);
    }

    public static java.util.Map<String, JSONObject> fetchCapabilities(AiProviderProfile profile,
                                                                     String baseUrl, String apiKey,
                                                                     @Nullable String codexAccountId) throws Exception {
        java.util.Map<String, JSONObject> out = new java.util.LinkedHashMap<>();
        if (profile != null && "openai-codex".equals(profile.id)) {
            JSONObject catalog = ProviderLogin.fetchCodexModels(apiKey, codexAccountId);
            JSONArray entries = catalog.optJSONArray("models");
            if (entries != null) {
                for (int i = 0; i < entries.length(); i++) {
                    JSONObject entry = entries.optJSONObject(i);
                    if (entry == null) continue;
                    String slug = entry.optString("slug", "");
                    if (!TextUtils.isEmpty(slug)) out.put(slug, entry);
                }
            }
            return out;
        }
        String url = modelsUrl(baseUrl);
        if (TextUtils.isEmpty(url)) throw new IllegalArgumentException("Provider has no model catalog URL.");

        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(45000);
        connection.setRequestProperty("Accept", "application/json");
        if (!TextUtils.isEmpty(apiKey)) connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        if (profile != null && "anthropic".equals(profile.id))
            connection.setRequestProperty("anthropic-version", "2023-06-01");

        int code = connection.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
        String text = readFully(stream);
        if (code < 200 || code >= 300)
            throw new IllegalStateException("Model catalog request failed with HTTP " + code + ": " + text);
        JSONObject root = new JSONObject(text);
        JSONArray data = root.optJSONArray("data");
        if (data == null) data = root.optJSONArray("models");
        if (data != null) {
            for (int i = 0; i < data.length(); i++) {
                JSONObject item = data.optJSONObject(i);
                if (item == null) continue;
                String id = item.optString("id", "");
                if (!TextUtils.isEmpty(id)) out.put(id, item);
            }
        }
        return out;
    }

    private static List<String> parseCodexModels(JSONObject catalog) {
        List<String> models = new ArrayList<>();
        List<long[]> priorities = new ArrayList<>();
        JSONArray entries = catalog.optJSONArray("models");
        if (entries != null) {
            for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.optJSONObject(i);
                if (entry == null) continue;
                String slug = entry.optString("slug", "");
                if (TextUtils.isEmpty(slug)) continue;
                if ("hidden".equals(entry.optString("visibility", ""))) continue;
                models.add(slug);
                priorities.add(new long[]{entry.optLong("priority", Long.MAX_VALUE / 2)});
            }
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < models.size(); i++) order.add(i);
        order.sort((a, b) -> Long.compare(priorities.get(a)[0], priorities.get(b)[0]));
        List<String> sorted = new ArrayList<>();
        for (int index : order) sorted.add(models.get(index));
        return sorted;
    }

    private static List<String> parseModels(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        JSONArray data = root.optJSONArray("data");
        if (data == null) data = root.optJSONArray("models");
        List<String> models = new ArrayList<>();
        if (data != null) {
            for (int i = 0; i < data.length(); i++) {
                Object item = data.opt(i);
                String id = null;
                if (item instanceof JSONObject) id = ((JSONObject) item).optString("id", null);
                else if (item instanceof String) id = (String) item;
                if (!TextUtils.isEmpty(id)) models.add(id);
            }
        }
        Collections.sort(models, String.CASE_INSENSITIVE_ORDER);
        return models;
    }

    private static String readFully(InputStream stream) throws Exception {
        if (stream == null) return "";
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) builder.append(line).append('\n');
        }
        return builder.toString();
    }
}
