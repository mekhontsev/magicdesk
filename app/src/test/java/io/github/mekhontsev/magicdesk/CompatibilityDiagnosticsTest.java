package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class CompatibilityDiagnosticsTest {
    @Test
    public void coalescesInterleavedSignaturesForProcessLifetime() {
        assertFalse(CompatibilityDiagnostics.isDuplicate(
                "test-display-event"));
        assertFalse(CompatibilityDiagnostics.isDuplicate(
                "test-wallpaper-event"));
        assertTrue(CompatibilityDiagnostics.isDuplicate(
                "test-display-event"));
        assertTrue(CompatibilityDiagnostics.isDuplicate(
                "test-display-event"));
    }

    @Test
    public void boundsProcessSignatureIndex() {
        assertFalse(CompatibilityDiagnostics.isDuplicate(
                "bounded-event-0"));
        for (int index = 1; index <= 300; index++) {
            assertFalse(CompatibilityDiagnostics.isDuplicate(
                    "bounded-event-" + index));
        }

        assertFalse(CompatibilityDiagnostics.isDuplicate(
                "bounded-event-0"));
        assertTrue(CompatibilityDiagnostics.isDuplicate(
                "bounded-event-0"));
    }

    @Test
    public void removesStaticAuditStatesFromHistoricalEvents() {
        final String events =
                "2026-08-04 | PROFILE-001 | Unverified firmware\n"
                        + "2026-08-04 | SHIZUKU-001 | Server stopped\n"
                        + "2026-08-04 | SHELL-CONSOLE-002 | Launch failed\n"
                        + "2026-08-04 | PLATFORM-001 | Unsupported platform\n"
                        + "2026-08-04 | NUBIA-SCREEN-002 | Screen failed\n";

        assertEquals(
                "2026-08-04 | SHELL-CONSOLE-002 | Launch failed\n"
                        + "2026-08-04 | NUBIA-SCREEN-002 | Screen failed\n",
                CompatibilityDiagnostics.filterRecordedEvents(events));
    }

    @Test
    public void keepsLatestHistoricalDuplicateIncludingStackLines() {
        final String events =
                "2026-08-09T06:10:21Z | NUBIA-DISPLAY-005 | Denied\n"
                        + " at MagicDesk.read(MagicDesk.java:1)\n"
                        + "2026-08-09T06:14:04Z | WALLPAPER-001 | Missing\n"
                        + "2026-08-09T06:14:19Z | NUBIA-DISPLAY-005 | Denied\n"
                        + " at MagicDesk.read(MagicDesk.java:1)\n";

        assertEquals(
                "2026-08-09T06:14:04Z | WALLPAPER-001 | Missing\n"
                        + "2026-08-09T06:14:19Z | NUBIA-DISPLAY-005 | Denied\n"
                        + " at MagicDesk.read(MagicDesk.java:1)\n",
                CompatibilityDiagnostics.filterRecordedEvents(events));
    }

    @Test
    public void selectsNewestCompleteEventsWithinReportLimit() {
        final String events =
                "2026-08-09T06:10:21Z | OLD-001 | Old failure\n"
                        + " at Old.call(Old.java:1)\n"
                        + "2026-08-09T06:14:04Z | NEW-001 | New failure\n"
                        + " at New.call(New.java:2)\n";
        final String newest =
                "2026-08-09T06:14:04Z | NEW-001 | New failure\n"
                        + " at New.call(New.java:2)\n";

        assertEquals(
                newest,
                CompatibilityDiagnostics.selectRecentRecordedEvents(
                        events, newest.length()));
    }

    @Test
    public void ignoresPartialEventAtBeginningOfRotatedInput() {
        final String events =
                " at Truncated.call(Truncated.java:1)\n"
                        + "2026-08-09T06:14:04Z | NEW-001 | New failure\n";

        assertEquals(
                "2026-08-09T06:14:04Z | NEW-001 | New failure\n",
                CompatibilityDiagnostics.filterRecordedEvents(events));
    }
}
