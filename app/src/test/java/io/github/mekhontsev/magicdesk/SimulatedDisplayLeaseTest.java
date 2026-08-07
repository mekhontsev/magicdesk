package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class SimulatedDisplayLeaseTest {
    @Test
    public void commandRestoresPreviousSettingOnEveryExit() {
        final String command = SimulatedDisplayLease.createCommand();

        assertTrue(command.contains(
                "settings get global overlay_display_devices"));
        assertTrue(command.contains("trap restore_overlay EXIT"));
        assertTrue(command.contains("HUP INT TERM"));
        assertTrue(command.contains(
                "settings delete global overlay_display_devices"));
        assertTrue(command.contains(
                "settings put global overlay_display_devices \"$previous\""));
        assertTrue(command.contains("'1920x1080/160'"));
    }
}
