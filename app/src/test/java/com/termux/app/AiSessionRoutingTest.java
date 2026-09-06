package com.termux.app;

import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.os.Looper;
import android.view.View;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import java.time.Duration;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Implementation;
import org.robolectric.shadow.api.Shadow;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(shadows = {AiSessionRoutingTest.RecordingRuntime.class, AiSessionRoutingTest.RecordingInstaller.class})
public class AiSessionRoutingTest {
    private AiActivity activity;
    private RecordingRuntime runtime;
    private AiDatabase db;
    private AiDatabase.RunRecord old;

    @Before public void setUp() throws Exception {
        RecordingInstaller.ready = null;
        RecordingInstaller.storageSetups = 0;
        RuntimeEnvironment.getApplication().deleteDatabase("termux_ai_runtime.db");
        db = new AiDatabase(RuntimeEnvironment.getApplication());
        AiRuntimeService service = new AiRuntimeService();
        runtime = Shadow.extract(service);
        runtime.db = db;
        // Bind the real chat views without starting Android services or provider discovery.
        activity = Robolectric.buildActivity(AiActivity.class).get();
        activity.setTheme(com.termux.R.style.Theme_AiWorkspace);
        activity.setContentView(com.termux.R.layout.activity_ai_workspace);
        call("bindViews", new Class<?>[0]);
        set("mProviderConfig", new AiProviderConfig(RuntimeEnvironment.getApplication()));
        set("mRuntimeService", service);
        set("mRuntimeBound", true);
        old = db.createRun("opencode", "/data/data/com.termux/files/home");
        db.appendMessage(old.id, "user", "old conversation");
        runtime.viewed = old;
        set("mCurrentRunId", old.id);
        set("mHasNativeSession", true);
        messages().addView(new TextView(activity));
    }

    @After public void tearDown() { db.close(); }

    @Test public void newSessionFirstMessageCreatesNewRunAndSecondContinuesIt() throws Exception {
        pickModel(false);
        assertNull(runtime.getActiveRun());
        assertNull(get("mCurrentRunId"));
        assertEquals(0, messages().getChildCount());
        send("new conversation");
        String freshId = runtime.getActiveRun().id;
        assertNotEquals(old.id, freshId);
        send("follow up before callbacks drain");
        assertEquals(freshId, runtime.getActiveRun().id);
        assertEquals(1, runtime.starts);
        assertEquals(1, db.getTranscript(old.id, 10).length());
        assertEquals("old conversation", db.getTranscript(old.id, 10).getJSONObject(0).getString("content"));
        assertEquals(2, db.getTranscript(freshId, 10).length());
    }

    @Test public void backgroundEventsCannotPopulateEmptyNewChat() throws Exception {
        pickModel(false);
        activity.onRunChanged(old);
        activity.onProtocolEvent(old.id, "item/agentMessage/delta", new JSONObject().put("text", "late reply"));
        activity.onProtocolEvent(old.id, "session/resumed", new JSONObject());
        activity.onProtocolEvent(old.id, "memory/reviewApplied",
            new JSONObject().put("mode", "verbose").put("preview", "background memory"));
        activity.onProtocolEvent(old.id, "memory/compactionComplete", new JSONObject());
        activity.onRuntimeError(old.id, "late error");
        assertEquals(0, messages().getChildCount());
        assertFalse((Boolean) get("mHasNativeSession"));
    }

    @Test public void backgroundReplyCannotEnterAnotherActiveChat() throws Exception {
        pickModel(false);
        send("new conversation");
        int count = messages().getChildCount();
        activity.onProtocolEvent(old.id, "item/agentMessage/delta", new JSONObject().put("text", "old reply"));
        assertEquals(count, messages().getChildCount());
        activity.onProtocolEvent(runtime.getActiveRun().id, "item/agentMessage/delta",
            new JSONObject().put("text", "current reply"));
        assertTrue(messages().getChildCount() > count);
    }

    @Test public void delayedResetCallbackCannotDetachNewRun() throws Exception {
        pickModel(false);
        send("new conversation");
        String freshId = runtime.getActiveRun().id;
        activity.onRunChanged(null);
        activity.onRunChanged(old);
        assertEquals(freshId, get("mCurrentRunId"));
        assertTrue((Boolean) get("mHasNativeSession"));
    }

