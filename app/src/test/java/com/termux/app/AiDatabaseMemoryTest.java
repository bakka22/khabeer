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

    @Test
    public void discoveryDemotesToolRowsBelowUserHits() throws Exception {
        AiDatabase.RunRecord toolOnly = db.createRun("opencode", "/tool-project");
        db.appendToolTranscript(toolOnly.id, "[tool_result] terminal\noutput: demote-zebra-nine", "{}");
        AiDatabase.RunRecord userHit = db.createRun("opencode", "/user-project");
        db.appendMessage(userHit.id, "user", "please investigate demote-zebra-nine today");

        JSONObject found = db.sessionSearch(new JSONObject().put("query", "demote-zebra-nine").put("limit", 5), null);
        assertTrue(found.toString(), found.optBoolean("success"));
        JSONArray results = found.optJSONArray("results");
        assertTrue(found.toString(), results != null && results.length() == 2);
        assertEquals(userHit.id, results.optJSONObject(0).optString("session_id"));
        assertEquals(toolOnly.id, results.optJSONObject(1).optString("session_id"));
        assertTrue(results.optJSONObject(0).has("window"));
        assertFalse(results.optJSONObject(1).has("window"));
    }

    @Test
    public void exportCoversTranscriptUsageAndMarkers() throws Exception {
        AiDatabase.RunRecord run = db.createRun("opencode", "/export-project");
        db.appendMessage(run.id, "user", "export me");
        db.appendMessage(run.id, "assistant", "exported reply");
        db.appendTurnUsage(run.id, "model-x", 10, 5, false);
        db.rewindSession(run.id, 5);

        String md = db.buildSessionMarkdown(run.id);
        assertTrue(md.contains("exporter: khabeer sessions export (md) v1"));
        assertTrue(md.contains("### User"));
        assertTrue(md.contains("### Assistant"));
        assertTrue(md.contains("export me"));
        assertTrue(md.contains("[rewound"));
        assertTrue(md.contains("total_tokens: 15"));
        assertEquals("", db.buildSessionMarkdown("missing"));
    }

    @Test
    public void rewindHidesTurnsButKeepsAuditAndClamps() throws Exception {
        AiDatabase.RunRecord run = db.createRun("opencode", "/rewind-project");
        db.appendMessage(run.id, "user", "first question");
        db.appendMessage(run.id, "assistant", "first answer");
        db.appendMessage(run.id, "user", "second question");
        db.appendMessage(run.id, "assistant", "second answer");

        JSONObject undone = db.rewindSession(run.id, 1);
        assertTrue(undone.toString(), undone.optBoolean("success"));
        assertEquals(1, undone.optInt("turns_undone"));
        assertEquals(2, undone.optInt("rewound_count"));
        assertEquals("second question", undone.optString("target_text"));

        JSONArray replay = db.getTranscript(run.id, 20);
        assertEquals(2, replay.length());
        assertEquals("first question", replay.optJSONObject(0).optString("content"));

        JSONArray audit = db.getHistoricalTranscript(run.id, 20);
        assertEquals(4, audit.length());

        JSONObject found = db.sessionSearch(new JSONObject().put("query", "second question").put("limit", 5), null);
        assertTrue(found.toString(), found.optBoolean("success"));
        assertEquals(0, found.optJSONArray("results").length());

        JSONObject clamp = db.rewindSession(run.id, 99);
        assertTrue(clamp.toString(), clamp.optBoolean("success"));
        assertEquals(1, clamp.optInt("turns_undone"));
        assertEquals(0, db.getTranscript(run.id, 20).length());

        JSONObject empty = db.rewindSession(run.id, 1);
        assertFalse(empty.optBoolean("success"));
    }

    @Test
    public void retryKeepsUserRowAndDropsFollowers() throws Exception {
        AiDatabase.RunRecord run = db.createRun("opencode", "/retry-project");
        db.appendMessage(run.id, "user", "retry me");
        db.appendMessage(run.id, "assistant", "failed reply");

        JSONObject last = db.lastActiveUserMessage(run.id);
        assertTrue(last.optBoolean("found"));
        assertEquals("retry me", last.optString("content"));

        assertEquals(1, db.clearAfterMessage(run.id, last.optLong("id")));
        JSONArray replay = db.getTranscript(run.id, 20);
        assertEquals(1, replay.length());
        assertEquals("retry me", replay.optJSONObject(0).optString("content"));
    }

    @Test
    public void turnUsageLedgerSumsPerSession() throws Exception {
        AiDatabase.RunRecord run = db.createRun("opencode", "/usage-project");
        db.appendTurnUsage(run.id, "model-a", 100, 50, false);
        db.appendTurnUsage(run.id, "model-a", 200, 0, true);
        JSONObject usage = db.getSessionUsage(run.id);
        assertEquals(2, usage.optInt("turns"));
        assertEquals(300, usage.optLong("prompt_tokens"));
        assertEquals(50, usage.optLong("completion_tokens"));
        assertEquals(350, usage.optLong("total_tokens"));
        assertEquals(1, usage.optInt("estimated_turns"));
        assertEquals(0, db.getSessionUsage("nope").optInt("turns"));
    }

    @Test
    public void scrollReportsCountsAndReadTruncatesMiddle() throws Exception {
        AiDatabase.RunRecord run = db.createRun("opencode", "/scroll-project");
        for (int i = 0; i < 40; i++) db.appendMessage(run.id, i % 2 == 0 ? "user" : "assistant", "scroll message " + i);
        JSONArray all = db.getHistoricalTranscript(run.id, 100);
        long anchor = all.optJSONObject(20).optLong("id");

        JSONObject scrolled = db.sessionSearch(
            new JSONObject().put("session_id", run.id).put("around_message_id", anchor).put("window", 5), null);
        assertTrue(scrolled.toString(), scrolled.optBoolean("success"));
        assertEquals("scroll", scrolled.optString("mode"));
        assertEquals(20, scrolled.optInt("messages_before"));
        assertEquals(19, scrolled.optInt("messages_after"));
        assertTrue(scrolled.has("hint"));

        JSONObject read = db.sessionSearch(new JSONObject().put("session_id", run.id).put("limit", 2), null);
        assertTrue(read.toString(), read.optBoolean("success"));
        assertEquals("read", read.optString("mode"));
        assertEquals(10, read.optInt("truncated_middle"));
        assertTrue(read.has("hint"));
    }
}
