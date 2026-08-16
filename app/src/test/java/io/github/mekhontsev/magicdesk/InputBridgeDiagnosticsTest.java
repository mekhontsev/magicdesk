package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

import java.io.IOException;

public final class InputBridgeDiagnosticsTest {
    @After
    public void reset() {
        InputBridgeDiagnostics.resetForTests();
    }

    @Test
    public void recordsOnlyBridgeLifecycleState() {
        InputBridgeDiagnostics.noteAttempt(4);
        InputBridgeDiagnostics.noteReady(true);
        InputBridgeDiagnostics.notePointerReactivation();
        InputBridgeDiagnostics.noteSourceRefreshFailure(
                new IOException("input refresh failed\nwithout event data"));

        final InputBridgeDiagnostics.Snapshot snapshot =
                InputBridgeDiagnostics.snapshot();
        assertEquals(1, snapshot.attempts);
        assertEquals(1, snapshot.readySessions);
        assertEquals(1, snapshot.sourceRefreshFailures);
        assertEquals(1, snapshot.pointerReactivations);
        assertEquals(4, snapshot.routingDisplayId);
        assertTrue(snapshot.lastFailure.contains("input refresh failed"));
        assertFalse(snapshot.lastFailure.contains("\n"));
    }
}
