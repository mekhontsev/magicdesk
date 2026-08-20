package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class TermuxIntegrationTest {
    @Test
    public void sessionNameIdentifiesNormalizedDirectory() {
        assertEquals(
                "MagicDesk: /storage/emulated/0/Documents",
                TermuxIntegration.shellNameForDirectory(
                        "/storage/emulated/0/Desktop/../Documents"));
    }

    @Test
    public void sessionNameRejectsRelativeDirectory() {
        assertThrows(
                IllegalArgumentException.class,
                () -> TermuxIntegration.shellNameForDirectory("Desktop"));
    }

    @Test
    public void x11CommandDefaultsWhenUnset() {
        assertEquals(
                "termux-x11 :1",
                TermuxX11StartupCommand.normalize("  "));
    }

    @Test
    public void x11CommandChecksForExistingServerBeforeStartup() {
        final String command = TermuxX11StartupCommand.startOrReconnect(
                "termux-x11 :2 -xstartup \"openbox-session\"");

        assertTrue(command.startsWith(
                "for cmdline in /proc/[0-9]*/cmdline; do\n"));
        assertTrue(command.contains(
                "\"termux-x11 com.termux.x11 \"*)"));
        assertTrue(command.contains(
                "printf '0xDEADBEEF\\0' >&3"));
        assertTrue(command.endsWith(
                "termux-x11 :2 -xstartup \"openbox-session\""));
    }

    @Test
    public void x11CommandRejectsOversizedInput() {
        assertThrows(
                IllegalArgumentException.class,
                () -> TermuxX11StartupCommand.normalize(
                        "x".repeat(TermuxX11StartupCommand.MAX_LENGTH + 1)));
    }
}
