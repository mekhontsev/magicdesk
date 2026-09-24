package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;
import org.junit.Test;

public final class GraphicalLaunchOptionsTest {
    private static final String ENTRY = "[Desktop Entry]\nType=Application\nName=Editor\nIcon=editor\nExec=editor %U\n";

    @Test public void termuxDefaultsToX11AndPreservesExplicitWayland() {
        assertEquals(GraphicalProtocol.X11, DesktopEntryFile.parseTermuxApplication(ENTRY).graphics.protocol());
        var app = DesktopEntryFile.parseTermuxApplication(ENTRY + "X-MagicDesk-Graphics=wayland\n");
        assertEquals(GraphicalProtocol.WAYLAND, app.graphics.protocol());
        assertFalse(app.graphics.desktop());
        assertTrue(app.literalExec);
        assertEquals(DesktopExecBackend.TERMUX, app.execBackend);
        var restored = (DesktopApplicationShortcut) DesktopEntryFile.parse(DesktopEntryFile.encodeApplication(app));
        assertEquals(app.graphics, restored.graphics);
        assertEquals("editor %U", restored.exec);
    }

    @Test public void commandAndTerminalEntriesDoNotAcquireGraphicsImplicitly() {
        assertNull(((DesktopApplicationShortcut) DesktopEntryFile.parse(ENTRY)).graphics);
        assertNull(DesktopEntryFile.parseTermuxApplication(ENTRY + "Terminal=true\n").graphics);
        assertNull(DesktopEntryFile.parseTermuxApplication(ENTRY + "Terminal=true\nX-MagicDesk-Graphics=wayland\n"));
        assertNull(DesktopEntryFile.parseTermuxApplication(ENTRY + "X-MagicDesk-Graphics=unknown\n"));
        assertNull(DesktopEntryFile.parse(ENTRY + "X-MagicDesk-GraphicsMode=application\n"));
    }

    @Test public void nestedDesktopAndGuestRecipesRoundTrip() {
        var desktop = DesktopEntryFile.parseTermuxApplication(ENTRY
                + "X-MagicDesk-Graphics=wayland\nX-MagicDesk-GraphicsMode=desktop\n");
        assertNotNull(desktop);
        assertTrue(desktop.graphics.desktop());
        assertEquals(desktop.graphics, ((DesktopApplicationShortcut) DesktopEntryFile.parse(
                DesktopEntryFile.encodeApplication(desktop))).graphics);
        var guest = new GraphicalLaunchOptions(GraphicalProtocol.WAYLAND, false, "", "", "ubuntu:root");
        var guestEntry = desktop.withGraphics(guest);
        assertEquals(guest, ((DesktopApplicationShortcut) DesktopEntryFile.parse(
                DesktopEntryFile.encodeApplication(guestEntry))).graphics);
    }

    @Test public void identityDistinguishesProtocolsButNotRecipeCopiesOrWindowTitles() {
        var x11 = DesktopEntryFile.parseTermuxApplication(ENTRY);
        var wayland = DesktopEntryFile.parseTermuxApplication(ENTRY + "X-MagicDesk-Graphics=wayland\n");
        var first = new RecentApplicationStore.Entry(wayland, "/a.desktop", "com.termux", 1);
        var second = new RecentApplicationStore.Entry(DesktopEntryFile.parseTermuxApplication(
                ENTRY.replace("Name=Editor", "Name=Renamed") + "X-MagicDesk-Graphics=wayland\n"),
                "/b.desktop", "com.termux", 2);
        assertEquals(first.key(), second.key());
        assertNotEquals(first.key(), new RecentApplicationStore.Entry(x11, "/a.desktop", "com.termux", 1).key());
        assertEquals(first.key(), DesktopEntryFile.parseRecent(DesktopEntryFile.encodeRecent(first)).key());
    }

    @Test public void catalogCannotChangeExecutorOrLaunchAndroidThroughGraphicsMetadata() {
        var app = DesktopEntryFile.parseTermuxApplication(ENTRY + "X-MagicDesk-Graphics=wayland\n"
                + "X-MagicDesk-ExecBackend=shell\nX-MagicDesk-Package=other.app\n"
                + "X-MagicDesk-KeyboardDirectory=/keyboard\nX-MagicDesk-Intent=evil\n");
        assertEquals(DesktopExecBackend.TERMUX, app.execBackend);
        assertNull(app.launchTarget);
        assertEquals("", app.intentUri);
        assertEquals("/keyboard", app.graphics.keyboardDirectory());
    }
}
