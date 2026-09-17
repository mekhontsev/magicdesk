package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class TermuxIconCommandTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void parsesCompleteBoundedRecordsIncludingMissingIcons() {
        byte[] bytes = new byte[TermuxIconCommand.MAX_BYTES];
        var records = TermuxIconCommand.parse(Base64.getEncoder().encodeToString(bytes) + "\n\nEND\n", 2);
        assertArrayEquals(bytes, records.get(0));
        assertEquals(0, records.get(1).length);
        assertThrows(IllegalArgumentException.class, () -> TermuxIconCommand.parse("\n", 1));
        assertThrows(IllegalArgumentException.class, () -> TermuxIconCommand.parse("?\nEND\n", 1));
        assertThrows(IllegalArgumentException.class, () -> TermuxIconCommand.parse("\n\nEND\n", 1));
        assertThrows(IllegalArgumentException.class, () -> TermuxIconCommand.parse(
                Base64.getEncoder().encodeToString(new byte[bytes.length + 1]) + "\nEND\n", 1));
    }

    @Test public void validatesRequestsWithoutTreatingNamesAsCommands() {
        assertTrue(TermuxIconCommand.valid("gimp"));
        assertTrue(TermuxIconCommand.valid("/home/a b/icon.png"));
        assertFalse(TermuxIconCommand.valid("../icon"));
        assertFalse(TermuxIconCommand.valid("a\nicon"));
        assertFalse(TermuxIconCommand.valid(""));
        assertThrows(IllegalArgumentException.class, () -> TermuxIconCommand.create(List.of()));
        assertThrows(IllegalArgumentException.class, () -> TermuxIconCommand.create(List.of("a", "b", "c", "d", "e")));
        assertTrue(TermuxIconCommand.create(List.of("$(touch bad)")).contains("'$(touch bad)'"));
    }

    @Test public void looksUpUserIconsBeforeInstalledAndBoundsFileReads() throws Exception {
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"));
        Path root = temporary.getRoot().toPath();
        Path home = Files.createDirectory(root.resolve("home"));
        Path prefix = Files.createDirectory(root.resolve("prefix"));
        write(home.resolve(".local/share/icons/hicolor/96x96/apps/gimp.png"), new byte[]{1, 2});
        write(prefix.resolve("share/icons/hicolor/64x64/apps/gimp.png"), new byte[]{3});
        write(prefix.resolve("share/icons/hicolor/96x96/apps/firefox.png"), new byte[TermuxIconCommand.MAX_BYTES + 1]);
        write(prefix.resolve("share/icons/hicolor/48x48/apps/firefox.png"), new byte[]{4, 5});
        Path absolute = root.resolve("icon ' with spaces.png");
        write(absolute, new byte[]{6});
        String shell = System.getenv("PREFIX") == null ? "/bin/bash" : System.getenv("PREFIX") + "/bin/bash";
        var process = new ProcessBuilder(shell, "-c", TermuxIconCommand.create(
                List.of("gimp", "firefox.png", absolute.toString(), "$(touch bad)"))).directory(root.toFile());
        process.environment().put("HOME", home.toString());
        process.environment().put("PREFIX", prefix.toString());
        process.environment().remove("XDG_DATA_HOME");
        var result = BoundedProcessRunner.run(process.start(), 5000, 100 * 1024);
        assertEquals(result.output, 0, result.exitCode);
        var records = TermuxIconCommand.parse(result.output, 4);
        assertArrayEquals(new byte[]{1, 2}, records.get(0));
        assertArrayEquals(new byte[]{4, 5}, records.get(1));
        assertArrayEquals(new byte[]{6}, records.get(2));
        assertEquals(0, records.get(3).length);
        assertFalse(Files.exists(root.resolve("bad")));
    }

    private static void write(Path path, byte[] bytes) throws Exception {
        Files.createDirectories(path.getParent());
        Files.write(path, bytes);
    }
}
