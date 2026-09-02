package com.termux.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class AiDatabaseMemoryTest {

    private static final String DB_NAME = "termux_ai_runtime.db";

    private Context context;
    private AiDatabase db;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase(DB_NAME);
        db = new AiDatabase(context);
    }

    @After
    public void tearDown() {
        if (db != null) db.close();
        if (context != null) context.deleteDatabase(DB_NAME);
    }

    @Test
    public void compactionTrimsActiveReplayButSessionSearchRecoversHistory() throws Exception {
        AiDatabase.RunRecord run = db.createRun("opencode", "/project");
        db.appendMessage(run.id, "user", "old user request with recoverable-alpha");
        db.appendMessage(run.id, "assistant", "old assistant answer");
        db.appendToolTranscript(run.id, "[tool_result] terminal\noutput: unique-tool-output", "{\"output\":\"unique-tool-output\"}");
        db.appendMessage(run.id, "user", "tail user");
        db.appendMessage(run.id, "assistant", "tail assistant");

        JSONObject compacted = db.compactSession(run.id,
            "[CONTEXT COMPACTION — REFERENCE ONLY]\nsummary with session_search(query='recoverable-alpha', session_id='" + run.id + "')",
            2);

        assertTrue(compacted.toString(), compacted.optBoolean("success"));
        assertEquals(2, compacted.optInt("archived_messages"));

        JSONArray replay = db.getTranscript(run.id, 20);
        assertEquals(3, replay.length());
        assertTrue(replay.optJSONObject(0).optString("content").startsWith("[CONTEXT COMPACTION"));
        assertEquals("tail user", replay.optJSONObject(1).optString("content"));
        assertEquals("tail assistant", replay.optJSONObject(2).optString("content"));
        assertFalse(replay.toString().contains("old user request with recoverable-alpha"));

        JSONObject read = db.sessionSearch(new JSONObject().put("session_id", run.id).put("limit", 20), null);
        assertTrue(read.toString(), read.optBoolean("success"));
        assertTrue(read.toString().contains("recoverable-alpha"));
        assertTrue(read.toString().contains("unique-tool-output"));

        JSONObject found = db.sessionSearch(new JSONObject().put("query", "unique-tool-output").put("limit", 5), null);
        assertTrue(found.toString(), found.optBoolean("success"));
        assertTrue(found.toString().contains(run.id));
    }
}
