package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class LinuxLaunchRecipeTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void readsOnlyInstalledNamesAndRejectsHumanOutput() {
        assertEquals(List.of("alpine", "ubuntu"), LinuxLaunchRecipe.parseInstalledProot("ubuntu\r\nalpine\nubuntu\n"));
        assertTrue(LinuxLaunchRecipe.parseInstalledProot("\n").isEmpty());
        for (String bad : List.of("Installed containers:", "-ubuntu", "ubuntu;id", "../ubuntu", "x".repeat(129)))
            assertThrows(IllegalArgumentException.class, () -> LinuxLaunchRecipe.parseInstalledProot(bad));
    }

    @Test public void terminalWithoutCommandEntersLoginShellWithoutX11() throws Exception {
        var shortcut = recipe("", "", LinuxLaunchRecipe.Presentation.TERMINAL);
        assertTrue(shortcut.terminal);
        assertNull(shortcut.graphics);
        assertEquals(List.of("login", "--isolated", "ubuntu"), arguments(shortcut));
        assertFalse(shortcut.exec.contains("DISPLAY"));
    }

    @Test public void terminalCommandAndGuestDirectoryStayLiteral() throws Exception {
        String command = "printf '%s\\n' \"$HOME\"; echo 'a b'";
        var shortcut = recipe(command, "/root/a ' b", LinuxLaunchRecipe.Presentation.TERMINAL);
        assertEquals(List.of("login", "--isolated", "--work-dir", "/root/a ' b", "ubuntu",
                "--", "/bin/sh", "-lc", command), arguments(shortcut));
        assertFalse(DesktopExecTemplate.acceptsArguments(shortcut.exec));
    }

    @Test public void graphicalRecipesUseDynamicSocketAuthorizationAndOwnGuestSession() throws Exception {
        for (var mode : List.of(LinuxLaunchRecipe.Presentation.APPLICATION, LinuxLaunchRecipe.Presentation.DESKTOP)) {
            var shortcut = recipe("xfce4-session", "", mode);
            assertFalse(shortcut.terminal);
            assertEquals(mode == LinuxLaunchRecipe.Presentation.DESKTOP, shortcut.graphics != null && shortcut.graphics.desktop());
            var args = arguments(shortcut);
            assertEquals(List.of("login", "--isolated", "--shared-tmp", "--bind",
                    "/private/runtime ' dir:/tmp/magicdesk-x11", "--env", "DISPLAY=:37", "--env", "XAUTHORITY=/tmp/magicdesk-x11/Xauthority",
                    "--bind", "/apk/helper:/tmp/magicdesk-guest-files",
                    "--env", "MAGICDESK_GUEST_FILES_SOCKET=channel", "--env", "MAGICDESK_GUEST_FILES_TOKEN=secret",
                    "ubuntu", "--", "/tmp/magicdesk-guest-files", "--", "/bin/sh", "-lc"),
                    args.subList(0, args.size() - 1));
            String guest = args.get(args.size() - 1);
            assertTrue(guest.contains("dbus-run-session -- /bin/sh -lc 'xfce4-session'"));
            assertTrue(guest.contains("mktemp -d /tmp/magicdesk-runtime.XXXXXX"));
            assertTrue(guest.contains("trap 'rm -rf -- \"$XDG_RUNTIME_DIR\"' EXIT"));
            var parsed = DesktopEntryFile.parseTermuxApplication(DesktopEntryFile.encodeApplication(shortcut));
            assertNotNull(parsed);
            assertEquals(shortcut.exec, parsed.exec);
            assertEquals(shortcut.graphics.desktop(), parsed.graphics.desktop());
            assertEquals(shortcut.graphics.fileEnvironment(), parsed.graphics.fileEnvironment());
        }
    }

    @Test public void rejectsIncompleteOrOversizedRecipes() {
        assertThrows(IllegalArgumentException.class, () -> recipe("", "", LinuxLaunchRecipe.Presentation.APPLICATION));
        assertThrows(IllegalArgumentException.class, () -> recipe("id", "relative", LinuxLaunchRecipe.Presentation.TERMINAL));
        assertThrows(IllegalArgumentException.class, () -> recipe("'".repeat(2000), "", LinuxLaunchRecipe.Presentation.DESKTOP));
    }

    @Test public void waylandGuestsBindTheSelectedSocketAndShareFileAccessWithoutX11() throws Exception {
        for (var mode : List.of(LinuxLaunchRecipe.Presentation.APPLICATION, LinuxLaunchRecipe.Presentation.DESKTOP)) {
            var shortcut = LinuxLaunchRecipe.build("Guest Wayland", proot(), "weston --backend=wayland --renderer=pixman",
                    "", "alice", mode, GraphicalProtocol.WAYLAND);
            var args = arguments(shortcut);
            assertEquals(List.of("login", "--isolated", "--user", "alice", "--bind",
                    "/private/wayland ' dir:/tmp/magicdesk-wayland", "--env", "WAYLAND_DISPLAY=/tmp/magicdesk-wayland/wayland-0",
                    "--bind", "/apk/helper:/tmp/magicdesk-guest-files", "--env", "MAGICDESK_GUEST_FILES_SOCKET=channel",
                    "--env", "MAGICDESK_GUEST_FILES_TOKEN=secret", "ubuntu", "--", "/tmp/magicdesk-guest-files", "--", "/bin/sh", "-lc"),
                    args.subList(0, args.size() - 1));
            assertTrue(args.get(args.size() - 1).contains("XDG_SESSION_TYPE=wayland"));
            assertFalse(shortcut.exec.contains("XAUTHORITY"));
            assertFalse(shortcut.exec.contains("MAGICDESK_X11"));
            assertEquals(GraphicalProtocol.WAYLAND, shortcut.graphics.protocol());
            assertEquals(mode == LinuxLaunchRecipe.Presentation.DESKTOP, shortcut.graphics.desktop());
            var parsed = DesktopEntryFile.parseTermuxApplication(DesktopEntryFile.encodeApplication(shortcut));
            assertEquals(shortcut.graphics, parsed.graphics);
            assertEquals(shortcut.exec, parsed.exec);
        }
    }

    private DesktopApplicationShortcut recipe(String command, String directory, LinuxLaunchRecipe.Presentation mode) {
        return LinuxLaunchRecipe.build("Linux", proot(), command, directory, "", mode);
    }

    private static LinuxLaunchRecipe.Environment proot() {
        return new LinuxLaunchRecipe.Environment(LinuxLaunchRecipe.Kind.PROOT, "ubuntu");
    }

    @Test public void fileEnvironmentIsSharedByGuestApplicationsButNotOtherGuestsOrUsers() {
        var mode = LinuxLaunchRecipe.Presentation.APPLICATION;
        var one = LinuxLaunchRecipe.build("Calc", proot(), "libreoffice --calc", "", "alice", mode);
        var two = LinuxLaunchRecipe.build("Writer", proot(), "libreoffice --writer", "/tmp", "alice", mode);
        var otherUser = LinuxLaunchRecipe.build("Calc", proot(), "libreoffice --calc", "", "bob", mode);
        var otherGuest = LinuxLaunchRecipe.build("Calc", new LinuxLaunchRecipe.Environment(LinuxLaunchRecipe.Kind.PROOT, "debian"),
                "libreoffice --calc", "", "alice", mode);
        assertEquals(one.graphics.fileEnvironment(), two.graphics.fileEnvironment());
        assertNotEquals(one.graphics.fileEnvironment(), otherUser.graphics.fileEnvironment());
        assertNotEquals(one.graphics.fileEnvironment(), otherGuest.graphics.fileEnvironment());
        assertNotEquals(new RecentApplicationStore.Entry(one, "", "com.termux", 1).key(),
                new RecentApplicationStore.Entry(one.withGraphics(new GraphicalLaunchOptions(false, "")), "", "com.termux", 1).key());
    }


    @Test public void selectedGuestUserIsAnArgumentForEveryPresentation() throws Exception {
        for (var mode : LinuxLaunchRecipe.Presentation.values()) {
            var shortcut = LinuxLaunchRecipe.build("Linux", proot(), "id", "", "ubuntu", mode);
            assertEquals(List.of("login", "--isolated", "--user", "ubuntu"), arguments(shortcut).subList(0, 4));
        }
        for (String user : List.of("--root", "a;id", "a b", "../root", "a\nb"))
            assertThrows(IllegalArgumentException.class, () -> LinuxLaunchRecipe.build("Linux", proot(), "", "", user,
                    LinuxLaunchRecipe.Presentation.TERMINAL));
    }

    @Test public void customLauncherUsesTheSameUserDirectoryAndGuestCommandContract() throws Exception {
        Path launcher = unixHome().resolve("enter ' linux%$script");
        var environment = new LinuxLaunchRecipe.Environment(LinuxLaunchRecipe.Kind.SCRIPT, launcher.toString());
        for (var mode : LinuxLaunchRecipe.Presentation.values()) {
            var shortcut = LinuxLaunchRecipe.build("Linux", environment, "printf '%s' \"$HOME\"", "/home/a ' b", "alice", mode);
            var args = arguments(shortcut, launcher);
            var expected = new java.util.ArrayList<>(List.of("--user", "alice", "--work-dir", "/home/a ' b", "--"));
            if (mode != LinuxLaunchRecipe.Presentation.TERMINAL) expected.addAll(List.of("/tmp/magicdesk-guest-files", "--"));
            expected.addAll(List.of("/bin/sh", "-lc"));
            assertEquals(expected, args.subList(0, args.size() - 1));
            assertFalse(shortcut.exec.contains("proot-distro"));
            assertFalse(shortcut.exec.contains("su -c"));
            assertEquals(mode == LinuxLaunchRecipe.Presentation.TERMINAL, shortcut.terminal);
            assertEquals(mode == LinuxLaunchRecipe.Presentation.DESKTOP, shortcut.graphics != null && shortcut.graphics.desktop());
            if (!shortcut.terminal) assertTrue(args.get(args.size() - 1).contains("dbus-run-session"));
            assertEquals(shortcut.exec, DesktopEntryFile.parseTermuxApplication(DesktopEntryFile.encodeApplication(shortcut)).exec);
        }
    }

    @Test public void customLoginShellDoesNotInventArgumentsOrRequireX11() throws Exception {
        Path launcher = unixHome().resolve("enter-linux");
        var environment = new LinuxLaunchRecipe.Environment(LinuxLaunchRecipe.Kind.SCRIPT, launcher.toString());
        var shortcut = LinuxLaunchRecipe.build("Linux", environment, "", "", "", LinuxLaunchRecipe.Presentation.TERMINAL);
        assertTrue(arguments(shortcut, launcher).isEmpty());
        assertFalse(shortcut.exec.contains("DISPLAY"));
        assertFalse(shortcut.exec.contains("dbus"));
        for (String bad : List.of("", "relative", "~/enter-linux", "/bad\0file"))
            assertThrows(IllegalArgumentException.class, () -> new LinuxLaunchRecipe.Environment(LinuxLaunchRecipe.Kind.SCRIPT, bad));
    }

    private List<String> arguments(DesktopApplicationShortcut shortcut) throws Exception {
        Path bin = Files.createTempDirectory(unixHome(), "bin");
        return arguments(shortcut, bin.resolve("proot-distro"));
    }

    @Test public void preparedLinuxHasTheSameRecipeWithEitherExecutor() throws Exception {
        Path launcher = unixHome().resolve("enter-linux");
        for (var backend : DesktopExecBackend.values()) {
            var environment = new LinuxLaunchRecipe.Environment(LinuxLaunchRecipe.Kind.SCRIPT,
                    launcher.toString(), backend, "/linux/usr/share/X11/xkb");
            for (var mode : LinuxLaunchRecipe.Presentation.values()) {
                var app = LinuxLaunchRecipe.build("Alpine", environment, "id", "/home/test", "test", mode);
                assertEquals(backend, app.execBackend);
                var parsed = (DesktopApplicationShortcut) DesktopEntryFile.parse(DesktopEntryFile.encodeApplication(app));
                assertNotNull(parsed);
                assertEquals(backend, parsed.execBackend);
                assertEquals(app.graphics, parsed.graphics);
                assertEquals(arguments(app, launcher), arguments(parsed, launcher));
                var recent = new RecentApplicationStore.Entry(app, "", backend == DesktopExecBackend.TERMUX ? "com.termux" : "", 1);
                assertEquals(recent.key(), DesktopEntryFile.parseRecent(DesktopEntryFile.encodeRecent(recent)).key());
            }
        }
    }

    @Test public void rootTerminalDoesNotRequireKeyboardDataButX11Does() {
        var environment = new LinuxLaunchRecipe.Environment(LinuxLaunchRecipe.Kind.SCRIPT, "/enter-linux", DesktopExecBackend.SHELL, "");
        assertNull(LinuxLaunchRecipe.build("Linux", environment, "", "", "", LinuxLaunchRecipe.Presentation.TERMINAL).graphics);
        assertThrows(IllegalArgumentException.class, () -> LinuxLaunchRecipe.build("Linux", environment, "xterm", "", "",
                LinuxLaunchRecipe.Presentation.APPLICATION));
        assertThrows(IllegalArgumentException.class, () -> new LinuxLaunchRecipe.Environment(
                LinuxLaunchRecipe.Kind.PROOT, "ubuntu", DesktopExecBackend.SHELL, ""));
    }

    private Path unixHome() {
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"));
        return temporary.getRoot().toPath();
    }

    private List<String> arguments(DesktopApplicationShortcut shortcut, Path launcher) throws Exception {
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"));
        String shell = System.getenv("PREFIX") == null ? "/bin/sh" : System.getenv("PREFIX") + "/bin/sh";
        Path mock = Files.writeString(launcher, "#!" + shell + "\nprintf '%s\\0' \"$@\"\n");
        assertTrue(mock.toFile().setExecutable(true));
        var builder = new ProcessBuilder(shell, "-c", DesktopExecTemplate.expandArguments(shortcut.exec,
                DesktopLaunchArguments.empty(), "Linux", "", ""));
        builder.environment().put("PATH", launcher.getParent() + ":" + System.getenv("PATH"));
        builder.environment().put("DISPLAY", ":37");
        builder.environment().put("XAUTHORITY", "/private/auth ' file");
        builder.environment().put("MAGICDESK_X11_RUNTIME", "/private/runtime ' dir");
        builder.environment().put("MAGICDESK_WAYLAND_RUNTIME", "/private/wayland ' dir");
        builder.environment().put("WAYLAND_DISPLAY", "wayland-0");
        builder.environment().put("MAGICDESK_GUEST_FILES_HELPER", "/apk/helper");
        builder.environment().put("MAGICDESK_GUEST_FILES_SOCKET", "channel");
        builder.environment().put("MAGICDESK_GUEST_FILES_TOKEN", "secret");
        var result = BoundedProcessRunner.run(builder.start(), 5000, 16384);
        assertEquals(result.output, 0, result.exitCode);
        return Arrays.asList(result.output.split("\0"));
    }
}
