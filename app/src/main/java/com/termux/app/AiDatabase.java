package com.termux.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.UUID;
import java.util.ArrayList;
import java.util.List;

/** Small durable store for AI runs and protocol events. */
public final class AiDatabase extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "termux_ai_runtime.db";
    private static final int DATABASE_VERSION = 7;

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

    @Override
    public void onOpen(SQLiteDatabase db) {
        try { db.execSQL("PRAGMA synchronous=NORMAL"); } catch (Exception ignored) {}
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
        if (oldVersion < 6) {
            // Title provenance (Hermes title_source): 'message' = derived from
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
        // Session listing mirrors Hermes: last_active ordering (fresh message
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

    /** Live sessions: hidden/archived rows and Hermes-style empty ghost
     * sessions (never got a user message AND never titled) stay out. */
    public synchronized List<RunRecord> getSessions(int limit) {
        List<RunRecord> records = new ArrayList<>();
        Cursor cursor = getReadableDatabase().query("runs", null,
            "COALESCE(archived, 0) = 0 AND (title IS NOT NULL OR EXISTS (SELECT 1 FROM messages m WHERE m.session_id = runs.id AND m.active = 1))", null, null, null,
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

    /** Archive = soft hide (Hermes set_session_archived): rows keep every
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
            new String[]{"role", "content"}, "session_id=? AND active=1",
            new String[]{sessionId}, null, null, "id ASC", String.valueOf(limit));
        try {
            while (c.moveToNext()) {
                try {
                    JSONObject row = new JSONObject();
                    row.put("role", c.getString(0));
                    row.put("content", c.getString(1));
                    out.put(row);
                } catch (Exception ignored) {}
            }
        } finally { c.close(); }
        return out;
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
