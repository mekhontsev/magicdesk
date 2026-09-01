package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ShellDesktopWorkspaceCoordinatorTest {
    private static final int WINDOWING_MODE_FULLSCREEN = 1;
    private static final int WINDOWING_MODE_FREEFORM = 5;

    @Test
    public void directRootCloseAcceptsOnlyFreeformTasks() {
        assertTrue(ShellDesktopWorkspaceCoordinator.supportsDirectClose(
                WINDOWING_MODE_FREEFORM, true));
        assertFalse(ShellDesktopWorkspaceCoordinator.supportsDirectClose(
                WINDOWING_MODE_FULLSCREEN, true));
    }

    @Test
    public void sessionCloseAcceptsSharedFullscreenTasks() {
        assertTrue(ShellDesktopWorkspaceCoordinator.supportsDirectClose(
                WINDOWING_MODE_FREEFORM, false));
        assertTrue(ShellDesktopWorkspaceCoordinator.supportsDirectClose(
                WINDOWING_MODE_FULLSCREEN, false));
    }
}
