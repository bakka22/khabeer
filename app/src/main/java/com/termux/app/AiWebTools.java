package com.termux.app;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Web search + fetch for the model (Hermes web_tools/website_policy port,
 * trimmed for mobile).
 *
 * Hermes fans out to paid backends (tavily/exa/firecrawl/...) with DDG and
 * keyless tiers last. The app keeps the same shape on a mobile budget:
 * search runs SearXNG when the user configures an instance, else keyless
 * DuckDuckGo HTML; fetch pulls a URL directly. Both sit behind the fetch
 * policy (http(s) only, no private-network targets, redirect caps,
 * download caps, user blocklist) mirroring website_policy + SSRF guards.
 */
public final class AiWebTools {
    private AiWebTools() {}

    public static final int MAX_SEARCH_RESULTS = 8;
    public static final int MAX_FETCH_BYTES = 2 * 1024 * 1024;
    public static final int MAX_OUTPUT_CHARS = 12000;
    public static final int MAX_REDIRECTS = 3;
    public static final int CONNECT_TIMEOUT_MS = 15000;
    public static final int READ_TIMEOUT_MS = 30000;

    private static volatile boolean sAllowPrivateForTests = false;

    static void setAllowPrivateForTests(boolean allow) {
        sAllowPrivateForTests = allow;
    }

    // ------------------------------------------------------------------
    // Fetch policy (website_policy port)
    // ------------------------------------------------------------------

    /** Null when the URL may be fetched, else the human-readable refusal. */
    public static String policyRefusal(String url, String blockedHostsCsv) {
        if (TextUtils.isEmpty(url)) return "A URL is required.";
        String trimmed = url.trim();
        if (!trimmed.regionMatches(true, 0, "http://", 0, 7)
            && !trimmed.regionMatches(true, 0, "https://", 0, 8)) {
            return "Only http(s) URLs can be fetched.";
        }
        String host;
        try {
            host = new URL(trimmed).getHost();
        } catch (Exception e) {
            return "That URL does not parse: " + e.getMessage();
        }
        if (TextUtils.isEmpty(host)) return "That URL has no host.";
        String lowerHost = host.toLowerCase(Locale.US);
        if (!TextUtils.isEmpty(blockedHostsCsv)) {
            for (String rule : blockedHostsCsv.split(",")) {
                String clean = rule.trim().toLowerCase(Locale.US);
                if (!clean.isEmpty()
                    && (lowerHost.equals(clean) || lowerHost.endsWith("." + clean))) {
                    return "That host is on your web blocklist.";
                }
            }
        }
        if (!sAllowPrivateForTests && isPrivateTarget(lowerHost)) {
            return "Private-network targets cannot be fetched.";
        }
        return null;
    }

    private static boolean isPrivateTarget(String host) {
        try {
            InetAddress addr = InetAddress.getByName(host);
            return addr.isAnyLocalAddress() || addr.isLoopbackAddress()
                || addr.isLinkLocalAddress() || addr.isSiteLocalAddress()
                || addr.isMulticastAddress();
        } catch (Exception e) {
            return true;
        }
    }

    // ------------------------------------------------------------------
    // Fetch
    // ------------------------------------------------------------------

