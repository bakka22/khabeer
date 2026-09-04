package com.termux.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.text.TextUtils;
import android.database.sqlite.SQLiteOpenHelper;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.UUID;
import java.util.ArrayList;
import java.util.List;

/** Small durable store for AI runs and protocol events. */
public final class AiDatabase extends SQLiteOpenHelper {

    private static final String TAG = "AiDatabase";
    private static final String DATABASE_NAME = "termux_ai_runtime.db";
    private static final int DATABASE_VERSION = 15;

    public static final class RunRecord {
        public String id;
        public String harnessId;
        public String workspace;
        public String threadId;
        public String turnId;
        public AiRunStateMachine.State state;
        public String lastError;
        public long updatedAt;
        public long createdAt;
        public String sessionKey;
        public String parentSessionId;
        public String previousResponseId;
        public String chatMessagesJson;
        public boolean resumePending;
        public String activeTurnToken;
        public long activeTurnStartedAt;
        public int hygieneFailureStreak;
        public long compressionCooldownUntil;
        public String modelOverride;
        public String lastResolvedModel;
        public boolean archived;
        public String title;
        public String titleSource;
        public String route;
    }

    public AiDatabase(Context context) {
        super(context.getApplicationContext(), DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        createV4Schema(db);
        createV8Schema(db);
        createV10Schema(db);
        createV11Schema(db);
        createV12Schema(db);
        createV13Schema(db);
    }

    private void createV8Schema(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS mcp_servers (" +
            "name TEXT PRIMARY KEY," +
            "transport TEXT DEFAULT 'http'," +
            "url TEXT," +
            "command TEXT," +
            "args_json TEXT," +
            "auth_type TEXT DEFAULT 'none'," +
            "timeout_seconds INTEGER DEFAULT 60," +
            "enabled INTEGER DEFAULT 1," +
            "trust TEXT DEFAULT 'untrusted'," +
            "last_status TEXT," +
            "last_tools_json TEXT," +
            "updated_at INTEGER DEFAULT 0)");
    }

    private void createV9Schema(SQLiteDatabase db) {
        try { db.execSQL("ALTER TABLE mcp_servers ADD COLUMN transport TEXT DEFAULT 'http'"); } catch (Exception ignored) {}
        try { db.execSQL("ALTER TABLE mcp_servers ADD COLUMN command TEXT"); } catch (Exception ignored) {}
        try { db.execSQL("ALTER TABLE mcp_servers ADD COLUMN args_json TEXT"); } catch (Exception ignored) {}
    }

    private void createV10Schema(SQLiteDatabase db) {
        try { db.execSQL("ALTER TABLE mcp_servers ADD COLUMN oauth_json TEXT"); } catch (Exception ignored) {}
    }

    /** True only when this SQLite build can actually USE an FTS5 table.
     * Full create/insert/match/drop cycle: some builds parse the CREATE
     * but fail only on first use (which is exactly when triggers would
     * start aborting every message insert), so existence checks alone
     * are not enough. */
    private static boolean fts5Available(SQLiteDatabase db) {
        try {
            db.execSQL("DROP TABLE IF EXISTS fts_probe");
            db.execSQL("CREATE VIRTUAL TABLE fts_probe USING fts5(x)");
            db.execSQL("INSERT INTO fts_probe(x) VALUES ('probe')");
            android.database.Cursor c = db.rawQuery("SELECT COUNT(*) FROM fts_probe WHERE fts_probe MATCH 'probe'", null);
            boolean ok = false;
            try { ok = c.moveToFirst() && c.getInt(0) == 1; } finally { c.close(); }
            db.execSQL("DROP TABLE IF EXISTS fts_probe");
            return ok;
        } catch (Exception e) {
            try { db.execSQL("DROP TABLE IF EXISTS fts_probe"); } catch (Exception ignored) {}
            return false;
        }
    }

    private void createV11Schema(SQLiteDatabase db) {
        // Per-statement guards: one vendor-specific failure must not nuke the
        // rest of the FTS setup, and the cause must reach logcat. Android
        // vendor SQLite builds can omit FTS5 — session_search then falls back
        // to LIKE without changing the model-facing tool contract.
        if (!fts5Available(db)) {
            try { android.util.Log.w(TAG, "schema fts5 skipped: no fts5 module in this SQLite build"); } catch (Exception ignored) {}
            return;
        }
        execSchema(db, "fts5-table", "CREATE VIRTUAL TABLE IF NOT EXISTS messages_fts USING fts5(" +
            "content, role UNINDEXED, session_id UNINDEXED, message_id UNINDEXED)");
        if (!ftsTableExists(db, "messages_fts")) return;
        execSchema(db, "fts5-insert-trigger", "CREATE TRIGGER IF NOT EXISTS messages_ai_fts AFTER INSERT ON messages BEGIN " +
            "INSERT INTO messages_fts(rowid, content, role, session_id, message_id) VALUES (new.id, new.content, new.role, new.session_id, new.id); END");
        execSchema(db, "fts5-delete-trigger", "CREATE TRIGGER IF NOT EXISTS messages_ad_fts AFTER DELETE ON messages BEGIN " +
            "DELETE FROM messages_fts WHERE rowid = old.id; END");
        execSchema(db, "fts5-update-trigger", "CREATE TRIGGER IF NOT EXISTS messages_au_fts AFTER UPDATE ON messages BEGIN " +
            "DELETE FROM messages_fts WHERE rowid = old.id; " +
            "INSERT INTO messages_fts(rowid, content, role, session_id, message_id) VALUES (new.id, new.content, new.role, new.session_id, new.id); END");
        execSchema(db, "fts5-backfill", "INSERT OR IGNORE INTO messages_fts(rowid, content, role, session_id, message_id) " +
            "SELECT id, content, role, session_id, id FROM messages WHERE active=1");
    }

    private static void execSchema(SQLiteDatabase db, String label, String sql) {
        try {
            db.execSQL(sql);
        } catch (Exception e) {
            try { android.util.Log.w(TAG, "schema step " + label + " skipped: " + e.getMessage()); } catch (Exception ignored) {}
        }
    }

    private void createV12Schema(SQLiteDatabase db) {
        try { db.execSQL("ALTER TABLE messages ADD COLUMN compacted INTEGER DEFAULT 0"); } catch (Exception ignored) {}
        try { db.execSQL("ALTER TABLE messages ADD COLUMN _compressed_summary INTEGER DEFAULT 0"); } catch (Exception ignored) {}
        try { db.execSQL("ALTER TABLE messages ADD COLUMN api_content TEXT"); } catch (Exception ignored) {}
        try { db.execSQL("CREATE INDEX IF NOT EXISTS messages_compaction_flags ON messages(session_id, active, compacted, _compressed_summary, id)"); } catch (Exception ignored) {}
    }

    /** Trigram FTS for substring/CJK-tolerant search (Hermes
     * messages_fts_trigram). Best-effort: vendor SQLite builds without the
     * trigram tokenizer throw here and search falls back to FTS5, then LIKE.
     * Nothing in the model-facing contract changes. */
    private static boolean ftsTableExists(SQLiteDatabase db, String table) {
        android.database.Cursor c = null;
        try {
            c = db.rawQuery("SELECT COUNT(*) FROM sqlite_master WHERE type IN ('table','virtual') AND name=?",
                new String[]{table});
            return c.moveToFirst() && c.getInt(0) > 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.close();
        }
    }

    private void createV13Schema(SQLiteDatabase db) {
        if (!fts5Available(db)) return;
        execSchema(db, "trigram-table", "CREATE VIRTUAL TABLE IF NOT EXISTS messages_fts_trigram USING fts5(" +
            "content, role UNINDEXED, session_id UNINDEXED, message_id UNINDEXED, tokenize='trigram')");
        if (!ftsTableExists(db, "messages_fts_trigram")) return;
        execSchema(db, "trigram-insert-trigger", "CREATE TRIGGER IF NOT EXISTS messages_ai_trigram AFTER INSERT ON messages BEGIN " +
            "INSERT INTO messages_fts_trigram(rowid, content, role, session_id, message_id) VALUES (new.id, new.content, new.role, new.session_id, new.id); END");
        execSchema(db, "trigram-delete-trigger", "CREATE TRIGGER IF NOT EXISTS messages_ad_trigram AFTER DELETE ON messages BEGIN " +
            "DELETE FROM messages_fts_trigram WHERE rowid = old.id; END");
        execSchema(db, "trigram-update-trigger", "CREATE TRIGGER IF NOT EXISTS messages_au_trigram AFTER UPDATE ON messages BEGIN " +
            "DELETE FROM messages_fts_trigram WHERE rowid = old.id; " +
            "INSERT INTO messages_fts_trigram(rowid, content, role, session_id, message_id) VALUES (new.id, new.content, new.role, new.session_id, new.id); END");
        execSchema(db, "trigram-backfill", "INSERT OR IGNORE INTO messages_fts_trigram(rowid, content, role, session_id, message_id) " +
            "SELECT id, content, role, session_id, id FROM messages");
    }

    /** One MCP server configuration: transport 'http' (Streamable HTTP, url)
     * or 'stdio' (local command spawned in the Termux environment). Auth
     * tokens never live here — they are Keystore-encrypted via
     * AiProviderConfig. */
    public static final class McpServerRecord {
        public String name;
        public String transport = "http";  // http | stdio
        public String url;
        public String command;             // stdio only
        public String argsJson;            // stdio only, JSON array of strings
        public String authType = "none";   // none | header | oauth
        public int timeoutSeconds = 60;
        public boolean enabled = true;
        public String trust = "untrusted"; // untrusted | trusted
        public String lastStatus;          // connected | disabled | failed: <msg>
        public String lastToolsJson;       // cached tool list JSON
        public String oauthJson;           // OAuth client/endpoint state (v10)
        public long updatedAt;
    }

    public synchronized java.util.List<McpServerRecord> getMcpServers() {
        java.util.List<McpServerRecord> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query("mcp_servers", null, null, null, null, null, "name ASC");
        try { while (c.moveToNext()) out.add(mcpServerFromCursor(c)); } finally { c.close(); }
        return out;
    }

    @Nullable
    public synchronized McpServerRecord getMcpServer(String name) {
        if (name == null) return null;
        Cursor c = getReadableDatabase().query("mcp_servers", null, "name=?", new String[]{name}, null, null, null);
        try { return c.moveToFirst() ? mcpServerFromCursor(c) : null; } finally { c.close(); }
    }

    private McpServerRecord mcpServerFromCursor(Cursor c) {
        McpServerRecord r = new McpServerRecord();
        r.name = c.getString(c.getColumnIndexOrThrow("name"));
        r.transport = c.getString(c.getColumnIndexOrThrow("transport"));
        if (TextUtils.isEmpty(r.transport)) r.transport = "http";
        r.url = c.getString(c.getColumnIndexOrThrow("url"));
        r.command = c.getString(c.getColumnIndexOrThrow("command"));
        r.argsJson = c.getString(c.getColumnIndexOrThrow("args_json"));
        r.authType = c.getString(c.getColumnIndexOrThrow("auth_type"));
        r.timeoutSeconds = c.getInt(c.getColumnIndexOrThrow("timeout_seconds"));
        r.enabled = c.getInt(c.getColumnIndexOrThrow("enabled")) == 1;
        r.trust = c.getString(c.getColumnIndexOrThrow("trust"));
        r.lastStatus = c.getString(c.getColumnIndexOrThrow("last_status"));
        r.lastToolsJson = c.getString(c.getColumnIndexOrThrow("last_tools_json"));
        int oauthIdx = c.getColumnIndex("oauth_json");
        if (oauthIdx >= 0) r.oauthJson = c.getString(oauthIdx);
        r.updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at"));
        return r;
    }

    public synchronized void saveMcpServer(McpServerRecord r) {
        ContentValues v = new ContentValues();
        v.put("name", r.name);
        v.put("transport", TextUtils.isEmpty(r.transport) ? "http" : r.transport);
        v.put("url", r.url);
        v.put("command", r.command);
        v.put("args_json", r.argsJson);
        v.put("auth_type", r.authType);
        v.put("timeout_seconds", r.timeoutSeconds);
        v.put("enabled", r.enabled ? 1 : 0);
        v.put("trust", r.trust);
        v.put("last_status", r.lastStatus);
        v.put("last_tools_json", r.lastToolsJson);
        if (r.oauthJson != null) v.put("oauth_json", r.oauthJson);
        v.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("mcp_servers", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized void deleteMcpServer(String name) {
        getWritableDatabase().delete("mcp_servers", "name=?", new String[]{name});
    }

    /** After a home-directory migration, rewrite stored paths so stdio MCP
     *  server commands keep pointing at the moved files. */
    public synchronized void rewriteMcpServerDataRoot(String from, String to) {
        try {
            getWritableDatabase().execSQL(
                "UPDATE mcp_servers SET " +
                "url = REPLACE(url, ?, ?), " +
                "command = REPLACE(command, ?, ?), " +
                "args_json = REPLACE(args_json, ?, ?), " +
                "last_tools_json = REPLACE(last_tools_json, ?, ?) " +
                "WHERE url LIKE ? ESCAPE '\\' OR command LIKE ? ESCAPE '\\' " +
                "OR args_json LIKE ? ESCAPE '\\' OR last_tools_json LIKE ? ESCAPE '\\'",
                new Object[]{from, to, from, to, from, to, from, to,
                    "%" + from + "%", "%" + from + "%", "%" + from + "%", "%" + from + "%"});
        } catch (Exception ignored) {}
    }

    public synchronized void setMcpServerEnabled(String name, boolean enabled) {
        ContentValues v = new ContentValues();
        v.put("enabled", enabled ? 1 : 0);
        v.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update("mcp_servers", v, "name=?", new String[]{name});
    }

    public synchronized void setMcpServerStatus(String name, String status, @Nullable String toolsJson) {
        ContentValues v = new ContentValues();
        v.put("last_status", status);
        if (toolsJson != null) v.put("last_tools_json", toolsJson);
        v.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update("mcp_servers", v, "name=?", new String[]{name});
    }

    private void createV4Schema(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS runs (" +
            "id TEXT PRIMARY KEY," +
            "harness_id TEXT NOT NULL," +
            "workspace TEXT NOT NULL," +
            "thread_id TEXT," +
            "turn_id TEXT," +
            "state TEXT NOT NULL," +
            "last_error TEXT," +
            "created_at INTEGER NOT NULL," +
            "updated_at INTEGER NOT NULL," +
            "session_key TEXT," +
            "parent_session_id TEXT," +
            "previous_response_id TEXT," +
            "chat_messages_json TEXT," +
            "resume_pending INTEGER DEFAULT 0," +
            "active_turn_token TEXT," +
            "active_turn_started_at INTEGER DEFAULT 0," +
            "hygiene_failure_streak INTEGER DEFAULT 0," +
            "compression_cooldown_until INTEGER DEFAULT 0," +
            "model_override TEXT," +
            "last_resolved_model TEXT," +
            "archived INTEGER DEFAULT 0," +
            "title TEXT," +
            "title_source TEXT," +
            "route TEXT)");
        db.execSQL("CREATE TABLE IF NOT EXISTS events (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "run_id TEXT NOT NULL," +
            "method TEXT NOT NULL," +
            "payload TEXT NOT NULL," +
            "created_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS events_run_id_index ON events(run_id, id)");
        db.execSQL("CREATE TABLE IF NOT EXISTS messages (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "session_id TEXT NOT NULL," +
            "role TEXT NOT NULL," +
            "content TEXT NOT NULL," +
            "active INTEGER DEFAULT 1," +
            "created_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS messages_session_active ON messages(session_id, active, id)");
        db.execSQL("CREATE TABLE IF NOT EXISTS session_turn_leases (" +
            "session_key TEXT PRIMARY KEY," +
            "token TEXT NOT NULL," +
            "generation INTEGER NOT NULL," +
            "started_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS runs_session_key ON runs(session_key)");
        db.execSQL("CREATE INDEX IF NOT EXISTS runs_resume_pending ON runs(resume_pending)");
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        // Rollback journal instead of WAL: the runtime is SIGKILLed often
        // (force-stops, MIUI), and stale WAL sidecars kept reverting reads
        // to pre-title pages. A single-file journal cannot disagree with
        // itself; write volume here is tiny.
        try { db.execSQL("PRAGMA journal_mode=TRUNCATE"); } catch (Exception ignored) {}
        db.setForeignKeyConstraintsEnabled(true);
    }

    private static boolean sFtsProbed;

    @Override
    public void onOpen(SQLiteDatabase db) {
        try { db.execSQL("PRAGMA synchronous=NORMAL"); } catch (Exception ignored) {}
        if (!sFtsProbed) {
            sFtsProbed = true;
            try {
                boolean fts5 = false;
                boolean trigram = false;
                android.database.Cursor c = db.rawQuery("PRAGMA compile_options", null);
                try {
                    while (c.moveToNext()) {
                        String opt = c.getString(0);
                        if (opt != null && opt.contains("FTS5")) fts5 = true;
                    }
                } finally { c.close(); }
                try {
                    db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS fts_probe USING fts5(x, tokenize='trigram')");
                    trigram = true;
                    db.execSQL("DROP TABLE IF EXISTS fts_probe");
                } catch (Exception ignored) {}
                try { android.util.Log.i(TAG, "sqlite fts5=" + fts5 + " trigram=" + trigram); } catch (Exception ignored) {}
            } catch (Exception e) {
                try { android.util.Log.w(TAG, "fts probe failed: " + e.getMessage()); } catch (Exception ignored) {}
            }
        }
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("CREATE TABLE IF NOT EXISTS events (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "run_id TEXT NOT NULL," +
                "method TEXT NOT NULL," +
                "payload TEXT NOT NULL," +
                "created_at INTEGER NOT NULL)");
            db.execSQL("CREATE INDEX IF NOT EXISTS events_run_id_index ON events(run_id, id)");
        }
        if (oldVersion < 3) {
            try { db.execSQL("ALTER TABLE runs ADD COLUMN session_key TEXT"); } catch (Exception ignored) {}
            try { db.execSQL("ALTER TABLE runs ADD COLUMN parent_session_id TEXT"); } catch (Exception ignored) {}
            try { db.execSQL("ALTER TABLE runs ADD COLUMN previous_response_id TEXT"); } catch (Exception ignored) {}
            try { db.execSQL("ALTER TABLE runs ADD COLUMN chat_messages_json TEXT"); } catch (Exception ignored) {}
            try { db.execSQL("ALTER TABLE runs ADD COLUMN resume_pending INTEGER DEFAULT 0"); } catch (Exception ignored) {}
            try { db.execSQL("ALTER TABLE runs ADD COLUMN active_turn_token TEXT"); } catch (Exception ignored) {}
            try { db.execSQL("ALTER TABLE runs ADD COLUMN active_turn_started_at INTEGER DEFAULT 0"); } catch (Exception ignored) {}
            try { db.execSQL("ALTER TABLE runs ADD COLUMN hygiene_failure_streak INTEGER DEFAULT 0"); } catch (Exception ignored) {}
            try { db.execSQL("ALTER TABLE runs ADD COLUMN compression_cooldown_until INTEGER DEFAULT 0"); } catch (Exception ignored) {}
            try { db.execSQL("ALTER TABLE runs ADD COLUMN model_override TEXT"); } catch (Exception ignored) {}
            try { db.execSQL("ALTER TABLE runs ADD COLUMN last_resolved_model TEXT"); } catch (Exception ignored) {}
            db.execSQL("CREATE TABLE IF NOT EXISTS messages (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "session_id TEXT NOT NULL," +
                "role TEXT NOT NULL," +
                "content TEXT NOT NULL," +
                "active INTEGER DEFAULT 1," +
                "created_at INTEGER NOT NULL)");
            db.execSQL("CREATE INDEX IF NOT EXISTS messages_session_active ON messages(session_id, active, id)");
            db.execSQL("CREATE TABLE IF NOT EXISTS session_turn_leases (" +
                "session_key TEXT PRIMARY KEY," +
                "token TEXT NOT NULL," +
                "generation INTEGER NOT NULL," +
                "started_at INTEGER NOT NULL)");
            db.execSQL("CREATE INDEX IF NOT EXISTS runs_session_key ON runs(session_key)");
        }
        if (oldVersion < 4) {
            db.execSQL("CREATE INDEX IF NOT EXISTS runs_resume_pending ON runs(resume_pending)");
        }
        if (oldVersion < 5) {
            try { db.execSQL("ALTER TABLE runs ADD COLUMN archived INTEGER DEFAULT 0"); } catch (Exception ignored) {}
            try { db.execSQL("ALTER TABLE runs ADD COLUMN title TEXT"); } catch (Exception ignored) {}
            db.execSQL("CREATE INDEX IF NOT EXISTS runs_archived ON runs(archived, updated_at)");
            backfillSessionTitles(db);
        }
        if (oldVersion < 7) {
            try { db.execSQL("ALTER TABLE runs ADD COLUMN route TEXT"); } catch (Exception ignored) {}
        }
        if (oldVersion < 8) {
            createV8Schema(db);
        }
        if (oldVersion < 9) {
            createV9Schema(db);
        }
        if (oldVersion < 10) {
            createV10Schema(db);
        }
        if (oldVersion < 11) {
            createV11Schema(db);
        }
        if (oldVersion < 12) {
            createV12Schema(db);
        }
        if (oldVersion < 13) {
            createV13Schema(db);
        }
        if (oldVersion < 15) {
            // Heal installs whose FTS setup was skipped by the old
            // all-or-nothing v11/v13 blocks. All statements are idempotent.
            createV11Schema(db);
            createV13Schema(db);
            // Remove orphan FTS triggers that point at tables the vendor
            // SQLite never created: they would abort every message insert.
            if (!ftsTableExists(db, "messages_fts")) {
                execSchema(db, "drop-orphan-fts-triggers",
                    "DROP TRIGGER IF EXISTS messages_ai_fts");
                execSchema(db, "drop-orphan-fts-triggers-del",
                    "DROP TRIGGER IF EXISTS messages_ad_fts");
                execSchema(db, "drop-orphan-fts-triggers-upd",
                    "DROP TRIGGER IF EXISTS messages_au_fts");
            }
            if (!ftsTableExists(db, "messages_fts_trigram")) {
                execSchema(db, "drop-orphan-trigram-triggers",
                    "DROP TRIGGER IF EXISTS messages_ai_trigram");
                execSchema(db, "drop-orphan-trigram-triggers-del",
                    "DROP TRIGGER IF EXISTS messages_ad_trigram");
                execSchema(db, "drop-orphan-trigram-triggers-upd",
                    "DROP TRIGGER IF EXISTS messages_au_trigram");
            }
        }
        if (oldVersion < 6) {
            // Title provenance (khabeer title_source): 'message' = derived from
            // the first user message, 'ai' = model-generated summary. Existing
            // titles are message-derived and may be regenerated by the AI
            // titler; AI titles are never overwritten.
            try { db.execSQL("ALTER TABLE runs ADD COLUMN title_source TEXT"); } catch (Exception ignored) {}
            try { db.execSQL("UPDATE runs SET title_source='message' WHERE title IS NOT NULL AND title_source IS NULL"); } catch (Exception ignored) {}
        }
    }

    /** Pre-v5 sessions have history but no title; without a backfill the
     * drawer filter (title IS NOT NULL) would hide them forever. Derive each
     * title from the session's first active user message. Sessions that never
     * got a user message stay untitled and stay hidden (ghost rule). */
    private void backfillSessionTitles(SQLiteDatabase db) {
        List<String> untitled = new ArrayList<>();
        Cursor c = db.query("runs", new String[]{"id"}, "title IS NULL", null, null, null, null);
        try { while (c.moveToNext()) untitled.add(c.getString(0)); } finally { c.close(); }
        for (String runId : untitled) {
            Cursor m = db.query("messages", new String[]{"content"},
                "session_id=? AND role='user' AND active=1", new String[]{runId}, null, null, "id ASC", "1");
            String title = null;
            try { if (m.moveToFirst()) title = boundTitle(m.getString(0)); } finally { m.close(); }
            if (title == null) continue;
            ContentValues v = new ContentValues();
            v.put("title", title);
            db.update("runs", v, "id=?", new String[]{runId});
        }
    }

    public synchronized RunRecord createRun(String harnessId, String workspace) {
        return createRun(harnessId, workspace, buildSessionKey(harnessId, workspace));
    }

    public synchronized RunRecord createRun(String harnessId, String workspace, String sessionKey) {
        RunRecord record = new RunRecord();
        record.id = UUID.randomUUID().toString();
        record.harnessId = harnessId;
        record.workspace = workspace;
        record.sessionKey = sessionKey;
        record.state = AiRunStateMachine.State.CREATED;
        record.updatedAt = System.currentTimeMillis();
        record.createdAt = record.updatedAt;
        saveRun(record);
        return record;
    }

    public static String buildSessionKey(String harnessId, String workspace) {
        String ns = "main";
        String safeWs = workspace == null ? "default" : workspace.replaceAll("[^a-zA-Z0-9._-]", "_");
        return "agent:" + ns + ":mobile:" + (harnessId == null ? "native" : harnessId) + ":" + safeWs;
    }

    public synchronized void saveRun(RunRecord record) {
        long now = System.currentTimeMillis();
        if (record.updatedAt == 0) record.updatedAt = now;
        if (record.createdAt == 0) record.createdAt = now;

        ContentValues values = new ContentValues();
        values.put("id", record.id);
        values.put("harness_id", record.harnessId);
        values.put("workspace", record.workspace);
        values.put("thread_id", record.threadId);
        values.put("turn_id", record.turnId);
        values.put("state", record.state.name());
        values.put("last_error", record.lastError);
        values.put("updated_at", record.updatedAt);
        values.put("session_key", record.sessionKey);
        values.put("parent_session_id", record.parentSessionId);
        values.put("previous_response_id", record.previousResponseId);
        values.put("chat_messages_json", record.chatMessagesJson);
        values.put("resume_pending", record.resumePending ? 1 : 0);
        values.put("active_turn_token", record.activeTurnToken);
        values.put("active_turn_started_at", record.activeTurnStartedAt);
        values.put("hygiene_failure_streak", record.hygieneFailureStreak);
        values.put("compression_cooldown_until", record.compressionCooldownUntil);
        values.put("model_override", record.modelOverride);
        values.put("last_resolved_model", record.lastResolvedModel);
        values.put("archived", record.archived ? 1 : 0);
        values.put("title", record.title);
        values.put("title_source", record.titleSource);
        values.put("route", record.route);

        SQLiteDatabase db = getWritableDatabase();
        if (db.update("runs", values, "id = ?", new String[]{record.id}) == 0) {
            values.put("created_at", record.createdAt);
            db.insertOrThrow("runs", null, values);
        }
    }

    public synchronized void setResumePending(String runId, boolean pending) {
        ContentValues v = new ContentValues();
        v.put("resume_pending", pending ? 1 : 0);
        v.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update("runs", v, "id=?", new String[]{runId});
    }

    public synchronized List<RunRecord> getResumePendingRuns() {
        List<RunRecord> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query("runs", null, "resume_pending=1", null, null, null, "updated_at DESC");
        try { while (c.moveToNext()) out.add(readRun(c)); } finally { c.close(); }
        return out;
    }

    public synchronized void appendMessage(String sessionId, String role, String content) {
        ContentValues v = new ContentValues();
        v.put("session_id", sessionId);
        v.put("role", role);
        v.put("content", content);
        v.put("active", 1);
        v.put("created_at", System.currentTimeMillis());
        getWritableDatabase().insertOrThrow("messages", null, v);
        // Session listing mirrors khabeer: last_active ordering (fresh message
        // bumps recency) and preview = first user message, bounded to 60 chars.
        ContentValues runUpdate = new ContentValues();
        runUpdate.put("updated_at", System.currentTimeMillis());
        if ("user".equals(role)) {
            Cursor c = getReadableDatabase().query("runs", new String[]{"title"}, "id=?", new String[]{sessionId}, null, null, null);
            boolean untitled = true;
            try { if (c.moveToFirst()) untitled = c.isNull(0); } finally { c.close(); }
            if (untitled) {
                runUpdate.put("title", boundTitle(content));
                runUpdate.put("title_source", "message");
            }
        }
        getWritableDatabase().update("runs", runUpdate, "id=?", new String[]{sessionId});
    }

    /** Searchable, durable tool transcript rows. They are intentionally not
     * active replay rows: provider replay needs structured tool_call IDs kept
     * in chatMessagesJson, while session_search/compaction need a searchable
     * record of what tools were requested and returned. */
    public synchronized void appendToolTranscript(String sessionId, String content, String apiContent) {
        ContentValues v = new ContentValues();
        v.put("session_id", sessionId);
        v.put("role", "tool");
        v.put("content", content == null ? "" : content);
        v.put("active", 0);
        v.put("compacted", 0);
        v.put("_compressed_summary", 0);
        v.put("api_content", apiContent == null ? "" : apiContent);
        v.put("created_at", System.currentTimeMillis());
        getWritableDatabase().insertOrThrow("messages", null, v);
        ContentValues runUpdate = new ContentValues();
        runUpdate.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update("runs", runUpdate, "id=?", new String[]{sessionId});
    }

    public synchronized void appendCompactionSummary(String sessionId, String content) {
        ContentValues v = new ContentValues();
        v.put("session_id", sessionId);
        v.put("role", "user");
        v.put("content", content);
        v.put("active", 1);
        v.put("compacted", 0);
        v.put("_compressed_summary", 1);
        v.put("created_at", System.currentTimeMillis());
        getWritableDatabase().insertOrThrow("messages", null, v);
    }

    public synchronized JSONObject compactSession(String sessionId, String summary, int protectLast) {
        JSONObject out = new JSONObject();
        SQLiteDatabase db = getWritableDatabase();
        int protectedTail = Math.max(1, protectLast);
        ArrayList<Long> protectedIds = new ArrayList<>();
        long cutoffId = Long.MAX_VALUE;
        int archived = 0;
        long summaryId = -1;
        db.beginTransaction();
        try {
            Cursor tail = db.query("messages", new String[]{"id"},
                "session_id=? AND active=1 AND COALESCE(_compressed_summary, 0)=0",
                new String[]{sessionId}, null, null, "id DESC", String.valueOf(protectedTail));
            try {
                while (tail.moveToNext()) {
                    long id = tail.getLong(0);
                    protectedIds.add(id);
                    if (id < cutoffId) cutoffId = id;
                }
            } finally {
                tail.close();
            }
            if (protectedIds.size() < 2) {
                return out.put("success", false).put("error", "Not enough active transcript messages to compact.");
            }
            ContentValues archivedValues = new ContentValues();
            archivedValues.put("active", 0);
            archivedValues.put("compacted", 1);
            archived = db.update("messages", archivedValues,
                "session_id=? AND active=1 AND COALESCE(_compressed_summary, 0)=0 AND id<?",
                new String[]{sessionId, String.valueOf(cutoffId)});
            ContentValues summaryValues = new ContentValues();
            summaryValues.put("session_id", sessionId);
            summaryValues.put("role", "user");
            summaryValues.put("content", summary);
            summaryValues.put("active", 1);
            summaryValues.put("compacted", 0);
            summaryValues.put("_compressed_summary", 1);
            summaryValues.put("created_at", System.currentTimeMillis());
            summaryId = db.insertOrThrow("messages", null, summaryValues);
            ContentValues runUpdate = new ContentValues();
            runUpdate.put("updated_at", System.currentTimeMillis());
            db.update("runs", runUpdate, "id=?", new String[]{sessionId});
            db.setTransactionSuccessful();
            out.put("success", true);
            out.put("archived_messages", archived);
            out.put("summary_message_id", summaryId);
            out.put("protected_tail", protectedIds.size());
            return out;
        } catch (Exception e) {
            try { out.put("success", false).put("error", "Compaction failed: " + e.getMessage()); } catch (Exception ignored) {}
            return out;
        } finally {
            db.endTransaction();
        }
    }

    private static String boundTitle(String content) {
        if (content == null) return null;
        String clean = content.replace('\n', ' ').replace('\r', ' ').trim();
        while (clean.contains("  ")) clean = clean.replace("  ", " ");
        if (clean.length() <= 60) return clean.isEmpty() ? null : clean;
        return clean.substring(0, 59) + "…";
    }

    public synchronized List<String> getActiveMessages(String sessionId, int limit) {
        List<String> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query("messages", new String[]{"content"}, "session_id=? AND active=1", new String[]{sessionId}, null, null, "id ASC", String.valueOf(limit));
        try { while (c.moveToNext()) out.add(c.getString(0)); } finally { c.close(); }
        return out;
    }

    public synchronized int countUserMessages(String sessionId) {
        Cursor c = getReadableDatabase().rawQuery(
            "SELECT COUNT(*) FROM messages WHERE session_id=? AND active=1 AND role='user'",
            new String[]{sessionId});
        try { return c.moveToFirst() ? c.getInt(0) : 0; } finally { c.close(); }
    }

    /** Nudge counter: counts every user turn including compacted history so
     * archiving the replay never resets the review cadence (Hermes
     * prior_user_turns hydration). */
    public synchronized int countAllUserMessages(String sessionId) {
        Cursor c = getReadableDatabase().rawQuery(
            "SELECT COUNT(*) FROM messages WHERE session_id=? AND role='user'",
            new String[]{sessionId});
        try { return c.moveToFirst() ? c.getInt(0) : 0; } finally { c.close(); }
    }

    /** One-time hygiene: older builds persisted the final chat reply twice
     * (once via the agentMessage/delta emit, once via an explicit append), so
     * rebuilt transcripts showed every chat reply doubled. Collapses each run
     * of identical consecutive assistant rows to its first row. */
    public synchronized void dedupeAssistantMessages() {
        try {
            getWritableDatabase().execSQL("DELETE FROM messages WHERE id IN ("
                + "SELECT m.id FROM messages m WHERE m.role='assistant' AND trim(m.content) != ''"
                + " AND EXISTS (SELECT 1 FROM messages p WHERE p.session_id = m.session_id AND p.id < m.id"
                + " AND p.role='assistant' AND p.content = m.content"
                + " AND NOT EXISTS (SELECT 1 FROM messages g WHERE g.session_id = m.session_id AND g.id > p.id AND g.id < m.id)))");
        } catch (Exception ignored) {}
    }

    public synchronized void markTurnLease(String sessionKey, String token, int generation) {
        ContentValues v = new ContentValues();
        v.put("session_key", sessionKey);
        v.put("token", token);
        v.put("generation", generation);
        v.put("started_at", System.currentTimeMillis());
        SQLiteDatabase db = getWritableDatabase();
        if (db.update("session_turn_leases", v, "session_key=?", new String[]{sessionKey}) == 0)
            db.insertOrThrow("session_turn_leases", null, v);
    }

    public synchronized void clearTurnLease(String sessionKey, int generation) {
        Cursor c = getReadableDatabase().query("session_turn_leases", new String[]{"generation"}, "session_key=?", new String[]{sessionKey}, null, null, null);
        try {
            if (c.moveToFirst() && c.getInt(0) == generation)
                getWritableDatabase().delete("session_turn_leases", "session_key=?", new String[]{sessionKey});
        } finally { c.close(); }
    }

    public synchronized void appendEvent(String runId, String method, String payload) {
        ContentValues values = new ContentValues();
        values.put("run_id", runId);
        values.put("method", method);
        values.put("payload", payload);
        values.put("created_at", System.currentTimeMillis());
        getWritableDatabase().insertOrThrow("events", null, values);
        getWritableDatabase().delete("events",
            "run_id = ? AND id NOT IN (SELECT id FROM events WHERE run_id = ? ORDER BY id DESC LIMIT 2000)",
            new String[]{runId, runId});
    }

    @Nullable
    public synchronized RunRecord getRun(String runId) {
        Cursor cursor = getReadableDatabase().query("runs", null, "id = ?", new String[]{runId}, null, null, null);
        try {
            return cursor.moveToFirst() ? readRun(cursor) : null;
        } finally {
            cursor.close();
        }
    }

    @Nullable
    public synchronized RunRecord getLatestActiveRun() {
        Cursor cursor = getReadableDatabase().query("runs", null,
            "state NOT IN (?, ?, ?) AND COALESCE(archived, 0) = 0",
            new String[]{AiRunStateMachine.State.COMPLETED.name(), AiRunStateMachine.State.FAILED.name(), AiRunStateMachine.State.CANCELED.name()},
            null, null, "updated_at DESC", "1");
        try {
            return cursor.moveToFirst() ? readRun(cursor) : null;
        } finally {
            cursor.close();
        }
    }

    public synchronized List<RunRecord> getRecentRuns(int limit) {
        List<RunRecord> records = new ArrayList<>();
        Cursor cursor = getReadableDatabase().query("runs", null, null, null, null, null,
            "updated_at DESC", String.valueOf(limit));
        try {
            while (cursor.moveToNext()) records.add(readRun(cursor));
        } finally {
            cursor.close();
        }
        return records;
    }

    /** Sessions with a real opening exchange but no title yet — the AI
     * titler's work queue. */
    public synchronized List<RunRecord> getUntitledSessions(int limit) {
        List<RunRecord> records = new ArrayList<>();
        Cursor cursor = getReadableDatabase().query("runs", null,
            "COALESCE(archived, 0) = 0 AND title IS NULL AND EXISTS (SELECT 1 FROM messages m WHERE m.session_id = runs.id AND m.active = 1)", null, null, null,
            "updated_at DESC", String.valueOf(limit));
        try {
            while (cursor.moveToNext()) records.add(readRun(cursor));
        } finally {
            cursor.close();
        }
        return records;
    }

    /** Every non-archived session — nothing is hidden automatically;
     *  archiving is a user action from the Sessions page. */
    public synchronized List<RunRecord> getSessions(int limit) {
        List<RunRecord> records = new ArrayList<>();
        Cursor cursor = getReadableDatabase().query("runs", null,
            "COALESCE(archived, 0) = 0", null, null, null,
            "updated_at DESC", String.valueOf(limit));
        try {
            while (cursor.moveToNext()) records.add(readRun(cursor));
        } finally {
            cursor.close();
        }
        return records;
    }

    public synchronized List<RunRecord> getArchivedSessions(int limit) {
        List<RunRecord> records = new ArrayList<>();
        Cursor cursor = getReadableDatabase().query("runs", null,
            "archived = 1", null, null, null,
            "updated_at DESC", String.valueOf(limit));
        try {
            while (cursor.moveToNext()) records.add(readRun(cursor));
        } finally {
            cursor.close();
        }
        return records;
    }

    /** Archive = soft hide (khabeer set_session_archived): rows keep every
     * message and can be un-archived later. Never deletes anything. */
    public synchronized void setRunArchived(String runId, boolean archived) {
        ContentValues v = new ContentValues();
        v.put("archived", archived ? 1 : 0);
        v.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update("runs", v, "id=?", new String[]{runId});
    }

    public synchronized JSONArray getTranscript(String sessionId, int limit) {
        JSONArray out = new JSONArray();
        Cursor c = getReadableDatabase().query("messages",
            new String[]{"id", "role", "content", "created_at"}, "session_id=? AND active=1",
            new String[]{sessionId}, null, null, "_compressed_summary DESC, id ASC", String.valueOf(limit));
        try {
            while (c.moveToNext()) {
                try {
                    JSONObject row = new JSONObject();
                    row.put("id", c.getLong(0));
                    row.put("role", c.getString(1));
                    row.put("content", c.getString(2));
                    row.put("created_at", c.getLong(3));
                    out.put(row);
                } catch (Exception ignored) {}
            }
        } finally { c.close(); }
        return out;
    }

    public synchronized JSONArray getHistoricalTranscript(String sessionId, int limit) {
        JSONArray out = new JSONArray();
        Cursor c = getReadableDatabase().query("messages",
            new String[]{"id", "role", "content", "created_at"}, "session_id=?",
            new String[]{sessionId}, null, null, "id ASC", String.valueOf(limit));
        try {
            while (c.moveToNext()) {
                try { out.put(messageRow(c)); } catch (Exception ignored) {}
            }
        } finally { c.close(); }
        return out;
    }

    public synchronized JSONObject sessionSearch(JSONObject args, String currentSessionId) {
        JSONObject out = new JSONObject();
        try {
            String query = args == null ? "" : args.optString("query", "").trim();
            String sessionId = args == null ? "" : args.optString("session_id", "").trim();
            long around = args == null ? 0 : args.optLong("around_message_id", 0);
            int limit = Math.max(1, Math.min(20, args == null ? 8 : args.optInt("limit", 8)));
            int window = Math.max(1, Math.min(20, args == null ? 5 : args.optInt("window", 5)));
            if (!TextUtils.isEmpty(sessionId) && around > 0) return messagesAround(sessionId, around, window);
            if (!TextUtils.isEmpty(sessionId)) return readSession(sessionId, Math.max(20, limit * 10));
            if (!TextUtils.isEmpty(query)) return discoverSessions(query, currentSessionId, limit, window);
            return browseSessions(limit);
        } catch (Exception e) {
            try { out.put("success", false).put("error", "session_search failed: " + e.getMessage()); } catch (Exception ignored) {}
            return out;
        }
    }

    private JSONObject browseSessions(int limit) throws Exception {
        JSONObject out = new JSONObject();
        JSONArray rows = new JSONArray();
        for (RunRecord r : getSessions(limit)) rows.put(sessionSummary(r, null));
        out.put("success", true);
        out.put("mode", "browse");
        out.put("sessions", rows);
        return out;
    }

    private JSONObject readSession(String sessionId, int limit) throws Exception {
        JSONObject out = new JSONObject();
        RunRecord r = getRun(sessionId);
        if (r == null) return out.put("success", false).put("error", "Session not found: " + sessionId);
        out.put("success", true);
        out.put("mode", "read");
        out.put("session", sessionSummary(r, null));
        int total = countSessionMessages(sessionId);
        if (total <= limit) {
            out.put("messages", getHistoricalTranscript(sessionId, limit));
            return out;
        }
        int head = 20;
        int tail = 10;
        JSONArray messages = new JSONArray();
        Cursor hc = getReadableDatabase().query("messages",
            new String[]{"id", "role", "content", "created_at"}, "session_id=?",
            new String[]{sessionId}, null, null, "id ASC", String.valueOf(head));
        try { while (hc.moveToNext()) messages.put(messageRow(hc)); } finally { hc.close(); }
        ArrayList<JSONObject> tailRows = new ArrayList<>();
        Cursor tc = getReadableDatabase().query("messages",
            new String[]{"id", "role", "content", "created_at"}, "session_id=?",
            new String[]{sessionId}, null, null, "id DESC", String.valueOf(tail));
        try { while (tc.moveToNext()) tailRows.add(messageRow(tc)); } finally { tc.close(); }
        for (int i = tailRows.size() - 1; i >= 0; i--) messages.put(tailRows.get(i));
        out.put("messages", messages);
        out.put("truncated_middle", total - head - tail);
        out.put("hint", "Middle " + (total - head - tail) + " messages hidden. Scroll with around_message_id set to a visible message id.");
        return out;
    }

    private int countSessionMessages(String sessionId) {
        Cursor c = getReadableDatabase().rawQuery(
            "SELECT COUNT(*) FROM messages WHERE session_id=?", new String[]{sessionId});
        try { return c.moveToFirst() ? c.getInt(0) : 0; } finally { c.close(); }
    }

    private JSONObject messagesAround(String sessionId, long aroundMessageId, int window) throws Exception {
        JSONObject out = new JSONObject();
        JSONArray before = new JSONArray();
        JSONArray after = new JSONArray();
        JSONObject anchor = null;
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query("messages", new String[]{"id", "role", "content", "created_at"},
            "session_id=? AND id<?", new String[]{sessionId, String.valueOf(aroundMessageId)}, null, null, "id DESC", String.valueOf(window));
        try {
            ArrayList<JSONObject> rev = new ArrayList<>();
            while (c.moveToNext()) rev.add(messageRow(c));
            for (int i = rev.size() - 1; i >= 0; i--) before.put(rev.get(i));
        } finally { c.close(); }
        c = db.query("messages", new String[]{"id", "role", "content", "created_at"},
            "session_id=? AND id=?", new String[]{sessionId, String.valueOf(aroundMessageId)}, null, null, null, "1");
        try { if (c.moveToFirst()) anchor = messageRow(c); } finally { c.close(); }
        c = db.query("messages", new String[]{"id", "role", "content", "created_at"},
            "session_id=? AND id>?", new String[]{sessionId, String.valueOf(aroundMessageId)}, null, null, "id ASC", String.valueOf(window));
        try { while (c.moveToNext()) after.put(messageRow(c)); } finally { c.close(); }
        if (anchor == null) return out.put("success", false).put("error", "Message not found in session.");
        JSONArray messages = new JSONArray();
        for (int i = 0; i < before.length(); i++) messages.put(before.opt(i));
        messages.put(anchor);
        for (int i = 0; i < after.length(); i++) messages.put(after.opt(i));
        SQLiteDatabase countDb = getReadableDatabase();
        Cursor bc = countDb.rawQuery("SELECT COUNT(*) FROM messages WHERE session_id=? AND id<?",
            new String[]{sessionId, String.valueOf(aroundMessageId)});
        int messagesBefore = 0;
        try { if (bc.moveToFirst()) messagesBefore = bc.getInt(0); } finally { bc.close(); }
        Cursor ac = countDb.rawQuery("SELECT COUNT(*) FROM messages WHERE session_id=? AND id>?",
            new String[]{sessionId, String.valueOf(aroundMessageId)});
        int messagesAfter = 0;
        try { if (ac.moveToFirst()) messagesAfter = ac.getInt(0); } finally { ac.close(); }
        out.put("success", true);
        out.put("mode", "scroll");
        out.put("session_id", sessionId);
        out.put("around_message_id", aroundMessageId);
        out.put("messages", messages);
        out.put("messages_before", messagesBefore);
        out.put("messages_after", messagesAfter);
        if (messagesBefore > before.length() || messagesAfter > after.length()) {
            long firstId = messages.optJSONObject(0) != null ? messages.optJSONObject(0).optLong("id") : aroundMessageId;
            long lastId = messages.optJSONObject(messages.length() - 1) != null ? messages.optJSONObject(messages.length() - 1).optLong("id") : aroundMessageId;
            out.put("hint", "Re-anchor with around_message_id=" + firstId + " to scroll back or around_message_id=" + lastId + " to scroll forward.");
        }
        return out;
    }

    /** Discovery with Hermes recall semantics: lineage dedupe (one hit per
     * session root), current-lineage skip unless the hit is compacted
     * history, tool-role demotion below user/assistant, snippets, and
     * adaptive hydration (top hit full window, rest anchor-only). */
    private JSONObject discoverSessions(String query, String currentSessionId, int limit, int window) throws Exception {
        JSONObject out = new JSONObject();
        ArrayList<JSONObject> primary = new ArrayList<>();
        ArrayList<JSONObject> demoted = new ArrayList<>();
        ArrayList<String> seenRoots = new ArrayList<>();
        String currentRoot = TextUtils.isEmpty(currentSessionId) ? null : resolveSessionRoot(currentSessionId);
        SearchCursor sc = openSearchCursor(query, limit * 12);
        Cursor c = sc.cursor;
        try {
            while (c.moveToNext() && primary.size() + demoted.size() < limit * 4) {
                String sid = c.getString(1);
                String root = resolveSessionRoot(sid);
                if (seenRoots.contains(root)) continue;
                boolean compactedHit = c.getInt(7) == 1 || c.getInt(8) == 1;
                if (currentRoot != null && currentRoot.equals(root) && !compactedHit) continue;
                RunRecord run = getRun(sid);
                if (run == null || run.archived) continue;
                seenRoots.add(root);
                String snippet = c.getString(3);
                String content = c.getString(4);
                if (TextUtils.isEmpty(snippet)) snippet = truncateText(content, 400);
                else snippet = truncateText(snippet, 400);
                JSONObject anchor = new JSONObject();
                anchor.put("id", c.getLong(0));
                anchor.put("role", c.getString(2));
                anchor.put("snippet", snippet);
                anchor.put("created_at", c.getLong(5));
                JSONObject hit = sessionSummary(run, anchor);
                if ("tool".equals(c.getString(2))) demoted.add(hit);
                else primary.add(hit);
            }
        } finally { c.close(); }
        JSONArray results = new JSONArray();
        boolean first = true;
        for (int pass = 0; pass < 2 && results.length() < limit; pass++) {
            ArrayList<JSONObject> bucket = pass == 0 ? primary : demoted;
            for (JSONObject hit : bucket) {
                if (results.length() >= limit) break;
                if (first) {
                    first = false;
                    JSONObject anchor = hit.optJSONObject("anchor");
                    if (anchor != null) {
                        hit.put("window", messagesAround(
                            hit.optString("session_id"), anchor.optLong("id"), window).optJSONArray("messages"));
                    }
                }
                results.put(hit);
            }
        }
        out.put("success", true);
        out.put("mode", "discovery");
        out.put("query", query);
        out.put("results", results);
        out.put("backend", sc.backend);
        return out;
    }

    private static String truncateText(String text, int max) {
        if (text == null) return "";
        if (text.length() <= max) return text;
        return text.substring(0, max) + "…";
    }

    /** Walks runs.parent_session_id to the lineage root (cycle-guarded).
     * Discovery dedupes and current-lineage-skips by root. */
    private String resolveSessionRoot(String sessionId) {
        String root = sessionId;
        java.util.HashSet<String> visited = new java.util.HashSet<>();
        for (int i = 0; i < 32 && root != null && !visited.contains(root); i++) {
            visited.add(root);
            RunRecord r = getRun(root);
            if (r == null || TextUtils.isEmpty(r.parentSessionId)) break;
            root = r.parentSessionId;
        }
        return root == null ? sessionId : root;
    }

    private static final class SearchCursor {
        Cursor cursor;
        String backend;
    }

    /** Backend chain: FTS5 phrase → FTS5 trigram (substring-tolerant) →
     * LIKE fallback. Uniform columns:
     * 0 id, 1 session_id, 2 role, 3 snippet, 4 content, 5 created_at,
     * 6 active, 7 compacted, 8 summary flag. */
    private SearchCursor openSearchCursor(String query, int limit) {
        SearchCursor sc = new SearchCursor();
        String safe = query == null ? "" : query.replace("\"", "\"\"");
        String phrase = "\"" + safe + "\"";
        String cols = "m.id, m.session_id, m.role, " +
            "snippet(messages_fts, 0, '>>>', '<<<', '…', 20), m.content, m.created_at, " +
            "m.active, COALESCE(m.compacted, 0), COALESCE(m._compressed_summary, 0) " +
            "FROM messages_fts f JOIN messages m ON m.id = f.message_id " +
            "WHERE messages_fts MATCH ? ORDER BY rank LIMIT ?";
        try {
            sc.cursor = getReadableDatabase().rawQuery(
                "SELECT " + cols, new String[]{phrase, String.valueOf(limit)});
            sc.backend = "fts5";
            return sc;
        } catch (Exception ignored) {}
        try {
            sc.cursor = getReadableDatabase().rawQuery(
                "SELECT " + cols.replace("messages_fts", "messages_fts_trigram"),
                new String[]{safe, String.valueOf(limit)});
            sc.backend = "fts5-trigram";
            return sc;
        } catch (Exception ignored) {}
        sc.cursor = getReadableDatabase().query("messages",
            new String[]{"id", "session_id", "role", "''", "content", "created_at",
                "active", "compacted", "_compressed_summary"},
            "content LIKE ? ESCAPE '\\'",
            new String[]{"%" + escapeLike(query) + "%"},
            null, null, "created_at DESC", String.valueOf(limit));
        sc.backend = "like_fallback";
        return sc;
    }

    private JSONObject sessionSummary(RunRecord r, @Nullable JSONObject anchor) throws Exception {
        JSONObject o = new JSONObject();
        o.put("session_id", r.id);
        o.put("title", r.title);
        o.put("title_source", r.titleSource);
        o.put("provider", r.harnessId);
        o.put("model", TextUtils.isEmpty(r.modelOverride) ? r.lastResolvedModel : r.modelOverride);
        o.put("workspace", r.workspace);
        o.put("created_at", r.createdAt);
        o.put("updated_at", r.updatedAt);
        o.put("message_count", countSessionMessages(r.id));
        Cursor pc = getReadableDatabase().query("messages", new String[]{"content"},
            "session_id=? AND role='user' AND active=1", new String[]{r.id}, null, null, "id ASC", "1");
        try {
            if (pc.moveToFirst()) o.put("preview", boundTitle(pc.getString(0)));
        } finally { pc.close(); }
        if (!TextUtils.isEmpty(r.parentSessionId)) o.put("parent_session_id", r.parentSessionId);
        if (anchor != null) o.put("anchor", anchor);
        return o;
    }

    private JSONObject messageRow(Cursor c) throws Exception {
        JSONObject row = new JSONObject();
        row.put("id", c.getLong(0));
        row.put("role", c.getString(1));
        row.put("content", c.getString(2));
        row.put("created_at", c.getLong(3));
        return row;
    }

    private static String escapeLike(String input) {
        if (input == null) return "";
        return input.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private RunRecord readRun(Cursor cursor) {
        RunRecord record = new RunRecord();
        record.id = cursor.getString(cursor.getColumnIndexOrThrow("id"));
        record.harnessId = cursor.getString(cursor.getColumnIndexOrThrow("harness_id"));
        record.workspace = cursor.getString(cursor.getColumnIndexOrThrow("workspace"));
        record.threadId = cursor.getString(cursor.getColumnIndexOrThrow("thread_id"));
        record.turnId = cursor.getString(cursor.getColumnIndexOrThrow("turn_id"));
        record.state = AiRunStateMachine.State.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("state")));
        record.lastError = cursor.getString(cursor.getColumnIndexOrThrow("last_error"));
        record.updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at"));
        try { record.createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")); } catch (Exception ignored) {}
        try { record.sessionKey = cursor.getString(cursor.getColumnIndexOrThrow("session_key")); } catch (Exception ignored) {}
        try { record.parentSessionId = cursor.getString(cursor.getColumnIndexOrThrow("parent_session_id")); } catch (Exception ignored) {}
        try { record.previousResponseId = cursor.getString(cursor.getColumnIndexOrThrow("previous_response_id")); } catch (Exception ignored) {}
        try { record.chatMessagesJson = cursor.getString(cursor.getColumnIndexOrThrow("chat_messages_json")); } catch (Exception ignored) {}
        try { record.resumePending = cursor.getInt(cursor.getColumnIndexOrThrow("resume_pending")) == 1; } catch (Exception ignored) {}
        try { record.activeTurnToken = cursor.getString(cursor.getColumnIndexOrThrow("active_turn_token")); } catch (Exception ignored) {}
        try { record.activeTurnStartedAt = cursor.getLong(cursor.getColumnIndexOrThrow("active_turn_started_at")); } catch (Exception ignored) {}
        try { record.hygieneFailureStreak = cursor.getInt(cursor.getColumnIndexOrThrow("hygiene_failure_streak")); } catch (Exception ignored) {}
        try { record.compressionCooldownUntil = cursor.getLong(cursor.getColumnIndexOrThrow("compression_cooldown_until")); } catch (Exception ignored) {}
        try { record.modelOverride = cursor.getString(cursor.getColumnIndexOrThrow("model_override")); } catch (Exception ignored) {}
        try { record.lastResolvedModel = cursor.getString(cursor.getColumnIndexOrThrow("last_resolved_model")); } catch (Exception ignored) {}
        try { record.archived = cursor.getInt(cursor.getColumnIndexOrThrow("archived")) == 1; } catch (Exception ignored) {}
        try { record.title = cursor.getString(cursor.getColumnIndexOrThrow("title")); } catch (Exception ignored) {}
        try { record.titleSource = cursor.getString(cursor.getColumnIndexOrThrow("title_source")); } catch (Exception ignored) {}
        try { record.route = cursor.getString(cursor.getColumnIndexOrThrow("route")); } catch (Exception ignored) {}
        return record;
    }

    public synchronized void recoverInterruptedTurns() {
        long now = System.currentTimeMillis();
        long freshnessWindowMs = 3600_000L;
        List<RunRecord> actives = new ArrayList<>();
        Cursor c = getReadableDatabase().query("runs", null, "state NOT IN (?,?,?) AND active_turn_token IS NOT NULL AND COALESCE(archived, 0) = 0",
            new String[]{AiRunStateMachine.State.COMPLETED.name(), AiRunStateMachine.State.FAILED.name(), AiRunStateMachine.State.CANCELED.name()},
            null, null, null);
        try { while (c.moveToNext()) actives.add(readRun(c)); } finally { c.close(); }
        for (RunRecord r : actives) {
            boolean fresh = now - r.updatedAt < freshnessWindowMs;
            if (fresh) setResumePending(r.id, true);
            else {
                ContentValues v = new ContentValues();
                v.put("active_turn_token", (String) null);
                v.put("updated_at", now);
                getWritableDatabase().update("runs", v, "id=?", new String[]{r.id});
            }
        }
    }
}
