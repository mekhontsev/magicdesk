package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class TermuxIntegrationTest {
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
                "requested=':2'\n"
                        + "for cmdline in /proc/[0-9]*/cmdline; do\n"));
        assertTrue(command.contains(
                "\"termux-x11 com.termux.x11 $requested\"|"
                        + "\"termux-x11 com.termux.x11 $requested \"*)"));
        assertTrue(command.contains(
                "printf '0xDEADBEEF\\0' >&3"));
        assertTrue(command.contains(
                "        break\n"
                        + "        ;;"));
        assertTrue(command.endsWith(
                "termux-x11 :2 -xstartup \"openbox-session\""));
    }

    @Test
    public void x11CommandSelectsTheRequestedDisplay() {
        assertEquals(
                ":3",
                TermuxX11StartupCommand.requestedDisplay(
                        "/data/data/com.termux/files/usr/bin/termux-x11"
                                + " :3 -xstartup xfce4-session"));
        assertEquals(
                "",
                TermuxX11StartupCommand.requestedDisplay(
                        "bash ~/start-desktop.sh"));
        assertEquals(
                "",
                TermuxX11StartupCommand.requestedDisplay(
                        "echo termux-x11 :9"));
    }

    @Test
    public void x11CustomScriptIsStartedWithoutGuessingAProcess() {
        assertEquals(
                "bash ~/start-desktop.sh",
                TermuxX11StartupCommand.startOrReconnect(
                        "bash ~/start-desktop.sh"));
    }

    @Test
    public void x11ReconnectDoesNotStartAnotherServer() {
        final String command = TermuxX11StartupCommand.reconnect(
                "termux-x11 :4");

        assertTrue(command.contains("reconnectedDisplay=%s"));
        assertTrue(command.contains("server not found for %s"));
        assertTrue(command.endsWith("exit 66"));
        assertTrue(!command.endsWith("termux-x11 :4"));
    }

    @Test
    public void x11StatusProbeIsBoundToConfiguredDisplay() {
        final String command = TermuxX11StartupCommand.statusProbe(
                "termux-x11 :5");

        assertTrue(command.startsWith("requested=':5'\n"));
        assertTrue(command.contains("socketListening=%s"));
        assertTrue(command.contains("/proc/$server_pid/net/tcp"));
        assertTrue(command.contains("/proc/net/tcp6"));
    }

    @Test
    public void x11CommandRejectsOversizedInput() {
        assertThrows(
                IllegalArgumentException.class,
                () -> TermuxX11StartupCommand.normalize(
                        "x".repeat(TermuxX11StartupCommand.MAX_LENGTH + 1)));
    }

    @Test
    public void desktopExecRejectsOversizedInput() {
        assertThrows(
                IllegalArgumentException.class,
                () -> DesktopExecCommand.normalize(
                        "x".repeat(DesktopExecCommand.MAX_LENGTH + 1)));
    }

    @Test
    public void termuxResultUsesAndroidResultOkAsSuccessErrno() {
        assertTrue(new TermuxIntegration.CommandResult(
                0, -1, "ok", "", "").success());
        assertTrue(!new TermuxIntegration.CommandResult(
                0, 0, "", "", "").success());
        assertTrue(!new TermuxIntegration.CommandResult(
                1, -1, "", "failed", "").success());
    }
}
