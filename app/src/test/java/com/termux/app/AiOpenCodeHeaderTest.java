package com.termux.app;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

@RunWith(RobolectricTestRunner.class)
public class AiOpenCodeHeaderTest {

    private static void apply(HttpURLConnection c, String url, String session) throws Exception {
        Method m = AiRuntimeService.class.getDeclaredMethod(
            "applyOpenCodeSessionHeader", HttpURLConnection.class, String.class, String.class);
        m.setAccessible(true);
        m.invoke(null, c, url, session);
    }

    private static String headerFor(String url, String session) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("https://example.test/v1").openConnection();
        apply(c, url, session);
        return c.getRequestProperty("x-opencode-session");
    }

    @Test
    public void goZenAndFreeRoutesCarryStableSessionId() throws Exception {
        assertEquals("sess-1", headerFor("https://opencode.ai/zen/go/v1/chat/completions", "sess-1"));
        assertEquals("sess-1", headerFor("https://opencode.ai/zen/v1/chat/completions", "sess-1"));
    }

    @Test
    public void otherProvidersAndMissingIdSendNothing() throws Exception {
        assertNull(headerFor("https://api.openai.com/v1/responses", "sess-1"));
        assertNull(headerFor("https://api.anthropic.com/v1/messages", "sess-1"));
        assertNull(headerFor("https://opencode.ai/zen/go/v1/chat/completions", null));
        assertNull(headerFor("https://opencode.ai/zen/go/v1/chat/completions", ""));
    }
}
