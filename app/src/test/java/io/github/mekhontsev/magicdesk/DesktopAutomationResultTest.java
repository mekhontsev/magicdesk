package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public final class DesktopAutomationResultTest {
    @Test
    public void failureContainsStableMachineReadableError() throws Exception {
        final DesktopAutomationResult result = DesktopAutomationResult.failure(
                DesktopAutomationErrorCode.TASK_NOT_FOUND,
                "task not found",
                false,
                new JSONObject().put("taskId", 42));
        final JSONObject json = result.toJson();

        assertFalse(json.getBoolean("success"));
        assertEquals("TASK_NOT_FOUND",
                json.getJSONObject("error").getString("code"));
        assertFalse(json.getJSONObject("error")
                .getBoolean("retryable"));
        assertEquals(42, json.getJSONObject("error")
                .getJSONObject("observation").getInt("taskId"));
    }

    @Test
    public void successHasNullErrorAndKeepsAttachmentOutOfJson()
            throws Exception {
        final DesktopAutomationResult result = DesktopAutomationResult.success(
                "captured",
                new JSONObject().put("width", 1),
                new DesktopAutomationImage("image/png", "AA=="));
        final JSONObject json = result.toJson();

        assertTrue(json.getBoolean("success"));
        assertTrue(json.isNull("error"));
        assertFalse(json.toString().contains("AA=="));
    }
}
