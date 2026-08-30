package com.termux.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import androidx.annotation.Nullable;

import java.util.UUID;
import java.util.ArrayList;
import java.util.List;

/** Small durable store for AI runs and protocol events. */
public final class AiDatabase extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "termux_ai_runtime.db";
    private static final int DATABASE_VERSION = 2;

    public static final class RunRecord {
        public String id;
        public String harnessId;
        public String workspace;
        public String threadId;
        public String turnId;
        public AiRunStateMachine.State state;
        public String lastError;
        public long updatedAt;
    }

    public AiDatabase(Context context) {
        super(context.getApplicationContext(), DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS runs (" +
            "id TEXT PRIMARY KEY," +
            "harness_id TEXT NOT NULL," +
            "workspace TEXT NOT NULL," +
            "thread_id TEXT," +
            "turn_id TEXT," +
            "state TEXT NOT NULL," +
            "last_error TEXT," +
            "created_at INTEGER NOT NULL," +
            "updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS events (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "run_id TEXT NOT NULL," +
            "method TEXT NOT NULL," +
            "payload TEXT NOT NULL," +
            "created_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS events_run_id_index ON events(run_id, id)");
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
    }

    public synchronized RunRecord createRun(String harnessId, String workspace) {
        RunRecord record = new RunRecord();
        record.id = UUID.randomUUID().toString();
        record.harnessId = harnessId;
        record.workspace = workspace;
        record.state = AiRunStateMachine.State.CREATED;
        record.updatedAt = System.currentTimeMillis();
        saveRun(record);
        return record;
    }

    public synchronized void saveRun(RunRecord record) {
        long now = System.currentTimeMillis();
        if (record.updatedAt == 0) record.updatedAt = now;

        ContentValues values = new ContentValues();
        values.put("id", record.id);
        values.put("harness_id", record.harnessId);
        values.put("workspace", record.workspace);
        values.put("thread_id", record.threadId);
        values.put("turn_id", record.turnId);
        values.put("state", record.state.name());
        values.put("last_error", record.lastError);
        values.put("updated_at", record.updatedAt);

        SQLiteDatabase db = getWritableDatabase();
        if (db.update("runs", values, "id = ?", new String[]{record.id}) == 0) {
            values.put("created_at", now);
            db.insertOrThrow("runs", null, values);
        }
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
            "state NOT IN (?, ?, ?)",
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
        return record;
    }
}
