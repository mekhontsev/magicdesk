package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class TermuxApplicationCommandTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();
    private Path root, home, prefix, applications;

    @Before public void setup() throws Exception {
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"));
        root = temporary.getRoot().toPath();
        home = Files.createDirectory(root.resolve("home"));
        prefix = Files.createDirectory(root.resolve("prefix"));
        applications = Files.createDirectories(prefix.resolve("share/applications"));
    }

    @Test public void discoversRegularFilesAndLinkedLaunchersWithoutChangingTheirPaths() throws Exception {
        Path regular = write(applications.resolve("ordinary.desktop"), "Ordinary");
        Path target = write(prefix.resolve("lib/office/writer.desktop"), "Writer");
        Path absolute = Files.createSymbolicLink(applications.resolve("office.desktop"), target);
        Path relative = Files.createSymbolicLink(applications.resolve("relative.desktop"),
                applications.relativize(target));
        Path chain = Files.createSymbolicLink(applications.resolve("space ' and\nnewline.desktop"),
                relative.getFileName());
        var entries = load();
        assertEquals(4, entries.size());
        assertTrue(entries.stream().map(entry -> entry.desktopFilePath).toList()
                .containsAll(List.of(regular.toString(), absolute.toString(), relative.toString(), chain.toString())));
        assertEquals(3, entries.stream().filter(entry -> entry.shortcut.name.equals("Writer")).count());
    }

    @Test public void skipsBrokenLinksLinkCyclesAndLinkedSubdirectories() throws Exception {
        write(applications.resolve("nested/good.desktop"), "Good");
        Path external = Files.createDirectory(prefix.resolve("elsewhere"));
        write(external.resolve("unexpected.desktop"), "Unexpected");
        Files.createSymbolicLink(applications.resolve("broken.desktop"), root.resolve("missing"));
        Files.createSymbolicLink(applications.resolve("loop.desktop"), Path.of("loop.desktop"));
        Files.createSymbolicLink(applications.resolve("directory.desktop"), external);
        Files.createSymbolicLink(applications.resolve("linked-directory"), external);
        Files.createSymbolicLink(applications.resolve("nested/back"), applications);
        var entries = load();
        assertEquals(1, entries.size());
        assertEquals("Good", entries.get(0).shortcut.name);
    }

    @Test public void linkedUserOverrideMasksInstalledEntryEvenWhenCatalogRootIsLinked() throws Exception {
        write(applications.resolve("office.desktop"), "Installed");
        Path userDirectory = Files.createDirectory(root.resolve("user-apps"));
        Path dataHome = Files.createDirectories(home.resolve(".local/share"));
        Files.createSymbolicLink(dataHome.resolve("applications"), userDirectory);
        Path hidden = write(root.resolve("hidden.desktop"), "Hidden");
        Files.writeString(hidden, Files.readString(hidden) + "Hidden=true\n");
        Files.createSymbolicLink(userDirectory.resolve("office.desktop"), hidden);
        assertTrue(load().isEmpty());
    }

    @Test public void linkedFileStillObeysSizeBound() throws Exception {
        Path oversized = root.resolve("oversized.desktop");
        Files.writeString(oversized, "x".repeat(65537));
        Files.createSymbolicLink(applications.resolve("large.desktop"), oversized);
        write(applications.resolve("good.desktop"), "Good");
        var entries = load();
        assertEquals(1, entries.size());
        assertEquals("Good", entries.get(0).shortcut.name);
    }

    @Test public void onlyOurDirectUserEntriesOfferDeletionAndFileMetadataCannotGrantIt() throws Exception {
        Path user = Files.createDirectories(home.resolve(".local/share/applications"));
        write(user.resolve("magicdesk-user.desktop"), "User");
        Path ordinary = write(user.resolve("ordinary.desktop"), "Ordinary");
        Path installed = write(applications.resolve("magicdesk-installed.desktop"), "Installed");
        for (Path file : List.of(ordinary, installed))
            Files.writeString(file, Files.readString(file) + "X-MagicDesk-UserShortcut=true\n");
        Files.createSymbolicLink(user.resolve("magicdesk-link.desktop"), installed);
        var entries = load();
        assertEquals(4, entries.size());
        for (var entry : entries) assertEquals(entry.shortcut.name.equals("User"), entry.userShortcut);
    }

    private List<DesktopApplicationRepository.Entry> load() throws Exception {
        String shell = System.getenv("PREFIX") == null ? "/bin/bash" : System.getenv("PREFIX") + "/bin/bash";
        var builder = new ProcessBuilder(shell, "-c", TermuxApplicationCommand.create()).directory(root.toFile());
        builder.environment().put("HOME", home.toString());
        builder.environment().put("PREFIX", prefix.toString());
        builder.environment().remove("XDG_DATA_HOME");
        var result = BoundedProcessRunner.run(builder.start(), 5000, 100 * 1024);
        assertEquals(result.output, 0, result.exitCode);
        return TermuxApplicationRecords.parse(result.output);
    }

    private static Path write(Path path, String name) throws Exception {
        Files.createDirectories(path.getParent());
        return Files.writeString(path, "[Desktop Entry]\nType=Application\nName=" + name + "\nExec=example\n");
    }
}
