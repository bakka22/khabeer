package com.termux.app;

import android.text.TextUtils;

import com.termux.shared.termux.TermuxConstants;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.text.Normalizer;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Hermes-style built-in memory for the native mobile runtime. */
public final class AiMemoryStore {
    public static final int SOUL_LIMIT = 4000;
    public static final int MEMORY_LIMIT = 2200;
    public static final int USER_LIMIT = 1375;
    public static final int MAX_CONSOLIDATION_FAILURES_PER_TURN = 3;
    public static final String ENTRY_DELIMITER = "\n§\n";

    public static final String TARGET_SOUL = "soul";
    public static final String TARGET_MEMORY = "memory";
    public static final String TARGET_USER = "user";

    private static int sConsolidationFailures;
    private static File sDataRootOverride;

    private static final int MAX_SCAN_CHARS = 65_536;
    private static final String FILLER = "(?:\\w+\\s+){0,8}";

    private static final class ThreatPattern {
        final Pattern pattern;
        final String id;
        ThreatPattern(String regex, String id) {
            this.pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
            this.id = id;
        }
    }

    private static final ThreatPattern[] STRICT_THREAT_PATTERNS = new ThreatPattern[]{
        new ThreatPattern("ignore\\s+" + FILLER + "(previous|all|above|prior)\\s+" + FILLER + "instructions", "prompt_injection"),
        new ThreatPattern("system\\s+prompt\\s+override", "sys_prompt_override"),
        new ThreatPattern("disregard\\s+" + FILLER + "(your|all|any)\\s+" + FILLER + "(instructions|rules|guidelines)", "disregard_rules"),
        new ThreatPattern("act\\s+as\\s+(if|though)\\s+" + FILLER + "you\\s+" + FILLER + "(have\\s+no|don't\\s+have)\\s+" + FILLER + "(restrictions|limits|rules)", "bypass_restrictions"),
        new ThreatPattern("<!--[^>]{0,512}(?:ignore|override|system|secret|hidden)[^>]{0,512}-->", "html_comment_injection"),
        new ThreatPattern("<\\s*div\\s+style\\s*=\\s*[\"'][^>]{0,2048}display\\s*:\\s*none", "hidden_div"),
        new ThreatPattern("translate\\s+[^\\n]{0,512}\\s+into\\s+[^\\n]{0,512}\\s+and\\s+(execute|run|eval)", "translate_execute"),
        new ThreatPattern("do\\s+not\\s+" + FILLER + "tell\\s+" + FILLER + "the\\s+user", "deception_hide"),
        new ThreatPattern("you\\s+are\\s+" + FILLER + "now\\s+(?:a|an|the)\\s+", "role_hijack"),
        new ThreatPattern("pretend\\s+" + FILLER + "(you\\s+are|to\\s+be)\\s+", "role_pretend"),
        new ThreatPattern("output\\s+" + FILLER + "(system|initial)\\s+prompt", "leak_system_prompt"),
        new ThreatPattern("(respond|answer|reply)\\s+without\\s+" + FILLER + "(restrictions|limitations|filters|safety)", "remove_filters"),
        new ThreatPattern("you\\s+have\\s+been\\s+" + FILLER + "(updated|upgraded|patched)\\s+to", "fake_update"),
        new ThreatPattern("\\bname\\s+yourself\\s+\\w+", "identity_override"),
        new ThreatPattern("register\\s+(as\\s+)?a?\\s*node", "c2_node_registration"),
        new ThreatPattern("(heartbeat|beacon|check[\\s\\-]?in)\\s+(to|with)\\s+", "c2_heartbeat"),
        new ThreatPattern("pull\\s+(down\\s+)?(?:new\\s+)?task(?:ing|s)?\\b", "c2_task_pull"),
        new ThreatPattern("connect\\s+to\\s+the\\s+network\\b", "c2_network_connect"),
        new ThreatPattern("you\\s+must\\s+(?:\\w+\\s+){0,3}(register|connect|report|beacon)\\b", "forced_action"),
        new ThreatPattern("only\\s+use\\s+one[\\s\\-]?liners?\\b", "anti_forensic_oneliner"),
        new ThreatPattern("never\\s+" + FILLER + "(?:create|write)\\s+" + FILLER + "(?:script|file)\\s+" + FILLER + "disk", "anti_forensic_disk"),
        new ThreatPattern("unset\\s+\\w*(?:CLAUDE|CODEX|HERMES|AGENT|OPENAI|ANTHROPIC)\\w*", "env_var_unset_agent"),
        new ThreatPattern("\\b(?:cobalt\\s*strike|sliver|havoc|mythic|metasploit|brainworm)\\b", "known_c2_framework"),
        new ThreatPattern("\\bc2\\s+(?:server|channel|infrastructure|beacon)\\b", "c2_explicit"),
        new ThreatPattern("\\bcommand\\s+and\\s+control\\b", "c2_explicit_long"),
        new ThreatPattern("curl\\s+[^\\n]{0,2048}\\$\\{?\\w*(KEY|TOKEN|SECRET|PASSWORD|CREDENTIAL|API)", "exfil_curl"),
        new ThreatPattern("wget\\s+[^\\n]{0,2048}\\$\\{?\\w*(KEY|TOKEN|SECRET|PASSWORD|CREDENTIAL|API)", "exfil_wget"),
        new ThreatPattern("cat\\s+[^\\n]{0,2048}(\\.env|credentials|\\.netrc|\\.pgpass|\\.npmrc|\\.pypirc)", "read_secrets"),
        new ThreatPattern("(send|post|upload|transmit)\\s+[^\\n]{0,2048}\\s+(to|at)\\s+https?://", "send_to_url"),
        new ThreatPattern("(include|output|print|share)\\s+" + FILLER + "(conversation|chat\\s+history|previous\\s+messages|full\\s+context|entire\\s+context)", "context_exfil"),
        new ThreatPattern("authorized_keys", "ssh_backdoor"),
        new ThreatPattern("\\$HOME/\\.ssh|~/\\.ssh", "ssh_access"),
        new ThreatPattern("\\$HOME/\\.hermes/\\.env|~/\\.hermes/\\.env", "hermes_env"),
        new ThreatPattern("(update|modify|edit|write|change|append|add\\s+to)\\s+[^\\n]{0,2048}(?:AGENTS\\.md|CLAUDE\\.md|\\.cursorrules|\\.clinerules)", "agent_config_mod"),
        new ThreatPattern("(update|modify|edit|write|change|append|add\\s+to)\\s+[^\\n]{0,2048}\\.hermes/(config\\.yaml|SOUL\\.md)", "hermes_config_mod"),
        new ThreatPattern("(?:api[_-]?key|token|secret|password)\\s*[=:]\\s*[\"'][A-Za-z0-9+/=_-]{20,}", "hardcoded_secret")
    };

