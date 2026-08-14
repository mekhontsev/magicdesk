package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class PhoneDisplayGuardTest {
    @Test
    public void selectsAndroid16DisplayRestoreCommand() {
        assertEquals("power-reset", PhoneDisplayGuard.selectRestoreOperation(
                "power-reset DISPLAY_ID\npower-off DISPLAY_ID"));
    }

    @Test
    public void selectsAdvertisedLegacyDisplayRestoreCommand() {
        assertEquals("power-on", PhoneDisplayGuard.selectRestoreOperation(
                "power-on DISPLAY_ID\npower-off DISPLAY_ID"));
    }

    @Test
    public void recognizesAndroid15CommandOmittedFromDisplayHelp() {
        assertTrue(PhoneDisplayGuard.isRecognizedRestoreProbe(
                new ShellAccess.CommandResult(
                        1, "Error: no displayId specified\n")));
        assertFalse(PhoneDisplayGuard.isRecognizedRestoreProbe(
                new ShellAccess.CommandResult(
                        -1, "Unknown command: power-on\n")));
    }

    @Test
    public void rejectsDisplayHelpWithoutRestoreCommand() {
        assertNull(PhoneDisplayGuard.selectRestoreOperation(
                "enable-display DISPLAY_ID\ndisable-display DISPLAY_ID"));
        assertNull(PhoneDisplayGuard.selectRestoreOperation(null));
    }

    @Test
    public void helperAcceptsOnlyKnownRestoreCommands() {
        assertTrue(PhoneDisplayGuardCommand.isRestoreOperation("power-reset"));
        assertTrue(PhoneDisplayGuardCommand.isRestoreOperation("power-on"));
        assertFalse(PhoneDisplayGuardCommand.isRestoreOperation("power-off"));
        assertFalse(PhoneDisplayGuardCommand.isRestoreOperation("reset"));
    }
}
