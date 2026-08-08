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
        assertFalse(DesktopScreenPolicy.isExternalDesktopSession(0, 0, true));
        assertFalse(DesktopScreenPolicy.isExternalDesktopSession(7, -1, false));
        assertTrue(DesktopScreenPolicy.isExternalDesktopSession(7, -1, true));
        assertTrue(DesktopScreenPolicy.isExternalDesktopSession(7, 7, false));
        assertFalse(DesktopScreenPolicy.isExternalDesktopSession(7, 8, false));

        assertFalse(DesktopScreenPolicy.canControlPhoneScreen(
                false, DesktopDisplayTarget.Kind.WIRELESS, true));
        assertFalse(DesktopScreenPolicy.canControlPhoneScreen(
                true, DesktopDisplayTarget.Kind.WIRELESS, false));
        assertFalse(DesktopScreenPolicy.canControlPhoneScreen(
                true, DesktopDisplayTarget.Kind.SIMULATED, true));
        assertTrue(DesktopScreenPolicy.canControlPhoneScreen(
                true, DesktopDisplayTarget.Kind.WIRELESS, true));
        assertTrue(DesktopScreenPolicy.canControlPhoneScreen(
                true, null, true));
    }
}