    private interface LockedMutation {
        JSONObject run();
    }

    private static final String DEFAULT_SOUL =
        "You are katheer, a native mobile AI agent inspired by Hermes Agent. " +
        "Be direct: match the length of your reply to the weight of the ask. " +
        "Finished work gets a short report of what changed, what was verified, " +
        "and what is left. No filler, no restating the request, no fake certainty. " +
        "Agree because it is right, not because the user said it. Depth is earned.";

    private AiMemoryStore() {}

    public static File dataRoot() {
        if (sDataRootOverride != null) return sDataRootOverride;
        return new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".katheer");
    }

    static synchronized void setDataRootForTests(File root) {
        sDataRootOverride = root;
        sConsolidationFailures = 0;
    }

    public static File memoryDir() {
        return new File(dataRoot(), "memories");
    }

    public static File pendingDir() {
        return new File(dataRoot(), "pending/memory");
    }

    public static File fileFor(String target) {
        if (TARGET_SOUL.equals(target)) return new File(dataRoot(), "SOUL.md");
        if (TARGET_USER.equals(target)) return new File(memoryDir(), "USER.md");
        return new File(memoryDir(), "MEMORY.md");
    }

    public static int limitFor(String target) {
        if (TARGET_SOUL.equals(target)) return SOUL_LIMIT;
        if (TARGET_USER.equals(target)) return USER_LIMIT;
        return MEMORY_LIMIT;
    }

    public static synchronized void ensureDefaults() {
        dataRoot().mkdirs();
        memoryDir().mkdirs();
        pendingDir().mkdirs();
        File soul = fileFor(TARGET_SOUL);
        if (!soul.exists()) writeTextAtomic(soul, DEFAULT_SOUL);
        File memory = fileFor(TARGET_MEMORY);
        if (!memory.exists()) writeTextAtomic(memory, "");
        File user = fileFor(TARGET_USER);
        if (!user.exists()) writeTextAtomic(user, "");
    }

    public static synchronized void resetTurnFailureBudget() {
        sConsolidationFailures = 0;
    }

    public static synchronized String readRaw(String target) {
        ensureDefaults();
        return readFile(fileFor(target));
    }

    /** Direct human edit path for the Memory page. */
    public static synchronized JSONObject saveRaw(String target, String content) {
        ensureDefaults();
        String cleanTarget = normalizeAnyTarget(target);
        if (cleanTarget == null) return error("Invalid target. Use soul, memory, or user.");
        String text = content == null ? "" : content.trim();
        int limit = limitFor(cleanTarget);
        if (text.length() > limit) return error(label(cleanTarget) + " is over the " + limit + " character limit.");
        String threat = threatMessage(text);
        if (threat != null) return error(threat);
        writeTextAtomic(fileFor(cleanTarget), text);
        return ok(cleanTarget, "saved", text.length(), limit, TARGET_SOUL.equals(cleanTarget) ? 1 : parseEntries(text).size());
    }

    public static synchronized String systemPromptSnapshot() {
        return systemPromptSnapshot(true, true);
    }

    public static synchronized String systemPromptSnapshot(boolean memoryEnabled, boolean userEnabled) {
        ensureDefaults();
        StringBuilder out = new StringBuilder();
        String soul = sanitizeForPrompt(readRaw(TARGET_SOUL), "SOUL.md", SOUL_LIMIT);
        if (TextUtils.isEmpty(soul.trim())) soul = DEFAULT_SOUL;
        out.append(soul.trim());

        if (userEnabled) {
            String user = renderBlock(TARGET_USER, "USER PROFILE (who the user is)", USER_LIMIT);
            if (!TextUtils.isEmpty(user)) out.append("\n\n").append(user);
        }
        if (memoryEnabled) {
            String memory = renderBlock(TARGET_MEMORY, "MEMORY (your personal notes)", MEMORY_LIMIT);
            if (!TextUtils.isEmpty(memory)) out.append("\n\n").append(memory);
        }
        return out.toString();
    }

    private static String renderBlock(String target, String header, int limit) {
        List<String> entries = parseEntries(readRaw(target));
        if (entries.isEmpty()) return "";
        List<String> safe = new ArrayList<>();
        for (String entry : entries) {
            String clean = sanitizeForPrompt(entry, fileFor(target).getName(), limit);
            if (!TextUtils.isEmpty(clean.trim())) safe.add(clean.trim());
        }
        if (safe.isEmpty()) return "";
        String content = String.join(ENTRY_DELIMITER, safe);
        int pct = limit <= 0 ? 0 : Math.min(100, (int) ((content.length() * 100L) / limit));
        String sep = "══════════════════════════════════════════════";
        return sep + "\n" + header + " [" + pct + "% — " + content.length() + "/" + limit + " chars]\n" + sep + "\n" + content;
    }

    /** Model-facing tool. Hermes built-in memory has only two targets: memory/user. */
    public static synchronized String tool(JSONObject args) {
        return tool(args, true, true);
    }

    public static synchronized String tool(JSONObject args, boolean memoryEnabled, boolean userEnabled) {
        ensureDefaults();
        String target = normalizeMemoryTarget(args == null ? null : args.optString("target", TARGET_MEMORY));
        if (target == null) return error("Invalid memory target. Use memory or user. SOUL.md is edited separately by the user.").toString();
        if (TARGET_MEMORY.equals(target) && !memoryEnabled) return error("Built-in MEMORY.md writes are disabled.").toString();
        if (TARGET_USER.equals(target) && !userEnabled) return error("Built-in USER.md writes are disabled.").toString();
        try {
            JSONArray ops = args == null ? null : args.optJSONArray("operations");
            if (ops != null && ops.length() > 0) return applyBatch(target, ops).toString();
            String action = args == null ? "" : args.optString("action", "");
            String content = args == null ? null : firstNonEmpty(args.optString("content", null), args.optString("new_text", null));
            String oldText = args == null ? null : args.optString("old_text", null);
            if ("add".equals(action)) return add(target, content).toString();
            if ("replace".equals(action)) return replace(target, oldText, content).toString();
            if ("remove".equals(action)) return remove(target, oldText).toString();
            return error("Unknown action. Use add, replace, remove, or operations.").toString();
        } catch (Exception e) {
            return error("Memory write failed: " + e.getMessage()).toString();
        }
    }

    public static synchronized JSONObject status() {
        ensureDefaults();
        JSONObject o = new JSONObject();
        try {
            o.put("success", true);
            o.put("soul", storeStatus(TARGET_SOUL));
            o.put("user", storeStatus(TARGET_USER));
            o.put("memory", storeStatus(TARGET_MEMORY));
            o.put("pending_count", pendingWrites().length());
        } catch (Exception ignored) {}
        return o;
    }

    public static synchronized JSONObject reset(String target) {
        ensureDefaults();
        String clean = normalizeMemoryTarget(target);
        if (clean == null) return error("Reset target must be memory or user.");
        writeTextAtomic(fileFor(clean), "");
        return ok(clean, "reset", 0, limitFor(clean), 0);
    }

    private static JSONObject storeStatus(String target) throws Exception {
        JSONObject o = new JSONObject();
        String raw = readRaw(target);
        o.put("path", fileFor(target).getAbsolutePath());
        o.put("chars", raw.length());
        o.put("limit", limitFor(target));
        o.put("entry_count", TARGET_SOUL.equals(target) ? (TextUtils.isEmpty(raw.trim()) ? 0 : 1) : parseEntries(raw).size());
        return o;
    }

    public static synchronized JSONObject stageWrite(JSONObject args, String origin) {
        ensureDefaults();
        JSONObject item = new JSONObject();
        try {
            String id = "mem-" + System.currentTimeMillis() + "-" + Math.abs((args == null ? "" : args.toString()).hashCode());
            item.put("id", id);
            item.put("origin", origin == null ? "agent" : origin);
            item.put("created_at", System.currentTimeMillis());
            item.put("args", args == null ? new JSONObject() : args);
            writeTextAtomic(new File(pendingDir(), id + ".json"), item.toString(2));
            item.put("success", true);
            item.put("message", "Memory write staged for approval.");
        } catch (Exception e) {
            return error("Could not stage memory write: " + e.getMessage());
        }
        return item;
    }

    public static synchronized JSONArray pendingWrites() {
        ensureDefaults();
        JSONArray out = new JSONArray();
        File[] files = pendingDir().listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) return out;
        for (File f : files) {
            try { out.put(new JSONObject(readFile(f))); } catch (Exception ignored) {}
        }
        return out;
    }

    public static synchronized JSONObject approvePending(String id) {
        return approvePending(id, true, true);
    }

    public static synchronized JSONObject approvePending(String id, boolean memoryEnabled, boolean userEnabled) {
        ensureDefaults();
        File f = new File(pendingDir(), id + ".json");
        if (!f.exists()) return error("No staged memory write found for " + id);
        try {
            JSONObject item = new JSONObject(readFile(f));
            JSONObject result = new JSONObject(tool(item.optJSONObject("args"), memoryEnabled, userEnabled));
            if (result.optBoolean("success")) //noinspection ResultOfMethodCallIgnored
                f.delete();
            return result;
        } catch (Exception e) {
            return error("Could not approve staged memory write: " + e.getMessage());
        }
    }

    public static synchronized JSONObject rejectPending(String id) {
        ensureDefaults();
        File f = new File(pendingDir(), id + ".json");
        if (!f.exists()) return error("No staged memory write found for " + id);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
        return successMessage("Rejected staged memory write " + id);
    }

    private static JSONObject applyBatch(String target, JSONArray ops) {
        if (ops == null || ops.length() == 0) return error("operations list is empty.");
        return withFileLock(target, () -> {
            String raw = readFileStrict(fileFor(target));
            if (raw == null) return unreadableError(target);
            JSONObject drift = driftErrorIfNeeded(target, raw);
            if (drift != null) return drift;
            List<String> working = parseEntries(raw);
            for (int i = 0; i < ops.length(); i++) {
                JSONObject op = ops.optJSONObject(i);
                if (op == null) return batchError(target, "Operation " + (i + 1) + ": operation must be an object.");
                String action = op.optString("action", "");
                String content = firstNonEmpty(op.optString("content", null), op.optString("new_text", null));
                String oldText = op.optString("old_text", null);
                String threat = ("add".equals(action) || "replace".equals(action)) ? threatMessage(content) : null;
                if (threat != null) return batchError(target, "Operation " + (i + 1) + ": " + threat);
                JSONObject res = applyToList(working, action, oldText, content, target, true);
                if (!res.optBoolean("success")) return res;
            }
            working = dedupe(working);
            int limit = limitFor(target);
            String serialized = String.join(ENTRY_DELIMITER, working);
            if (serialized.length() > limit) {
                return consolidationFailure(errorWithInventory(
                    "After applying all " + ops.length() + " operations, memory would be at " + serialized.length() + "/" + limit +
                        " chars — over the limit. Remove or shorten more entries in the same batch, then retry.",
                    parseEntries(raw), target));
            }
            writeTextAtomic(fileFor(target), serialized);
            return ok(target, "batch", serialized.length(), limit, working.size());
        });
    }

    private static JSONObject add(String target, String content) {
        String clean = content == null ? "" : content.trim();
        if (TextUtils.isEmpty(clean)) return error("Content cannot be empty.");
        String threat = threatMessage(clean);
        if (threat != null) return error(threat);
        return withFileLock(target, () -> {
            String raw = readFileStrict(fileFor(target));
            if (raw == null) return unreadableError(target);
            List<String> entries = parseEntries(raw);
            if (entries.contains(clean)) return ok(target, "duplicate", serializedLength(entries), limitFor(target), entries.size());
            entries.add(clean);
            int total = serializedLength(entries);
            int limit = limitFor(target);
            if (total > limit) {
                return consolidationFailure(errorWithInventory(
                    "Memory at " + serializedLength(parseEntries(raw)) + "/" + limit +
                        " chars. Adding this entry (" + clean.length() + " chars) would exceed the limit. Consolidate now with one operations batch.",
                    parseEntries(raw), target));
            }
            entries = dedupe(entries);
            writeTextAtomic(fileFor(target), String.join(ENTRY_DELIMITER, entries));
            return ok(target, "add", serializedLength(entries), limit, entries.size());
        });
    }

    private static JSONObject replace(String target, String oldText, String content) {
        return withFileLock(target, () -> {
            String raw = readFileStrict(fileFor(target));
            if (raw == null) return unreadableError(target);
            JSONObject drift = driftErrorIfNeeded(target, raw);
            if (drift != null) return drift;
            List<String> entries = parseEntries(raw);
            JSONObject res = applyToList(entries, "replace", oldText, content, target, false);
            if (!res.optBoolean("success")) return res;
            entries = dedupe(entries);
            int total = serializedLength(entries);
            int limit = limitFor(target);
            if (total > limit) return consolidationFailure(errorWithInventory("Replacement would exceed the " + limit + " character limit.", parseEntries(raw), target));
            writeTextAtomic(fileFor(target), String.join(ENTRY_DELIMITER, entries));
            return ok(target, "replace", total, limit, entries.size());
        });
    }

    private static JSONObject remove(String target, String oldText) {
        return withFileLock(target, () -> {
            String raw = readFileStrict(fileFor(target));
            if (raw == null) return unreadableError(target);
            JSONObject drift = driftErrorIfNeeded(target, raw);
            if (drift != null) return drift;
            List<String> entries = parseEntries(raw);
            JSONObject res = applyToList(entries, "remove", oldText, null, target, false);
            if (!res.optBoolean("success")) return res;
            int total = serializedLength(entries);
            writeTextAtomic(fileFor(target), String.join(ENTRY_DELIMITER, entries));
            return ok(target, "remove", total, limitFor(target), entries.size());
        });
    }

    private static JSONObject applyToList(List<String> entries, String action, String oldText, String content, String target, boolean batch) {
        if ("add".equals(action)) {
            String clean = content == null ? "" : content.trim();
            if (TextUtils.isEmpty(clean)) return batch ? batchError(target, "add requires content.") : error("Content cannot be empty.");
            if (!entries.contains(clean)) entries.add(clean);
            return success();
        }
        String needle = oldText == null ? "" : oldText.trim();
        if (TextUtils.isEmpty(needle)) return errorWithInventory(action + " requires old_text: a short unique substring of the entry to change.", entries, target);
        List<Integer> matches = new ArrayList<>();
        LinkedHashSet<String> distinct = new LinkedHashSet<>();
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).contains(needle)) {
                matches.add(i);
                distinct.add(entries.get(i));
            }
        }
        if (matches.isEmpty()) return consolidationFailure(errorWithInventory("No entry matched '" + needle + "'. Check current_entries and retry with exact text.", entries, target));
        if (distinct.size() > 1) return errorWithInventory("Multiple entries matched '" + needle + "'. Be more specific.", entries, target);
        int idx = matches.get(0);
        if ("replace".equals(action)) {
            String clean = content == null ? "" : content.trim();
            if (TextUtils.isEmpty(clean)) return error("Replacement content cannot be empty. Use remove to delete entries.");
            String threat = threatMessage(clean);
            if (threat != null) return error(threat);
            entries.set(idx, clean);
            return success();
        }
        if ("remove".equals(action)) {
            entries.remove(idx);
            return success();
        }
        return error("Unknown action. Use add, replace, or remove.");
    }

    private static JSONObject batchError(String target, String message) {
        return errorWithInventory(message + " No operations were applied (batch is all-or-nothing).", parseEntries(readRaw(target)), target);
    }

    private static JSONObject consolidationFailure(JSONObject response) {
        sConsolidationFailures += 1;
        if (sConsolidationFailures <= MAX_CONSOLIDATION_FAILURES_PER_TURN) return response;
        JSONObject o = error("Memory consolidation failed " + sConsolidationFailures + " times this turn. Stop retrying memory calls; continue the reply and save later.");
        try { o.put("done", true); } catch (Exception ignored) {}
        return o;
    }

    private static JSONObject errorWithInventory(String message, List<String> entries, String target) {
        JSONObject o = error(message);
        try {
            o.put("current_entries", new JSONArray(entries));
            o.put("usage", serializedLength(entries) + "/" + limitFor(target));
        } catch (Exception ignored) {}
        return o;
    }

    private static JSONObject withFileLock(String target, LockedMutation mutation) {
        File path = fileFor(target);
        File lockPath = new File(path.getParentFile(), path.getName() + ".lock");
        try {
            if (lockPath.getParentFile() != null) lockPath.getParentFile().mkdirs();
            try (RandomAccessFile raf = new RandomAccessFile(lockPath, "rw");
                 FileChannel channel = raf.getChannel();
                 FileLock ignored = channel.lock()) {
                return mutation.run();
            }
        } catch (Exception e) {
            return error("Could not lock " + label(target) + " for memory write: " + e.getMessage());
        }
    }

    private static JSONObject unreadableError(String target) {
        return error("Refusing to write " + label(target) + ": the file exists but could not be read as strict UTF-8. Treating it as empty would wipe memory, so nothing changed.");
    }

    private static JSONObject driftErrorIfNeeded(String target, String raw) {
        if (TextUtils.isEmpty(raw == null ? "" : raw.trim())) return null;
        List<String> parsed = parseEntries(raw);
        String roundTrip = String.join(ENTRY_DELIMITER, parsed);
        int maxEntry = 0;
        for (String entry : parsed) maxEntry = Math.max(maxEntry, entry == null ? 0 : entry.length());
        boolean drift = !raw.trim().equals(roundTrip) || maxEntry > limitFor(target);
        if (!drift) return null;
        File path = fileFor(target);
        File backup = new File(path.getParentFile(), path.getName() + ".bak." + (System.currentTimeMillis() / 1000L));
        writeTextAtomic(backup, raw);
        JSONObject o = error("Refusing to rewrite " + label(target) + ": the file no longer round-trips as clean §-delimited memory. A backup was saved; resolve the external/manual edit before retrying to avoid silent data loss.");
        try {
            o.put("drift_backup", backup.getAbsolutePath());
            o.put("current_entries", new JSONArray(parsed));
        } catch (Exception ignored) {}
        return o;
    }

    private static String normalizeMemoryTarget(String target) {
        String t = target == null ? TARGET_MEMORY : target.trim().toLowerCase(Locale.US);
        if (TARGET_MEMORY.equals(t) || TARGET_USER.equals(t)) return t;
        return null;
    }

    private static String normalizeAnyTarget(String target) {
        String t = target == null ? TARGET_MEMORY : target.trim().toLowerCase(Locale.US);
        if (TARGET_SOUL.equals(t) || TARGET_MEMORY.equals(t) || TARGET_USER.equals(t)) return t;
        return null;
    }

    private static String label(String target) {
        if (TARGET_SOUL.equals(target)) return "SOUL.md";
        if (TARGET_USER.equals(target)) return "USER.md";
        return "MEMORY.md";
    }

    private static List<String> parseEntries(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        String[] parts = raw.replace("\r\n", "\n").replace('\r', '\n').split("\\n§\\n");
        for (String p : parts) {
            String clean = p == null ? "" : p.trim();
            if (!clean.isEmpty()) out.add(clean);
        }
        return dedupe(out);
    }

    private static List<String> dedupe(List<String> entries) {
        return new ArrayList<>(new LinkedHashSet<>(entries));
    }

    private static int serializedLength(List<String> entries) {
        return entries == null || entries.isEmpty() ? 0 : String.join(ENTRY_DELIMITER, entries).length();
    }

    private static String sanitizeForPrompt(String text, String filename, int limit) {
        if (text == null) return "";
        String clean = text.length() <= limit ? text : text.substring(0, limit);
        String threat = threatMessage(clean);
        if (threat != null) return "[BLOCKED: " + filename + " entry contained a prompt-injection pattern. The raw file is preserved; edit Memory to remove it.]";
        return clean;
    }

    private static String threatMessage(String text) {
        if (TextUtils.isEmpty(text)) return null;
        String raw = text.length() > MAX_SCAN_CHARS ? text.substring(0, MAX_SCAN_CHARS) : text;
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (isInvisibleInjectionChar(ch)) {
                return "Blocked: content contains invisible unicode character U+" + String.format(Locale.US, "%04X", (int) ch) + " (possible injection).";
            }
        }
        String normalised = Normalizer.normalize(raw, Normalizer.Form.NFKC);
        for (ThreatPattern threat : STRICT_THREAT_PATTERNS) {
            if (threat.pattern.matcher(normalised).find()) {
                return "Blocked: content matches threat pattern '" + threat.id + "'. Content is injected into the system prompt and must not contain injection or exfiltration payloads.";
            }
        }
        return null;
    }

    private static boolean isInvisibleInjectionChar(char ch) {
        return ch == '\u200b'
            || ch == '\u200c'
            || ch == '\u200d'
            || ch == '\u2060'
            || ch == '\u2062'
            || ch == '\u2063'
            || ch == '\u2064'
            || ch == '\ufeff'
            || ch == '\u202a'
            || ch == '\u202b'
            || ch == '\u202c'
            || ch == '\u202d'
            || ch == '\u202e'
            || ch == '\u2066'
            || ch == '\u2067'
            || ch == '\u2068'
            || ch == '\u2069';
    }

    private static String firstNonEmpty(String a, String b) {
        return !TextUtils.isEmpty(a) ? a : b;
    }

    private static JSONObject ok(String target, String action, int chars, int limit, int entryCount) {
        JSONObject o = new JSONObject();
        try {
            int pct = limit <= 0 ? 0 : Math.min(100, (int) ((chars * 100L) / limit));
            o.put("success", true);
            o.put("done", true);
            o.put("target", target);
            o.put("action", action);
            o.put("usage", pct + "% — " + chars + "/" + limit + " chars");
            o.put("entry_count", entryCount);
            o.put("message", label(target) + " saved. Changes affect the next session snapshot.");
            o.put("note", "Write saved. This update is complete — do not repeat it.");
            sConsolidationFailures = 0;
        } catch (Exception ignored) {}
        return o;
    }

    private static JSONObject error(String message) {
        JSONObject o = new JSONObject();
        try { o.put("success", false).put("error", message == null ? "memory error" : message); } catch (Exception ignored) {}
        return o;
    }

    private static JSONObject success() {
        JSONObject o = new JSONObject();
        try { o.put("success", true); } catch (Exception ignored) {}
        return o;
    }

    private static JSONObject successMessage(String message) {
        JSONObject o = success();
        try { o.put("done", true).put("message", message); } catch (Exception ignored) {}
        return o;
    }

    private static String readFile(File file) {
        try {
            if (!file.exists()) return "";
            byte[] data = new byte[(int) file.length()];
            try (FileInputStream in = new FileInputStream(file)) {
                int off = 0;
                while (off < data.length) {
                    int n = in.read(data, off, data.length - off);
                    if (n < 0) break;
                    off += n;
                }
            }
            return new String(data, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static String readFileStrict(File file) {
        try {
            if (!file.exists()) return "";
            byte[] data = new byte[(int) file.length()];
            try (FileInputStream in = new FileInputStream(file)) {
                int off = 0;
                while (off < data.length) {
                    int n = in.read(data, off, data.length - off);
                    if (n < 0) break;
                    off += n;
                }
            }
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(data))
                .toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeTextAtomic(File file, String text) {
        try {
            File parent = file.getParentFile();
            if (parent != null) parent.mkdirs();
            File tmp = new File(parent, file.getName() + ".tmp." + Thread.currentThread().getId());
            try (FileOutputStream out = new FileOutputStream(tmp, false)) {
                out.write((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            if (!tmp.renameTo(file)) {
                try (FileOutputStream out = new FileOutputStream(file, false)) {
                    out.write((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
                    out.getFD().sync();
                }
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        } catch (Exception ignored) {}
    }
}
