package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class TermuxDesktopEntriesTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();
    private Path commands;

    @Test public void publishesOnceWithoutClobberingExistingLauncher() throws Exception {
        Path home = unixHome();
        var shortcut = LinuxLaunchRecipe.build("Linux ' test",
                new LinuxLaunchRecipe.Environment(LinuxLaunchRecipe.Kind.PROOT, "ubuntu"),
                "", "", "", LinuxLaunchRecipe.Presentation.TERMINAL);
        String encoded = DesktopEntryFile.encodeApplication(shortcut);
        String name = DesktopEntryFile.shortcutFileName(shortcut.name);
        assertWrite(0, home, name, encoded);
        assertWrite(0, home, name, encoded);
        assertWrite(1, home, name, encoded + "Comment=changed\n");
        Path directory = home.resolve(".local/share/applications");
        assertEquals(encoded, Files.readString(directory.resolve("magicdesk-" + name)));
        try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
    }

    @Test public void identicalLauncherSucceedsWhenSkippedMoveReturnsFailure() throws Exception {
        useMoveCommand("for destination do :; done\n"
                + "[ ! -e \"$destination\" ] && [ ! -L \"$destination\" ] || exit 1\n"
                + "PATH=" + ShellCommandLine.quote(System.getenv("PATH")) + " exec mv \"$@\"\n");
        publishesOnceWithoutClobberingExistingLauncher();
    }

    @Test public void failedMoveDoesNotReportSuccessOrLeaveTemporaryFile() throws Exception {
        useMoveCommand("exit 1\n");
        Path home = unixHome();
        assertWrite(1, home, "failed.desktop", "[Desktop Entry]\nName=Failed\n");
        try (var files = Files.list(home.resolve(".local/share/applications"))) {
            assertEquals(0, files.count());
        }
    }

    @Test public void existingDirectoryIsNotAnIdenticalLauncher() throws Exception {
        Path home = unixHome();
        Path directory = Files.createDirectories(home.resolve(".local/share/applications"));
        Path destination = Files.createDirectory(directory.resolve("magicdesk-folder.desktop"));
        assertWrite(1, home, "folder.desktop", "[Desktop Entry]\nName=Folder\n");
        assertTrue(Files.isDirectory(destination));
        try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
    }

    @Test public void rejectsDirectoryTraversal() {
        assertThrows(IllegalArgumentException.class, () -> TermuxDesktopEntries.createCommand("../bad.desktop"));
        assertThrows(IllegalArgumentException.class, () -> TermuxDesktopEntries.deleteCommand("relative.desktop"));
        assertThrows(IllegalArgumentException.class, () -> TermuxDesktopEntries.deleteCommand("/home/app.desktop"));
    }

    @Test public void deletesOnlyDirectUserShortcutsAndAbsenceIsIdempotent() throws Exception {
        Path home = unixHome();
        Path directory = Files.createDirectories(home.resolve(".local/share/applications"));
        Path shortcut = Files.writeString(directory.resolve("magicdesk-user.desktop"), "user");
        assertDelete(0, home, shortcut);
        assertFalse(Files.exists(shortcut));
        assertDelete(0, home, shortcut);
        Path installed = Files.createDirectories(home.resolve("prefix/share/applications"))
                .resolve("magicdesk-installed.desktop");
        Files.writeString(installed, "installed");
        assertDelete(1, home, installed);
        Path link = Files.createSymbolicLink(directory.resolve("magicdesk-link.desktop"), installed);
        assertDelete(1, home, link);
        assertTrue(Files.isSymbolicLink(link));
        assertEquals("installed", Files.readString(installed));
        Path folder = Files.createDirectory(directory.resolve("magicdesk-folder.desktop"));
        assertDelete(1, home, folder);
        assertTrue(Files.isDirectory(folder));
    }

    @Test public void userDirectoryCannotAliasPackageInstallation() throws Exception {
        Path home = unixHome();
        Path installed = Files.createDirectories(home.resolve("prefix/share/applications"));
        Path shortcut = Files.writeString(installed.resolve("magicdesk-installed.desktop"), "installed");
        Path data = Files.createDirectories(home.resolve(".local/share"));
        Path directory = Files.createSymbolicLink(data.resolve("applications"), installed);
        assertDelete(1, home, directory.resolve(shortcut.getFileName()));
        assertEquals("installed", Files.readString(shortcut));
    }

    private void assertDelete(int expected, Path home, Path path) throws Exception {
        var result = BoundedProcessRunner.run(command(home, TermuxDesktopEntries.deleteCommand(path.toString())).start(),
                5000, 16384);
        assertEquals(result.output, expected, result.exitCode);
    }

    private void assertWrite(int expected, Path home, String name, String content) throws Exception {
        var process = command(home, TermuxDesktopEntries.createCommand(name)).start();
        try (var input = process.getOutputStream()) { input.write(content.getBytes(StandardCharsets.UTF_8)); }
        var result = BoundedProcessRunner.run(process, 5000, 16384);
        assertEquals(result.output, expected, result.exitCode);
    }

    private void useMoveCommand(String body) throws Exception {
        String interpreter = shell();
        commands = Files.createDirectory(unixHome().resolve("commands"));
        Path move = Files.writeString(commands.resolve("mv"), "#!" + interpreter + "\n" + body);
        assertTrue(move.toFile().setExecutable(true));
    }

    private Path unixHome() {
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"));
        return temporary.getRoot().toPath();
    }

    private static String shell() {
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"));
        return System.getenv("PREFIX") == null ? "/bin/sh" : System.getenv("PREFIX") + "/bin/sh";
    }

    private ProcessBuilder command(Path home, String command) {
        var builder = new ProcessBuilder(shell(), "-c", command);
        builder.environment().put("HOME", home.toString());
        builder.environment().put("PREFIX", home.resolve("prefix").toString());
        builder.environment().remove("XDG_DATA_HOME");
        if (commands != null) builder.environment().put("PATH", commands + ":" + System.getenv("PATH"));
        return builder;
    }
}
