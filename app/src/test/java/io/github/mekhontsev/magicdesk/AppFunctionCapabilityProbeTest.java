package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AppFunctionCapabilityProbeTest {
    @Test
    public void recognizesStructuredAutomationResult() {
        assertTrue(AppFunctionCapabilityProbe.isSuccessfulResponse(
                "{\"success\":true,\"message\":\"ok\",\"data\":{}}"));
        assertFalse(AppFunctionCapabilityProbe.isSuccessfulResponse(
                "{\"success\":false}"));
        assertFalse(AppFunctionCapabilityProbe.isSuccessfulResponse("invalid"));
    }
}
