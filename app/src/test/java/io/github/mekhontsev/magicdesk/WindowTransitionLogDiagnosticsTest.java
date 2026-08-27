package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class WindowTransitionLogDiagnosticsTest {
    @Test
    public void reportsOwnershipErrorsWithinSelfTestInterval() {
        final WindowTransitionLogDiagnostics.Snapshot snapshot =
                WindowTransitionLogDiagnostics.parse(
                        "1787815110.006  5538 E ShellTransitions: "
                                + "Got transitionReady for non-pending transition x\n"
                                + "1787815119.369  3420 E TransitionChain: "
                                + "Mismatch between current collecting\n"
                                + "1787815119.369  3420 E TransitionChain: "
                                + "container=Display{#19 state=ON}\n"
                                + "1787815119.369  3420 E TransitionChain: "
                                + "io.github.mekhontsev.magicdesk\n"
                                + "1787815120.000  3420 E TransitionChain: "
                                + "Can't collect into a chain with no transition\n",
                        1_787_815_109_000L,
                        19);

        assertEquals(3, snapshot.errorCount());
        assertEquals(1, snapshot.nonPendingCount);
        assertEquals(1, snapshot.mismatchCount);
        assertEquals(1, snapshot.emptyChainCount);
        assertTrue(snapshot.testDisplayReferenced);
        assertTrue(snapshot.magicDeskReferenced);
    }

    @Test
    public void ignoresOwnershipErrorsBeforeSelfTestInterval() {
        final WindowTransitionLogDiagnostics.Snapshot snapshot =
                WindowTransitionLogDiagnostics.parse(
                        "1787815110.006  5538 E ShellTransitions: "
                                + "Got transitionReady for non-pending transition x\n",
                        1_787_815_111_000L,
                        19);

        assertEquals(0, snapshot.errorCount());
        assertFalse(snapshot.testDisplayReferenced);
        assertFalse(snapshot.magicDeskReferenced);
    }
}
