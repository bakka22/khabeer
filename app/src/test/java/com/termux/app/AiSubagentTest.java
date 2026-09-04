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
public class AiSubagentTest {

    private static JSONObject tool(String name) throws Exception {
        return new JSONObject().put("type", "function").put("name", name);
    }

    private static JSONObject chatTool(String name) throws Exception {
        return new JSONObject().put("type", "function")
            .put("function", new JSONObject().put("name", name));
    }

    private static boolean contains(JSONArray tools, String name) throws Exception {
        for (int i = 0; i < tools.length(); i++) {
            JSONObject tool = tools.optJSONObject(i);
            if (tool == null) continue;
            if (name.equals(tool.optString("name"))) return true;
            JSONObject fn = tool.optJSONObject("function");
            if (fn != null && name.equals(fn.optString("name"))) return true;
        }
        return false;
    }

    @Test
    public void childToolsetBlocksWritesManagementAndNesting() throws Exception {
        JSONArray flat = new JSONArray()
            .put(tool("terminal"))
            .put(tool("memory"))
            .put(tool("session_search"))
            .put(tool("delegate_task"))
            .put(tool("skills_list"))
            .put(tool("skill_view"))
            .put(tool("skill_manage"));
        JSONArray filtered = AiRuntimeService.filterBlockedTools(flat);
        assertTrue(contains(filtered, "terminal"));
        assertTrue(contains(filtered, "session_search"));
        assertTrue(contains(filtered, "skills_list"));
        assertTrue(contains(filtered, "skill_view"));
        assertFalse(contains(filtered, "memory"));
        assertFalse(contains(filtered, "skill_manage"));
        assertFalse(contains(filtered, "delegate_task"));

        JSONArray chat = new JSONArray()
            .put(chatTool("memory"))
            .put(chatTool("terminal"))
            .put(chatTool("delegate_task"));
        JSONArray chatFiltered = AiRuntimeService.filterBlockedTools(chat);
        assertTrue(contains(chatFiltered, "terminal"));
        assertFalse(contains(chatFiltered, "memory"));
        assertFalse(contains(chatFiltered, "delegate_task"));
    }

    @Test
    public void blockedSetCoversHermesLeafBans() {
        assertTrue(AiRuntimeService.SUBAGENT_BLOCKED_TOOLS.contains("delegate_task"));
        assertTrue(AiRuntimeService.SUBAGENT_BLOCKED_TOOLS.contains("memory"));
        assertTrue(AiRuntimeService.SUBAGENT_BLOCKED_TOOLS.contains("skill_manage"));
        assertEquals(3, AiRuntimeService.SUBAGENT_BLOCKED_TOOLS.size());
    }
}
