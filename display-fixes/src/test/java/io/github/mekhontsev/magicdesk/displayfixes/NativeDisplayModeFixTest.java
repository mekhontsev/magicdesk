package io.github.mekhontsev.magicdesk.displayfixes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.github.mekhontsev.magicdesk.display.DisplayTimingPolicy;

import org.junit.Test;

public final class NativeDisplayModeFixTest {
    @Test
    public void extractsOnlyMarkedVendorModes() {
        final String output = "su notice\n"
                + "MAGICDESK_MODES_BEGIN\n"
                + "1920x1080 75 0\n2560x1080 75 0\n"
                + "MAGICDESK_MODES_END\ntrailing";

        assertEquals(
                "1920x1080 75 0\n2560x1080 75 0",
                NativeDisplayModeFix.section(
                        output,
                        "MAGICDESK_MODES_BEGIN",
                        "MAGICDESK_MODES_END"));
    }

    @Test
    public void applyCommandUsesValidatedTimingAndRestoresHpd() {
        final DisplayTimingPolicy.ParsedTiming timing =
                new DisplayTimingPolicy.ParsedTiming(2560, 1080, 75, 0);

        final String command =
                NativeDisplayModeFix.createApplyCommand(timing);

        assertTrue(command.contains("'2560 1080 75 0'"));
        assertTrue(command.contains("trap restore_hpd EXIT HUP INT TERM"));
        assertTrue(command.contains("/system/bin/printf 1 > "
                + "/sys/kernel/lcd_enhance/hpd"));
        assertFalse(command.toLowerCase().contains("shizuku"));
    }

    @Test
    public void probeRequiresDirectRootIdentity() {
        final String command = NativeDisplayModeFix.createProbeCommand();

        assertTrue(command.contains("/system/bin/id -u"));
        assertTrue(command.contains("MAGICDESK_STATUS=root_required"));
        assertFalse(command.toLowerCase().contains("shizuku"));
    }
}
