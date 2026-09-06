package com.termux.app;

import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

@RunWith(RobolectricTestRunner.class)
public class AiSessionDeleteTest {

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
    public void deleteRunWipesSessionEverywhere() throws Exception {
        AiDatabase.RunRecord run = db.createRun("opencode", "/project");
        db.appendMessage(run.id, "user", "hello");
        db.appendMessage(run.id, "assistant", "hi there");

        AiDatabase.RunRecord other = db.createRun("opencode", "/project");
        db.appendMessage(other.id, "user", "keep me");

        db.deleteRun(run.id);

        assertNull(db.getRun(run.id));
        assertEquals(0, db.getTranscript(run.id, 50).length());
        assertEquals(false, db.sessionSearch(
            new org.json.JSONObject().put("session_id", run.id), null).optBoolean("success", true));

        assertEquals("keep me",
            db.getTranscript(other.id, 50).optJSONObject(0).optString("content"));
    }
}
