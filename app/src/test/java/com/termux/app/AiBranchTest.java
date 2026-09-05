package com.termux.app;

import android.content.Context;

import org.json.JSONArray;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class AiBranchTest {

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
    public void emptySessionRefusesBranch() {
        AiDatabase.RunRecord run = db.createRun("opencode", "/project");
        assertNull(db.branchSession(run.id, null));
        assertNull(db.branchSession("missing-id", null));
    }

    @Test
    public void branchCopiesHistoryAndLinksParent() {
        AiDatabase.RunRecord parent = db.createRun("opencode", "/project");
        db.appendMessage(parent.id, "user", "original request");
        db.appendMessage(parent.id, "assistant", "original answer");
        parent = db.getRun(parent.id);

        AiDatabase.RunRecord child = db.branchSession(parent.id, null);
        assertNotNull(child);
        assertEquals(parent.id, child.parentSessionId);
        assertEquals(parent.harnessId, child.harnessId);
        assertEquals("branch", child.titleSource);

        JSONArray parentRows = db.getTranscript(parent.id, 20);
        JSONArray childRows = db.getTranscript(child.id, 20);
        assertEquals(parentRows.length(), childRows.length());
        assertEquals("original request", childRows.optJSONObject(0).optString("content"));

        AiDatabase.RunRecord reread = db.getRun(parent.id);
        assertEquals(2, db.getTranscript(reread.id, 20).length());
    }

    @Test
    public void branchTitlesFollowLineage() {
        AiDatabase.RunRecord parent = db.createRun("opencode", "/project");
        db.appendMessage(parent.id, "user", "hello");
        parent = db.getRun(parent.id);
        String base = parent.title;
        assertTrue(base != null && !base.isEmpty());

        AiDatabase.RunRecord first = db.branchSession(parent.id, null);
        assertNotNull(first);
        assertEquals(base + " #2", first.title);

        AiDatabase.RunRecord second = db.branchSession(parent.id, null);
        assertNotNull(second);
        assertEquals(base + " #3", second.title);

        AiDatabase.RunRecord named = db.branchSession(parent.id, "spike");
        assertNotNull(named);
        assertEquals("spike", named.title);
    }
}
