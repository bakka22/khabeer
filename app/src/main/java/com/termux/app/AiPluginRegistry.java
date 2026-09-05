package com.termux.app;

import android.text.TextUtils;

import com.termux.shared.termux.TermuxConstants;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Runtime-loadable plugin packages (Hermes Agent Plugins v1 portable
 * contract, no-code subset).
 *
 * A plugin is a directory under {@code ~/.khabeer/plugins/<name>/} with a
 * {@code plugin.json} manifest. Like Hermes, the app never imports plugin
 * code: the manifest translates into records the existing runtimes consume —
 * provider profiles (merged over the hardcoded list), skill directories
 * (merged into skill discovery), and MCP servers (synced into the database).
 *
 * Manifest shape:
 * {@code {name, version, description?, providers?: [{id, name?, baseUrl,
 * model?, needsKey?, dialect?}], mcpServers?: [{name, transport?, url?,
 * command?, args?, timeoutSeconds?}], skillsDir?}}
 * Unknown top-level fields are ignored with a diagnostic (Hermes parity).
 * Provider ids must not collide with builtins or each other; MCP servers
 * sync under {@code plugin/<plugin>/<server>} names so uninstall/disable
 * never touches user-created servers.
 */
public final class AiPluginRegistry {
    private AiPluginRegistry() {}

    public static final String MCP_NAME_PREFIX = "plugin/";
    private static final int MAX_MANIFEST_BYTES = 256 * 1024;
    private static final java.util.regex.Pattern NAME_RE =
        java.util.regex.Pattern.compile("^(?!.*(?:--|\\.\\.))[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?$");
    private static final java.util.regex.Pattern PROVIDER_ID_RE =
        java.util.regex.Pattern.compile("^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$");

    public static final class PluginPackage {
        public String name = "";
        public String version = "";
        public String description = "";
        public boolean enabled = true;
        public final List<AiProviderProfile> providers = new ArrayList<>();
        public final List<JSONObject> mcpServers = new ArrayList<>();
        public File skillsDir;
        public final List<String> diagnostics = new ArrayList<>();
    }

    private static volatile File sRootOverride;

    static synchronized void setRootForTests(File root) {
        sRootOverride = root;
    }

