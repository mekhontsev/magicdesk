package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class CaptureDiagnosticsTest {
    @Test
    public void detailLimitPreservesWholeUnicodeCharacters() {
        final String prefix = "a".repeat(599);
        assertEquals(prefix, CaptureDiagnostics.clean(prefix + "\uD83D\uDE00b"));
        assertEquals("a  b", CaptureDiagnostics.clean(" a\n\u0000b "));
        assertEquals("", CaptureDiagnostics.clean(null));
    }

    @Test
    public void mapsStoredWorkflowStatesToReportLabels() {
        assertEquals("PASS", CaptureDiagnostics.reportState("passed"));
        assertEquals("FAIL", CaptureDiagnostics.reportState("failed"));
        assertEquals("IN_PROGRESS", CaptureDiagnostics.reportState("started"));
        assertEquals("NOT_TESTED", CaptureDiagnostics.reportState("unknown"));
        assertEquals("NOT_TESTED", CaptureDiagnostics.reportState(null));
    }
}
