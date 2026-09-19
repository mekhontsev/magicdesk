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
                FrameworkDesktopShellApi.moveAction(
                        "desktopmode moveTaskToDesk <taskId>\n"
                                + "moveToDesktop <taskId>"));
    }

    @Test
    public void selectsAndroid15DesktopCommand() {
        assertEquals("moveToDesktop",
                FrameworkDesktopShellApi.moveAction(
                        "desktopmode moveToDesktop <taskId>"));
    }

    @Test
    public void rejectsUnrelatedWmShellHelp() {
        assertNull(FrameworkDesktopShellApi.moveAction(
                "pip help\nsplitscreen help"));
        assertNull(FrameworkDesktopShellApi.moveAction(
                "desktopmode moveToNextDisplay <taskId>"));
    }

    @Test public void explicitDeskIsNotAOneArgumentMove() {
        for (String task : new String[]{"<taskId>", "<taskId|0>"}) {
            String help = "desktopmode\n  moveTaskToDesk " + task + " <deskId>\n"
                    + "  moveTaskOutOfDesk <taskId>\n";
            assertNull(FrameworkDesktopShellApi.moveAction(help));
            assertTrue(FrameworkDesktopShellApi.canExitDesk(help));
        }
        assertFalse(FrameworkDesktopShellApi.canExitDesk("desktopmode"));
        assertFalse(FrameworkDesktopShellApi.canExitDesk(null));
    }

    @Test public void optionalMultideskHelpStillAllowsLegacyMove() {
        String help = "desktopmode\n moveTaskToDesk <taskId> \n"
                + " moveTaskToDesk <taskId> <deskId>\n moveTaskOutOfDesk <taskId>\n";
        assertEquals("moveTaskToDesk", FrameworkDesktopShellApi.moveAction(help));
    }
}
