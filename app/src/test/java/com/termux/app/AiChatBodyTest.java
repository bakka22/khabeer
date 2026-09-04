package com.termux.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class AiChatBodyTest {

    @Test
    public void bodyWithToolsSendsFunctionCalling() throws Exception {
        JSONArray messages = new JSONArray().put(new JSONObject().put("role", "user").put("content", "Hello"));
        JSONArray tools = new JSONArray().put(new JSONObject().put("type", "function"));

        JSONObject body = AiRuntimeService.chatCompletionsBody("muse-spark-1.3-contributor-free", messages, tools);

        assertEquals("muse-spark-1.3-contributor-free", body.optString("model"));
        assertTrue(body.optBoolean("stream"));
        assertTrue(body.has("tools"));
        assertEquals("auto", body.optString("tool_choice"));
    }

    @Test
    public void degradedBodyOmitsToolsEntirely() throws Exception {
        JSONArray messages = new JSONArray().put(new JSONObject().put("role", "user").put("content", "Hello"));

        JSONObject withoutTools = AiRuntimeService.chatCompletionsBody("muse-spark-1.3-contributor-free", messages, null);
        assertFalse(withoutTools.has("tools"));
        assertFalse(withoutTools.has("tool_choice"));

        JSONObject emptyTools = AiRuntimeService.chatCompletionsBody("muse-spark-1.3-contributor-free", messages, new JSONArray());
        assertFalse(emptyTools.has("tools"));
        assertFalse(emptyTools.has("tool_choice"));
    }
}
