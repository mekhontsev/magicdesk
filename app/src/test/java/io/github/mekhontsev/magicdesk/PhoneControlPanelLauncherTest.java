package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class PhoneControlPanelLauncherTest {
    @Test
    public void launchCommandTargetsPhoneDisplay() {
        assertEquals(
                "/system/bin/am start --user 0 --display 0"
                        + " --activity-clear-top --activity-single-top"
                        + " -n io.github.example/.ControlActivity",
                PhoneControlPanelLauncher.createLaunchCommand(
                        "io.github.example",
                        "io.github.example.ControlActivity"));
    }

    @Test
    public void commandFailureRecognizesActivityManagerErrors() {
        assertTrue(PhoneControlPanelLauncher.commandFailed(
                "Error: Activity not started"));
        assertTrue(PhoneControlPanelLauncher.commandFailed(
                "java.lang.SecurityException: Permission Denial"));
        assertFalse(PhoneControlPanelLauncher.commandFailed(
                "Starting: Intent { cmp=example/.ControlActivity }"));
    }
}
