package com.termux.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
public class AiReasoningLevelsTest {

    private static Map<String, JSONObject> entries(JSONObject... items) throws Exception {
        Map<String, JSONObject> out = new HashMap<>();
        for (JSONObject item : items) out.put(item.optString("id"), item);
        return out;
    }

    @Test
    public void unknownModelFallsBackToDefault() {
        AiReasoningLevels.Result result =
            AiReasoningLevels.interpret(new HashMap<>(), "gpt-x");
        assertEquals(AiReasoningLevels.Status.DEFAULT, result.status);
    }

    @Test
    public void missingMetadataFallsBackToDefault() throws Exception {
        Map<String, JSONObject> catalog = entries(new JSONObject().put("id", "plain-model"));
        AiReasoningLevels.Result result = AiReasoningLevels.interpret(catalog, "plain-model");
        assertEquals(AiReasoningLevels.Status.DEFAULT, result.status);
    }

    @Test
    public void supportedParametersWithoutReasoningHidesLadder() throws Exception {
        Map<String, JSONObject> catalog = entries(new JSONObject()
            .put("id", "text-only")
            .put("supported_parameters", new JSONArray().put("temperature").put("tools")));
        AiReasoningLevels.Result result = AiReasoningLevels.interpret(catalog, "text-only");
        assertEquals(AiReasoningLevels.Status.UNSUPPORTED, result.status);
    }

    @Test
    public void supportedParametersWithReasoningKeepsLadder() throws Exception {
        Map<String, JSONObject> catalog = entries(new JSONObject()
            .put("id", "thinker")
            .put("supported_parameters", new JSONArray().put("reasoning").put("tools")));
        AiReasoningLevels.Result result = AiReasoningLevels.interpret(catalog, "thinker");
        assertEquals(AiReasoningLevels.Status.DEFAULT, result.status);
    }

    @Test
    public void copilotStyleExplicitLevels() throws Exception {
        Map<String, JSONObject> catalog = entries(new JSONObject()
            .put("id", "copilot-model")
            .put("capabilities", new JSONObject()
                .put("supports", new JSONObject()
                    .put("reasoning_effort", new JSONArray().put("low").put("high")))));
        AiReasoningLevels.Result result = AiReasoningLevels.interpret(catalog, "copilot-model");
        assertEquals(AiReasoningLevels.Status.EXPLICIT, result.status);
        assertEquals(Arrays.asList("low", "high"), result.levels);
    }

    @Test
    public void clampNeverEscalatesCost() {
        assertEquals("medium",
            AiReasoningLevels.clamp("xhigh", Arrays.asList("low", "medium")));
        assertEquals("low",
            AiReasoningLevels.clamp("low", Arrays.asList("low", "medium")));
        assertEquals("ultra",
            AiReasoningLevels.clamp("ultra", Arrays.asList("low", "ultra")));
        assertEquals("low",
            AiReasoningLevels.clamp("ultra", Arrays.asList("low")));
        assertEquals("mystery",
            AiReasoningLevels.clamp("mystery", Arrays.asList("low", "medium")));
    }
}
