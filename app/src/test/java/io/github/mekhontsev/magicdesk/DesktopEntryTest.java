package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import org.junit.Test;

public final class DesktopEntryTest {
    @Test
    public void everyEntryTypeRejectsNulBeforeTrimming() {
        for (final String name : List.of("\0App", "App\0", "My\0App")) {
            for (final Function<String, DesktopEntry> create : factories()) {
                assertThrows(IllegalArgumentException.class, () -> create.apply(name));
            }
        }
    }

    @Test
    public void everyEntryTypeRejectsMissingNames() {
        for (final String name : Arrays.asList(null, "", " \t\n")) {
            for (final Function<String, DesktopEntry> create : factories()) {
                assertThrows(IllegalArgumentException.class, () -> create.apply(name));
            }
        }
    }

    @Test
    public void labelsAreNotConfusedWithFileNames() {
        for (final Function<String, DesktopEntry> create : factories()) {
            assertEquals("My / Files \\ Tools", create.apply(" My / Files \\ Tools ").name);
        }
    }

    @Test
    public void labelsRoundTripThroughAllEntryFormats() {
        final String name = "My \ud83d\ude80\nFiles";
        assertEquals(name, DesktopEntryFile.parse(DesktopEntryFile.encodeLink(name, "/tmp")).name);
        assertEquals(name, DesktopEntryFile.parse(DesktopEntryFile.encodeWebLink(
                name, "https://example.com")).name);
        assertEquals(name, DesktopEntryFile.parse(DesktopEntryFile.encodeApplication(application(name))).name);
    }

    @Test
    public void fileParsersRejectInvalidNamesInsteadOfRepairingThem() {
        for (final String name : List.of("\0App", "App\0", "My\0App")) {
            assertNull(DesktopEntryFile.parse("[Desktop Entry]\nType=Link\nName="
                    + name + "\nURL=file:///tmp\n"));
            assertNull(DesktopEntryFile.parse("[Desktop Entry]\nType=Application\nName="
                    + name + "\nExec=pwd\n"));
        }
    }

    private static List<Function<String, DesktopEntry>> factories() {
        return List.of(name -> new DesktopFolderShortcut(name, "/tmp", false),
                name -> new DesktopWebShortcut(name, "", "https://example.com"),
                DesktopEntryTest::application);
    }

    private static DesktopApplicationShortcut application(final String name) {
        return new DesktopApplicationShortcut(name, "", "pwd", null, "",
                DesktopLaunchMode.AUTO, false, DesktopExecBackend.SHELL, false);
    }
}
