package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class NativeDesktopControllerTest {
    @Test public void desktopShellModeIsIndependentFromTaskCommandSignature() {
        assertEquals(FrameworkDesktopShellApi.Mode.NATIVE,
                FrameworkDesktopShellApi.fromHelp("desktopmode\n moveTaskToDesk <taskId> <deskId>\n").mode());
        assertEquals(FrameworkDesktopShellApi.Mode.BASIC,
                FrameworkDesktopShellApi.fromHelp("  Window Manager Shell commands:\n    splitscreen\n    help\n").mode());
        for (String help : new String[]{null, "", "Permission denied", "unknown desktopmode command"}) {
            assertEquals(FrameworkDesktopShellApi.Mode.UNKNOWN, FrameworkDesktopShellApi.fromHelp(help).mode());
        }
    }

    @Test public void explicitRefreshReplacesCachedBackendAfterSystemUiRestart() throws Exception {
        final String controller = RuntimeSourceFixture.nestedClass("NativeDesktopController", "NativeDesktopController")
                .replace("final class NativeDesktopController", "static final class NativeDesktopController");
        final String api = RuntimeSourceFixture.nestedClass("FrameworkDesktopShellApi", "FrameworkDesktopShellApi")
                .replace("final class FrameworkDesktopShellApi", "static final class FrameworkDesktopShellApi")
                .replace("Pattern.", "java.util.regex.Pattern.");
        RuntimeSourceFixture.verify("""
                static class Log {
                    static void w(String tag, String message, Throwable error) { }
                    static void i(String tag, String message) { }
                }
                static class ShellAccess {
                    static String help = "desktopmode moveTaskToDesk <taskId>";
                    static boolean fail;
                    static int reads;
                    static boolean isReady() { return true; }
                    static String run(String command) throws IOException {
                        reads++;
                        if (fail) throw new IOException("offline");
                        return help;
                    }
                }
                """ + api + controller + """
                public static void verify() {
                    check(NativeDesktopController.isAvailable(), "initial native backend unavailable");
                    check(NativeDesktopController.isAvailable() && ShellAccess.reads == 1, "cached calls reprobe");
                    ShellAccess.help = "Window Manager Shell commands:\\n splitscreen\\n";
                    check(NativeDesktopController.refresh().mode() == FrameworkDesktopShellApi.Mode.BASIC, "stale mode after restart");
                    check(!NativeDesktopController.isAvailable() && ShellAccess.reads == 2, "stale entry command");
                    ShellAccess.fail = true;
                    check(NativeDesktopController.refresh().mode() == FrameworkDesktopShellApi.Mode.UNKNOWN, "failure inferred basic mode");
                    ShellAccess.fail = false;
                    ShellAccess.help = "desktopmode moveToDesktop <taskId>";
                    check(NativeDesktopController.isAvailable(), "failed probe prevented recovery");
                }
                """);
    }

    @Test
    public void nativeDesktopRequiresPrivilegedBackendAndSuccessfulProbe() {
        assertFalse(NativeDesktopController.shouldUse(false, true));
        assertFalse(NativeDesktopController.shouldUse(true, false));
        assertTrue(NativeDesktopController.shouldUse(true, true));
    }

    @Test
    public void selectsAndroid16DesktopCommandWhenAvailable() {
        assertEquals("/system/bin/cmd statusbar wmshell-passthrough desktopmode moveTaskToDesk 42",
                FrameworkDesktopShellApi.fromHelp(
                        "desktopmode moveTaskToDesk <taskId>\n"
                                + "moveToDesktop <taskId>").enterDesktopCommand(
                                        FrameworkDesktopShellApi.Transport.STATUS_BAR, 42));
    }

    @Test
    public void selectsAndroid15DesktopCommand() {
        assertEquals("/system/bin/cmd window shell desktopmode moveToDesktop 42",
                FrameworkDesktopShellApi.fromHelp(
                        "desktopmode moveToDesktop <taskId>").enterDesktopCommand(
                                FrameworkDesktopShellApi.Transport.WINDOW, 42));
    }

    @Test
    public void rejectsUnrelatedWmShellHelp() {
        assertFalse(FrameworkDesktopShellApi.fromHelp(
                "pip help\nsplitscreen help").canEnterDesktop());
        assertFalse(FrameworkDesktopShellApi.fromHelp(
                "desktopmode moveToNextDisplay <taskId>").canEnterDesktop());
    }

    @Test public void explicitDeskIsNotAOneArgumentMove() {
        for (String task : new String[]{"<taskId>", "<taskId|0>"}) {
            String help = "desktopmode\n  moveTaskToDesk " + task + " <deskId>\n"
                    + "  moveTaskOutOfDesk <taskId>\n";
            final FrameworkDesktopShellApi api = FrameworkDesktopShellApi.fromHelp(help);
            assertFalse(api.canEnterDesktop());
            assertTrue(api.canExitDesktop());
            assertEquals("/system/bin/cmd window shell desktopmode moveTaskOutOfDesk 42",
                    api.exitDesktopCommand(FrameworkDesktopShellApi.Transport.WINDOW, 42));
        }
        assertFalse(FrameworkDesktopShellApi.fromHelp("desktopmode").canExitDesktop());
        assertFalse(FrameworkDesktopShellApi.fromHelp(null).canExitDesktop());
    }

    @Test public void optionalMultideskHelpStillAllowsLegacyMove() {
        String help = "desktopmode\n moveTaskToDesk <taskId> \n"
                + " moveTaskToDesk <taskId> <deskId>\n moveTaskOutOfDesk <taskId>\n";
        assertTrue(FrameworkDesktopShellApi.fromHelp(help).canEnterDesktop());
        assertEquals("wmshell-passthrough desktopmode moveTaskToDesk",
                FrameworkDesktopShellApi.fromHelp(help).entryDescription());
    }

    @Test(expected = IllegalStateException.class)
    public void unavailableEntryCannotProduceACommand() {
        FrameworkDesktopShellApi.fromHelp(null).enterDesktopCommand(
                FrameworkDesktopShellApi.Transport.WINDOW, 42);
    }

    @Test(expected = IllegalStateException.class)
    public void unavailableExitCannotProduceACommand() {
        FrameworkDesktopShellApi.fromHelp(null).exitDesktopCommand(
                FrameworkDesktopShellApi.Transport.WINDOW, 42);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsInvalidTaskId() {
        FrameworkDesktopShellApi.fromHelp("desktopmode moveToDesktop <taskId>")
                .enterDesktopCommand(FrameworkDesktopShellApi.Transport.STATUS_BAR, -1);
    }
}
