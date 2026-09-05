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
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class AiSkillCuratorTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private AiProviderConfig config;

    @Before
    public void setUp() throws Exception {
        AiSkillRegistry.setRootForTests(temp.newFolder("khabeer-curator"));
        config = new AiProviderConfig(RuntimeEnvironment.getApplication());
    }

    @After
    public void tearDown() {
        AiSkillRegistry.setRootForTests(null);
    }

    private static String skillMd(String name) {
        return "---\nname: " + name + "\ndescription: Curator test skill for unit runs.\n---\n\nBody text here.\n";
    }

    private void createSkill(String name) throws Exception {
        JSONObject op = new JSONObject()
            .put("action", "create")
            .put("name", name)
            .put("content", skillMd(name));
        assertTrue(new JSONObject(AiSkillRegistry.manageTool(op)).optBoolean("success"));
    }

    private void writeUsage(String name, long viewedAt) throws Exception {
        File usage = new File(new File(AiSkillRegistry.skillsRoot(), ".usage.json").getAbsolutePath());
        JSONObject root = new JSONObject();
        if (usage.isFile()) {
            byte[] data = java.nio.file.Files.readAllBytes(usage.toPath());
            root = new JSONObject(new String(data, StandardCharsets.UTF_8));
        }
        root.put(name, new JSONObject().put("last_viewed_at", viewedAt));
        try (FileOutputStream out = new FileOutputStream(usage, false)) {
            out.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    public void usageEventsDriveActivityClock() throws Exception {
        assertEquals(0, AiSkillRegistry.skillLastActivity("never-touched"));
        createSkill("clocked");
        long afterCreate = AiSkillRegistry.skillLastActivity("clocked");
        assertTrue("creation counts as activity", afterCreate > 0);
        Thread.sleep(5);
        AiSkillRegistry.recordSkillEvent("clocked", "last_viewed_at");
        assertTrue(AiSkillRegistry.skillLastActivity("clocked") >= afterCreate);
        assertTrue(AiSkillRegistry.loadSkillUsage().has("clocked"));
    }

    @Test
    public void staleDerivedArchiveRestoreAndPinExemption() throws Exception {
        createSkill("old-workhorse");
        createSkill("fresh-talent");
        long now = System.currentTimeMillis();
        writeUsage("old-workhorse", now - 100L * 86400000L);
        writeUsage("fresh-talent", now - 1000L);

        assertTrue(AiSkillCurator.staleSkills(config).contains("old-workhorse"));
        assertFalse(AiSkillCurator.staleSkills(config).contains("fresh-talent"));
        assertTrue(AiSkillCurator.archiveCandidates(config).contains("old-workhorse"));

        JSONObject dry = AiSkillCurator.run(config, true);
        assertTrue(dry.toString(), dry.optBoolean("success"));
        assertEquals(1, dry.optJSONArray("archived").length());
        assertTrue(new File(AiSkillRegistry.skillsRoot(), "old-workhorse").isDirectory());

        assertTrue(AiSkillRegistry.setPinned("old-workhorse", true));
        JSONObject dryPinned = AiSkillCurator.run(config, true);
        assertEquals(0, dryPinned.optJSONArray("archived").length());
        assertTrue(dryPinned.optJSONArray("skipped").length() > 0);
        assertTrue(AiSkillRegistry.setPinned("old-workhorse", false));

        JSONObject real = AiSkillCurator.run(config, false);
        assertEquals(1, real.optJSONArray("archived").length());
        assertFalse(new File(AiSkillRegistry.skillsRoot(), "old-workhorse").isDirectory());
        assertEquals(1, AiSkillRegistry.archivedSkills().length());

        assertTrue(AiSkillRegistry.restoreSkill("old-workhorse").optBoolean("success"));
        assertTrue(new File(AiSkillRegistry.skillsRoot(), "old-workhorse").isDirectory());
        assertEquals(0, AiSkillRegistry.archivedSkills().length());
    }

    @Test
    public void seededSkillsExemptAndFirstRunDefers() throws Exception {
        createSkill("core-seed");
        AiSkillRegistry.writeSeededManifest(java.util.Collections.singletonList("core-seed"));
        assertTrue(AiSkillRegistry.isSeeded("core-seed"));
        long now = System.currentTimeMillis();
        writeUsage("core-seed", now - 400L * 86400000L);
        assertFalse(AiSkillCurator.archiveCandidates(config).contains("core-seed"));

        assertFalse("first observation seeds and defers", AiSkillCurator.shouldRun(config));
        assertFalse(AiSkillCurator.shouldRun(config));
    }

    @Test
    public void adoptAndPinRoundTrip() throws Exception {
        createSkill("wild-one");
        assertFalse(AiSkillRegistry.isReviewManaged("wild-one"));
        assertTrue(AiSkillRegistry.adoptSkill("wild-one"));
        assertTrue(AiSkillRegistry.isReviewManaged("wild-one"));
        assertTrue(AiSkillRegistry.setPinned("wild-one", true));
        assertTrue(AiSkillRegistry.isPinned("wild-one"));
        assertFalse("pin blocks review writes", AiSkillRegistry.isReviewManaged("wild-one"));
    }
}
