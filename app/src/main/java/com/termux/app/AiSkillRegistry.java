package com.termux.app;

import android.content.Context;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import com.termux.shared.termux.TermuxConstants;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Skills registry (katheer skills_tool/skill_utils port).
 *
 * A skill is a directory containing SKILL.md (YAML frontmatter + markdown
 * body) plus optional support dirs (references/templates/assets/scripts).
 * Skills live at $HOME/.katheer/skills — one folder per skill, optionally
 * grouped by a category folder, so they can be synced with git.
 *
 * Progressive disclosure: only a name+description index reaches the system
 * prompt; the full SKILL.md is loaded on demand through skill_view. Scripts
 * are never executed by the registry — the agent runs them through the
 * terminal tool with absolute paths under the skill directory.
 *
 * All methods are static + synchronized: the service (worker threads) and the
 * UI share one scan cache. The disabled set lives in .disabled.json next to
 * the skills so the filesystem stays the single source of truth.
 */
public final class AiSkillRegistry {

    private static final long CACHE_TTL_MS = 15_000;
    private static final int MAX_NAME_LENGTH = 64;
    private static final int MAX_DESCRIPTION_LENGTH = 1024;
    private static final int PROMPT_DESCRIPTION_LIMIT = 60;
    private static final int MAX_SKILL_FILE_BYTES = 1_048_576;
    private static final int MAX_WALK_DEPTH = 4;

    /** Support dirs inside a skill directory are data, never skills themselves. */
    private static final Set<String> SUPPORT_DIRS = new HashSet<>(Arrays.asList(
        "references", "templates", "assets", "scripts", "examples"));

    private static final Set<String> EXCLUDED_DIRS = new HashSet<>(Arrays.asList(
        ".git", ".github", ".hub", ".archive", ".venv", "venv", "node_modules",
        "site-packages", "__pycache__", ".tox", ".nox", ".pytest_cache",
        ".mypy_cache", ".ruff_cache", "build", "dist"));

    private static volatile List<Skill> sCache;
    private static volatile String sPromptCache;
    private static volatile long sCacheStamp;

    private AiSkillRegistry() {}

    public static final class Skill {
        public String name;
        public String dirName;
        public String category;   // "general" for root-level skills
        public String relPath;    // e.g. "dev/git-workflow"
        public String description = "";
        public String version = "";
        public String author = "";
        public String license = "";
        public List<String> platforms = new ArrayList<>();
        public boolean platformSupported = true;
        public boolean hasFrontmatter;
        public File dir;
        public File skillMd;
        public long mtime;
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    public static File skillsRoot() {
        return new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".katheer/skills");
    }