    public static JSONObject fetch(String url, String blockedHostsCsv) {
        JSONObject out = new JSONObject();
        try {
            String refusal = policyRefusal(url, blockedHostsCsv);
            if (refusal != null) return out.put("success", false).put("error", refusal);
            String current = url.trim();
            String contentType = "text/plain";
            byte[] body = null;
            for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
                refusal = policyRefusal(current, blockedHostsCsv);
                if (refusal != null) return out.put("success", false).put("error", refusal);
                HttpURLConnection c = (HttpURLConnection) new URL(current).openConnection();
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(CONNECT_TIMEOUT_MS);
                c.setReadTimeout(READ_TIMEOUT_MS);
                c.setRequestProperty("User-Agent", "khabeer-web/1.0 (model fetch)");
                c.setRequestProperty("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.1");
                int code = c.getResponseCode();
                if (code >= 300 && code < 400) {
                    String location = c.getHeaderField("Location");
                    c.disconnect();
                    if (TextUtils.isEmpty(location)) {
                        return out.put("success", false).put("error", "Redirect without a Location header.");
                    }
                    current = new URL(new URL(current), location).toString();
                    if (hop == MAX_REDIRECTS) {
                        return out.put("success", false).put("error", "Too many redirects.");
                    }
                    continue;
                }
                if (code < 200 || code >= 300) {
                    String err = readText(c.getErrorStream(), 4096);
                    c.disconnect();
                    return out.put("success", false)
                        .put("error", "HTTP " + code + (TextUtils.isEmpty(err) ? "." : ": " + err));
                }
                contentType = c.getContentType() == null ? "text/plain" : c.getContentType();
                body = readBytes(c.getInputStream(), MAX_FETCH_BYTES + 1);
                c.disconnect();
                break;
            }
            if (body == null) return out.put("success", false).put("error", "Empty response.");
            if (body.length > MAX_FETCH_BYTES) {
                return out.put("success", false).put("error", "Page exceeds the 2 MB fetch cap.");
            }
            String lowerType = contentType.toLowerCase(Locale.US);
            String text;
            if (lowerType.contains("html")) {
                text = htmlToText(new String(body, StandardCharsets.UTF_8));
            } else if (lowerType.contains("text") || lowerType.contains("json")
                || lowerType.contains("xml") || lowerType.contains("javascript")) {
                text = new String(body, StandardCharsets.UTF_8);
            } else {
                return out.put("success", false)
                    .put("error", "Not a readable page (Content-Type: " + contentType + ").");
            }
            text = collapseWhitespace(text);
            boolean truncated = false;
            if (text.length() > MAX_OUTPUT_CHARS) {
                text = text.substring(0, MAX_OUTPUT_CHARS);
                truncated = true;
            }
            out.put("success", true).put("url", current).put("content", text);
            if (truncated) out.put("truncated", true);
        } catch (Exception e) {
            try {
                out.put("success", false)
                    .put("error", e.getMessage() == null ? e.toString() : e.getMessage());
            } catch (Exception ignored) {}
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Search (SearXNG when configured, else keyless DuckDuckGo HTML)
    // ------------------------------------------------------------------

    public static JSONObject search(String query, int limit, String backend,
                                    String searxngUrl, String blockedHostsCsv) {
        JSONObject out = new JSONObject();
        try {
            if (TextUtils.isEmpty(query) || query.trim().isEmpty()) {
                return out.put("success", false).put("error", "A search query is required.");
            }
            limit = Math.max(1, Math.min(MAX_SEARCH_RESULTS, limit <= 0 ? 5 : limit));
            String want = backend == null ? "auto" : backend.trim().toLowerCase(Locale.US);
            JSONArray results = null;
            String used = null;
            String error = null;
            if (("auto".equals(want) || "searxng".equals(want)) && !TextUtils.isEmpty(searxngUrl)) {
                try {
                    results = searxngSearch(searxngUrl, query.trim(), limit);
                    used = "searxng";
                } catch (Exception e) {
                    error = e.getMessage();
                    if ("searxng".equals(want)) {
                        return out.put("success", false).put("error", "SearXNG failed: " + error);
                    }
                }
            }
            if (results == null) {
                if ("searxng".equals(want)) {
                    return out.put("success", false).put("error", "No SearXNG instance configured.");
                }
                try {
                    results = duckDuckGoSearch(query.trim(), limit);
                    used = "duckduckgo";
                } catch (Exception e) {
                    String msg = "Search failed"
                        + (error == null ? "" : " (searxng: " + error + ")")
                        + ": " + (e.getMessage() == null ? e.toString() : e.getMessage());
                    return out.put("success", false).put("error", msg);
                }
            }
            JSONArray filtered = new JSONArray();
            for (int i = 0; i < results.length() && filtered.length() < limit; i++) {
                JSONObject r = results.optJSONObject(i);
                if (r == null) continue;
                String resultUrl = r.optString("url", "");
                if (policyRefusal(resultUrl, blockedHostsCsv) != null) continue;
                filtered.put(r);
            }
            out.put("success", true).put("backend", used).put("results", filtered);
        } catch (Exception e) {
            try {
                out.put("success", false)
                    .put("error", e.getMessage() == null ? e.toString() : e.getMessage());
            } catch (Exception ignored) {}
        }
        return out;
    }

    static JSONArray searxngSearch(String instanceUrl, String query, int limit) throws Exception {
        String base = instanceUrl.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        String target = base + "/search?q=" + encode(query) + "&format=json&language=en";
        JSONObject root = getJson(target);
        JSONArray in = root.optJSONArray("results");
        JSONArray out = new JSONArray();
        if (in == null) return out;
        for (int i = 0; i < in.length() && out.length() < limit; i++) {
            JSONObject r = in.optJSONObject(i);
            if (r == null) continue;
            String url = r.optString("url", "");
            if (TextUtils.isEmpty(url)) continue;
            out.put(new JSONObject()
                .put("title", r.optString("title", url))
                .put("url", url)
                .put("snippet", collapseWhitespace(r.optString("content", ""))));
        }
        return out;
    }

    static JSONArray duckDuckGoSearch(String query, int limit) throws Exception {
        String target = "https://html.duckduckgo.com/html/?q=" + encode(query);
        String html = getText(target);
        JSONArray out = new JSONArray();
        Pattern link = Pattern.compile(
            "<a[^>]+class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Pattern snip = Pattern.compile(
            "<a[^>]+class=\"result__snippet\"[^>]*>(.*?)</a>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher links = link.matcher(html);
        List<String[]> found = new ArrayList<>();
        while (links.find() && found.size() < limit) {
            String href = links.group(1).trim();
            if (href.startsWith("//")) href = "https:" + href;
            if (!href.regionMatches(true, 0, "http", 0, 4)) continue;
            found.add(new String[]{href, htmlToText(links.group(2))});
        }
        Matcher snips = snip.matcher(html);
        List<String> snippets = new ArrayList<>();
        while (snips.find() && snippets.size() < limit) {
            snippets.add(collapseWhitespace(htmlToText(snips.group(1))));
        }
        for (int i = 0; i < found.size(); i++) {
            out.put(new JSONObject()
                .put("title", found.get(i)[1])
                .put("url", found.get(i)[0])
                .put("snippet", i < snippets.size() ? snippets.get(i) : ""));
        }
        if (out.length() == 0) throw new IllegalStateException("No results parsed.");
        return out;
    }

    // ------------------------------------------------------------------
    // HTTP + text helpers
    // ------------------------------------------------------------------

    private static String encode(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    private static JSONObject getJson(String target) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(target).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(READ_TIMEOUT_MS);
        c.setRequestProperty("User-Agent", "khabeer-web/1.0 (model search)");
        c.setRequestProperty("Accept", "application/json");
        int code = c.getResponseCode();
        try {
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("HTTP " + code + " from search backend.");
            }
            byte[] body = readBytes(c.getInputStream(), MAX_FETCH_BYTES + 1);
            if (body.length > MAX_FETCH_BYTES) throw new IllegalStateException("Search response too large.");
            return new JSONObject(new String(body, StandardCharsets.UTF_8));
        } finally {
            c.disconnect();
        }
    }

    private static String getText(String target) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(target).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(READ_TIMEOUT_MS);
        c.setRequestProperty("User-Agent", "khabeer-web/1.0 (model search)");
        int code = c.getResponseCode();
        try {
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("HTTP " + code + " from search backend.");
            }
            byte[] body = readBytes(c.getInputStream(), MAX_FETCH_BYTES + 1);
            if (body.length > MAX_FETCH_BYTES) throw new IllegalStateException("Search response too large.");
            return new String(body, StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }

    private static byte[] readBytes(InputStream in, long cap) throws Exception {
        if (in == null) return new byte[0];
        try (InputStream autoClose = in;
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            long total = 0;
            int n;
            while ((n = autoClose.read(buf)) > 0) {
                total += n;
                if (total > cap) throw new java.io.IOException("Response exceeds size cap.");
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static String readText(InputStream in, long cap) {
        try {
            return new String(readBytes(in, cap), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    static String htmlToText(String html) {
        if (html == null) return "";
        String noScript = html.replaceAll("(?is)<(script|style|noscript|template)[^>]*>.*?</\\1>", " ");
        String noTags = noScript.replaceAll("(?s)<[^>]*>", " ");
        return unescapeEntities(noTags);
    }

    private static String unescapeEntities(String text) {
        if (text == null) return "";
        String out = text.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ");
        Matcher numeric = Pattern.compile("&#(\\d+);").matcher(out);
        StringBuffer sb = new StringBuffer();
        while (numeric.find()) {
            int code;
            try {
                code = Integer.parseInt(numeric.group(1));
            } catch (Exception e) {
                continue;
            }
            numeric.appendReplacement(sb, Matcher.quoteReplacement(
                code > 0 && code < 0x110000 ? new String(Character.toChars(code)) : ""));
        }
        numeric.appendTail(sb);
        return sb.toString();
    }

    static String collapseWhitespace(String text) {
        if (text == null) return "";
        return text.replaceAll("\\s+", " ").trim();
    }
}
