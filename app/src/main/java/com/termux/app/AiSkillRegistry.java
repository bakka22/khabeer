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

/**
 * Skills registry (Hermes skills_tool/skill_utils port).
 *
 * A skill is a directory containing SKILL.md (YAML frontmatter + markdown
 * body) plus optional support dirs (references/templates/assets/scripts).
 * Skills live at $HOME/.hermes/skills — the same layout desktop Hermes uses,
 * so skills can be synced between the phone and a desktop install with git.
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
     * quotes and inline lists ([a, b] or a, b). The same naive fallback Hermes
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

    /** Hermes platform gate: empty platforms = all; "linux" matches Android. */
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
     *        to reuse the copy already in context (Hermes repeat-view dedup).
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

    public static void seedFromAssets(Context context) {
        try {
            File root = skillsRoot();
            root.mkdirs();
            String[] top = context.getAssets().list("skills");
            if (top == null) return;
            for (String entry : top) copyAssetDir(context, "skills/" + entry, new File(root, entry));
            invalidate();
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
}
