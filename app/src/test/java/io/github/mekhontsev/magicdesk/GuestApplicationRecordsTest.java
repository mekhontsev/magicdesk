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

    @Test public void identitiesAreEnvironmentScopedAndRecipesUseImmutableStores() throws Exception {
        var records = new JSONArray();
        for (String id : new String[]{A, B}) records.put(entry(id, "linux", "/usr/share/applications/editor.desktop",
                "Name=Editor\nExec=editor --title \"two words\" %U\nIcon=editor\nPath=/work\n"));
        var result = GuestApplicationRecords.parse(records.toString());
        assertEquals(2, result.entries().size());
        for (int i = 0; i < 2; ++i) {
            var shortcut = result.entries().get(i).shortcut;
            assertEquals(DesktopExecBackend.SHELL, shortcut.execBackend);
            assertTrue(shortcut.exec.contains(i == 0 ? A : B));
            assertEquals(GraphicalProtocol.X11, shortcut.graphics.protocol());
            assertFalse(shortcut.exec.contains("%U"));
            assertEquals("editor --title \"two words\" %U", StartMenuEntry.desktopApplication(result.entries().get(i)).detail);
        }
        assertNotEquals(result.entries().get(0).desktopFilePath, result.entries().get(1).desktopFilePath);
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
        var records = new JSONArray().put(entry(A, "work", "/applications/../bad.desktop", "Name=Bad\nExec=true\n"));
        assertThrows(IllegalArgumentException.class, () -> GuestApplicationRecords.parse(records.toString()));
    }
}
