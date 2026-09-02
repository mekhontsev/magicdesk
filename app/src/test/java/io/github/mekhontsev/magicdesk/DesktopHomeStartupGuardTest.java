package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DesktopHomeStartupGuardTest {
    @Test
    public void mainProcessOwnsStartupRecovery() {
        assertTrue(DesktopHomeStartupGuard.isPrimaryProcess(
                "io.github.mekhontsev.magicdesk",
                "io.github.mekhontsev.magicdesk"));
    }

    @Test
    public void auxiliaryProcessesCannotReleaseHome() {
        assertFalse(DesktopHomeStartupGuard.isPrimaryProcess(
                "io.github.mekhontsev.magicdesk:task_area_backstop",
                "io.github.mekhontsev.magicdesk"));
        assertFalse(DesktopHomeStartupGuard.isPrimaryProcess(
                "io.github.mekhontsev.magicdesk:selftest",
                "io.github.mekhontsev.magicdesk"));
    }
}