    @Test public void switchingProviderKeepsExistingConversation() throws Exception {
        pickModel(true);
        send("continue old conversation");
        assertEquals(old.id, runtime.getActiveRun().id);
        assertEquals(0, runtime.starts);
        assertEquals(2, db.getTranscript(old.id, 10).length());
    }

    @Test public void continuousReasoningRendersBeforeStreamStops() throws Exception {
        set("mShowThinkingDetails", true);
        event("turn/started", "");
        event("item/reasoning/delta", "first ");
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));
        event("item/reasoning/delta", "second ");
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));
        event("item/reasoning/delta", "third");
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50));
        // View creation can advance Robolectric's clock: a render may have
        // happened before the third chunk, but must not wait for stream end.
        assertTrue(reasoningView("detail").getText().toString().startsWith("first"));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250));
        assertEquals("first second third", reasoningView("detail").getText().toString());
        assertEquals(View.VISIBLE, reasoningView("detail").getVisibility());
        assertTrue((Boolean) get("mRunActive"));
        assertEquals(View.VISIBLE, ((View) get("mStopButton")).getVisibility());
        assertEquals(View.VISIBLE, ((View) get("mTurnStatus")).getVisibility());
        assertEquals("Running…", ((TextView) get("mTurnStatus")).getText().toString());
    }

    @Test public void compactReasoningCanBeExpandedMidTurnWithoutLosingText() throws Exception {
        event("turn/started", "");
        event("item/reasoning/delta", "available reasoning");
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250));
        assertEquals(View.GONE, reasoningView("detail").getVisibility());
        call("toggleThinkingDetails", new Class<?>[0]);
        assertEquals(View.VISIBLE, reasoningView("detail").getVisibility());
        assertEquals("available reasoning", reasoningView("detail").getText().toString());
        call("toggleThinkingDetails", new Class<?>[0]);
        assertEquals(View.GONE, reasoningView("detail").getVisibility());
    }

    @Test public void completionFlushesTailAndRetiresLiveLabel() throws Exception {
        set("mShowThinkingDetails", true);
        event("turn/started", "");
        event("item/reasoning/delta", "first");
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250));
        TextView detail = reasoningView("detail");
        TextView summary = reasoningView("summary");
        event("item/reasoning/delta", " tail");
        old.state = AiRunStateMachine.State.COMPLETED;
        activity.onRunChanged(old);
        assertEquals("first tail", detail.getText().toString());
        assertEquals("Thinking details", summary.getText().toString());
        assertFalse((Boolean) get("mRunActive"));
        assertEquals(View.GONE, ((View) get("mStopButton")).getVisibility());
        assertEquals(View.GONE, ((View) get("mTurnStatus")).getVisibility());
        assertNull(get("mThinkingBubble"));
    }

    @Test public void answerEndsThinkingSegmentButKeepsTurnRunning() throws Exception {
        set("mShowThinkingDetails", true);
        event("turn/started", "");
        assertNotNull(get("mThinkingBubble"));
        assertNull(get("mReasoningBubble"));
        event("item/reasoning/delta", "reasoning before answer");
        event("item/agentMessage/delta", "answer");
        assertNull(get("mThinkingBubble"));
        assertNull(get("mReasoningBubble"));
        assertTrue((Boolean) get("mRunActive"));
        assertEquals(View.VISIBLE, ((View) get("mStopButton")).getVisibility());
    }

    private void event(String method, String text) throws Exception {
        activity.onProtocolEvent(old.id, method, new JSONObject().put("text", text));
    }

    @Test public void terminalSetupStartsWithoutOpeningShellAndGatesSend() throws Exception {
        call("initializeTerminalEnvironment", new Class<?>[0]);
        assertNotNull(RecordingInstaller.ready);
        assertFalse(((View) get("mChatSendButton")).isEnabled());
        RecordingInstaller.ready.run();
        assertTrue(((View) get("mChatSendButton")).isEnabled());
        java.util.Set<String> services = new java.util.HashSet<>();
        android.content.Intent started;
        while ((started = Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService()) != null)
            services.add(started.getComponent().getClassName());
        assertTrue(services.contains(TermuxService.class.getName()));
        assertTrue(services.contains(AiRuntimeService.class.getName()));
        assertEquals(View.GONE, ((View) get("mTerminalCard")).getVisibility());
    }

    @Test public void storageButtonRequestsPermissionThenSetsUpLinksAfterGrant() throws Exception {
        call("ensureStorageAccess", new Class<?>[0]);
        org.robolectric.shadows.ShadowActivity.PermissionsRequest request =
            Shadows.shadowOf(activity).getLastRequestedPermission();
        assertNotNull(request);
        assertEquals(9002, request.requestCode);
        assertEquals(0, RecordingInstaller.storageSetups);
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
            android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
        activity.onRequestPermissionsResult(9002, new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE},
            new int[]{android.content.pm.PackageManager.PERMISSION_GRANTED});
        assertEquals(1, RecordingInstaller.storageSetups);
    }

    @Test public void returningFromStorageSettingsRechecksActualPermission() throws Exception {
        activity.onActivityResult(9002, android.app.Activity.RESULT_OK, null);
        assertEquals(0, RecordingInstaller.storageSetups);
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
            android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
        activity.onActivityResult(9002, android.app.Activity.RESULT_CANCELED, null);
        assertEquals(1, RecordingInstaller.storageSetups);
    }

    @Implements(value = TermuxInstaller.class, isInAndroidSdk = false)
    public static class RecordingInstaller {
        static Runnable ready;
        static int storageSetups;
        @Implementation protected static void setupBootstrapIfNeeded(android.app.Activity activity, Runnable done) {
            ready = done;
        }
        @Implementation protected static void setupStorageSymlinks(android.content.Context context) {
            storageSetups++;
        }
    }

    private TextView reasoningView(String name) throws Exception {
        Object bubble = get("mReasoningBubble");
        assertNotNull(bubble);
        Field f = bubble.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return (TextView) f.get(bubble);
    }

    private void pickModel(boolean forSession) throws Exception {
        set("mNsForSession", forSession);
        call("nsModelPicked", new Class<?>[]{AiProviderProfile.class, String.class, String.class},
            AiProviderProfile.find("opencode"), AiProviderConfig.OC_ROUTE_FREE, "test-model");
    }

    private void send(String text) throws Exception {
        ((EditText) get("mPromptInput")).setText(text);
        call("sendPrompt", new Class<?>[0]);
    }

    private LinearLayout messages() throws Exception { return (LinearLayout) get("mChatMessages"); }
    private Object get(String name) throws Exception {
        Field f = AiActivity.class.getDeclaredField(name); f.setAccessible(true); return f.get(activity);
    }
    private void set(String name, Object value) throws Exception {
        Field f = AiActivity.class.getDeclaredField(name); f.setAccessible(true); f.set(activity, value);
    }
    private void call(String name, Class<?>[] types, Object... args) throws Exception {
        Method m = AiActivity.class.getDeclaredMethod(name, types); m.setAccessible(true); m.invoke(activity, args);
    }

    /** Replace only the network/service boundary; exercise the real picker and send UI. */
    @Implements(value = AiRuntimeService.class, isInAndroidSdk = false)
    public static class RecordingRuntime {
        AiDatabase db;
        AiDatabase.RunRecord viewed;
        int starts;
        @Implementation public AiDatabase.RunRecord getActiveRun() { return viewed; }
        @Implementation public void newSession() { viewed = null; }
        @Implementation public List<AiDatabase.RunRecord> getSessions() { return Collections.emptyList(); }
        @Implementation public List<AiDatabase.RunRecord> getArchivedSessions() { return Collections.emptyList(); }
        @Implementation public void setSessionProvider(String provider) { viewed.harnessId = provider; }
        @Implementation public void setSessionRoute(String route) { viewed.route = route; }
        @Implementation public void setSessionModel(String model) { viewed.modelOverride = model; }
        @Implementation public void startAgent(String provider, String url, String key, String workspace,
                String prompt, String model, String effort, String approval, String route) {
            starts++;
            viewed = db.createRun(provider, workspace);
            db.appendMessage(viewed.id, "user", prompt);
        }
        @Implementation public void sendPrompt(String prompt, String effort, String approval) {
            db.appendMessage(viewed.id, "user", prompt);
        }
    }
}
