package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public final class DesktopFolderShortcutFileTest {
    @Test
    public void roundTripPreservesNameAndAbsoluteTarget() {
        final String encoded = DesktopFolderShortcutFile.encode(
                "Work files", "/storage/emulated/0/My Work");

        final DesktopFolderShortcut parsed =
                DesktopFolderShortcutFile.parse(encoded);

        assertEquals("Work files", parsed.name);
        assertEquals("/storage/emulated/0/My Work", parsed.targetPath);
        assertFalse(parsed.available);
    }

    @Test
    public void parsesStandardLinkAndEscapedName() {
        final DesktopFolderShortcut parsed = DesktopFolderShortcutFile.parse(
                "# comment\n"
                        + "[Desktop Entry]\n"
                        + "Type=Link\n"
                        + "Name=Project\\sFiles\n"
                        + "URL=file:///storage/emulated/0/Project%20Files\n");

        assertEquals("Project Files", parsed.name);
        assertEquals(
                "/storage/emulated/0/Project Files", parsed.targetPath);
    }

    @Test
    public void rejectsApplicationEntriesAndRemoteLinks() {
        assertNull(DesktopFolderShortcutFile.parse(
                "[Desktop Entry]\nType=Application\nName=Unsafe\nExec=id\n"));
        assertNull(DesktopFolderShortcutFile.parse(
                "[Desktop Entry]\nType=Link\nName=Remote\n"
                        + "URL=https://example.com/\n"));
        assertNull(DesktopFolderShortcutFile.parse(
                "[Desktop Entry]\nType=Link\nName=Remote file\n"
                        + "URL=file://server/share\n"));
    }

    @Test
    public void rejectsRelativeAndMalformedTargets() {
        assertNull(DesktopFolderShortcutFile.parse(
                "[Desktop Entry]\nType=Link\nName=Relative\nURL=folder\n"));
        assertNull(DesktopFolderShortcutFile.parse(
                "[Desktop Entry]\nType=Link\nName=Missing URL\n"));
        assertNull(DesktopFolderShortcutFile.parse(
                "[Desktop Entry]\nType=Link\nURL=file:///tmp\n"));
    }

    @Test
    public void appendsDesktopExtensionOnce() {
        assertEquals(
                "Downloads.desktop",
                DesktopFolderShortcutFile.shortcutFileName("Downloads"));
        assertEquals(
                "Downloads.desktop",
                DesktopFolderShortcutFile.shortcutFileName(
                        "Downloads.desktop"));
    }
}
