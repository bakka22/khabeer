package com.termux.app;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class AiProviderFlowTest {

    private AiProviderConfig config;

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        config = new AiProviderConfig(context);
    }

    @Test
    public void freeRouteIsAlwaysConfiguredZenNeedsKey() {
        // Free needs no key; keyed routes start unconfigured. (Keystore
        // round-trips are verified on device, not under Robolectric.)
        assertTrue(config.isRouteConfigured(AiProviderConfig.OC_ROUTE_FREE));
        assertFalse(config.isRouteConfigured(AiProviderConfig.OC_ROUTE_ZEN));
        assertFalse(config.isRouteConfigured(AiProviderConfig.OC_ROUTE_GO));
        assertTrue(AiProviderConfig.ocRouteNeedsKey(AiProviderConfig.OC_ROUTE_ZEN));
        assertFalse(AiProviderConfig.ocRouteNeedsKey(AiProviderConfig.OC_ROUTE_FREE));
    }

    @Test
    public void unimplementedAndTerminalProvidersNeverOffered() {
        assertFalse(config.isProviderConfigured(AiProviderProfile.find("nous")));
        assertFalse(config.isProviderConfigured(AiProviderProfile.find("bedrock")));
        assertFalse(config.isProviderConfigured(null));
    }

    @Test
    public void keyProvidersNeedKeys() {
        AiProviderProfile deepseek = AiProviderProfile.find("deepseek");
        assertFalse(config.isProviderConfigured(deepseek));
        assertTrue(config.configuredSummary(deepseek).contains("API key"));
    }

    @Test
    public void lastUsedRoundTrips() {
        config.setLastUsed("opencode", "glm-5", AiProviderConfig.OC_ROUTE_GO);
        assertEquals("opencode", config.getLastProviderId());
        assertEquals("glm-5", config.getLastModel());
        assertEquals(AiProviderConfig.OC_ROUTE_GO, config.getLastRoute());
    }
}
