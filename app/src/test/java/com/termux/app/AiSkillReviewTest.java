package com.termux.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class AiSkillReviewTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Before
    public void setUp() throws Exception {
        AiSkillRegistry.setRootForTests(temp.newFolder("khabeer-skills"));
    }

    @After
    public void tearDown() {
        AiSkillRegistry.setRootForTests(null);
    }

    private static String skillMd(String name) {
        return "---\nname: " + name + "\ndescription: Review test skill for unit runs.\n---\n\nBody text here.\n";
    }

    @Test
    public void stagedSkillCreateApprovesAndTagsManaged() throws Exception {
        JSONObject op = new JSONObject()
            .put("action", "create")
            .put("name", "review-probe")
            .put("content", skillMd("review-probe"));

        JSONObject staged = AiSkillRegistry.stageSkillWrite(op, "unit-test");
        assertTrue(staged.toString(), staged.optBoolean("success"));
        assertEquals(1, AiSkillRegistry.pendingSkillWrites().length());

        JSONObject approved = AiSkillRegistry.approvePendingSkill(staged.getString("id"));
        assertTrue(approved.toString(), approved.optInt("applied", 0) > 0);
        assertEquals(0, AiSkillRegistry.pendingSkillWrites().length());
        assertTrue(AiSkillRegistry.isReviewManaged("review-probe"));

        JSONObject rejected = AiSkillRegistry.stageSkillWrite(op, "unit-test");
        assertTrue(AiSkillRegistry.rejectPendingSkill(rejected.getString("id")).optBoolean("success"));
        assertEquals(0, AiSkillRegistry.pendingSkillWrites().length());
    }

    @Test
    public void protectedSkillsRefusedButManagedExtendable() throws Exception {
        JSONObject create = new JSONObject()
            .put("action", "create")
            .put("name", "hand-made")
            .put("content", skillMd("hand-made"));
        assertTrue(new JSONObject(AiSkillRegistry.manageTool(create)).optBoolean("success"));
        assertFalse(AiSkillRegistry.isReviewManaged("hand-made"));

        JSONObject patchProtected = new JSONObject()
            .put("action", "patch")
            .put("name", "hand-made")
            .put("old_string", "Body text here.")
            .put("new_string", "Review rewrote this.");
        JSONObject refused = AiSkillRegistry.applyReviewSkillOps(new JSONArray().put(patchProtected));
        assertTrue(refused.toString(), refused.optBoolean("success"));
        assertEquals(0, refused.optInt("applied"));

        JSONObject createManaged = new JSONObject()
            .put("action", "create")
            .put("name", "auto-learned")
            .put("content", skillMd("auto-learned"));
        JSONObject created = AiSkillRegistry.applyReviewSkillOps(new JSONArray().put(createManaged));
        assertEquals(1, created.optInt("applied"));
        assertTrue(AiSkillRegistry.isReviewManaged("auto-learned"));

        JSONObject patchManaged = new JSONObject()
            .put("action", "patch")
            .put("name", "auto-learned")
            .put("old_string", "Body text here.")
            .put("new_string", "Review extended this.");
        JSONObject patched = AiSkillRegistry.applyReviewSkillOps(new JSONArray().put(patchManaged));
        assertEquals(1, patched.optInt("applied"));
    }
}
