package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AppFunctionCapabilityProbeTest {
    @Test
    public void abandonedProbeCancelsTheFrameworkRequest() throws Exception {
        final String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/io/github/mekhontsev/magicdesk/"
                        + "AppFunctionCapabilityProbe.java"));
        assertTrue(source.contains("Runnable::run,\n                        cancellation,"));
        assertTrue(source.contains("if (!completed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {\n"
                + "                    cancellation.cancel();"));
        assertTrue(source.contains("Thread.currentThread().interrupt();\n"
                + "                cancellation.cancel();"));
    }

    @Test
    public void recognizesStructuredAutomationResult() {
        assertTrue(AppFunctionCapabilityProbe.isSuccessfulResponse(
                "{\"success\":true,\"message\":\"ok\",\"data\":{}}"));
        assertFalse(AppFunctionCapabilityProbe.isSuccessfulResponse(
                "{\"success\":false}"));
        assertFalse(AppFunctionCapabilityProbe.isSuccessfulResponse("invalid"));
    }
}