    public static File pluginsRoot() {
        if (sRootOverride != null) return new File(sRootOverride, "plugins");
        return new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".khabeer/plugins");
    }

    private static File customProvidersFile() {
        if (sRootOverride != null) return new File(sRootOverride, "custom-providers.json");
        return new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".khabeer/custom-providers.json");
    }

    private static File disabledFile() {
        return new File(pluginsRoot(), ".disabled.json");
    }

    // ------------------------------------------------------------------
    // Custom providers (user-created endpoints)
    // ------------------------------------------------------------------

    /** User custom providers, stored as a JSON array in the khabeer root. */
    public static synchronized JSONArray loadCustomProviders() {
        try {
            File f = customProvidersFile();
            if (!f.isFile()) return new JSONArray();
            byte[] data = java.nio.file.Files.readAllBytes(f.toPath());
            if (data.length > MAX_MANIFEST_BYTES) return new JSONArray();
            String raw = new String(data, java.nio.charset.StandardCharsets.UTF_8).trim();
            if (raw.isEmpty()) return new JSONArray();
            return new JSONArray(raw);
        } catch (Exception ignored) {
            return new JSONArray();
        }
    }

    private static synchronized void saveCustomProviders(JSONArray arr) {
        try {
            File f = customProvidersFile();
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(f, false)) {
                out.write(arr.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {}
    }

    /** Creates a custom OpenAI-compatible provider. Returns {success, id|error}. */
    public static synchronized JSONObject addCustomProvider(String name, String baseUrl,
                                                            String model, boolean needsKey,
                                                            String dialect) {
        JSONObject out = new JSONObject();
        try {
            String cleanName = name == null ? "" : name.trim();
            if (cleanName.isEmpty()) return pluginError("A provider name is required.");
            String url = baseUrl == null ? "" : baseUrl.trim();
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                return pluginError("Base URL must start with http:// or https://.");
            }
            String cleanModel = model == null ? "" : model.trim();
            if (cleanModel.isEmpty()) return pluginError("A default model is required.");
            String slug = cleanName.toLowerCase(java.util.Locale.US)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
            if (slug.isEmpty()) slug = "endpoint";
            String id = "custom-" + slug;
            JSONArray arr = loadCustomProviders();
            for (int i = 0; i < arr.length(); i++) {
                if (id.equals(arr.optJSONObject(i).optString("id"))) {
                    id = "custom-" + slug + "-" + UUID.randomUUID().toString().substring(0, 4);
                    break;
                }
            }
            String safeDialect = "anthropic".equals(dialect) ? "anthropic"
                : "responses".equals(dialect) ? "responses" : "chat";
            JSONObject entry = new JSONObject()
                .put("id", id)
                .put("name", cleanName)
                .put("baseUrl", url)
                .put("model", cleanModel)
                .put("needsKey", needsKey)
                .put("dialect", safeDialect)
                .put("created_at", System.currentTimeMillis());
            arr.put(entry);
            saveCustomProviders(arr);
            refreshOverlay(null);
            out.put("success", true).put("id", id)
                .put("message", "Custom provider '" + cleanName + "' added. Set its key/endpoint on Home if needed.");
        } catch (Exception e) {
            try { out.put("success", false).put("error", e.getMessage()); } catch (Exception ignored) {}
        }
        return out;
    }

    /** Updates a custom provider in place (id is stable — stored keys
     * follow the id, so credentials survive edits). */
    public static synchronized JSONObject updateCustomProvider(String id, String name,
                                                               String baseUrl, String model,
                                                               boolean needsKey, String dialect) {
        JSONObject out = new JSONObject();
        try {
            if (TextUtils.isEmpty(id) || !id.startsWith("custom-")) {
                return pluginError("Only custom providers can be edited.");
            }
            String cleanName = name == null ? "" : name.trim();
            if (cleanName.isEmpty()) return pluginError("A provider name is required.");
            String url = baseUrl == null ? "" : baseUrl.trim();
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                return pluginError("Base URL must start with http:// or https://.");
            }
            String cleanModel = model == null ? "" : model.trim();
            if (cleanModel.isEmpty()) return pluginError("A default model is required.");
            JSONArray arr = loadCustomProviders();
            boolean found = false;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject e = arr.optJSONObject(i);
                if (e != null && id.equals(e.optString("id"))) {
                    e.put("name", cleanName).put("baseUrl", url).put("model", cleanModel)
                        .put("needsKey", needsKey)
                        .put("dialect", "anthropic".equals(dialect) ? "anthropic"
                            : "responses".equals(dialect) ? "responses" : "chat");
                    found = true;
                }
            }
            if (!found) return pluginError("Custom provider not found: '" + id + "'.");
            saveCustomProviders(arr);
            refreshOverlay(null);
            out.put("success", true).put("message", "Custom provider updated.");
        } catch (Exception e) {
            try { out.put("success", false).put("error", e.getMessage()); } catch (Exception ignored) {}
        }
        return out;
    }

    /** Validates a directory as a plugin without installing it (import preview). */
    public static synchronized PluginPackage previewDir(File dir) {
        if (dir == null) return null;
        Set<String> ids = builtinIds();
        for (AiProviderProfile p : customProfiles()) ids.add(p.id);
        for (PluginPackage pkg : loadPlugins()) {
            for (AiProviderProfile p : pkg.providers) ids.add(p.id);
        }
        PluginPackage probe = loadOne(dir, false, ids);
        if (probe.name != null) {
            for (PluginPackage pkg : loadPlugins()) {
                if (probe.name.equals(pkg.name)) {
                    probe.diagnostics.add("A plugin named '" + probe.name + "' is already installed.");
                    break;
                }
            }
        }
        return probe;
    }

    /** Deletes a custom provider (stored endpoint/model rows keyed by its
     * id become inert once the profile is gone). */
    public static synchronized JSONObject deleteCustomProvider(String id) {
        JSONObject out = new JSONObject();
        try {
            if (TextUtils.isEmpty(id) || !id.startsWith("custom-")) {
                return pluginError("Only custom providers can be deleted.");
            }
            JSONArray arr = loadCustomProviders();
            JSONArray kept = new JSONArray();
            boolean found = false;
            for (int i = 0; i < arr.length(); i++) {
                if (id.equals(arr.optJSONObject(i).optString("id"))) found = true;
                else kept.put(arr.optJSONObject(i));
            }
            if (!found) return pluginError("Custom provider not found: '" + id + "'.");
            saveCustomProviders(kept);
            refreshOverlay(null);
            out.put("success", true).put("message", "Custom provider removed.");
        } catch (Exception e) {
            try { out.put("success", false).put("error", e.getMessage()); } catch (Exception ignored) {}
        }
        return out;
    }

    private static List<AiProviderProfile> customProfiles() {
        List<AiProviderProfile> out = new ArrayList<>();
        JSONArray arr = loadCustomProviders();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject e = arr.optJSONObject(i);
            if (e == null) continue;
            String id = e.optString("id", "");
            if (!PROVIDER_ID_RE.matcher(id).matches()) continue;
            String url = e.optString("baseUrl", "");
            if (!url.startsWith("http://") && !url.startsWith("https://")) continue;
            String mark = e.optString("name", "?");
            mark = mark.isEmpty() ? "•" : mark.substring(0, 1).toUpperCase(java.util.Locale.US);
            out.add(AiProviderProfile.customProfile(id, e.optString("name", id), mark,
                "Custom endpoint: " + url, url, e.optString("model", ""),
                e.optBoolean("needsKey", true), e.optString("dialect", "chat")));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Plugin packages
    // ------------------------------------------------------------------

    /** All discovered packages (valid or not — diagnostics explain skips). */
    public static synchronized List<PluginPackage> loadPlugins() {
        List<PluginPackage> out = new ArrayList<>();
        Set<String> disabled = readDisabled();
        Set<String> seenIds = new HashSet<>();
        for (AiProviderProfile builtin : AiProviderProfile.PROFILES) seenIds.add(builtin.id);
        File root = pluginsRoot();
        File[] dirs = root.listFiles();
        if (dirs == null) return out;
        java.util.Arrays.sort(dirs, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File dir : dirs) {
            if (!dir.isDirectory() || dir.getName().startsWith(".")) continue;
            PluginPackage pkg = loadOne(dir, disabled.contains(dir.getName()), seenIds);
            out.add(pkg);
        }
        return out;
    }

    private static PluginPackage loadOne(File dir, boolean disabled, Set<String> seenIds) {
        PluginPackage pkg = new PluginPackage();
        pkg.name = dir.getName();
        pkg.enabled = !disabled;
        File manifest = new File(dir, "plugin.json");
        JSONObject m;
        try {
            if (!manifest.isFile()) {
                pkg.diagnostics.add("manifest: plugin.json missing — directory ignored.");
                return pkg;
            }
            byte[] data = java.nio.file.Files.readAllBytes(manifest.toPath());
            if (data.length > MAX_MANIFEST_BYTES) {
                pkg.diagnostics.add("manifest: plugin.json too large — directory ignored.");
                return pkg;
            }
            m = new JSONObject(new String(data, java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            pkg.diagnostics.add("manifest: plugin.json is not valid JSON — directory ignored.");
            return pkg;
        }
        for (java.util.Iterator<String> it = m.keys(); it.hasNext();) {
            String key = it.next();
            if (!"name".equals(key) && !"version".equals(key) && !"description".equals(key)
                && !"providers".equals(key) && !"mcpServers".equals(key) && !"skillsDir".equals(key)) {
                pkg.diagnostics.add("manifest: ignored unknown top-level field: " + key);
            }
        }
        String name = m.optString("name", dir.getName()).trim();
        if (!NAME_RE.matcher(name).matches()) {
            pkg.diagnostics.add("manifest: invalid name '" + name + "' — directory ignored.");
            return pkg;
        }
        pkg.name = name;
        pkg.version = m.optString("version", "").trim();
        if (pkg.version.isEmpty()) {
            pkg.diagnostics.add("manifest: version is required — directory ignored.");
            return pkg;
        }
        pkg.description = m.optString("description", "").trim();
        JSONArray providers = m.optJSONArray("providers");
        if (providers != null) {
            for (int i = 0; i < providers.length(); i++) {
                JSONObject p = providers.optJSONObject(i);
                if (p == null) {
                    pkg.diagnostics.add("providers[" + i + "]: ignored non-object entry.");
                    continue;
                }
                String id = p.optString("id", "").trim();
                if (!PROVIDER_ID_RE.matcher(id).matches()) {
                    pkg.diagnostics.add("providers[" + i + "]: invalid id — entry skipped.");
                    continue;
                }
                if (seenIds.contains(id)) {
                    pkg.diagnostics.add("providers[" + i + "]: id '" + id + "' collides — entry skipped.");
                    continue;
                }
                String url = p.optString("baseUrl", "").trim();
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    pkg.diagnostics.add("providers[" + i + "]: baseUrl must be http(s) — entry skipped.");
                    continue;
                }
                String label = p.optString("name", id).trim();
                String mark = label.isEmpty() ? "•" : label.substring(0, 1).toUpperCase(java.util.Locale.US);
                seenIds.add(id);
                pkg.providers.add(AiProviderProfile.customProfile(id, label, mark,
                    "Plugin '" + pkg.name + "'" + (pkg.description.isEmpty() ? "" : ": " + pkg.description),
                    url, p.optString("model", "").trim(),
                    p.optBoolean("needsKey", true), p.optString("dialect", "chat")));
            }
        }
        JSONArray servers = m.optJSONArray("mcpServers");
        if (servers != null) {
            for (int i = 0; i < servers.length(); i++) {
                JSONObject s = servers.optJSONObject(i);
                if (s == null) {
                    pkg.diagnostics.add("mcpServers[" + i + "]: ignored non-object entry.");
                    continue;
                }
                String sname = s.optString("name", "").trim();
                if (sname.isEmpty() || sname.contains("/") || sname.contains("..")) {
                    pkg.diagnostics.add("mcpServers[" + i + "]: invalid name — entry skipped.");
                    continue;
                }
                String transport = s.optString("transport", "http").trim();
                if (!"http".equals(transport) && !"stdio".equals(transport)) {
                    pkg.diagnostics.add("mcpServers[" + i + "]: transport must be http or stdio — entry skipped.");
                    continue;
                }
                if ("http".equals(transport)
                    && !s.optString("url", "").trim().startsWith("http")) {
                    pkg.diagnostics.add("mcpServers[" + i + "]: http servers need a url — entry skipped.");
                    continue;
                }
                if ("stdio".equals(transport) && s.optString("command", "").trim().isEmpty()) {
                    pkg.diagnostics.add("mcpServers[" + i + "]: stdio servers need a command — entry skipped.");
                    continue;
                }
                try {
                    s.put("name", MCP_NAME_PREFIX + pkg.name + "/" + sname);
                    pkg.mcpServers.add(s);
                } catch (Exception ignored) {}
            }
        }
        String skillsDir = m.optString("skillsDir", "skills").trim();
        if (!skillsDir.isEmpty()) {
            File candidate = new File(dir, skillsDir.replace('/', File.separatorChar));
            if (candidate.isDirectory()) pkg.skillsDir = candidate;
            else if (m.has("skillsDir")) {
                pkg.diagnostics.add("skillsDir '" + skillsDir + "' not found — no skills contributed.");
            }
        }
        return pkg;
    }

    /** Skill roots contributed by enabled plugins (main library first —
     * main-library names win on duplicates). */
    public static synchronized List<File> pluginSkillRoots() {
        List<File> out = new ArrayList<>();
        Set<String> disabled = readDisabled();
        File root = pluginsRoot();
        File[] dirs = root.listFiles();
        if (dirs == null) return out;
        java.util.Arrays.sort(dirs, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File dir : dirs) {
            if (!dir.isDirectory() || dir.getName().startsWith(".")
                || disabled.contains(dir.getName())) continue;
            File manifest = new File(dir, "plugin.json");
            if (!manifest.isFile()) continue;
            try {
                byte[] data = java.nio.file.Files.readAllBytes(manifest.toPath());
                if (data.length > MAX_MANIFEST_BYTES) continue;
                JSONObject m = new JSONObject(new String(data, java.nio.charset.StandardCharsets.UTF_8));
                String skillsDir = m.optString("skillsDir", "skills").trim();
                if (skillsDir.isEmpty()) continue;
                File candidate = new File(dir, skillsDir.replace('/', File.separatorChar));
                if (candidate.isDirectory()) out.add(candidate);
            } catch (Exception ignored) {}
        }
        return out;
    }

    /** Plugin display name owning a skills-dir root, or null. */
    public static synchronized String ownerOfSkillRoot(File root) {
        if (root == null) return null;
        try {
            String canonical = root.getCanonicalPath();
            File base = pluginsRoot().getCanonicalFile();
            if (!canonical.startsWith(base.getCanonicalPath() + File.separator)) return null;
            String rel = canonical.substring(base.getCanonicalPath().length() + 1);
            int slash = rel.indexOf(File.separatorChar);
            return slash < 0 ? rel : rel.substring(0, slash);
        } catch (Exception e) {
            return null;
        }
    }

    public static synchronized Set<String> readDisabled() {
        try {
            File f = disabledFile();
            if (!f.isFile()) return new HashSet<>();
            byte[] data = java.nio.file.Files.readAllBytes(f.toPath());
            JSONArray arr = new JSONArray(new String(data, java.nio.charset.StandardCharsets.UTF_8));
            Set<String> out = new HashSet<>();
            for (int i = 0; i < arr.length(); i++) out.add(arr.optString(i));
            return out;
        } catch (Exception e) {
            return new HashSet<>();
        }
    }

    public static synchronized void setPluginEnabled(String name, boolean enabled) {
        try {
            Set<String> disabled = readDisabled();
            if (enabled) disabled.remove(name);
            else if (!TextUtils.isEmpty(name)) disabled.add(name);
            pluginsRoot().mkdirs();
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(disabledFile(), false)) {
                out.write(new JSONArray(new ArrayList<>(disabled)).toString(2)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            AiSkillRegistry.invalidate();
            refreshOverlay(null);
        } catch (Exception ignored) {}
    }

    /** Deletes a plugin tree and its synced MCP servers. Skills live inside
     * the tree, so they vanish with it — nothing else is touched. */
    public static synchronized JSONObject deletePlugin(String name, AiDatabase db) {
        JSONObject out = new JSONObject();
        try {
            if (TextUtils.isEmpty(name) || name.contains("..") || name.contains("/")) {
                return pluginError("Invalid plugin name.");
            }
            File dir = new File(pluginsRoot(), name);
            if (!dir.isDirectory()) return pluginError("Plugin not found: '" + name + "'.");
            if (db != null) {
                for (AiDatabase.McpServerRecord r : db.getMcpServers()) {
                    if (r.name != null && r.name.startsWith(MCP_NAME_PREFIX + name + "/")) {
                        db.deleteMcpServer(r.name);
                    }
                }
            }
            deleteRecursive(dir);
            Set<String> disabled = readDisabled();
            if (disabled.remove(name)) setPluginEnabled(name, true);
            AiSkillRegistry.invalidate();
            refreshOverlay(db);
            out.put("success", true).put("message", "Plugin '" + name + "' removed.");
        } catch (Exception e) {
            try { out.put("success", false).put("error", e.getMessage()); } catch (Exception ignored) {}
        }
        return out;
    }

    /** Installs a plugin from a directory containing plugin.json (validates
     * the manifest before copying anything). */
    public static synchronized JSONObject installFromDir(File srcDir, AiDatabase db) {
        JSONObject out = new JSONObject();
        try {
            if (srcDir == null || !new File(srcDir, "plugin.json").isFile()) {
                return pluginError("Source directory has no plugin.json.");
            }
            PluginPackage probe = loadOne(srcDir, false, builtinIds());
            if (probe.version.isEmpty() || !NAME_RE.matcher(probe.name).matches()) {
                return pluginError("Invalid manifest: "
                    + (probe.diagnostics.isEmpty() ? "unreadable." : probe.diagnostics.get(0)));
            }
            File dest = new File(pluginsRoot(), probe.name);
            if (dest.exists()) return pluginError("A plugin named '" + probe.name + "' is already installed.");
            dest.mkdirs();
            copyDir(srcDir, dest);
            AiSkillRegistry.invalidate();
            refreshOverlay(db);
            out.put("success", true).put("name", probe.name)
                .put("message", "Plugin '" + probe.name + "' installed."
                    + (probe.diagnostics.isEmpty() ? "" : " " + probe.diagnostics.size() + " note(s)."));
        } catch (Exception e) {
            try { out.put("success", false).put("error", e.getMessage()); } catch (Exception ignored) {}
        }
        return out;
    }

    private static Set<String> builtinIds() {
        Set<String> ids = new HashSet<>();
        for (AiProviderProfile p : AiProviderProfile.PROFILES) ids.add(p.id);
        return ids;
    }

    /** Rebuilds the provider overlay (custom + enabled plugin providers)
     * and syncs plugin MCP servers into the database. Call on service start
     * and after every plugin/custom change. */
    public static synchronized void refreshOverlay(AiDatabase db) {
        List<AiProviderProfile> overlay = new ArrayList<>(customProfiles());
        Set<String> seen = new HashSet<>();
        for (AiProviderProfile p : overlay) seen.add(p.id);
        for (AiProviderProfile p : AiProviderProfile.PROFILES) seen.add(p.id);
        for (PluginPackage pkg : loadPlugins()) {
            if (!pkg.enabled || pkg.version.isEmpty()) continue;
            for (AiProviderProfile p : pkg.providers) {
                if (seen.add(p.id)) overlay.add(p);
            }
        }
        AiProviderProfile.setOverlay(overlay);
        if (db != null) syncMcpServers(db);
    }

    /** Upserts enabled plugins' MCP servers; removes servers whose plugin
     * vanished or dropped them. User-created servers are never touched.
     * User edits (enabled flag, tokens live in config) survive re-sync:
     * only manifest-owned fields refresh on an existing row. */
    private static void syncMcpServers(AiDatabase db) {
        try {
            Set<String> wanted = new HashSet<>();
            Set<String> disabled = readDisabled();
            for (PluginPackage pkg : loadPlugins()) {
                if (!pkg.enabled || pkg.version.isEmpty()
                    || disabled.contains(pkg.name)) continue;
                for (JSONObject s : pkg.mcpServers) {
                    String name = s.optString("name", "");
                    if (name.isEmpty()) continue;
                    wanted.add(name);
                    AiDatabase.McpServerRecord existing = db.getMcpServer(name);
                    AiDatabase.McpServerRecord r = new AiDatabase.McpServerRecord();
                    r.name = name;
                    r.transport = s.optString("transport", "http");
                    r.url = s.optString("url", null);
                    r.command = s.optString("command", null);
                    try {
                        JSONArray args = s.optJSONArray("args");
                        r.argsJson = args == null ? null : args.toString();
                    } catch (Exception ignored) {}
                    r.authType = s.optString("authType", "none");
                    r.timeoutSeconds = s.optInt("timeoutSeconds", 60);
                    if (r.timeoutSeconds <= 0) r.timeoutSeconds = 60;
                    r.trust = "untrusted";
                    if (existing != null) {
                        r.enabled = existing.enabled;
                        r.lastStatus = existing.lastStatus;
                        r.lastToolsJson = existing.lastToolsJson;
                        r.oauthJson = existing.oauthJson;
                    } else {
                        r.enabled = true;
                    }
                    db.saveMcpServer(r);
                }
            }
            for (AiDatabase.McpServerRecord r : db.getMcpServers()) {
                if (r.name != null && r.name.startsWith(MCP_NAME_PREFIX) && !wanted.contains(r.name)) {
                    db.deleteMcpServer(r.name);
                }
            }
        } catch (Exception ignored) {}
    }

    private static JSONObject pluginError(String message) {
        try {
            return new JSONObject().put("success", false).put("error", message);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static void copyDir(File src, File dest) {
        if (src == null || dest == null) return;
        File[] children = src.listFiles();
        if (children == null) return;
        dest.mkdirs();
        for (File child : children) {
            File target = new File(dest, child.getName());
            try {
                if (child.isDirectory()) copyDir(child, target);
                else java.nio.file.Files.copy(child.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception ignored) {}
        }
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] children = f.isDirectory() ? f.listFiles() : null;
        if (children != null) for (File child : children) deleteRecursive(child);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    /** All runtime-loaded provider profiles (custom + plugins). */
    public static synchronized List<AiProviderProfile> overlayProfiles() {
        List<AiProviderProfile> out = new ArrayList<>(customProfiles());
        Set<String> seen = new HashSet<>();
        for (AiProviderProfile p : out) seen.add(p.id);
        for (AiProviderProfile p : AiProviderProfile.PROFILES) seen.add(p.id);
        for (PluginPackage pkg : loadPlugins()) {
            if (!pkg.enabled || pkg.version.isEmpty()) continue;
            for (AiProviderProfile p : pkg.providers) {
                if (seen.add(p.id)) out.add(p);
            }
        }
        return out;
    }
}
