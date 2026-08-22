package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import org.junit.After;
import org.junit.Test;

public final class TaskActivityModeDiagnosticsTest {
    @After
    public void reset() {
        TaskActivityModeDiagnostics.resetForTests();
    }

    @Test
    public void recordsSuccessfulStartupCorrection() {
        TaskActivityModeDiagnostics.noteCorrection(
                42, "com.example/.PermissionActivity", "fullscreen");

        final TaskActivityModeDiagnostics.Snapshot snapshot =
                TaskActivityModeDiagnostics.snapshot();
        assertEquals(1, snapshot.corrections);
        assertEquals(42, snapshot.lastTaskId);
        assertEquals(
                "com.example/.PermissionActivity",
                snapshot.lastActivity);
        assertEquals("fullscreen", snapshot.lastMode);
    }
}
