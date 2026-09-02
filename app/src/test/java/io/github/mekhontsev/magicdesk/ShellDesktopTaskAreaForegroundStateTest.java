package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ShellDesktopTaskAreaForegroundStateTest {
    @Test
    public void hostKeepsDesktopForegroundWithoutRaisingApplications() {
        final ShellDesktopTaskArea.ForegroundState state =
                ShellDesktopTaskArea.ForegroundState.HOST;

        assertTrue(state.desktopSessionForeground);
        assertFalse(state.applicationAreaForeground);
    }

    @Test
    public void applicationRaisesSessionAreaInsideDesktop() {
        final ShellDesktopTaskArea.ForegroundState state =
                ShellDesktopTaskArea.ForegroundState.APPLICATION;

        assertTrue(state.desktopSessionForeground);
        assertTrue(state.applicationAreaForeground);
    }

    @Test
    public void outsideTaskLowersAndLeavesDesktop() {
        final ShellDesktopTaskArea.ForegroundState state =
                ShellDesktopTaskArea.ForegroundState.OUTSIDE;

        assertFalse(state.desktopSessionForeground);
        assertFalse(state.applicationAreaForeground);
    }
}
