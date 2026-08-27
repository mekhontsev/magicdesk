package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;

public final class WindowTransitionHealthDiagnosticsTest {
    @Test
    public void findsTransitionSessionsForRemovedDisplays() {
        final String dumpsys = "Window manager state\n"
                + "SystemPerformanceHinter:\n"
                + "  Active sessions (3):\n"
                + "    reason=Transition flags=3 display=7\n"
                + "    reason=Transition flags=3 display=3\n"
                + "    reason=Animation flags=1 display=9\n";

        final WindowTransitionHealthDiagnostics.Snapshot snapshot =
                WindowTransitionHealthDiagnostics.parse(
                        dumpsys,
                        new HashSet<>(Arrays.asList(0, 2, 3)));

        assertTrue(snapshot.available);
        assertEquals(3, snapshot.sessions.size());
        assertEquals(1, snapshot.staleTransitions.size());
        assertEquals(7, snapshot.staleTransitions.get(0).displayId);
    }

    @Test
    public void liveTransitionSessionsAreHealthy() {
        final WindowTransitionHealthDiagnostics.Snapshot snapshot =
                WindowTransitionHealthDiagnostics.parse(
                        "SystemPerformanceHinter:\n"
                                + "  Active sessions (1):\n"
                                + "    reason=Transition flags=3 display=5\n",
                        new HashSet<>(Arrays.asList(0, 5)));

        assertTrue(snapshot.available);
        assertFalse(snapshot.hasStaleTransitions());
        assertTrue(snapshot.hasTransitionForDisplay(5));
        assertFalse(snapshot.hasTransitionForDisplay(7));
        assertEquals("system transition active on display 5 (flags=3)",
                snapshot.transitionDetail(5));
    }

    @Test
    public void missingSectionIsReportedAsUnavailable() {
        final WindowTransitionHealthDiagnostics.Snapshot snapshot =
                WindowTransitionHealthDiagnostics.parse(
                        "Window manager state\n",
                        new HashSet<>(Arrays.asList(0)));

        assertFalse(snapshot.available);
    }

    @Test
    public void staleTransitionCountsDistinguishNewCleanupResidue() {
        final WindowTransitionHealthDiagnostics.Snapshot before =
                WindowTransitionHealthDiagnostics.parse(
                        "SystemPerformanceHinter:\n"
                                + "  Active sessions (1):\n"
                                + "    reason=Transition flags=3 display=7\n",
                        new HashSet<>(Arrays.asList(0)));
        final WindowTransitionHealthDiagnostics.Snapshot after =
                WindowTransitionHealthDiagnostics.parse(
                        "SystemPerformanceHinter:\n"
                                + "  Active sessions (2):\n"
                                + "    reason=Transition flags=3 display=7\n"
                                + "    reason=Transition flags=3 display=9\n",
                        new HashSet<>(Arrays.asList(0)));

        final java.util.Map<String, Integer> newCounts =
                after.staleTransitionCountsAfter(
                        before.staleTransitionCounts());

        assertEquals(1, newCounts.size());
        assertEquals("display=9 flags=3", after.staleDetail(newCounts));
        assertEquals(1, newCounts.size());
    }

    @Test
    public void duplicateStaleTransitionAfterBaselineIsNewResidue() {
        final WindowTransitionHealthDiagnostics.Snapshot before =
                WindowTransitionHealthDiagnostics.parse(
                        "SystemPerformanceHinter:\n"
                                + "  Active sessions (1):\n"
                                + "    reason=Transition flags=3 display=7\n",
                        new HashSet<>(Arrays.asList(0)));
        final WindowTransitionHealthDiagnostics.Snapshot after =
                WindowTransitionHealthDiagnostics.parse(
                        "SystemPerformanceHinter:\n"
                                + "  Active sessions (2):\n"
                                + "    reason=Transition flags=3 display=7\n"
                                + "    reason=Transition flags=3 display=7\n",
                        new HashSet<>(Arrays.asList(0)));

        final java.util.Map<String, Integer> newCounts =
                after.staleTransitionCountsAfter(
                        before.staleTransitionCounts());

        assertEquals(1, newCounts.size());
        assertEquals("display=7 flags=3", after.staleDetail(newCounts));
    }

}
