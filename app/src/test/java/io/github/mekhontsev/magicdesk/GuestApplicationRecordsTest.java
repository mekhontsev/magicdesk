package io.github.mekhontsev.magicdesk;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class GuestApplicationRecordsTest {
    private JSONObject entry(String id, String name, String path, String text) throws Exception {
        return new JSONObject().put("environment", new JSONObject().put("id", id).put("name", name)
                .put("store", "/library/instances/" + id).put("source", "fixture"))
                .put("path", path).put("text", "[Desktop Entry]\nType=Application\n" + text);
    }
    private static final String A = "00000000-0000-0000-0000-000000000001";
    private static final String B = "00000000-0000-0000-0000-000000000002";

    @Test public void identicalLibrariesRetainTheirSelectedExecutor() throws Exception {
        String records = new JSONArray().put(entry(A, "linux", "/usr/share/applications/editor.desktop",
                "Name=Editor\nExec=editor\nIcon=editor\n")).toString();
        var user = GuestApplicationRecords.parse(records, DesktopExecBackend.APP).entries().get(0);
        var shell = GuestApplicationRecords.parse(records, DesktopExecBackend.SHELL).entries().get(0);
        assertNotEquals(user.desktopFilePath, shell.desktopFilePath);
        assertEquals(DesktopExecBackend.APP, user.shortcut.execBackend);
        assertEquals(DesktopExecBackend.SHELL, shell.shortcut.execBackend);
        assertNotEquals(ApplicationIconKey.of(user.shortcut, ""), ApplicationIconKey.of(shell.shortcut, ""));
        assertEquals(user.shortcut.exec, shell.shortcut.exec);
    }

    @Test public void identitiesAreEnvironmentScopedAndRecipesUseImmutableStores() throws Exception {
        var records = new JSONArray();
        for (String id : new String[]{A, B}) records.put(entry(id, "linux", "/usr/share/applications/editor.desktop",
                "Name=Editor\nExec=editor --title \"two words\" %U\nIcon=editor\nPath=/work\n"));
        var result = GuestApplicationRecords.parse(records.toString());
        assertEquals("", result.error());
        assertEquals(2, result.entries().size());
        for (int i = 0; i < 2; ++i) {
            var shortcut = result.entries().get(i).shortcut;
            assertEquals(DesktopExecBackend.SHELL, shortcut.execBackend);
            assertTrue(shortcut.exec.contains(i == 0 ? A : B));
            assertEquals(GraphicalProtocol.X11, shortcut.graphics.protocol());
            assertFalse(shortcut.exec.contains("%U"));
            assertTrue(shortcut.exec.length() < 2048);
            assertFalse(shortcut.exec.contains("md_session_cleanup"));
            assertEquals("editor --title \"two words\" %U", StartMenuEntry.desktopApplication(result.entries().get(i)).detail);
        }
        assertNotEquals(result.entries().get(0).desktopFilePath, result.entries().get(1).desktopFilePath);
    }

    @Test public void unavailableEnvironmentDoesNotHideHealthyApplications() throws Exception {
        var unavailable = new JSONObject().put("environment", new JSONObject().put("id", A).put("name", "bad")
                .put("store", "/library/instances/" + A).put("source", "fixture"))
                .put("error", "Unreadable environment");
        var healthy = entry(B, "good", "/usr/share/applications/editor.desktop", "Name=Editor\nExec=editor\nIcon=editor\n");
        var result = GuestApplicationRecords.parse(new JSONArray().put(unavailable).put(healthy).toString());
        assertEquals(1, result.entries().size());
        assertTrue(result.entries().get(0).shortcut.exec.contains(B));
        assertEquals("bad: Unreadable environment", result.error());
    }

    @Test public void desktopLabelsKeepDistributionAndIndependentInstanceNames() throws Exception {
        var records = new JSONArray();
        String text = "Name=Alpine Linux - Xfce Desktop\nExec=magicdesk-desktop-session xfce\n"
                + "X-MagicDesk-Graphics=x11\nX-MagicDesk-GraphicsMode=desktop\n";
        records.put(entry(A, "work", "/usr/local/share/applications/magicdesk-xfce.desktop", text));
        records.put(entry(B, "personal", "/usr/local/share/applications/magicdesk-xfce.desktop", text));
        var entries = GuestApplicationRecords.parse(records.toString()).entries();
        assertEquals("Alpine Linux - Xfce Desktop (work)", entries.get(0).shortcut.name);
        assertEquals("Alpine Linux - Xfce Desktop (personal)", entries.get(1).shortcut.name);
        assertNotEquals(entries.get(0).desktopFilePath, entries.get(1).desktopFilePath);
        assertTrue(entries.get(0).shortcut.graphics.desktop());
        assertTrue(entries.get(1).shortcut.graphics.desktop());
    }

    @Test public void hiddenUserEntryMasksSystemEntryAndTerminalKeepsItsPresentation() throws Exception {
        var records = new JSONArray().put(entry(A, "work", "/root/.local/share/applications/editor.desktop", "Hidden=true\n"))
                .put(entry(A, "work", "/usr/share/applications/editor.desktop", "Name=Editor\nExec=editor\n"))
                .put(entry(A, "work", "/usr/share/applications/terminal.desktop", "Name=Console\nExec=sh\nTerminal=true\n"));
        var result = GuestApplicationRecords.parse(records.toString());
        assertEquals(1, result.entries().size());
        assertTrue(result.entries().get(0).shortcut.terminal);
        assertNull(result.entries().get(0).shortcut.graphics);
    }

    @Test public void catalogRejectsHostPathTraversal() throws Exception {
        var records = new JSONArray().put(entry(A, "work", "/applications/../bad.desktop", "Name=Bad\nExec=true\n"))
                .put(entry(A, "work", "/usr/share/applications/good.desktop", "Name=Good\nExec=true\n"));
        var result = GuestApplicationRecords.parse(records.toString());
        assertEquals(1, result.entries().size());
        assertEquals("Good (work)", result.entries().get(0).shortcut.name);
        assertFalse(result.error().isEmpty());
    }

    @Test public void brokenRecordsAndOversizedRecipesDoNotHideHealthyNeighbors() throws Exception {
        var records = new JSONArray()
                .put(entry(A, "work", "/usr/share/applications/first.desktop", "Name=First\nExec=true\n"))
                .put("not an object")
                .put(new JSONObject())
                .put(entry("0-0-0-0-1", "bad-identity", "/usr/share/applications/bad.desktop", "Name=Bad\nExec=true\n"))
                .put(entry(A, "work", "/root/.local/share/applications/bad.desktop", "Name=Bad\nExec=program " + "x".repeat(3500) + "\n"))
                .put(entry(A, "work", "/usr/share/applications/bad.desktop", "Name=Masked\nExec=true\n"))
                .put(entry(B, "other", "/usr/share/applications/last.desktop", "Name=Last\nExec=true\n"));
        var result = GuestApplicationRecords.parse(records.toString());
        assertEquals(java.util.List.of("First (work)", "Last (other)"),
                result.entries().stream().map(e -> e.shortcut.name).toList());
        assertTrue(result.error(), result.error().contains("Guest record 2:"));
        assertTrue(result.error(), result.error().contains("Guest record 3:"));
        assertTrue(result.error(), result.error().contains("Guest record 4: Invalid guest identity"));
        assertTrue(result.error(), result.error().contains("work: bad.desktop:"));
    }

    @Test public void invalidCatalogEnvelopeStillFails() {
        assertThrows(Exception.class, () -> GuestApplicationRecords.parse("{"));
        assertThrows(IllegalArgumentException.class, () -> GuestApplicationRecords.parse("[" + "{},".repeat(1024) + "{}]"));
    }

    @Test public void desktopPresentationIsRetainedWithoutTrustingGuestHostPaths() throws Exception {
        for (String protocol : new String[]{"x11", "wayland"}) {
            var record = entry(A, "work", "/usr/share/applications/session.desktop",
                    "Name=Session\nExec=session\nStartupWMClass=session\nX-MagicDesk-Graphics=" + protocol
                            + "\nX-MagicDesk-GraphicsMode=desktop\nX-MagicDesk-KeyboardDirectory=/untrusted/xkb"
                            + "\nX-MagicDesk-FileEnvironment=/untrusted/files\n");
            var shortcut = GuestApplicationRecords.parse(new JSONArray().put(record).toString()).entries().get(0).shortcut;
            assertTrue(shortcut.graphics.desktop());
            assertEquals(protocol.equals("x11") ? GraphicalProtocol.X11 : GraphicalProtocol.WAYLAND, shortcut.graphics.protocol());
            assertEquals("session", shortcut.graphics.startupClass());
            assertEquals("guest:/library/instances/" + A, shortcut.graphics.keyboardDirectory());
            assertTrue(shortcut.graphics.fileEnvironment().contains("/library/instances/" + A));
            assertFalse(shortcut.exec.contains("/untrusted"));
        }
    }

    @Test public void applicationAndTerminalPresentationRemainIndependent() throws Exception {
        for (boolean terminal : new boolean[]{false, true}) {
            var record = entry(A, "work", "/usr/share/applications/program.desktop",
                    "Name=Program\nExec=program\nTerminal=" + terminal + "\n"
                            + (terminal ? "" : "X-MagicDesk-Graphics=wayland\nX-MagicDesk-GraphicsMode=application\n"));
            var shortcut = GuestApplicationRecords.parse(new JSONArray().put(record).toString()).entries().get(0).shortcut;
            assertEquals(terminal, shortcut.terminal);
            if (terminal) assertNull(shortcut.graphics);
            else {
                assertFalse(shortcut.graphics.desktop());
                assertEquals(GraphicalProtocol.WAYLAND, shortcut.graphics.protocol());
            }
        }
    }
}
