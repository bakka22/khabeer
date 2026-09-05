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

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class AiVisionTest {

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
    public void imageRefsPersistOnTranscriptRows() throws Exception {
        AiDatabase.RunRecord run = db.createRun("opencode", "/project");
        JSONArray images = new JSONArray().put(new JSONObject()
            .put("name", "shot.jpg").put("mime", "image/jpeg")
            .put("path", "/tmp/shot.jpg").put("isImage", true));
        db.appendMessage(run.id, "user", "what is this?", images);
        db.appendMessage(run.id, "assistant", "looks fine");

        JSONArray rows = db.getTranscript(run.id, 20);
        assertEquals(2, rows.length());
        JSONArray back = rows.optJSONObject(0).optJSONArray("images");
        assertNotNull(back);
        assertEquals(1, back.length());
        assertEquals("shot.jpg", back.optJSONObject(0).optString("name"));
        assertNull(rows.optJSONObject(1).optJSONArray("images"));

        JSONArray history = db.getHistoricalTranscript(run.id, 20);
        assertEquals(2, history.length());
        assertEquals("shot.jpg",
            history.optJSONObject(0).optJSONArray("images").optJSONObject(0).optString("name"));
    }

    @Test
    public void branchCopiesImageRefs() throws Exception {
        AiDatabase.RunRecord parent = db.createRun("opencode", "/project");
        JSONArray images = new JSONArray().put(new JSONObject()
            .put("name", "shot.jpg").put("mime", "image/jpeg")
            .put("path", "/tmp/shot.jpg").put("isImage", true));
        db.appendMessage(parent.id, "user", "see this", images);
        AiDatabase.RunRecord child = db.branchSession(parent.id, null);
        assertNotNull(child);
        JSONArray rows = db.getTranscript(child.id, 20);
        assertEquals(1, rows.length());
        assertEquals("shot.jpg",
            rows.optJSONObject(0).optJSONArray("images").optJSONObject(0).optString("name"));
    }

    @Test
    public void wireBuildersEmitNativeImageParts() throws Exception {
        File dir = new File(context.getCacheDir(), "vision-wire");
        dir.mkdirs();
        File shot = new File(dir, "shot.jpg");
        byte[] bytes = new byte[256];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) i;
        try (FileOutputStream out = new FileOutputStream(shot)) {
            out.write(bytes);
        }
        JSONArray images = new JSONArray().put(new JSONObject()
            .put("name", "shot.jpg").put("mime", "image/jpeg")
            .put("path", shot.getAbsolutePath()).put("isImage", true));
        JSONArray history = new JSONArray().put(new JSONObject()
            .put("role", "user").put("content", "hi").put("images", images));

        AiRuntimeService service = AiRuntimeServiceForTests();
        JSONArray chatWire = invokeWire(service, "toChatWireMessages", history);
        JSONObject user = chatWire.optJSONObject(0);
        JSONArray content = user.optJSONArray("content");
        assertNotNull("chat user becomes content array", content);
        assertEquals("text", content.optJSONObject(0).optString("type"));
        assertEquals("image_url", content.optJSONObject(1).optString("type"));
        String url = content.optJSONObject(1).optJSONObject("image_url").optString("url");
        assertTrue(url.startsWith("data:image/jpeg;base64,"));

        JSONArray responses = invokeWire(service, "toResponsesInput", history);
        JSONObject rUser = responses.optJSONObject(0);
        JSONArray rContent = rUser.optJSONArray("content");
        assertEquals("input_text", rContent.optJSONObject(0).optString("type"));
        assertEquals("input_image", rContent.optJSONObject(1).optString("type"));
        assertTrue(rContent.optJSONObject(1).optString("image_url").startsWith("data:"));

        JSONArray codex = invokeWire(service, "toCodexResponsesInput", history);
        JSONArray cContent = codex.optJSONObject(0).optJSONArray("content");
        assertEquals("input_image", cContent.optJSONObject(1).optString("type"));

        JSONArray anthropic = invokeWire(service, "toAnthropicMessages", history);
        JSONArray aContent = anthropic.optJSONObject(0).optJSONArray("content");
        assertEquals("text", aContent.optJSONObject(0).optString("type"));
        assertEquals("image", aContent.optJSONObject(1).optString("type"));
        assertEquals("base64", aContent.optJSONObject(1).optJSONObject("source").optString("type"));

        JSONArray gone = new JSONArray().put(new JSONObject()
            .put("role", "user").put("content", "hi").put("images", new JSONArray().put(new JSONObject()
                .put("name", "missing.jpg").put("mime", "image/jpeg")
                .put("path", new File(dir, "missing.jpg").getAbsolutePath()).put("isImage", true))));
        JSONArray plain = invokeWire(service, "toChatWireMessages", gone);
        assertTrue("missing files degrade to the string shape",
            plain.optJSONObject(0).optString("content", "").contains("[image unavailable: missing.jpg]"));
    }

    private static AiRuntimeService AiRuntimeServiceForTests() throws Exception {
        java.lang.reflect.Constructor<AiRuntimeService> c =
            AiRuntimeService.class.getDeclaredConstructor();
        c.setAccessible(true);
        return c.newInstance();
    }

    private static JSONArray invokeWire(AiRuntimeService service, String method, JSONArray history) throws Exception {
        java.lang.reflect.Method m =
            AiRuntimeService.class.getDeclaredMethod(method, JSONArray.class);
        m.setAccessible(true);
        return (JSONArray) m.invoke(service, history);
    }

    @Test
    public void fileImportGuards() throws Exception {
        File dir = new File(context.getCacheDir(), "vision-src");
        dir.mkdirs();
        File tiny = new File(dir, "note.txt");
        try (FileOutputStream out = new FileOutputStream(tiny)) {
            out.write("hello".getBytes(StandardCharsets.UTF_8));
        }
        JSONObject record = AiAttachments.importFile(tiny, "pending");
        assertNotNull(record);
        assertFalse(record.optBoolean("isImage"));
        assertTrue(new File(record.optString("path")).isFile());

        File huge = new File(dir, "huge.bin");
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(huge, "rw")) {
            raf.setLength(AiAttachments.MAX_IMPORT_BYTES + 1);
        }
        assertNull("oversize imports rejected", AiAttachments.importFile(huge, "pending"));
        assertNull(AiAttachments.importFile(new File(dir, "missing.txt"), "pending"));

        JSONArray only = AiAttachments.imagesOnly(new JSONArray().put(record));
        assertEquals(0, only.length());
    }
}
