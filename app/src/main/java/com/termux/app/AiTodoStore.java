package com.termux.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Per-session structured task list (Hermes TodoStore port).
 *
 * Items are ordered — list position is priority. Each item has an id,
 * content, status, and optional parent for nested subtasks. The model
 * rewrites the list through the todowrite tool (merge by id); active
 * items are re-injected after compaction so compressed sessions keep
 * their plan. Completed/cancelled items are dropped from injection so
 * the model never re-does finished work.
 *
 * Bounds mirror Hermes: item content capped, list capped.
 */
public final class AiTodoStore {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_IN_PROGRESS = "in_progress";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_CANCELLED = "cancelled";

    public static final int MAX_CONTENT_CHARS = 4000;
    public static final int MAX_ITEMS = 256;

    public static final String INJECTION_HEADER =
        "[Your active task list was preserved across context compression]";

    private final List<JSONObject> items = new ArrayList<>();

    public synchronized JSONObject write(JSONArray todos) {
        if (todos != null) {
            Map<String, JSONObject> byId = new LinkedHashMap<>();
            for (JSONObject item : items) {
                try { byId.put(item.getString("id"), item); } catch (Exception ignored) {}
            }
            for (int i = 0; i < todos.length(); i++) {
                JSONObject clean = validate(todos.optJSONObject(i));
                if (clean == null) continue;
                try {
                    byId.put(clean.getString("id"), clean);
                } catch (Exception ignored) {}
            }
            items.clear();
            items.addAll(byId.values());
            while (items.size() > MAX_ITEMS) items.remove(items.size() - 1);
        }
        return snapshot();
    }

    public synchronized JSONArray read() {
        JSONArray out = new JSONArray();
        for (JSONObject item : items) {
            try {
                JSONObject copy = new JSONObject();
                copy.put("id", item.optString("id", ""));
                copy.put("content", item.optString("content", ""));
                copy.put("status", item.optString("status", STATUS_PENDING));
                if (!item.optString("parent", "").isEmpty()) copy.put("parent", item.optString("parent", ""));
                out.put(copy);
            } catch (Exception ignored) {}
        }
        return out;
    }

    public synchronized JSONObject snapshot() {
        JSONObject o = new JSONObject();
        try { o.put("todos", read()); } catch (Exception ignored) {}
        return o;
    }

    public synchronized void restore(JSONArray todos) {
        items.clear();
        if (todos == null) return;
        Map<String, JSONObject> byId = new LinkedHashMap<>();
        for (int i = 0; i < todos.length(); i++) {
            JSONObject clean = validate(todos.optJSONObject(i));
            if (clean == null) continue;
            try { byId.put(clean.getString("id"), clean); } catch (Exception ignored) {}
        }
        items.addAll(byId.values());
        while (items.size() > MAX_ITEMS) items.remove(items.size() - 1);
    }

    public synchronized boolean isEmpty() {
        return items.isEmpty();
    }

    /** Renders active items for post-compaction injection, or null when
     * there is nothing active to preserve. */
    public synchronized String formatForInjection() {
        if (items.isEmpty()) return null;
        Map<String, List<JSONObject>> children = new LinkedHashMap<>();
        List<JSONObject> roots = new ArrayList<>();
        for (JSONObject item : items) {
            String parent = item.optString("parent", "");
            if (!parent.isEmpty()) {
                List<JSONObject> list = children.get(parent);
                if (list == null) {
                    list = new ArrayList<>();
                    children.put(parent, list);
                }
                list.add(item);
            } else {
                roots.add(item);
            }
        }
        StringBuilder out = new StringBuilder(INJECTION_HEADER);
        boolean[] any = new boolean[]{false};
        for (JSONObject root : roots) {
            if (render(root, children, 0, out)) any[0] = true;
        }
        return any[0] ? out.toString() : null;
    }

    private boolean render(JSONObject item, Map<String, List<JSONObject>> children, int depth, StringBuilder out) {
        List<JSONObject> kids = children.get(item.optString("id", ""));
        boolean hasActiveKid = false;
        StringBuilder kidLines = new StringBuilder();
        if (kids != null) {
            for (JSONObject kid : kids) {
                if (render(kid, children, depth + 1, kidLines)) hasActiveKid = true;
            }
        }
        String status = item.optString("status", STATUS_PENDING);
        boolean keep = STATUS_PENDING.equals(status) || STATUS_IN_PROGRESS.equals(status) || hasActiveKid;
        if (!keep) return false;
        String marker = STATUS_COMPLETED.equals(status) ? "[x]"
            : STATUS_IN_PROGRESS.equals(status) ? "[>]"
            : STATUS_CANCELLED.equals(status) ? "[~]" : "[ ]";
        out.append("\n");
        for (int i = 0; i < depth; i++) out.append("  ");
        out.append("- ").append(marker).append(" ").append(item.optString("id", "?")).append(". ")
            .append(item.optString("content", "")).append(" (").append(status).append(")");
        out.append(kidLines);
        return true;
    }

    private static JSONObject validate(JSONObject item) {
        if (item == null) return null;
        try {
            String id = item.optString("id", "").trim();
            if (id.isEmpty()) id = UUID.randomUUID().toString().substring(0, 8);
            String content = item.optString("content", "").trim();
            if (content.isEmpty()) return null;
            if (content.length() > MAX_CONTENT_CHARS) content = content.substring(0, MAX_CONTENT_CHARS) + "… [truncated]";
            String status = item.optString("status", STATUS_PENDING).trim().toLowerCase(java.util.Locale.US);
            if (!STATUS_PENDING.equals(status) && !STATUS_IN_PROGRESS.equals(status)
                && !STATUS_COMPLETED.equals(status) && !STATUS_CANCELLED.equals(status)) {
                status = STATUS_PENDING;
            }
            String parent = item.optString("parent", "").trim();
            JSONObject clean = new JSONObject();
            clean.put("id", id);
            clean.put("content", content);
            clean.put("status", status);
            if (!parent.isEmpty()) clean.put("parent", parent);
            return clean;
        } catch (Exception e) {
            return null;
        }
    }
}
