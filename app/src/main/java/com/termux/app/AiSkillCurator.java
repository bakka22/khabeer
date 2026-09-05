package com.termux.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Deterministic library janitor (Hermes curator port, no-LLM pass).
 *
 * Walks curator-eligible skills and moves longer-unused ones to the
 * archive (restorable from the Skills page). Stale is a derived display
 * state (unused past the stale threshold); archived is an action.
 *
 * Eligibility: installed and review-created skills. Exempt: pinned
 * skills, bundled (seeded) skills, disabled skills are still checked
 * (disabling is not archiving).
 *
 * First observation seeds last_run_at and defers one full interval —
 * never mutate the library on first sight. Runs on service start when
 * due, plus manual and dry-run triggers from the Skills page.
 */
public final class AiSkillCurator {

    private AiSkillCurator() {}

    private static File stateFile() {
        return new File(AiSkillRegistry.skillsRoot(), ".curator-state.json");
    }

    public static synchronized JSONObject loadState() {
        JSONObject base = new JSONObject();
        try {
            base.put("last_run_at", JSONObject.NULL);
            base.put("run_count", 0);
            base.put("last_summary", "");
            File f = stateFile();
            if (!f.isFile()) return base;
            String raw = readAll(f);
            if (raw == null || raw.trim().isEmpty()) return base;
            JSONObject stored = new JSONObject(raw);
            if (stored.has("last_run_at")) base.put("last_run_at", stored.opt("last_run_at"));
            if (stored.has("run_count")) base.put("run_count", stored.optInt("run_count", 0));
            if (stored.has("last_summary")) base.put("last_summary", stored.optString("last_summary", ""));
        } catch (Exception ignored) {}
        return base;
    }

    private static synchronized void saveState(JSONObject state) {
        try {
            File f = stateFile();
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp, false)) {
                out.write(state.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(f)) {
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(f, false)) {
                    out.write(state.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        } catch (Exception ignored) {}
    }

    private static String readAll(File f) {
        try {
            byte[] data = new byte[(int) f.length()];
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                int off = 0;
                while (off < data.length) {
                    int n = in.read(data, off, data.length - off);
                    if (n < 0) break;
                    off += n;
                }
            }
            return new String(data, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /** True when a pass is due. First observation seeds and defers. */
    public static synchronized boolean shouldRun(AiProviderConfig config) {
        if (config == null || !config.isCuratorEnabled()) return false;
        JSONObject state = loadState();
        if (state.isNull("last_run_at")) {
            try {
                state.put("last_run_at", System.currentTimeMillis());
                state.put("last_summary", "First observation — library check deferred one full interval.");
                saveState(state);
            } catch (Exception ignored) {}
            return false;
        }
        long elapsed = System.currentTimeMillis() - state.optLong("last_run_at", 0);
        return elapsed >= config.getCuratorIntervalDays() * 86400000L;
    }

    /** Automatic trigger for service start (background thread, file ops
     * only — no model calls). run() persists its own state. */
    public static void maybeRun(AiProviderConfig config) {
        if (config == null) return;
        final AiProviderConfig cfg = config;
        if (!shouldRun(cfg)) return;
        Thread t = new Thread(() -> {
            try {
                run(cfg, false);
            } catch (Exception ignored) {}
        }, "khabeer-curator");
        t.setDaemon(true);
        t.start();
    }

    /** Runs one pass. Dry-run previews without moving anything. */
    public static synchronized JSONObject run(AiProviderConfig config, boolean dryRun) {
        JSONObject report = new JSONObject();
        JSONArray stale = new JSONArray();
        JSONArray archived = new JSONArray();
        JSONArray skipped = new JSONArray();
        int checked = 0;
        try {
            long now = System.currentTimeMillis();
            long staleMs = (config == null ? 30 : config.getCuratorStaleDays()) * 86400000L;
            long archiveMs = (config == null ? 90 : config.getCuratorArchiveDays()) * 86400000L;
            List<AiSkillRegistry.Skill> skills = AiSkillRegistry.listSkills();
            for (AiSkillRegistry.Skill skill : skills) {
                if (skill == null || skill.name == null) continue;
                if (AiSkillRegistry.isPinned(skill.name)) {
                    skipped.put(skill.name + " (pinned)");
                    continue;
                }
                if (AiSkillRegistry.isSeeded(skill.name)) continue;
                checked++;
                // No activity record means never observably used (e.g. just
                // installed): exempt until first use, never presume stale.
                long last = AiSkillRegistry.skillLastActivity(skill.name);
                if (last <= 0) continue;
                long idle = now - last;
                if (idle >= archiveMs) {
                    if (dryRun) {
                        archived.put(skill.name + " (would archive)");
                    } else {
                        JSONObject res = AiSkillRegistry.archiveSkill(skill.name);
                        if (res.optBoolean("success")) archived.put(skill.name);
                        else skipped.put(skill.name + " (archive failed)");
                    }
                } else if (idle >= staleMs) {
                    stale.put(skill.name);
                }
            }
            report.put("success", true);
            report.put("dry_run", dryRun);
            report.put("checked", checked);
            report.put("stale", stale);
            report.put("archived", archived);
            report.put("skipped", skipped);
            if (!dryRun) {
                JSONObject state = loadState();
                state.put("last_run_at", now);
                state.put("run_count", state.optInt("run_count", 0) + 1);
                state.put("last_summary", summarize(report));
                saveState(state);
            }
        } catch (Exception e) {
            try { report.put("success", false).put("error", e.getMessage()); } catch (Exception ignored) {}
        }
        return report;
    }

    private static String summarize(JSONObject report) {
        int stale = report.optJSONArray("stale") == null ? 0 : report.optJSONArray("stale").length();
        int archived = report.optJSONArray("archived") == null ? 0 : report.optJSONArray("archived").length();
        int checked = report.optInt("checked", 0);
        if (stale == 0 && archived == 0) return "Checked " + checked + " skills — nothing stale.";
        return "Checked " + checked + ": " + stale + " stale, " + archived + " archived.";
    }

    /** Skills currently past the stale threshold (display badges). */
    public static synchronized List<String> staleSkills(AiProviderConfig config) {
        List<String> out = new ArrayList<>();
        try {
            long now = System.currentTimeMillis();
            long staleMs = (config == null ? 30 : config.getCuratorStaleDays()) * 86400000L;
            for (AiSkillRegistry.Skill skill : AiSkillRegistry.listSkills()) {
                if (skill == null || skill.name == null) continue;
                if (AiSkillRegistry.isPinned(skill.name) || AiSkillRegistry.isSeeded(skill.name)) continue;
                long last = AiSkillRegistry.skillLastActivity(skill.name);
                if (last <= 0) continue;
                if (now - last >= staleMs) out.add(skill.name);
            }
        } catch (Exception ignored) {}
        return out;
    }

    /** Skills past the archive threshold (shown with emphasis, not auto-run). */
    public static synchronized List<String> archiveCandidates(AiProviderConfig config) {
        List<String> out = new ArrayList<>();
        try {
            long now = System.currentTimeMillis();
            long archiveMs = (config == null ? 90 : config.getCuratorArchiveDays()) * 86400000L;
            for (AiSkillRegistry.Skill skill : AiSkillRegistry.listSkills()) {
                if (skill == null || skill.name == null) continue;
                if (AiSkillRegistry.isPinned(skill.name) || AiSkillRegistry.isSeeded(skill.name)) continue;
                long last = AiSkillRegistry.skillLastActivity(skill.name);
                if (last <= 0) continue;
                if (now - last >= archiveMs) out.add(skill.name);
            }
        } catch (Exception ignored) {}
        return out;
    }
}
