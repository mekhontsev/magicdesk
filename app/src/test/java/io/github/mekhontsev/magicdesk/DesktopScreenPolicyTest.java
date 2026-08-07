package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DesktopScreenPolicyTest {
    @Test
    public void workspaceActionDoesNotDependOnInternalOrExternalDisplay() {
        assertEquals(
                DesktopScreenPolicy.WorkspaceAction.FOCUS_DESKTOP,
                DesktopScreenPolicy.workspaceAction(0, Boolean.TRUE));
        assertEquals(
                DesktopScreenPolicy.WorkspaceAction.RESTORE_WINDOWS,
                DesktopScreenPolicy.workspaceAction(0, Boolean.FALSE));
        assertEquals(
                DesktopScreenPolicy.WorkspaceAction.FOCUS_DESKTOP,
                DesktopScreenPolicy.workspaceAction(7, Boolean.TRUE));
        assertEquals(
                DesktopScreenPolicy.WorkspaceAction.FOCUS_DESKTOP,
                DesktopScreenPolicy.workspaceAction(7, null));
        assertEquals(
                DesktopScreenPolicy.WorkspaceAction.RESTORE_WINDOWS,
                DesktopScreenPolicy.workspaceAction(7, Boolean.FALSE));
    }

    @Test
    public void missingDesktopStartsExternalSession() {
        assertEquals(
                DesktopScreenPolicy.WorkspaceAction.START_EXTERNAL_DESKTOP,
                DesktopScreenPolicy.workspaceAction(-1, null));
    }

    @Test
    public void phoneScreenControlRequiresExternalDesktopSession() {
        assertFalse(DesktopScreenPolicy.isExternalDesktop(0));
        assertTrue(DesktopScreenPolicy.isExternalDesktop(7));
        assertFalse(DesktopScreenPolicy.canControlPhoneScreen(0, true, true));
        assertFalse(DesktopScreenPolicy.canControlPhoneScreen(7, false, true));
        assertFalse(DesktopScreenPolicy.canControlPhoneScreen(7, true, false));
        assertTrue(DesktopScreenPolicy.canControlPhoneScreen(7, true, true));
    }
}
