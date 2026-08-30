package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public final class DesktopTaskLaunchDiagnosticsTest {
    @Before
    public void setUp() {
        DesktopTaskLaunchDiagnostics.resetForTests();
    }

    @After
    public void tearDown() {
        DesktopTaskLaunchDiagnostics.resetForTests();
    }

    @Test
    public void explicitLaunchWinsOverLaterObservedTransfer() {
        DesktopTaskLaunchDiagnostics.note(
                42, 0, 3, "desktop-window-reuse");
        DesktopTaskLaunchDiagnostics.noteIfAbsent(
                42, 3, 4, "observed-display-transfer");

        final DesktopTaskLaunchDiagnostics.Entry entry =
                DesktopTaskLaunchDiagnostics.find(42);
        assertEquals(0, entry.originalDisplayId);
        assertEquals(3, entry.targetDisplayId);
        assertEquals("desktop-window-reuse", entry.path);
    }
}
