package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class NativeDesktopControllerTest {
    @Test
    public void nativeDesktopRequiresPrivilegedBackendAndSuccessfulProbe() {
        assertFalse(NativeDesktopController.shouldUse(false, true));
        assertFalse(NativeDesktopController.shouldUse(true, false));
        assertTrue(NativeDesktopController.shouldUse(true, true));
    }

    @Test
    public void selectsAndroid16DesktopCommandWhenAvailable() {
        assertEquals("moveTaskToDesk",
                NativeDesktopController.selectMoveAction(
                        "desktopmode moveTaskToDesk <taskId>\n"
                                + "moveToDesktop <taskId>"));
    }

    @Test
    public void selectsAndroid15DesktopCommand() {
        assertEquals("moveToDesktop",
                NativeDesktopController.selectMoveAction(
                        "desktopmode moveToDesktop <taskId>"));
    }

    @Test
    public void rejectsUnrelatedWmShellHelp() {
        assertNull(NativeDesktopController.selectMoveAction(
                "pip help\nsplitscreen help"));
        assertNull(NativeDesktopController.selectMoveAction(
                "desktopmode moveToNextDisplay <taskId>"));
    }
}