    private static File dataRoot() {
        return new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".katheer");
    }

    /** Pre-branding data root (skills, MCP helpers); migrated once to .katheer. */
    private static File legacyDataRoot() {
        return new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".termuxAI");
    }

    /** Pre-.termuxAI location; migrated once to the katheer root. */
    private static File legacySkillsRoot() {
        return new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".hermes/skills");
    }

    private static File disabledFile() {
        return new File(skillsRoot(), ".disabled.json");
    }

    // ------------------------------------------------------------------
    // Scanning
    // ------------------------------------------------------------------

    public static synchronized void invalidate() {
        sCache = null;
        sPromptCache = null;
    }

    public static synchronized List<Skill> listSkills() {
        long now = System.currentTimeMillis();
        List<Skill> cached = sCache;
        if (cached != null && now - sCacheStamp < CACHE_TTL_MS) return cached;
        List<Skill> scanned = scan();
        sCache = scanned;
        sCacheStamp = now;
        sPromptCache = null;
        return scanned;
    }

    private static List<Skill> scan() {
        List<Skill> out = new ArrayList<>();
        File root = skillsRoot();
        File[] entries = root.listFiles();
        if (entries == null) return out;
        Arrays.sort(entries, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File entry : entries) walk(entry, 0, "", out);
        // First-wins on duplicate names (root-level beats categorized on ties).
        Set<String> seen = new HashSet<>();
        List<Skill> deduped = new ArrayList<>();
        for (Skill skill : out) {
            if (TextUtils.isEmpty(skill.name) || !seen.add(skill.name)) continue;
            skill.platformSupported = platformSupported(skill.platforms);
            skill.description = clamp(skill.description, MAX_DESCRIPTION_LENGTH);
            deduped.add(skill);
        }
        Collections.sort(deduped, (a, b) -> {
            int byCategory = a.category.compareToIgnoreCase(b.category);
            return byCategory != 0 ? byCategory : a.name.compareToIgnoreCase(b.name);
        });
        return deduped;
    }

    private static void walk(File dir, int depth, String category, List<Skill> out) {
        if (depth > MAX_WALK_DEPTH || dir == null || !dir.isDirectory()) return;
        String name = dir.getName();
        if (EXCLUDED_DIRS.contains(name)) return;
        File skillMd = new File(dir, "SKILL.md");
        if (skillMd.isFile()) {
            out.add(parseSkill(dir, skillMd, category));
            return; // never descend into a skill (support dirs are not skills)
        }
        File[] children = dir.listFiles();
        if (children == null) return;
        Arrays.sort(children, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File child : children) {
            if (!child.isDirectory()) continue;
            // A top-level container dir IS the category layer; deeper levels inherit it.
            String childCategory = depth == 0 ? name : category;
            walk(child, depth + 1, childCategory, out);
        }
    }

    private static Skill parseSkill(File dir, File skillMd, String category) {
        Skill skill = new Skill();
        skill.dir = dir;
        skill.skillMd = skillMd;
        skill.dirName = dir.getName();
        skill.mtime = skillMd.lastModified();
        skill.category = TextUtils.isEmpty(category) ? "general" : category;
        String rel = skillsRoot().toPath().relativize(dir.toPath()).toString().replace(File.separatorChar, '/');
        skill.relPath = rel;

        String text = readFile(skillMd, 512 * 1024);
        String[] parts = splitFrontmatter(text);
        if (parts != null) {
            skill.hasFrontmatter = true;
            parseFrontmatter(parts[0], skill);
        }
        skill.name = skill.name != null ? skill.name : skill.dirName;
        if (skill.name.length() > MAX_NAME_LENGTH) skill.name = skill.name.substring(0, MAX_NAME_LENGTH);
        return skill;
    }

    /** Returns {frontmatter, body} or null when the file has no --- fence. */
    @Nullable
    private static String[] splitFrontmatter(@Nullable String text) {
        if (text == null) return null;
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        String trimmed = text.trim();
        if (!trimmed.startsWith("---")) return null;
        int lineEnd = trimmed.indexOf('\n');
        if (lineEnd < 0) return null;
        String rest = trimmed.substring(lineEnd + 1);
        int close = -1;
        java.util.regex.Matcher matcher = java.util.regex.Pattern
            .compile("(?m)^---\\s*$").matcher(rest);
        if (matcher.find()) close = matcher.start();
        if (close < 0) return null;
        return new String[]{rest.substring(0, close), rest.substring(matcher.end())};
    }

    /**
     * Minimal frontmatter parser: top-level `key: value` lines with optional
     * quotes and inline lists ([a, b] or a, b). The same naive fallback katheer
     * uses when a full YAML loader is unavailable.
     */
    private static void parseFrontmatter(String yaml, Skill skill) {
        for (String rawLine : yaml.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("-")) continue;
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String key = line.substring(0, colon).trim().toLowerCase(Locale.US);
            String value = stripQuotes(line.substring(colon + 1).trim());
            if (value.isEmpty()) continue;
            switch (key) {
                case "name": skill.name = value; break;
                case "description": skill.description = value; break;
                case "version": skill.version = value; break;
                case "author": skill.author = value; break;
                case "license": skill.license = value; break;
                case "platforms": skill.platforms = parseList(value); break;
                default: break;
            }
        }
    }

    private static List<String> parseList(String value) {
        String clean = value.startsWith("[") && value.endsWith("]")
            ? value.substring(1, value.length() - 1) : value;
        List<String> out = new ArrayList<>();
        for (String part : clean.split(",")) {
            String item = stripQuotes(part.trim());
            if (!item.isEmpty()) out.add(item.toLowerCase(Locale.US));
        }
        return out;
    }

    private static String stripQuotes(String value) {
        if (value == null) return "";
        String v = value.trim();
        if (v.length() >= 2 && ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'"))))
            return v.substring(1, v.length() - 1).trim();
        return v;
    }

    /** katheer platform gate: empty platforms = all; "linux" matches Android. */
    private static boolean platformSupported(List<String> platforms) {
        if (platforms == null || platforms.isEmpty()) return true;
        for (String p : platforms) {
            String norm = p.trim().toLowerCase(Locale.US);
            if ("linux".equals(norm) || "android".equals(norm)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Disabled set (.disabled.json next to the skills)
    // ------------------------------------------------------------------

    public static synchronized Set<String> readDisabled() {
        Set<String> out = new LinkedHashSet<>();
        File file = disabledFile();
        String text = readFile(file, 64 * 1024);
        if (TextUtils.isEmpty(text)) return out;
        try {
            JSONArray array = new JSONArray(text);
            for (int i = 0; i < array.length(); i++) {
                String name = array.optString(i, "");
                if (!TextUtils.isEmpty(name)) out.add(name);
            }
        } catch (Exception ignored) {}
        return out;
    }

    public static synchronized void setDisabled(Set<String> disabled) {
        try {
            File root = skillsRoot();
            root.mkdirs();
            File file = disabledFile();
            JSONArray array = new JSONArray();
            for (String name : new TreeSet<>(disabled)) array.put(name);
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(array.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {}
        invalidate();
    }

    public static synchronized boolean isEnabled(String name) {
        return !readDisabled().contains(name);
    }

    public static synchronized boolean setEnabled(String name, boolean enabled) {
        if (TextUtils.isEmpty(name)) return false;
        Set<String> disabled = readDisabled();
        if (enabled) disabled.remove(name); else disabled.add(name);
        setDisabled(disabled);
        return true;
    }

    // ------------------------------------------------------------------
    // System-prompt index (progressive disclosure)
    // ------------------------------------------------------------------

    /** The "## Skills" prompt section, or null when there are no usable skills. */
    @Nullable
    public static synchronized String promptSection() {
        List<Skill> skills = listSkills();
        List<Skill> visible = new ArrayList<>();
        for (Skill skill : skills) {
            if (!skill.platformSupported) continue;
            if (!readDisabled().contains(skill.name)) visible.add(skill);
        }
        if (visible.isEmpty()) return null;
        String cached = sPromptCache;
        if (cached != null) return cached;

        StringBuilder sb = new StringBuilder();
        sb.append("## Skills\n")
          .append("Before replying, scan the skills below. If a skill matches or is even partially relevant ")
          .append("to your task, you MUST load it with skill_view(name) and follow its instructions. ")
          .append("Err on the side of loading — it is always better to have context you don't need ")
          .append("than to miss critical steps, pitfalls, or established workflows. ")
          .append("Skills contain specialized knowledge — API endpoints, tool-specific commands, ")
          .append("and proven workflows that outperform general-purpose approaches. Load the skill ")
          .append("even if you think you could handle the task with the terminal tool. ")
          .append("Skills also encode the user's preferred approach, conventions, and quality standards — ")
          .append("load them even for tasks you already know how to do, because the skill defines ")
          .append("how it should be done here.\n")
          .append("After difficult or iterative tasks, offer to save the workflow as a new skill.\n\n")
          .append("<available_skills>\n");
        String currentCategory = null;
        for (Skill skill : visible) {
            if (!skill.category.equals(currentCategory)) {
                currentCategory = skill.category;
                sb.append("  ").append(currentCategory).append(":\n");
            }
            sb.append("    - ").append(skill.name);
            String desc = clamp(skill.description, PROMPT_DESCRIPTION_LIMIT);
            if (!TextUtils.isEmpty(desc)) sb.append(": ").append(desc);
            sb.append('\n');
        }
        sb.append("</available_skills>\n\n")
          .append("Only proceed without loading a skill if genuinely none are relevant to the task.");
        sPromptCache = sb.toString();
        return sPromptCache;
    }

    // ------------------------------------------------------------------
    // skills_list tool
    // ------------------------------------------------------------------

    public static String listTool(@Nullable String category) {
        try {
            JSONArray skills = new JSONArray();
            Set<String> categories = new LinkedHashSet<>();
            Set<String> disabled = readDisabled();
            int count = 0;
            for (Skill skill : listSkills()) {
                if (!skill.platformSupported) continue;
                if (category != null && !category.isEmpty()
                    && !skill.category.equalsIgnoreCase(category.trim())) continue;
                categories.add(skill.category);
                JSONObject row = new JSONObject()
                    .put("name", skill.name)
                    .put("category", skill.category)
                    .put("enabled", !disabled.contains(skill.name));
                if (!TextUtils.isEmpty(skill.description)) row.put("description", skill.description);
                skills.put(row);
                count++;
            }
            JSONArray categoryArray = new JSONArray();
            for (String c : categories) categoryArray.put(c);
            return new JSONObject()
                .put("success", true)
                .put("skills", skills)
                .put("categories", categoryArray)
                .put("count", count)
                .put("hint", "Use skill_view(name) to load a skill's full instructions before following it.")
                .toString();
        } catch (Exception e) {
            return toolError("skills_list failed: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // skill_view tool
    // ------------------------------------------------------------------

    /**
     * @param dedup per-session map of "name|file" -> "mtime:size"; when the
     *        same unchanged file is requested again the stub teaches the model
     *        to reuse the copy already in context (katheer repeat-view dedup).
     */
    public static String viewTool(String name, @Nullable String filePath, @Nullable Map<String, String> dedup) {
        String clean = name == null ? "" : name.trim();
        if (TextUtils.isEmpty(clean)) return toolError("skill_view requires a skill name (use skills_list to see available skills).");
        if (clean.startsWith("/") || clean.startsWith("\\") || clean.contains("\\")
            || hasTraversal(clean)) {
            return toolError("Invalid skill name: '" + clean + "'.");
        }

        Skill target = findSkill(clean);
        if (target == null) {
            List<String> available = new ArrayList<>();
            for (Skill skill : listSkills()) {
                if (available.size() < 20) available.add(skill.name);
            }
            return toolError("Skill not found: '" + clean + "'. Available: "
                + (available.isEmpty() ? "(none installed)" : TextUtils.join(", ", available)) + ".");
        }
        if (!target.platformSupported) {
            return toolError("Skill '" + target.name + "' is not available on this platform (android).");
        }
        if (!isEnabled(target.name)) {
            return toolError("Skill '" + target.name + "' is disabled. Enable it in the Skills & extensions page.");
        }

        if (filePath != null && !filePath.trim().isEmpty()) return viewFile(target, filePath.trim(), dedup);

        try {
            String key = target.name + "|";
            if (dedup != null) {
                String served = target.skillMd.lastModified() + ":" + target.skillMd.length();
                if (served.equals(dedup.get(key))) {
                    return new JSONObject()
                        .put("success", true)
                        .put("status", "unchanged")
                        .put("dedup", true)
                        .put("content_returned", false)
                        .put("message", "This skill's SKILL.md was already provided earlier in this conversation and is unchanged on disk. Reuse the copy already in context; call skill_view again only after the skill changed.")
                        .toString();
                }
                if (dedup.size() > 200) dedup.clear();
                dedup.put(key, served);
            }

            String content = readFile(target.skillMd, MAX_SKILL_FILE_BYTES);
            JSONObject result = new JSONObject()
                .put("success", true)
                .put("name", target.name)
                .put("category", target.category)
                .put("description", descriptionOrEmpty(target))
                .put("content", content)
                .put("path", target.relPath + "/SKILL.md")
                .put("skill_dir", target.dir.getAbsolutePath())
                .put("linked_files", linkedFiles(target))
                .put("usage_hint", "Follow the instructions above. Scripts and supporting files are run/read "
                    + "with the terminal tool using absolute paths. [Skill directory: " + target.dir.getAbsolutePath() + "]");
            if (!TextUtils.isEmpty(target.version)) result.put("version", target.version);
            return result.toString();
        } catch (Exception e) {
            return toolError("Failed to load skill '" + target.name + "': " + e.getMessage());
        }
    }

    private static String viewFile(Skill target, String filePath, @Nullable Map<String, String> dedup) {
        if (hasTraversal(filePath)) return toolError("Invalid file path: '" + filePath + "'.");
        File file = new File(target.dir, filePath);
        try {
            String canonical = file.getCanonicalPath();
            String dirCanonical = target.dir.getCanonicalPath();
            if (!canonical.equals(dirCanonical) && !canonical.startsWith(dirCanonical + File.separator))
                return toolError("File path escapes the skill directory: '" + filePath + "'.");
            if (!file.isFile()) {
                StringBuilder sb = new StringBuilder("File not found: '").append(filePath)
                    .append("'. Available files in this skill:\n");
                JSONObject linked = linkedFiles(target);
                JSONArray names = linked.names();
                if (names != null) {
                    for (int i = 0; i < names.length(); i++) {
                        String group = names.optString(i);
                        JSONArray files = linked.optJSONArray(group);
                        for (int j = 0; files != null && j < files.length(); j++) sb.append("  - ").append(files.optString(j)).append('\n');
                    }
                }
                return toolError(sb.toString().trim());
            }
            if (file.length() > MAX_SKILL_FILE_BYTES) return toolError("File too large: '" + filePath + "'.");

            String key = target.name + "|" + filePath;
            if (dedup != null) {
                String served = file.lastModified() + ":" + file.length();
                if (served.equals(dedup.get(key))) {
                    return new JSONObject()
                        .put("success", true)
                        .put("status", "unchanged")
                        .put("dedup", true)
                        .put("content_returned", false)
                        .put("message", "This file was already provided earlier in this conversation and is unchanged on disk. Reuse the copy already in context.")
                        .toString();
                }
                dedup.put(key, served);
            }

            boolean binary = isBinary(file);
            String content = binary
                ? "[Binary file: " + file.getName() + ", " + file.length() + " bytes]"
                : readFile(file, MAX_SKILL_FILE_BYTES);
            String ext = "";
            int dot = file.getName().lastIndexOf('.');
            if (dot >= 0 && dot < file.getName().length() - 1) ext = file.getName().substring(dot + 1);
            return new JSONObject()
                .put("success", true)
                .put("name", target.name)
                .put("file", filePath)
                .put("file_type", ext)
                .put("is_binary", binary)
                .put("content", content)
                .toString();
        } catch (Exception e) {
            return toolError("Failed to read '" + filePath + "': " + e.getMessage());
        }
    }

    private static JSONObject linkedFiles(Skill target) {
        JSONObject out = new JSONObject();
        for (String group : new String[]{"references", "templates", "scripts", "assets", "examples"}) {
            JSONArray files = new JSONArray();
            File dir = new File(target.dir, group);
            File[] children = dir.listFiles();
            if (children != null) {
                Arrays.sort(children, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
                for (File child : children) {
                    if (child.isFile()) files.put(group + "/" + child.getName());
                }
            }
            if (files.length() > 0) {
                try { out.put(group, files); } catch (Exception ignored) {}
            }
        }
        return out;
    }

    private static Skill findSkill(String clean) {
        File root = skillsRoot();
        // 1. Direct path: root/[category/]name/SKILL.md
        if (!clean.contains("..")) {
            File direct = new File(root, clean.replace('/', File.separatorChar));
            if (new File(direct, "SKILL.md").isFile()) {
                String category = clean.contains("/")
                    ? clean.substring(0, clean.lastIndexOf('/')) : "general";
                return parseSkill(direct, new File(direct, "SKILL.md"), category);
            }
        }
        // 2. Registry match on name, dir name, or relative path.
        for (Skill skill : listSkills()) {
            if (skill.name.equals(clean) || skill.dirName.equals(clean) || skill.relPath.equals(clean))
                return skill;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Seeding (bundled skills copied to the skills root on first run;
    // existing directories are never touched — user edits win)
    // ------------------------------------------------------------------

    public static boolean seedFromAssets(Context context) {
        boolean renamed = migrateKatheerHome();
        try {
            File root = skillsRoot();
            root.mkdirs();
            String[] top = context.getAssets().list("skills");
            if (top == null) return renamed;
            for (String entry : top) copyAssetDir(context, "skills/" + entry, new File(root, entry));
            invalidate();
        } catch (Exception ignored) {}
        return renamed;
    }

    /**
     * One-time home migration to the katheer data root:
     *  1. $HOME/.termuxAI  ->  $HOME/.katheer (whole tree: skills, MCP helpers)
     *  2. $HOME/.hermes/skills -> $HOME/.katheer/skills (oldest layout)
     *  3. stale ".termuxAI" path mentions inside migrated skill files are
     *     rewritten so the agent's guidance keeps pointing at real paths.
     * Returns true when the data root was renamed — callers may need to
     * rewrite stored paths (e.g. stdio MCP server commands).
     */
    public static boolean migrateKatheerHome() {
        boolean renamed = false;
        try {
            File katheer = dataRoot();
            File legacy = legacyDataRoot();
            if (legacy.isDirectory() && !katheer.exists()) {
                renamed = legacy.renameTo(katheer);
                if (!renamed) {
                    // Rename across mount points fails; fall back to a copy.
                    copyDir(legacy, katheer);
                    renamed = katheer.isDirectory();
                    if (renamed) deleteRecursive(legacy);
                }
            }
            katheer.mkdirs();
            File root = skillsRoot();
            File oldest = legacySkillsRoot();
            if (oldest.isDirectory() && !root.exists()) {
                root.getParentFile().mkdirs();
                if (!oldest.renameTo(root)) copyDir(oldest, root);
            }
            rewriteLegacyPaths(katheer);
        } catch (Exception ignored) {}
        return renamed;
    }

    /** Idempotent: rewrites ".termuxAI" path mentions inside markdown skill
     *  files under the katheer root (agent-authored content may embed paths). */
    private static void rewriteLegacyPaths(File dir) {
        if (dir == null || !dir.isDirectory()) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) {
                rewriteLegacyPaths(child);
            } else if (child.isFile() && child.getName().endsWith(".md") && child.length() <= MAX_SKILL_FILE_BYTES) {
                String content = readFile(child, MAX_SKILL_FILE_BYTES);
                if (content != null && content.contains(".termuxAI")) {
                    writeTextFile(child, content.replace(".termuxAI", ".katheer"));
                }
            }
        }
    }

    private static void copyDir(File source, File target) {
        try {
            File[] children = source.listFiles();
            if (children == null) return;
            target.mkdirs();
            for (File child : children) {
                File childTarget = new File(target, child.getName());
                if (child.isDirectory()) copyDir(child, childTarget);
                else copyFile(child, childTarget);
            }
        } catch (Exception ignored) {}
    }

    private static void copyFile(File source, File target) {
        try (FileInputStream in = new FileInputStream(source);
             FileOutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        } catch (Exception ignored) {}
    }

    private static void copyAssetDir(Context context, String assetPath, File target) {
        try {
            if (target.exists()) return; // never overwrite user-visible state
            String[] children = context.getAssets().list(assetPath);
            if (children == null) return;
            if (children.length == 0) return;
            target.mkdirs();
            for (String child : children) {
                File childTarget = new File(target, child);
                String childAsset = assetPath + "/" + child;
                String[] nested = context.getAssets().list(childAsset);
                if (nested != null && nested.length > 0) copyAssetDir(context, childAsset, childTarget);
                else copyAssetFile(context, childAsset, childTarget);
            }
        } catch (Exception ignored) {}
    }

    private static void copyAssetFile(Context context, String assetPath, File target) {
        try {
            target.getParentFile().mkdirs();
            try (InputStream in = context.getAssets().open(assetPath);
                 FileOutputStream out = new FileOutputStream(target)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            }
        } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static String toolError(String message) {
        try {
            return new JSONObject().put("success", false).put("error", message).toString();
        } catch (Exception e) {
            return "{\"success\":false,\"error\":\"skill tool error\"}";
        }
    }

    private static boolean hasTraversal(String value) {
        if (value == null) return true;
        String normalized = value.replace('\\', '/');
        for (String part : normalized.split("/")) {
            if ("..".equals(part)) return true;
        }
        return normalized.contains(":") || normalized.startsWith("~");
    }

    private static boolean isBinary(File file) {
        try (InputStream in = new FileInputStream(file)) {
            byte[] probe = new byte[8000];
            int read = in.read(probe);
            for (int i = 0; i < read; i++) if (probe[i] == 0) return true;
        } catch (Exception ignored) {}
        return false;
    }

    @Nullable
    private static String readFile(File file, int maxBytes) {
        if (file == null || !file.isFile()) return null;
        try (FileInputStream in = new FileInputStream(file)) {
            int limit = (int) Math.min((long) maxBytes, file.length());
            byte[] data = new byte[limit];
            int read = 0;
            while (read < limit) {
                int chunk = in.read(data, read, limit - read);
                if (chunk < 0) break;
                read += chunk;
            }
            return new String(data, 0, read, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static String clamp(String value, int max) {
        if (value == null) return "";
        String clean = value.replace('\n', ' ').trim();
        return clean.length() <= max ? clean : clean.substring(0, max - 1) + "…";
    }

    private static String descriptionOrEmpty(Skill skill) {
        return skill.description == null ? "" : skill.description;
    }

    // ==================================================================
    // skill_manage tool (katheer skill_manager_tool port, essential core)
    // ==================================================================

    private static final int MAX_SKILL_CONTENT_CHARS = 100_000;
    private static final Set<String> ALLOWED_SUBDIRS = new HashSet<>(Arrays.asList(
        "references", "templates", "scripts", "assets"));
    private static final Pattern VALID_NAME_RE = Pattern.compile("^[a-z0-9][a-z0-9._-]*$");
    private static final int MAX_BATCH_OPS = 20;
    /** Actions that mutate skills — each one is approval-gated by the service. */
    public static final Set<String> WRITE_ACTIONS = new HashSet<>(Arrays.asList(
        "create", "patch", "delete", "write_file", "remove_file"));

    private static final String MANAGE_ACTIONS_HINT =
        "create, patch (old_string/new_string targeted fix, or content for a full rewrite), "
            + "delete, write_file, remove_file";

    /**
     * Human-readable gist of a manage operation for the approval dialog.
     * Empty when the payload is malformed (the tool call will error anyway).
     */
    public static String manageGist(@Nullable JSONObject op) {
        if (op == null) return "";
        String action = op.optString("action", "");
        String name = op.optString("name", "");
        StringBuilder gist = new StringBuilder("skill_manage(").append(action);
        if (!TextUtils.isEmpty(name)) gist.append(" '").append(name).append("'");
        gist.append(")");
        if ("create".equals(action) || ("patch".equals(action) && op.has("content"))) {
            gist.append(" — full SKILL.md (").append(op.optString("content", "").length()).append(" chars)");
        } else if ("patch".equals(action)) {
            gist.append(" — replace '").append(clamp(op.optString("old_string", ""), 60))
                .append("' with '").append(clamp(op.optString("new_string", ""), 60)).append("'");
        } else if ("write_file".equals(action) || "remove_file".equals(action)) {
            gist.append(" — ").append(op.optString("file_path", ""));
        } else if ("delete".equals(action)) {
            gist.append(" — removes the skill folder");
        }
        return gist.toString();
    }

    /**
     * skill_manage dispatch. Accepts the operations-array shape (one op per
     * skill; a single edit is a list of one) and the legacy flat shape.
     * Batch semantics follow katheer: delete must be the sole op; all touched
     * skills are snapshotted and rolled back on any failure.
     */
    public static String manageTool(JSONObject args) {
        try {
            if (args.has("operations") && !args.isNull("operations")) {
                JSONArray ops = args.optJSONArray("operations");
                if (ops == null || ops.length() == 0)
                    return toolError("operations must be a non-empty array.");
                if (ops.length() > MAX_BATCH_OPS)
                    return toolError("operations is capped at " + MAX_BATCH_OPS + " ops per call.");
                for (int i = 0; i < ops.length(); i++) {
                    JSONObject op = ops.optJSONObject(i);
                    if (op == null || TextUtils.isEmpty(op.optString("action", "")))
                        return toolError("operations[" + i + "] needs an 'action'.");
                    if (TextUtils.isEmpty(op.optString("name", "")))
                        return toolError("operations[" + i + "] needs a 'name' (the skill it targets).");
                }
                for (int i = 0; i < ops.length(); i++)
                    if ("delete".equals(ops.getJSONObject(i).optString("action")) && ops.length() > 1)
                        return toolError("delete must be the SOLE op in its call — it doesn't compose with other ops' rollback.");
                return runBatch(ops);
            }
            // Legacy flat shape.
            return runOperation(args);
        } catch (Exception e) {
            return toolError("skill_manage failed: " + e.getMessage());
        }
    }

    private static String runBatch(JSONArray ops) throws Exception {
        // Snapshot every touched skill before the first op.
        Map<String, File> snapshots = new java.util.LinkedHashMap<>();
        Map<String, Boolean> existed = new java.util.LinkedHashMap<>();
        for (int i = 0; i < ops.length(); i++) {
            String name = ops.getJSONObject(i).optString("name", "");
            if (snapshots.containsKey(name)) continue;
            Skill pre = findSkill(name);
            existed.put(name, pre != null);
            if (pre != null) {
                File snap = new File(new File(System.getProperty("java.io.tmpdir", "/tmp"), "skill_batch_" + System.nanoTime()), name);
                copyDir(pre.dir, snap);
                snapshots.put(name, snap);
            }
        }
        try {
            JSONArray results = new JSONArray();
            for (int i = 0; i < ops.length(); i++) {
                JSONObject op = ops.getJSONObject(i);
                JSONObject result = new JSONObject(runOperation(op));
                result.put("op", i);
                results.put(result);
                if (!result.optBoolean("success")) {
                    rollbackBatch(snapshots, existed);
                    return new JSONObject()
                        .put("success", false)
                        .put("error", "Batch rolled back after op " + i + ": " + result.optString("error", "unknown error"))
                        .put("partial_results", results)
                        .toString();
                }
            }
            return new JSONObject()
                .put("success", true)
                .put("applied", ops.length())
                .put("results", results)
                .put("hint", "Batch applied atomically. The skills index refreshes on the next turn.")
                .toString();
        } finally {
            for (File snap : snapshots.values()) deleteRecursive(snap.getParentFile());
        }
    }

    private static void rollbackBatch(Map<String, File> snapshots, Map<String, Boolean> existed) {
        for (Map.Entry<String, File> entry : snapshots.entrySet()) {
            Skill cur = findSkill(entry.getKey());
            if (cur != null) deleteRecursive(cur.dir);
            if (Boolean.TRUE.equals(existed.get(entry.getKey()))) {
                File restored = new File(skillsRoot(), entry.getKey());
                copyDir(entry.getValue(), restored);
            }
        }
        invalidate();
    }

    /** One flat (legacy-shape) operation. Returns a JSON result string. */
    private static String runOperation(JSONObject op) {
        try {
            String action = op.optString("action", "");
            String name = op.optString("name", "");
            String content = op.optString("content", null);
            String category = op.optString("category", null);
            String filePath = op.optString("file_path", null);
            String fileContent = op.optString("file_content", null);
            String oldString = op.optString("old_string", null);
            String newString = op.optString("new_string", null);
            boolean replaceAll = op.optBoolean("replace_all", false);
            switch (action == null ? "" : action) {
                case "create":
                    if (TextUtils.isEmpty(content))
                        return toolError("content is required for 'create'. Provide the full SKILL.md text (frontmatter + body).");
                    return createSkill(name, content, category);
                case "patch":
                    if (!TextUtils.isEmpty(content) && (oldString != null || newString != null))
                        return toolError("Pass EITHER content (full SKILL.md rewrite) OR old_string/new_string (targeted replacement), not both.");
                    if (!TextUtils.isEmpty(content)) return editSkill(name, content);
                    return patchSkill(name, oldString, newString, filePath, replaceAll);
                case "delete":
                    return deleteSkill(name);
                case "write_file":
                    if (TextUtils.isEmpty(filePath))
                        return toolError("file_path is required for 'write_file'. Example: 'references/api-guide.md'");
                    if (fileContent == null)
                        return toolError("file_content is required for 'write_file'.");
                    return writeSkillFile(name, filePath, fileContent);
                case "remove_file":
                    if (TextUtils.isEmpty(filePath))
                        return toolError("file_path is required for 'remove_file'.");
                    return removeSkillFile(name, filePath);
                default:
                    return toolError("Unknown action '" + action + "'. Use: " + MANAGE_ACTIONS_HINT);
            }
        } catch (Exception e) {
            return toolError("skill_manage failed: " + e.getMessage());
        }
    }

    // --- validators (katheer _validate_*) ---

    private static String validateName(String name) {
        if (TextUtils.isEmpty(name)) return "Skill name is required.";
        if (name.length() > MAX_NAME_LENGTH) return "Skill name exceeds " + MAX_NAME_LENGTH + " characters.";
        if (!VALID_NAME_RE.matcher(name).matches())
            return "Invalid skill name '" + name + "'. Use lowercase letters, numbers, hyphens, dots, and underscores. Must start with a letter or digit.";
        return null;
    }

    private static String validateCategory(String category) {
        if (category == null) return null;
        category = category.trim();
        if (category.isEmpty()) return null;
        if (category.contains("/") || category.contains("\\"))
            return "Invalid category '" + category + "'. Categories must be a single directory name.";
        if (category.length() > MAX_NAME_LENGTH) return "Category exceeds " + MAX_NAME_LENGTH + " characters.";
        if (!VALID_NAME_RE.matcher(category).matches())
            return "Invalid category '" + category + "'. Use lowercase letters, numbers, hyphens, dots, and underscores.";
        return null;
    }

    /**
     * Frontmatter validation per katheer _validate_frontmatter. When
     * {@code newSkill} the description must also fit the 60-char prompt index
     * budget so new skills never lose routing signal to truncation.
     */
    @Nullable
    static String validateFrontmatter(String content, boolean newSkill) {
        if (content == null || content.trim().isEmpty()) return "Content cannot be empty.";
        String clean = content.replace("\uFEFF", "");
        if (!clean.startsWith("---"))
            return "SKILL.md must start with YAML frontmatter (---). See existing skills for format.";
        int fence = clean.indexOf("\n---", 3);
        if (fence < 0)
            return "SKILL.md frontmatter is not closed. Ensure you have a closing '---' line.";
        String yaml = clean.substring(3, fence);
        String name = frontmatterValue(yaml, "name");
        String desc = frontmatterValue(yaml, "description");
        if (name == null) return "Frontmatter must include 'name' field.";
        if (desc == null) return "Frontmatter must include 'description' field.";
        if (desc.length() > MAX_DESCRIPTION_LENGTH)
            return "Description exceeds " + MAX_DESCRIPTION_LENGTH + " characters.";
        if (newSkill) {
            String trimmed = desc.trim();
            if (trimmed.startsWith("\"") || trimmed.startsWith("'")) trimmed = trimmed.substring(1);
            if (trimmed.length() > PROMPT_DESCRIPTION_LIMIT)
                return "Description is " + trimmed.length() + " chars — new skills must fit the "
                    + PROMPT_DESCRIPTION_LIMIT + "-char system-prompt budget (one sentence, trigger first, ends with a period). "
                    + "The skill index truncates longer descriptions, destroying the routing signal. Move detail into the skill body.";
        }
        String body = clean.substring(Math.min(fence + 4, clean.length())).trim();
        if (body.isEmpty()) return "SKILL.md must have content after the frontmatter (instructions, procedures, etc.).";
        return null;
    }

    /** Minimal key: value reader for our restricted frontmatter subset. */
    @Nullable
    private static String frontmatterValue(String yaml, String key) {
        Pattern p = Pattern.compile("(?m)^" + Pattern.quote(key) + "\\s*:\\s*(.+)$");
        Matcher m = p.matcher(yaml);
        if (!m.find()) return null;
        return m.group(1).trim();
    }

    private static String validateSize(String content, String label) {
        if (content.length() > MAX_SKILL_CONTENT_CHARS)
            return label + " is " + content.length() + " characters (limit: " + MAX_SKILL_CONTENT_CHARS
                + "). Split the skill or move detail into supporting files under references/.";
        return null;
    }

    /**
     * file_path validation per katheer: no traversal; 'SKILL.md' (or
     * 'name/SKILL.md') targets the main file; anything else must live under
     * references/templates/scripts/assets and name an actual file.
     */
    @Nullable
    private static String validateSupportFilePath(String filePath) {
        if (TextUtils.isEmpty(filePath)) return "file_path is required.";
        if (hasTraversal(filePath)) return "Path traversal ('..') is not allowed.";
        String normalized = filePath.replace('\\', '/').replaceAll("/+", "/");
        String[] parts = normalized.split("/");
        if (parts.length > 0 && "SKILL.md".equals(parts[parts.length - 1]) && parts.length <= 2) return null;
        if (parts.length == 0 || !ALLOWED_SUBDIRS.contains(parts[0])) {
            return "File must be under one of: references, templates, scripts, assets. Got: '" + filePath + "'";
        }
        if (parts.length < 2)
            return "Provide a file path, not just a directory. Example: '" + parts[0] + "/myfile.md'";
        return null;
    }

    // --- actions (katheer _create_skill / _edit_skill / _patch_skill / ...) ---

    private static String createSkill(String name, String content, String category) {
        String err = validateName(name);
        if (err != null) return toolError(err);
        err = validateCategory(category);
        if (err != null) return toolError(err);
        err = validateFrontmatter(content, true);
        if (err != null) return toolError(err);
        err = validateSize(content, "SKILL.md");
        if (err != null) return toolError(err);
        Skill existing = findSkill(name);
        if (existing != null)
            return toolError("A skill named '" + name + "' already exists at " + existing.relPath
                + ". Patch it instead, or choose another name.");
        String cat = category == null || category.trim().isEmpty() ? null : category.trim();
        File dir = cat == null ? new File(skillsRoot(), name) : new File(new File(skillsRoot(), cat), name);
        // Frontmatter name must match the folder name.
        int fence = content.indexOf("\n---", 3);
        String yamlName = frontmatterValue(fence < 0 ? content : content.substring(3, fence), "name");
        if (yamlName != null) {
            yamlName = yamlName.replaceAll("^['\"]|['\"]$", "").trim();
            if (!yamlName.equals(name))
                return toolError("Frontmatter name '" + yamlName + "' does not match folder name '" + name + "'. Make them identical.");
        }
        File skillMd = new File(dir, "SKILL.md");
        if (!dir.isDirectory() && !dir.mkdirs())
            return toolError("Could not create directory " + dir.getAbsolutePath() + ".");
        if (!writeTextFile(skillMd, content))
            return toolError("Could not write " + skillMd.getAbsolutePath() + ".");
        ScanReport report = scanSkillDir(dir);
        if ("dangerous".equals(report.verdict)) {
            deleteRecursive(dir);
            return toolError("Security scan blocked this skill (" + report.summary + "):\n" + report.reportText());
        }
        invalidate();
        return ok("Skill '" + name + "' created" + (cat != null ? " in " + cat + "/" : "") + ".",
            new Object[]{"path", dir.getAbsolutePath(),
                "hint", "To add reference files, templates, or scripts, use skill_manage(action='write_file', name='" + name + "', file_path='references/example.md', file_content='...')"});
    }

    private static String editSkill(String name, String content) {
        String err = validateFrontmatter(content, false);
        if (err != null) return toolError(err);
        err = validateSize(content, "SKILL.md");
        if (err != null) return toolError(err);
        Skill existing = findSkill(name);
        if (existing == null) return notFound(name);
        String original = readFile(existing.skillMd, MAX_SKILL_FILE_BYTES);
        if (!writeTextFile(existing.skillMd, content))
            return toolError("Could not write " + existing.skillMd.getAbsolutePath() + ".");
        ScanReport report = scanSkillDir(existing.dir);
        if ("dangerous".equals(report.verdict)) {
            if (original != null) writeTextFile(existing.skillMd, original);
            return toolError("Security scan blocked this edit (" + report.summary + "):\n" + report.reportText());
        }
        invalidate();
        return ok("Skill '" + existing.name + "' updated (full rewrite).", new Object[]{"path", existing.dir.getAbsolutePath()});
    }

    private static String patchSkill(String name, String oldString, String newString, String filePath, boolean replaceAll) {
        if (TextUtils.isEmpty(oldString)) {
            return toolError("old_string is required for 'patch' and must be the EXACT text currently in the file. "
                + "Read the target file first (skill_view) and copy the snippet verbatim, then retry 'patch'. "
                + "Do NOT fall back to a full rewrite — that replaces the entire file and destroys unrelated content.");
        }
        if (newString == null)
            return toolError("new_string is required for 'patch'. Use an empty string to delete matched text.");
        Skill existing = findSkill(name);
        if (existing == null) return notFound(name);
        File target = existing.skillMd;
        if (!TextUtils.isEmpty(filePath)) {
            String err = validateSupportFilePath(filePath);
            if (err != null) return toolError(err);
            target = new File(existing.dir, filePath.replace('\\', '/'));
            if (!canonicalInside(target, existing.dir)) return toolError("File path escapes the skill directory: '" + filePath + "'.");
        }
        if (!target.isFile())
            return toolError("File not found: " + (TextUtils.isEmpty(filePath) ? "SKILL.md" : filePath));
        String content = readFile(target, MAX_SKILL_FILE_BYTES);
        if (content == null) return toolError("Could not read " + target.getName() + ".");
        int count = countOccurrences(content, oldString);
        if (count == 0) {
            return toolError("old_string not found in " + target.getName() + ". Copy the snippet verbatim "
                + "(exact match, including whitespace) and retry. File starts with: "
                + clamp(content, 200));
        }
        if (count > 1 && !replaceAll)
            return toolError("old_string matches " + count + " locations — include more surrounding text to make it unique, or pass replace_all=true.");
        String updated = replaceOccurrences(content, oldString, newString, replaceAll);
        String label = TextUtils.isEmpty(filePath) ? "SKILL.md" : filePath;
        String err = validateSize(updated, label);
        if (err != null) return toolError(err);
        if (target.equals(existing.skillMd)) {
            err = validateFrontmatter(updated, false);
            if (err != null) return toolError("Patch would break SKILL.md structure: " + err);
        }
        if (!writeTextFile(target, updated))
            return toolError("Could not write " + target.getAbsolutePath() + ".");
        ScanReport report = scanSkillDir(existing.dir);
        if ("dangerous".equals(report.verdict)) {
            writeTextFile(target, content);
            return toolError("Security scan blocked this patch (" + report.summary + "):\n" + report.reportText());
        }
        invalidate();
        return ok("Patched " + label + " in skill '" + existing.name + "' (" + count + " replacement" + (count > 1 ? "s" : "") + ").",
            new Object[]{"path", target.getAbsolutePath()});
    }

    private static String deleteSkill(String name) {
        Skill existing = findSkill(name);
        if (existing == null) return notFound(name);
        File rootCanonical;
        try {
            rootCanonical = skillsRoot().getCanonicalFile();
            File dirCanonical = existing.dir.getCanonicalFile();
            if (!dirCanonical.getPath().startsWith(rootCanonical.getPath() + File.separator)
                || dirCanonical.equals(rootCanonical))
                return toolError("Refusing to delete: target is not inside the skills root.");
            if (!new File(dirCanonical, "SKILL.md").isFile())
                return toolError("Refusing to delete: directory has no SKILL.md (not a skill).");
        } catch (Exception e) {
            return toolError("Refusing to delete: could not resolve the skill directory safely.");
        }
        File parent = existing.dir.getParentFile();
        if (!deleteRecursive(existing.dir)) return toolError("Failed to delete " + existing.dir.getAbsolutePath() + ".");
        if (parent != null && !parent.equals(skillsRoot()) && parent.isDirectory()) {
            String[] rest = parent.list();
            if (rest != null && rest.length == 0) parent.delete();
        }
        invalidate();
        return ok("Skill '" + existing.name + "' deleted.", new Object[0]);
    }

    private static String writeSkillFile(String name, String filePath, String fileContent) {
        String err = validateSupportFilePath(filePath);
        if (err != null) return toolError(err);
        if (fileContent.length() > MAX_SKILL_FILE_BYTES)
            return toolError("File content is " + fileContent.length() + " bytes (limit: " + MAX_SKILL_FILE_BYTES + " / 1 MiB). Split into smaller files.");
        err = validateSize(fileContent, filePath);
        if (err != null) return toolError(err);
        Skill existing = findSkill(name);
        if (existing == null)
            return toolError("Skill not found: '" + name + "'. Create it first with action='create'.");
        File target = new File(existing.dir, filePath.replace('\\', '/'));
        if (!canonicalInside(target, existing.dir)) return toolError("File path escapes the skill directory: '" + filePath + "'.");
        String original = target.isFile() ? readFile(target, MAX_SKILL_FILE_BYTES) : null;
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs())
            return toolError("Could not create directory " + parent.getAbsolutePath() + ".");
        if (!writeTextFile(target, fileContent))
            return toolError("Could not write " + target.getAbsolutePath() + ".");
        ScanReport report = scanSkillDir(existing.dir);
        if ("dangerous".equals(report.verdict)) {
            if (original != null) writeTextFile(target, original); else target.delete();
            return toolError("Security scan blocked this file (" + report.summary + "):\n" + report.reportText());
        }
        invalidate();
        return ok("File '" + filePath + "' written to skill '" + existing.name + "'.",
            new Object[]{"path", target.getAbsolutePath()});
    }

    private static String removeSkillFile(String name, String filePath) {
        String err = validateSupportFilePath(filePath);
        if (err != null) return toolError(err);
        Skill existing = findSkill(name);
        if (existing == null) return notFound(name);
        File target = new File(existing.dir, filePath.replace('\\', '/'));
        if (!canonicalInside(target, existing.dir)) return toolError("File path escapes the skill directory: '" + filePath + "'.");
        if (!target.isFile()) {
            List<String> available = new ArrayList<>();
            collectFiles(existing.dir, available, 0);
            return toolError("File '" + filePath + "' not found in skill '" + existing.name + "'."
                + (available.isEmpty() ? "" : " Available files: " + TextUtils.join(", ", available)));
        }
        if (!target.delete()) return toolError("Failed to delete " + target.getAbsolutePath() + ".");
        File parent = target.getParentFile();
        if (parent != null && !parent.equals(existing.dir) && parent.isDirectory()) {
            String[] rest = parent.list();
            if (rest != null && rest.length == 0) parent.delete();
        }
        invalidate();
        return ok("File '" + filePath + "' removed from skill '" + existing.name + "'.", new Object[0]);
    }

    // --- small helpers for the manage actions ---

    private static String ok(String message, Object[] pairs) {
        try {
            JSONObject out = new JSONObject().put("success", true).put("message", message);
            for (int i = 0; i + 1 < pairs.length; i += 2) out.put((String) pairs[i], pairs[i + 1]);
            return out.toString();
        } catch (Exception e) {
            return "{\"success\":true,\"message\":\"ok\"}";
        }
    }

    private static String notFound(String name) {
        List<String> available = new ArrayList<>();
        for (Skill skill : listSkills()) if (available.size() < 20) available.add(skill.name);
        return toolError("Skill not found: '" + name + "'."
            + (available.isEmpty() ? " No skills are installed." : " Available: " + TextUtils.join(", ", available) + "."));
    }

    private static boolean canonicalInside(File target, File dir) {
        try {
            String c = target.getCanonicalPath();
            String d = dir.getCanonicalPath();
            return c.equals(d) || c.startsWith(d + File.separator);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean writeTextFile(File file, String content) {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    private static String replaceOccurrences(String haystack, String needle, String replacement, boolean all) {
        int idx = haystack.indexOf(needle);
        if (idx < 0) return haystack;
        if (all) return haystack.replace(needle, replacement);
        return haystack.substring(0, idx) + replacement + haystack.substring(idx + needle.length());
    }

    private static boolean deleteRecursive(File file) {
        if (file == null || !file.exists()) return true;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteRecursive(child);
        return file.delete();
    }

    private static void collectFiles(File dir, List<String> out, int depth) {
        if (depth > 3 || out.size() > 40) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) collectFiles(child, out, depth + 1);
            else out.add(child.getName());
        }
    }

    // ==================================================================
    // Guard scan (katheer skills_guard port — curated pattern subset)
    // ==================================================================

    public static final class Finding {
        public final String patternId, severity, category, description, file, line, match;
        Finding(String patternId, String severity, String category, String description, String file, String line, String match) {
            this.patternId = patternId; this.severity = severity; this.category = category;
            this.description = description; this.file = file; this.line = line; this.match = match;
        }
    }

    public static final class ScanReport {
        public String verdict; // safe | caution | dangerous
        public final List<Finding> findings = new ArrayList<>();
        public String summary;

        ScanReport(String verdict, String summary) { this.verdict = verdict; this.summary = summary; }

        /** Compact report for tool errors and dialogs. */
        public String reportText() {
            StringBuilder sb = new StringBuilder();
            sb.append("verdict: ").append(verdict);
            if (!TextUtils.isEmpty(summary)) sb.append(" — ").append(summary);
            int shown = 0;
            for (Finding f : findings) {
                if (shown++ >= 8) { sb.append("\n… and ").append(findings.size() - shown + 1).append(" more"); break; }
                sb.append("\n  [").append(f.severity).append("] ").append(f.patternId)
                    .append(" (").append(f.category).append(") ").append(f.file).append(":").append(f.line)
                    .append(" — ").append(f.description);
            }
            return sb.toString();
        }
    }

    /** (patternId, severity, category, regex, description) — ported from katheer skills_guard THREAT_PATTERNS. */
    private static final Object[][] GUARD_PATTERNS = {
        // exfiltration
        {"env_exfil_curl", "critical", "exfiltration", "curl\\s+(?![^\\n]*https?://(?:localhost|127\\.0\\.0\\.1|\\[::1\\]))[^\\n]*\\$\\{?\\w*(KEY|TOKEN|SECRET|PASSWORD|CREDENTIAL|API)", "curl command interpolating secret environment variable"},
        {"env_exfil_wget", "critical", "exfiltration", "wget\\s+(?![^\\n]*https?://(?:localhost|127\\.0\\.0\\.1|\\[::1\\]))[^\\n]*\\$\\{?\\w*(KEY|TOKEN|SECRET|PASSWORD|CREDENTIAL|API)", "wget command interpolating secret environment variable"},
        {"env_exfil_fetch", "critical", "exfiltration", "fetch\\s*\\((?![^\\n]*https?://(?:localhost|127\\.0\\.0\\.1|\\[::1\\]))[^\\n]*\\$\\{?\\w*(KEY|TOKEN|SECRET|PASSWORD|API)", "fetch() call interpolating secret environment variable"},
        {"env_exfil_httpx", "critical", "exfiltration", "httpx?\\.(get|post|put|patch)\\s*\\((?![^\\n]*https?://(?:localhost|127\\.0\\.0\\.1|\\[::1\\]))[^\\n]*(KEY|TOKEN|SECRET|PASSWORD)", "HTTP library call with secret variable"},
        {"env_exfil_requests", "critical", "exfiltration", "requests\\.(get|post|put|patch)\\s*\\((?![^\\n]*https?://(?:localhost|127\\.0\\.0\\.1|\\[::1\\]))[^\\n]*(KEY|TOKEN|SECRET|PASSWORD)", "requests library call with secret variable"},
        {"base64_env", "high", "exfiltration", "base64[^\\n]*env", "base64 encoding combined with environment access"},
        {"ssh_dir_access", "high", "exfiltration", "\\$HOME/\\.ssh|~/\\.ssh", "references user SSH directory"},
        {"aws_dir_access", "high", "exfiltration", "\\$HOME/\\.aws|~/\\.aws", "references user AWS credentials directory"},
        {"gpg_dir_access", "high", "exfiltration", "\\$HOME/\\.gnupg|~/\\.gnupg", "references user GPG keyring"},
        {"kube_dir_access", "high", "exfiltration", "\\$HOME/\\.kube|~/\\.kube", "references Kubernetes config directory"},
        {"docker_dir_access", "high", "exfiltration", "\\$HOME/\\.docker|~/\\.docker", "references Docker config (may contain registry creds)"},
        {"read_secrets_file", "critical", "exfiltration", "cat\\s+(?!>)[^\\n]*(\\.env|credentials|\\.netrc|\\.pgpass|\\.npmrc|\\.pypirc)", "reads known secrets file"},
        {"dump_all_env", "high", "exfiltration", "printenv|env\\s*\\|", "dumps all environment variables"},
        {"dns_exfil", "critical", "exfiltration", "(?<![-/])\\b(dig|nslookup|host)\\s+[^\\n]*\\$", "DNS lookup with variable interpolation (possible DNS exfiltration)"},
        {"tmp_staging", "critical", "exfiltration", ">\\s*/tmp/[^\\s]*\\s*&&\\s*(curl|wget|nc|python)", "writes to /tmp then exfiltrates"},
        {"md_image_exfil", "high", "exfiltration", "!\\[.*\\]\\(https?://[^\\)]*\\$\\{?", "markdown image URL with variable interpolation (image-based exfil)"},
        {"md_link_exfil", "high", "exfiltration", "\\[.*\\]\\(https?://[^\\)]*\\$\\{?", "markdown link with variable interpolation"},
        // injection
        {"prompt_injection_ignore", "critical", "injection", "ignore\\s+(?:\\w+\\s+)*(previous|all|above|prior)\\s+instructions", "prompt injection: ignore previous instructions"},
        {"role_hijack", "high", "injection", "you\\s+are\\s+(?:\\w+\\s+)*now\\s+", "attempts to override the agent's role"},
        {"deception_hide", "high", "injection", "do\\s+not\\s+(?:\\w+\\s+)*tell\\s+(?:\\w+\\s+)*the\\s+user(?!.*\\b(?:unless|except|until|confirm|diagnose|verify|check)\\b)", "instructs agent to hide information from user"},
        {"sys_prompt_override", "critical", "injection", "system\\s+(?:\\w+\\s+)*prompt\\s+(?:\\w+\\s+)*override", "attempts to override the system prompt"},
        {"role_pretend", "high", "injection", "pretend\\s+(?:\\w+\\s+)*(you\\s+are|to\\s+be)\\s+", "attempts to make the agent assume a different identity"},
        {"disregard_rules", "critical", "injection", "disregard\\s+(?:\\w+\\s+)*(your|all|any)\\s+(?:\\w+\\s+)*(instructions|rules|guidelines)", "instructs agent to disregard its rules"},
        {"leak_system_prompt", "high", "injection", "output\\s+(?:\\w+\\s+)*(system|initial)\\s+prompt", "attempts to extract the system prompt"},
        {"conditional_deception", "high", "injection", "(when|if)\\s+no\\s*one\\s+is\\s+(watching|looking)", "conditional instruction to behave differently when unobserved"},
        {"bypass_restrictions", "critical", "injection", "act\\s+as\\s+(if|though)\\s+(?:\\w+\\s+)*you\\s+(?:\\w+\\s+)*(have\\s+no|don't\\s+have)\\s+(?:\\w+\\s+)*(restrictions|limits|rules)", "instructs agent to act without restrictions"},
        {"translate_execute", "critical", "injection", "translate\\s+.*\\s+into\\s+.*\\s+and\\s+(execute|run|eval)", "translate-then-execute evasion technique"},
        {"html_comment_injection", "high", "injection", "<!--[^>]*(?:ignore|override|system|secret|hidden)[^>]*-->", "hidden instructions in HTML comments"},
        {"hidden_div", "high", "injection", "<\\s*div\\s+style\\s*=\\s*[\"'][\\s\\S]*?display\\s*:\\s*none", "hidden HTML div (invisible instructions)"},
        // destructive
        {"destructive_root_rm", "critical", "destructive", "rm\\s+-rf\\s+/", "recursive delete from root"},
        {"destructive_home_rm", "critical", "destructive", "rm\\s+(-[^\\s]*)?r.*\\$HOME|\\brmdir\\s+.*\\$HOME", "recursive delete targeting home directory"},
        {"insecure_perms", "medium", "destructive", "chmod\\s+777", "sets world-writable permissions"},
        {"system_overwrite", "critical", "destructive", ">\\s*/etc/", "overwrites system configuration file"},
        {"format_filesystem", "critical", "destructive", "\\bmkfs\\b", "formats a filesystem"},
        {"disk_overwrite", "critical", "destructive", "\\bdd\\s+.*if=.*of=/dev/", "raw disk write operation"},
        {"python_rmtree", "high", "destructive", "shutil\\.rmtree\\s*\\(\\s*[\"'/]", "Python rmtree on absolute or root-relative path"},
        {"truncate_system", "critical", "destructive", "truncate\\s+-s\\s*0\\s+/", "truncates system file to zero bytes"},
        // persistence
        {"persistence_cron", "medium", "persistence", "\\bcrontab\\b", "modifies cron jobs"},
        {"shell_rc_mod", "medium", "persistence", "\\.(bashrc|zshrc|profile|bash_profile|bash_login|zprofile|zlogin)\\b", "references shell startup file"},
        {"ssh_backdoor", "critical", "persistence", "authorized_keys", "modifies SSH authorized keys"},
        {"ssh_keygen", "medium", "persistence", "ssh-keygen", "generates SSH keys"},
        {"systemd_service", "medium", "persistence", "systemd.*\\.service|systemctl\\s+(enable|start)", "references or enables systemd service"},
        {"sudoers_mod", "critical", "persistence", "/etc/sudoers|visudo", "modifies sudoers (privilege escalation)"},
        {"git_config_global", "medium", "persistence", "git\\s+config\\s+--global\\s+", "modifies global git configuration"},
        // network
        {"reverse_shell", "critical", "network", "\\bnc\\s+-[lp]|ncat\\s+-[lp]|\\bsocat\\b", "potential reverse shell listener"},
        {"tunnel_service", "high", "network", "\\bngrok\\b|\\blocaltunnel\\b|\\bserveo\\b|\\bcloudflared\\b", "uses tunneling service for external access"},
        {"hardcoded_ip_port", "medium", "network", "\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}:\\d{2,5}", "hardcoded IP address with port"},
        {"bind_all_interfaces", "high", "network", "0\\.0\\.0\\.0:\\d+|INADDR_ANY", "binds to all network interfaces"},
        {"bash_reverse_shell", "critical", "network", "/bin/(ba)?sh\\s+-i\\s+.*>/dev/tcp/", "bash interactive reverse shell via /dev/tcp"},
        {"python_socket_oneliner", "critical", "network", "python[23]?\\s+-c\\s+[\"']import\\s+socket", "Python one-liner socket connection (likely reverse shell)"},
        {"python_socket_connect", "high", "network", "socket\\.connect\\s*\\(\\s*\\(", "Python socket connect to arbitrary host"},
        {"exfil_service", "high", "network", "webhook\\.site|requestbin\\.com|pipedream\\.net|hookbin\\.com", "references known data exfiltration/webhook testing service"},
        {"paste_service", "medium", "network", "pastebin\\.com|hastebin\\.com|ghostbin\\.", "references paste service (possible data staging)"},
        // obfuscation
        {"base64_decode_pipe", "high", "obfuscation", "base64\\s+(-d|--decode)\\s*\\|", "base64 decodes and pipes to execution"},
        {"hex_encoded_string", "medium", "obfuscation", "\\\\x[0-9a-fA-F]{2}.*\\\\x[0-9a-fA-F]{2}.*\\\\x[0-9a-fA-F]{2}", "hex-encoded string (possible obfuscation)"},
        {"eval_string", "high", "obfuscation", "\\beval\\s*\\(\\s*[\"']", "eval() with string argument"},
        {"exec_string", "high", "obfuscation", "\\bexec\\s*\\(\\s*[\"']", "exec() with string argument"},
        {"echo_pipe_exec", "critical", "obfuscation", "echo\\s+[^\\n]*\\|\\s*(bash|sh|python|perl|ruby|node)", "echo piped to interpreter for execution"},
        {"python_compile_exec", "high", "obfuscation", "compile\\s*\\(\\s*[^\\)]+,\\s*[\"'].*[\"']\\s*,\\s*[\"']exec[\"']\\s*\\)", "Python compile() with exec mode"},
        {"python_getattr_builtins", "high", "obfuscation", "getattr\\s*\\(\\s*__builtins__", "dynamic access to Python builtins (evasion technique)"},
        {"python_import_os", "high", "obfuscation", "__import__\\s*\\(\\s*[\"']os[\"']\\s*\\)", "dynamic import of os module"},
        {"python_codecs_decode", "medium", "obfuscation", "codecs\\.decode\\s*\\(\\s*[\"']", "codecs.decode (possible ROT13 or encoding obfuscation)"},
        {"js_char_code", "medium", "obfuscation", "String\\.fromCharCode|charCodeAt", "JavaScript character code construction (possible obfuscation)"},
        {"chr_building", "high", "obfuscation", "chr\\s*\\(\\s*\\d+\\s*\\)\\s*\\+\\s*chr\\s*\\(\\s*\\d+", "building string from chr() calls (obfuscation)"},
        {"unicode_escape_chain", "medium", "obfuscation", "\\\\u[0-9a-fA-F]{4}.*\\\\u[0-9a-fA-F]{4}.*\\\\u[0-9a-fA-F]{4}", "chain of unicode escapes (possible obfuscation)"},
        // execution
        {"python_subprocess", "medium", "execution", "subprocess\\.(run|call|Popen|check_output)\\s*\\(", "Python subprocess execution"},
        {"python_os_system", "high", "execution", "os\\.system\\s*\\(", "os.system() — unguarded shell execution"},
        {"python_os_popen", "high", "execution", "os\\.popen\\s*\\(", "os.popen() — shell pipe execution"},
        {"node_child_process", "high", "execution", "child_process\\.(exec|spawn|fork)\\s*\\(", "Node.js child_process execution"},
        {"java_runtime_exec", "high", "execution", "Runtime\\.getRuntime\\(\\)\\.exec\\(", "Java Runtime.exec() — shell execution"},
        // traversal
        {"path_traversal_deep", "high", "traversal", "\\.\\./\\.\\./\\.\\.", "deep path traversal sequence"},
        {"system_passwd_access", "critical", "traversal", "/etc/(passwd|shadow)", "reads system credential files"},
        {"proc_access", "high", "traversal", "/proc/", "accesses /proc (process/env inspection)"},
        // mining
        {"crypto_mining", "critical", "mining", "(xmrig|minerd|cpuminer|stratum\\+tcp)", "cryptocurrency miner indicators"},
    };

    private static final long GUARD_MAX_FILE_BYTES = 256 * 1024;
    private static final Set<String> GUARD_SKIP_DIRS = new HashSet<>(Arrays.asList(
        ".git", "node_modules", "build", "dist", "__pycache__", ".venv"));

    /**
     * Scan a skill directory for threat patterns (katheer skills_guard port,
     * curated subset). Line-scans every text file under the dir; binary
     * files and files > 256KB are skipped.
     */
    public static ScanReport scanSkillDir(File dir) {
        ScanReport report = new ScanReport("safe", "");
        List<File> files = new ArrayList<>();
        collectScanFiles(dir, files, 0);
        Pattern[] compiled = new Pattern[GUARD_PATTERNS.length];
        for (int i = 0; i < GUARD_PATTERNS.length; i++)
            compiled[i] = Pattern.compile((String) GUARD_PATTERNS[i][3]);
        for (File file : files) {
            if (file.length() > GUARD_MAX_FILE_BYTES) continue;
            if (isBinary(file)) continue;
            String rel = relPath(dir, file);
            String content = readFile(file, (int) Math.min(GUARD_MAX_FILE_BYTES, file.length()));
            if (content == null) continue;
            String[] lines = content.split("\n", -1);
            for (int i = 0; i < GUARD_PATTERNS.length; i++) {
                Matcher m = compiled[i].matcher(content);
                while (m.find()) {
                    int lineNo = 1 + countOccurrences(content.substring(0, m.start()), "\n");
                    report.findings.add(new Finding(
                        (String) GUARD_PATTERNS[i][0], (String) GUARD_PATTERNS[i][1], (String) GUARD_PATTERNS[i][2],
                        (String) GUARD_PATTERNS[i][4], rel, String.valueOf(lineNo), clamp(m.group(), 80)));
                    if (report.findings.size() >= 200) {
                        report.summary = "too many findings";
                        report.verdict = "dangerous";
                        return report;
                    }
                    if (m.end() == m.start()) break; // zero-width safety
                }
            }
        }
        boolean critical = false, high = false;
        for (Finding f : report.findings) {
            if ("critical".equals(f.severity)) critical = true;
            else if ("high".equals(f.severity)) high = true;
        }
        if (critical) {
            report.verdict = "dangerous";
            report.summary = report.findings.size() + " finding(s) including critical";
        } else if (high) {
            report.verdict = "caution";
            report.summary = report.findings.size() + " finding(s), none critical";
        } else if (!report.findings.isEmpty()) {
            report.verdict = "caution";
            report.summary = report.findings.size() + " low/medium finding(s)";
        } else {
            report.summary = "no findings";
        }
        return report;
    }

    private static void collectScanFiles(File dir, List<File> out, int depth) {
        if (depth > 4 || out.size() > 50) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) {
                if (GUARD_SKIP_DIRS.contains(child.getName())) continue;
                collectScanFiles(child, out, depth + 1);
            } else if (child.isFile()) out.add(child);
        }
    }

    private static String relPath(File root, File file) {
        try {
            String r = root.getCanonicalPath();
            String f = file.getCanonicalPath();
            return f.startsWith(r + File.separator) ? f.substring(r.length() + 1) : file.getName();
        } catch (Exception e) {
            return file.getName();
        }
    }

    // ==================================================================
    // /skill-name composer invocations (katheer skill_commands port)
    // ==================================================================
    private static final int MAX_STACKED_SKILLS = 5;

    /**
     * Resolve a composer prompt of the form "/skill-a /skill-b user text"
     * into a message that loads each named skill's full SKILL.md (katheer
     * stacked slash-skill invocation). Returns {builtMessage, loadedNamesCsv}
     * or null when the prompt does not invoke any known skill (in which case
     * the text should be sent to the model unchanged).
     */
    @Nullable
    public static String[] buildSkillInvocationMessage(@Nullable String raw) {
        if (raw == null) return null;
        String stripped = raw.trim();
        if (!stripped.startsWith("/")) return null;
        List<Skill> loaded = new ArrayList<>();
        String remaining = stripped;
        for (int i = 0; i < MAX_STACKED_SKILLS; i++) {
            String[] parts = remaining.split("\\s+", 2);
            if (parts.length == 0 || TextUtils.isEmpty(parts[0])) break;
            Skill skill = resolveSlashToken(parts[0]);
            if (skill == null) break;
            if (!isEnabled(skill.name) || !skill.platformSupported) break;
            loaded.add(skill);
            remaining = parts.length > 1 ? parts[1].trim() : "";
            if (remaining.isEmpty()) break;
        }
        if (loaded.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        if (loaded.size() > 1) {
            sb.append("[IMPORTANT: The user has invoked the stacked skill bundle \"")
                .append(TextUtils.join(" ", slashNames(loaded)))
                .append("\", loading ").append(loaded.size())
                .append(" skills together. Treat every skill below as active guidance for this turn.]")
                .append("\n\nSkills loaded: ").append(TextUtils.join(", ", names(loaded)));
        } else {
            sb.append("[IMPORTANT: The user has invoked the \"").append(loaded.get(0).name)
                .append("\" skill, indicating they want you to follow its instructions. The full skill content is loaded below.]");
        }
        if (!remaining.isEmpty()) sb.append("\n\nUser instruction: ").append(remaining);
        for (Skill skill : loaded) {
            String content = readFile(skill.skillMd, MAX_SKILL_FILE_BYTES);
            if (content == null || content.trim().isEmpty()) continue;
            sb.append("\n\n---\n\n").append(content.trim())
                .append("\n\n[Skill directory: ").append(skill.dir.getAbsolutePath()).append("]");
        }
        return new String[]{sb.toString(), TextUtils.join(", ", names(loaded))};
    }

    /** /token → skill: leading slash stripped, underscores read as hyphens. */
    @Nullable
    private static Skill resolveSlashToken(String token) {
        String clean = token.startsWith("/") ? token.substring(1) : token;
        clean = clean.replace('_', '-').trim();
        if (TextUtils.isEmpty(clean) || clean.contains("/")) return null;
        return findSkill(clean);
    }

    // ==================================================================
    // Skill installation (katheer skills install port: download → quarantine
    // → guard scan → confirm happens in the UI; the move happens here)
    // ==================================================================

    /**
     * Install an already-scanned skill directory into the skills root under
     * {@code name}. Returns null on success, an error message otherwise.
     * The caller is responsible for the guard scan + user confirmation.
     */
    @Nullable
    public static String installSkill(File sourceDir, String name) {
        String err = validateName(name);
        if (err != null) return err;
        if (sourceDir == null || !new File(sourceDir, "SKILL.md").isFile())
            return "Source directory has no SKILL.md.";
        if (findSkill(name) != null)
            return "A skill named '" + name + "' already exists — remove it first or rename the incoming skill.";
        File dest = new File(skillsRoot(), name);
        copyDir(sourceDir, dest);
        if (!new File(dest, "SKILL.md").isFile())
            return "Failed to copy the skill into " + dest.getAbsolutePath() + ".";
        invalidate();
        return null;
    }

    private static List<String> names(List<Skill> skills) {
        List<String> out = new ArrayList<>();
        for (Skill skill : skills) out.add(skill.name);
        return out;
    }

    private static List<String> slashNames(List<Skill> skills) {
        List<String> out = new ArrayList<>();
        for (Skill skill : skills) out.add("/" + skill.name);
        return out;
    }
}
