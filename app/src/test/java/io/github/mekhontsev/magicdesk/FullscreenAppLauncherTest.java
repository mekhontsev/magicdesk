package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class FullscreenAppLauncherTest {
    @Test
    public void launchCommandForcesFullscreenOnTargetDisplay() {
        assertEquals(
                "/system/bin/am start --user 0 --display 19"
                        + " --windowingMode 1"
                        + " -f 0x34220000"
                        + " -a android.intent.action.MAIN"
                        + " -c android.intent.category.LAUNCHER"
                        + " --ez start_from_heartservice_app_lock true"
                        + " -n com.example/.MainActivity",
                FullscreenAppLauncher.createLaunchCommand(
                        "com.example",
                        "com.example.MainActivity",
                        19,
                        0x34220000));
    }

    @Test
    public void commandFailureRecognizesActivityManagerErrors() {
        assertTrue(FullscreenAppLauncher.commandFailed(
                "Error: Activity not started"));
        assertTrue(FullscreenAppLauncher.commandFailed(
                "java.lang.SecurityException: Permission Denial"));
        assertFalse(FullscreenAppLauncher.commandFailed(
                "Starting: Intent { cmp=com.example/.MainActivity }"));
    }
}
