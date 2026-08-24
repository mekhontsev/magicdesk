package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DesktopCommandApplicationDraftTest {
    @Test
    public void buildsTermuxApplicationWithMultipleFileContract() {
        final DesktopApplicationShortcut shortcut =
                new DesktopCommandApplicationDraft(
                        "Editor",
                        "nvim",
                        DesktopExecBackend.TERMUX,
                        "/data/data/com.termux/files/home",
                        DesktopCommandApplicationDraft.FileArguments.MULTIPLE,
                        "text/plain;text/markdown")
                        .build();

        assertEquals("nvim %F", shortcut.exec);
        assertEquals(DesktopExecBackend.TERMUX, shortcut.execBackend);
        assertTrue(shortcut.terminal);
        assertTrue(shortcut.mimeTypes.matches("text/plain"));
        assertTrue(shortcut.mimeTypes.matches("text/markdown"));
    }

    @Test
    public void preservesExistingDesktopEntryArgumentCode() {
        final DesktopApplicationShortcut shortcut =
                new DesktopCommandApplicationDraft(
                        "Viewer",
                        "viewer -- %u",
                        DesktopExecBackend.SHELL,
                        "",
                        DesktopCommandApplicationDraft.FileArguments.SINGLE,
                        "image/*;")
                        .build();

        assertEquals("viewer -- %u", shortcut.exec);
        assertTrue(shortcut.mimeTypes.matches("image/png"));
    }

    @Test
    public void rejectsMimeTypesWithoutFileArgument() {
        final DesktopCommandApplicationDraft draft =
                new DesktopCommandApplicationDraft(
                        "Editor",
                        "editor",
                        DesktopExecBackend.SHELL,
                        "",
                        DesktopCommandApplicationDraft.FileArguments.NONE,
                        "text/plain");

        assertThrows(IllegalArgumentException.class, draft::build);
    }

    @Test
    public void derivesApplicationNameFromExecutableFile() {
        assertEquals(
                "backup",
                DesktopCommandApplicationDraft.displayName("backup.sh"));
        assertEquals(
                "nvim",
                DesktopCommandApplicationDraft.displayName("nvim"));
    }
}
