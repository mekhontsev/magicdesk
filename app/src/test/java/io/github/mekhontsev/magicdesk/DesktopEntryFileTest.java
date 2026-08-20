package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DesktopEntryFileTest {
    @Test
    public void linkRoundTripPreservesStandardFields() {
        final DesktopEntry parsed = DesktopEntryFile.parse(
                DesktopEntryFile.encodeLink(
                        "Work files", "/storage/emulated/0/My Work"));

        assertTrue(parsed instanceof DesktopFolderShortcut);
        final DesktopFolderShortcut link = (DesktopFolderShortcut) parsed;
        assertEquals("Work files", link.name);
        assertEquals("/storage/emulated/0/My Work", link.targetPath);
        assertFalse(link.available);
    }

    @Test
    public void parsesEscapedLinkName() {
        final DesktopEntry parsed = DesktopEntryFile.parse(
                "# comment\n"
                        + "[Desktop Entry]\n"
                        + "Type=Link\n"
                        + "Name=Project\\sFiles\n"
                        + "URL=file:///storage/emulated/0/Project%20Files\n");

        assertTrue(parsed instanceof DesktopFolderShortcut);
        final DesktopFolderShortcut link =
                (DesktopFolderShortcut) parsed;
        assertEquals("Project Files", link.name);
        assertEquals(
                "/storage/emulated/0/Project Files", link.targetPath);
    }

    @Test
    public void webLinkRoundTripPreservesUrlAndStandardFields() {
        final DesktopEntry parsed = DesktopEntryFile.parse(
                DesktopEntryFile.encodeWebLink(
                        "Example",
                        "https://example.com/search?q=magicdesk#result"));

        assertTrue(parsed instanceof DesktopWebShortcut);
        final DesktopWebShortcut link = (DesktopWebShortcut) parsed;
        assertEquals("Example", link.name);
        assertEquals("web-browser", link.icon);
        assertEquals(
                "https://example.com/search?q=magicdesk#result",
                link.url);
    }

    @Test
    public void applicationRoundTripPreservesIntentExecAndMode() {
        final AppLaunchTarget target = AppLaunchTarget.explicit(
                "example.application",
                "example.application.MainActivity",
                "android.intent.action.MAIN");
        final String intent = "intent:#Intent;component="
                + "example.application/.MainActivity;S.query=hello%20world;end";
        final DesktopApplicationShortcut source =
                new DesktopApplicationShortcut(
                        "Example App",
                        "example.application",
                        DesktopEntryFile.applicationExec(intent),
                        target,
                        intent,
                        DesktopLaunchMode.WINDOWED,
                        false,
                        DesktopExecBackend.SHELL,
                        false);

        final DesktopEntry parsed = DesktopEntryFile.parse(
                DesktopEntryFile.encodeApplication(source));

        assertTrue(parsed instanceof DesktopApplicationShortcut);
        final DesktopApplicationShortcut app =
                (DesktopApplicationShortcut) parsed;
        assertEquals("Example App", app.name);
        assertEquals("example.application", app.icon);
        assertEquals(source.exec, app.exec);
        assertEquals(intent, app.intentUri);
        assertEquals(target, app.launchTarget);
        assertEquals(DesktopLaunchMode.WINDOWED, app.launchMode);
        assertFalse(app.defaultLaunch);
        assertEquals(DesktopExecBackend.SHELL, app.execBackend);
        assertFalse(app.terminal);
    }

    @Test
    public void execOnlyApplicationIsRetainedForFutureExecution() {
        final DesktopEntry parsed = DesktopEntryFile.parse(
                "[Desktop Entry]\n"
                        + "Type=Application\n"
                        + "Name=Midnight Commander\n"
                        + "Exec=mc --colors\n"
                        + "Terminal=true\n");

        assertTrue(parsed instanceof DesktopApplicationShortcut);
        final DesktopApplicationShortcut app =
                (DesktopApplicationShortcut) parsed;
        assertEquals("mc --colors", app.exec);
        assertTrue(app.intentUri.isEmpty());
        assertNull(app.launchTarget);
        assertEquals(DesktopExecBackend.SHELL, app.execBackend);
        assertTrue(app.terminal);
        assertTrue(app.hasExecLaunch());
    }

    @Test
    public void termuxExecBackendRoundTripsWithoutChangingStandardExec() {
        final DesktopEntry parsed = DesktopEntryFile.parse(
                "[Desktop Entry]\n"
                        + "Type=Application\n"
                        + "Name=X11 desktop\n"
                        + "Icon=com.termux.x11\n"
                        + "Exec=termux-x11 :1\n"
                        + "Path=/data/data/com.termux/files/home/project\n"
                        + "X-MagicDesk-Package=com.termux.x11\n"
                        + "X-MagicDesk-ExecBackend=termux\n"
                        + "X-MagicDesk-WindowMode=windowed\n");

        assertTrue(parsed instanceof DesktopApplicationShortcut);
        final DesktopApplicationShortcut app =
                (DesktopApplicationShortcut) parsed;
        assertEquals("termux-x11 :1", app.exec);
        assertEquals(DesktopExecBackend.TERMUX, app.execBackend);
        assertEquals(
                "/data/data/com.termux/files/home/project",
                app.workingDirectory);
        assertEquals(DesktopLaunchMode.WINDOWED, app.launchMode);
        assertTrue(app.hasExecLaunch());
        assertEquals(
                "[Desktop Entry]\n"
                        + "Version=1.5\n"
                        + "Type=Application\n"
                        + "Name=X11 desktop\n"
                        + "Icon=com.termux.x11\n"
                        + "Exec=termux-x11 :1\n"
                        + "Path=/data/data/com.termux/files/home/project\n"
                        + "X-MagicDesk-Package=com.termux.x11\n"
                        + "X-MagicDesk-WindowMode=windowed\n"
                        + "X-MagicDesk-ExecBackend=termux\n",
                DesktopEntryFile.encodeApplication(app));
    }

    @Test
    public void androidIntentTakesPriorityOverExecFallback() {
        final DesktopEntry parsed = DesktopEntryFile.parse(
                "[Desktop Entry]\n"
                        + "Type=Application\n"
                        + "Name=Android app\n"
                        + "Exec=/system/bin/am start "
                        + "-n example.application/.Main\n"
                        + "X-MagicDesk-Package=example.application\n"
                        + "X-MagicDesk-Intent=intent:#Intent;component="
                        + "example.application/.Main;end\n");

        assertTrue(parsed instanceof DesktopApplicationShortcut);
        final DesktopApplicationShortcut app =
                (DesktopApplicationShortcut) parsed;
        assertTrue(app.hasIntentLaunch());
        assertFalse(app.hasExecLaunch());
    }

    @Test
    public void unknownExecBackendRejectsEntryInsteadOfUsingShell() {
        assertNull(DesktopEntryFile.parse(
                "[Desktop Entry]\n"
                        + "Type=Application\n"
                        + "Name=Wrong backend\n"
                        + "Exec=example\n"
                        + "X-MagicDesk-ExecBackend=termx\n"));
    }

    @Test
    public void relativeWorkingDirectoryRejectsEntry() {
        assertNull(DesktopEntryFile.parse(
                "[Desktop Entry]\n"
                        + "Type=Application\n"
                        + "Name=Relative path\n"
                        + "Exec=pwd\n"
                        + "Path=project\n"));
    }

    @Test
    public void oversizedExecutableEntryIsRejectedDuringParsing() {
        assertNull(DesktopEntryFile.parse(
                "[Desktop Entry]\n"
                        + "Type=Application\n"
                        + "Name=Too large\n"
                        + "Exec="
                        + "x".repeat(DesktopExecCommand.MAX_LENGTH + 1)
                        + "\n"));
    }

    @Test
    public void rejectsMalformedOrUnsupportedEntries() {
        assertNull(DesktopEntryFile.parse(
                "[Desktop Entry]\nType=Application\nName=Missing target\n"));
        assertNull(DesktopEntryFile.parse(
                "[Desktop Entry]\nType=Link\nName=Remote\n"
                        + "URL=ftp://example.com/\n"));
        assertNull(DesktopEntryFile.parse(
                "[Desktop Entry]\nType=Link\nName=Script\n"
                        + "URL=javascript:alert(1)\n"));
        assertNull(DesktopEntryFile.parse(
                "[Desktop Entry]\nType=Directory\nName=Folder\n"));
        assertNull(DesktopEntryFile.parse(
                "[Desktop Entry]\nType=Link\nName=Remote file\n"
                        + "URL=file://server/share\n"));
        assertNull(DesktopEntryFile.parse(
                "[Desktop Entry]\nType=Link\nName=Relative\nURL=folder\n"));
        assertNull(DesktopEntryFile.parse(
                "[Desktop Entry]\nType=Link\nName=Missing URL\n"));
        assertNull(DesktopEntryFile.parse(
                "[Desktop Entry]\nType=Link\nURL=file:///tmp\n"));
    }

    @Test
    public void applicationFileNameIsPortableAndStable() {
        assertEquals(
                "Files_Settings.desktop",
                DesktopEntryFile.shortcutFileName("Files/Settings"));
        assertEquals(
                "Files.desktop",
                DesktopEntryFile.shortcutFileName("Files.desktop"));
    }

    @Test
    public void applicationExecEscapesDesktopFieldCodes() {
        assertEquals(
                "/system/bin/am start --user current "
                        + "\"intent:#Intent;S.query=100%%25;end\"",
                DesktopEntryFile.applicationExec(
                        "intent:#Intent;S.query=100%25;end"));
        assertEquals(
                "/system/bin/am start --user current "
                        + "\"intent:#Intent;S.query=100%25;end\"",
                DesktopExecCommand.prepare(
                        DesktopEntryFile.applicationExec(
                                "intent:#Intent;S.query=100%25;end")));
    }
}
