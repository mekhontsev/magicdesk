package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.json.JSONObject;
import org.junit.Test;

public final class AndroidActivityCompatibilityHistoryTest {
    @Test
    public void evidenceDoesNotKeepNestedReferencesToTheLaunchResult() throws Exception {
        final JSONObject source = new JSONObject()
                .put("bounds", new JSONObject().put("left", 12))
                .put("authorization", new JSONObject().put("decision", "allowed"));
        for (final boolean success : new boolean[] {true, false}) {
            final var result = success ? DesktopAutomationResult.success("ok", source)
                    : DesktopAutomationResult.failure("failed", source);
            final JSONObject evidence = AndroidActivityCompatibilityHistory
                    .diagnosticSummary(null, result).getJSONObject("result");
            source.getJSONObject("bounds").put("privateContent", "must not reach history");
            source.getJSONObject("authorization").put("decision", "changed");
            assertEquals("allowed", evidence.getJSONObject("authorization").getString("decision"));
            assertFalse(evidence.getJSONObject("bounds").has("privateContent"));
            source.getJSONObject("bounds").remove("privateContent");
            source.getJSONObject("authorization").put("decision", "allowed");
        }
    }

    @Test
    public void changingEvidenceDoesNotModifyTheOperationResult() throws Exception {
        final JSONObject source = new JSONObject()
                .put("bounds", new JSONObject().put("left", 12));
        final JSONObject evidence = AndroidActivityCompatibilityHistory.diagnosticSummary(
                null, DesktopAutomationResult.success("ok", source)).getJSONObject("result");
        evidence.getJSONObject("bounds").put("left", 99);
        assertEquals(12, source.getJSONObject("bounds").getInt("left"));
    }

    @Test
    public void projectionStillExcludesExtrasAndUnlistedFields() throws Exception {
        final JSONObject source = new JSONObject()
                .put("extras", new JSONObject().put("text", "private"))
                .put("clipUris", "private")
                .put("taskId", 12);
        final JSONObject evidence = AndroidActivityCompatibilityHistory.diagnosticSummary(
                null, DesktopAutomationResult.success("ok", source)).getJSONObject("result");
        assertEquals(1, evidence.length());
        assertEquals(12, evidence.getInt("taskId"));
    }
}
