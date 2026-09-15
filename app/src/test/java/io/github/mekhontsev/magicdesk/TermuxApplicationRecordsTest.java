package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.Test;

public class TermuxApplicationRecordsTest {
    private static String record(String path, String body) {
        return encoded(path) + "\t" + encoded(body) + "\n";
    }
    private static String encoded(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
    private static String application(String name, String exec) {
        return "[Desktop Entry]\nType=Application\nName=" + name + "\nExec=" + exec + "\n";
    }

    @Test public void userHiddenEntryMasksSystemEntry() {
        var result = TermuxApplicationRecords.parse(
                record("/home/test/.local/share/applications/firefox.desktop", application("Hidden", "firefox") + "Hidden=true\n")
                + record("/prefix/share/applications/firefox.desktop", application("Firefox", "firefox %u")) + "END\n");
        assertTrue(result.isEmpty());
    }

    @Test public void importsCommandsNotAndroidPrivileges() {
        var result = TermuxApplicationRecords.parse(record("/prefix/share/applications/browser.desktop",
                application("Browser", "firefox %u") + "X-MagicDesk-Package=example.application\nX-MagicDesk-ExecBackend=shell\n") + "END\n");
        var shortcut = result.get(0).shortcut;
        assertEquals(DesktopExecBackend.X11, shortcut.execBackend);
        assertNull(shortcut.launchTarget);
        assertEquals("'firefox'", DesktopLaunchRequest.from(shortcut).prepareExec().exec.command);
    }

    @Test public void literalArgumentsAreNotShellCodeEvenWithoutFieldCodes() {
        var shortcut = DesktopEntryFile.parseTermuxApplication(application("Test", "program \"$HOME\" \"a;b\" \"\""));
        assertEquals("'program' '$HOME' 'a;b' ''", DesktopLaunchRequest.from(shortcut).prepareExec().exec.command);
        assertEquals(DesktopExecBackend.X11, ((DesktopApplicationShortcut) DesktopEntryFile.parse(
                DesktopEntryFile.encodeApplication(shortcut))).execBackend);
    }

    @Test public void requiresCompleteAndValidTransport() {
        assertThrows(IllegalArgumentException.class, () -> TermuxApplicationRecords.parse(""));
        assertThrows(IllegalArgumentException.class, () -> TermuxApplicationRecords.parse("bad\nEND\n"));
        assertThrows(IllegalArgumentException.class, () -> TermuxApplicationRecords.parse(
                record("relative/applications/a.desktop", application("A", "a")) + "END\n"));
    }

    @Test public void x11BackendCannotBecomeAnOrdinaryPty() {
        assertThrows(IllegalArgumentException.class, () -> DesktopExecBackend.X11.requireConsole());
        assertEquals(DesktopExecBackend.TERMUX, DesktopExecBackend.TERMUX.requireConsole());
    }

    @Test public void terminalEntryUsesExistingTermuxConsoleWithoutAnXServer() {
        var shortcut = DesktopEntryFile.parseTermuxApplication(application("Editor", "editor \"a b.txt\"")
                + "Terminal=true\nPath=/work dir\n");
        var request = X11ApplicationLaunch.prepare(null, DesktopLaunchRequest.from(shortcut).prepareExec());
        assertEquals(DesktopExecBackend.TERMUX, request.exec.backend);
        assertTrue(request.exec.terminal);
        assertEquals("'editor' 'a b.txt'", request.exec.command);
        assertEquals("/work dir", request.exec.workingDirectory);
        assertNull(request.androidLaunch);
    }

    @Test public void noDisplayAndNonApplicationEntriesAreNotImported() {
        assertNull(DesktopEntryFile.parseTermuxApplication(application("Hidden", "hidden") + "NoDisplay=true\n"));
        assertNull(DesktopEntryFile.parseTermuxApplication("[Desktop Entry]\nType=Link\nName=Link\nURL=https://example.org\n"));
    }

    @Test public void nestedDesktopIdentityMatchesItsFlatXdgOverride() {
        var result = TermuxApplicationRecords.parse(
                record("/home/test/.local/share/applications/editors-main.desktop", application("Override", "one"))
                + record("/prefix/share/applications/editors/main.desktop", application("Installed", "two")) + "END\n");
        assertEquals(1, result.size());
        assertEquals("Override", result.get(0).shortcut.name);
    }
}
