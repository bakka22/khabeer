package com.termux.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class AiTodoStoreTest {

    private static JSONObject item(String id, String content, String status) throws Exception {
        JSONObject o = new JSONObject().put("content", content).put("status", status);
        if (id != null) o.put("id", id);
        return o;
    }

    @Test
    public void mergesByIdAndAssignsIds() throws Exception {
        AiTodoStore store = new AiTodoStore();
        JSONArray first = new JSONArray()
            .put(item("a", "First task", "pending"))
            .put(item(null, "Second task", "pending"));
        JSONObject snap = store.write(first);
        assertEquals(2, snap.optJSONArray("todos").length());
        String autoId = snap.optJSONArray("todos").optJSONObject(1).optString("id");
        assertFalse(autoId.isEmpty());

        JSONArray update = new JSONArray().put(item("a", "First task", "in_progress"));
        JSONObject snap2 = store.write(update);
        assertEquals(2, snap2.optJSONArray("todos").length());
        assertEquals("in_progress", snap2.optJSONArray("todos").optJSONObject(0).optString("status"));
    }

    @Test
    public void injectionKeepsActiveDropsDone() throws Exception {
        AiTodoStore store = new AiTodoStore();
        assertNull(store.formatForInjection());
        store.write(new JSONArray()
            .put(item("a", "Do this", "in_progress"))
            .put(item("b", "Done that", "completed"))
            .put(item("c", "Skipped", "cancelled"))
            .put(item("d", "Later", "pending")));
        String injected = store.formatForInjection();
        assertTrue(injected.startsWith(AiTodoStore.INJECTION_HEADER));
        assertTrue(injected.contains("Do this"));
        assertTrue(injected.contains("Later"));
        assertFalse(injected.contains("Done that"));
        assertFalse(injected.contains("Skipped"));
    }

    @Test
    public void nestingKeepsParentsOfActiveChildren() throws Exception {
        AiTodoStore store = new AiTodoStore();
        JSONObject parent = item("p", "Parent", "completed");
        JSONObject kid = item("k", "Child", "pending");
        kid.put("parent", "p");
        store.write(new JSONArray().put(parent).put(kid));
        String injected = store.formatForInjection();
        assertTrue(injected.contains("Parent"));
        assertTrue(injected.contains("Child"));
    }

    @Test
    public void capsAndRestore() throws Exception {
        AiTodoStore store = new AiTodoStore();
        StringBuilder big = new StringBuilder();
        while (big.length() <= AiTodoStore.MAX_CONTENT_CHARS) big.append("x");
        store.write(new JSONArray().put(item("a", big.toString(), "pending")));
        String content = store.read().optJSONObject(0).optString("content");
        assertTrue(content.length() <= AiTodoStore.MAX_CONTENT_CHARS + 20);

        AiTodoStore restored = new AiTodoStore();
        restored.restore(store.read());
        assertEquals(1, restored.read().length());
        assertEquals("a", restored.read().optJSONObject(0).optString("id"));

        JSONObject bad = store.write(new JSONArray().put(new JSONObject().put("status", "bogus")));
        assertEquals(1, bad.optJSONArray("todos").length());
    }
}
