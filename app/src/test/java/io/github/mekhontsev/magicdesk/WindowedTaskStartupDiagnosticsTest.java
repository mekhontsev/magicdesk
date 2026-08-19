package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import org.junit.After;
import org.junit.Test;

public final class WindowedTaskStartupDiagnosticsTest {
    @After
    public void reset() {
        WindowedTaskStartupDiagnostics.resetForTests();
    }

    @Test
    public void recordsSuccessfulStartupCorrection() {
        WindowedTaskStartupDiagnostics.noteCorrection(
                42, "com.example/.PermissionActivity");

        final WindowedTaskStartupDiagnostics.Snapshot snapshot =
                WindowedTaskStartupDiagnostics.snapshot();
        assertEquals(1, snapshot.corrections);
        assertEquals(42, snapshot.lastTaskId);
        assertEquals(
                "com.example/.PermissionActivity",
                snapshot.lastActivity);
    }
}
