package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

public final class DesktopProcessFailureTest {
    @Test
    public void exposesStableDiagnosticIdentity() {
        assertEquals(
                "DESKTOP-PROCESS-CRASH-001",
                DesktopProcessFailure.code(DesktopProcessFailure.CRASH));
        assertEquals(
                "DESKTOP-PROCESS-ANR-001",
                DesktopProcessFailure.code(DesktopProcessFailure.ANR));
        assertEquals("", DesktopProcessFailure.code(99));
    }

    @Test
    public void compactsAndLimitsFailureReason() {
        final String reason = "  Input\n\tdispatching  "
                + repeat('x', 300);

        final String compact = DesktopProcessFailure.compactReason(reason);

        assertEquals(160, compact.length());
        assertFalse(compact.contains("\n"));
        assertFalse(compact.contains("\t"));
        assertFalse(compact.contains("  "));
    }

    @Test
    public void formatsTaskCorrelationWithoutStackTrace() {
        assertEquals(
                "process=com.example.app | pid=123 | task=42 | display=2"
                        + " | windowingMode=5"
                        + " | top=com.example.app/.MainActivity"
                        + " | reason=Illegal state",
                DesktopProcessFailure.technicalDetail(
                        "com.example.app",
                        123,
                        42,
                        2,
                        5,
                        "com.example.app/.MainActivity",
                        "Illegal state"));
    }

    private static String repeat(final char character, final int count) {
        final StringBuilder result = new StringBuilder(count);
        for (int index = 0; index < count; index++) {
            result.append(character);
        }
        return result.toString();
    }
}
