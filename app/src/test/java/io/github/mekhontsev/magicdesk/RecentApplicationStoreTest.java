package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class RecentApplicationStoreTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    private Path directory() { return temporary.getRoot().toPath().resolve("recent"); }
    private RecentApplicationStore store() { return new RecentApplicationStore(directory()); }
    private static DesktopApplicationShortcut android(AppProfile profile, String name) {
        return new DesktopApplicationShortcut(name, name, "", AppLaunchTarget.packageDefault(name), "",
                DesktopLaunchMode.AUTO, true, DesktopExecBackend.SHELL, false).withApplication(profile.application(name));
    }
    private static DesktopApplicationShortcut x11(String name, String command) {
        return new DesktopApplicationShortcut(name, "gimp", command, null, "", DesktopLaunchMode.AUTO,
                false, DesktopExecBackend.X11, false, "/data/data/com.termux/files/home");
    }
    private static RecentApplicationStore.Entry entry(DesktopApplicationShortcut app, String path) {
        return new RecentApplicationStore.Entry(app, path, app.hasExecLaunch() ? "com.termux" : "", 100);
    }

    @Test public void sourcePathsUseAndroidNamespaceOnEveryHost() {
        var app = x11("GIMP", "gimp %k");
        String source = "/data/data/com.termux/files/home/app with spaces.desktop";
        assertEquals(source, entry(app, source).sourcePath());
        assertEquals("", entry(app, "").sourcePath());
        for (String invalid : new String[]{"relative.desktop", "C:/apps/gimp.desktop",
                "C:\\apps\\gimp.desktop", "\\\\server\\app.desktop", "/bad\0.desktop"}) {
            assertThrows(IllegalArgumentException.class, () -> entry(app, invalid));
        }
    }

    @Test public void mixedHistorySurvivesRestartWithoutAnIndexOrRuntimeIds() throws Exception {
        var app = entry(android(new AppProfile(0, 5), "example.app"), "");
        var gimp = entry(x11("GIMP", "gimp %U"), "/termux/gimp.desktop");
        var desktop = entry(x11("Ubuntu", "proot-distro login ubuntu -- startxfce4").withX11Desktop(true), "");
        store().record(app); store().record(gimp); store().record(desktop);
        var entries = store().read();
        assertEquals(List.of(desktop.key(), gimp.key(), app.key()), entries.stream().map(RecentApplicationStore.Entry::key).toList());
        assertTrue(entries.get(0).shortcut().x11Desktop);
        assertEquals("gimp %U", entries.get(1).shortcut().exec);
        assertEquals("/termux/gimp.desktop", entries.get(1).sourcePath());
        assertEquals(app.shortcut().application, entries.get(2).shortcut().application);
        try (var files = Files.list(directory())) {
            assertTrue(files.allMatch(path -> path.getFileName().toString().matches("[0-9a-f]{64}\\.desktop")));
        }
    }

    @Test public void repeatedLaunchesMoveOneRecordToFrontEvenWhenClockMovesBackwards() throws Exception {
        var a = entry(x11("GIMP", "gimp %U"), "/first/gimp.desktop");
        var b = entry(x11("Firefox", "firefox"), "");
        store().record(a); store().record(b);
        var renamed = entry(x11("GNU Image Editor", "gimp %U"), "/second/gimp.desktop");
        var entries = store().record(renamed.usedAt(1));
        assertEquals(2, entries.size());
        assertEquals(a.key(), entries.get(0).key());
        assertEquals("GNU Image Editor", entries.get(0).shortcut().name);
        assertTrue(entries.get(0).lastUsed() > entries.get(1).lastUsed());
        for (int i = 0; i < 50; i++) store().record(a);
        assertEquals(2, store().read().size());
        try (var files = Files.list(directory())) { assertEquals(2, files.count()); }
    }

    @Test public void androidShortcutAndTaskFocusShareIdentityButProfilesAndBuiltInsDoNot() throws Exception {
        var profile = new AppProfile(0, 5);
        var app = android(profile, "example.app");
        var explicit = new DesktopApplicationShortcut("Renamed", "new-icon", "ignored fallback", AppLaunchTarget.explicit("example.app", "example.app.Main", ""),
                "ignored intent", DesktopLaunchMode.FULLSCREEN, true, DesktopExecBackend.SHELL, false).withApplication(app.application);
        assertEquals(entry(app, "").key(), entry(explicit, "/desktop/app.desktop").key());
        store().record(entry(app, "")); store().record(entry(explicit, "/desktop/app.desktop"));
        assertEquals(1, store().read().size());
        assertNotEquals(entry(app, "").key(), entry(android(new AppProfile(10, 19), "example.app"), "").key());
        assertNotEquals(entry(app, "").key(), entry(android(new AppProfile(0, 27), "example.app"), "").key());
        var files = new DesktopApplicationShortcut("Files", "", "", BuiltInDesktopAppCatalog.filesTarget(), "", DesktopLaunchMode.AUTO,
                true, DesktopExecBackend.SHELL, false).withApplication(profile.application(BuildConfig.APPLICATION_ID));
        var settings = new DesktopApplicationShortcut("Settings", "", "", BuiltInDesktopAppCatalog.settingsTarget(), "", DesktopLaunchMode.AUTO,
                true, DesktopExecBackend.SHELL, false).withApplication(profile.application(BuildConfig.APPLICATION_ID));
        assertNotEquals(entry(files, "").key(), entry(settings, "").key());
    }

    @Test public void distinctRecipesAndEnvironmentsAreNotMerged() {
        var gimp = entry(x11("GIMP", "gimp"), "");
        assertNotEquals(gimp.key(), entry(x11("GIMP", "gimp --no-data"), "").key());
        assertNotEquals(gimp.key(), entry(gimp.shortcut().withX11Desktop(true), "").key());
        assertNotEquals(gimp.key(), new RecentApplicationStore.Entry(gimp.shortcut(), "", "org.example.termux", 100).key());
        var field = x11("Wrapper", "wrapper %k");
        assertNotEquals(entry(field, "/a.desktop").key(), entry(field, "/b.desktop").key());
        var named = x11("One", "wrapper %c");
        assertNotEquals(entry(named, "").key(), entry(x11("Two", "wrapper %c"), "").key());
    }

    @Test public void historyPrunesOldFilesAndRecreationCannotGrowIt() throws Exception {
        for (int i = 0; i < RecentApplicationStore.LIMIT + 10; i++) store().record(entry(x11("App", "app-" + i), ""));
        assertEquals(RecentApplicationStore.LIMIT, store().read().size());
        try (var files = Files.list(directory())) { assertEquals(RecentApplicationStore.LIMIT, files.count()); }
    }

    @Test public void corruptedOrUnboundEntriesCannotHideGoodHistory() throws Exception {
        var valid = entry(x11("GIMP", "gimp"), "");
        store().record(valid);
        Files.writeString(directory().resolve("broken.desktop"), "[Desktop Entry]\nType=Application\nName=Broken\n");
        Files.writeString(directory().resolve("copy.desktop"), DesktopEntryFile.encodeRecent(valid));
        Files.writeString(directory().resolve("oversize.desktop"), "x".repeat(65537));
        assertEquals(1, store().read().size());
        assertNull(DesktopEntryFile.parseRecent("[Desktop Entry]\nType=Application\nName=App\nX-MagicDesk-Package=example.app\nX-MagicDesk-Default=true\nX-MagicDesk-LastUsed=100\n"));
        assertNull(DesktopEntryFile.parseRecent(DesktopEntryFile.encodeRecent(valid).replace("LastUsed=100", "LastUsed=" + Long.MAX_VALUE)));
    }

    @Test public void importedTermuxDesktopModePreservesOnlySupportedExtensions() {
        var app = DesktopEntryFile.parseTermuxApplication("[Desktop Entry]\nType=Application\nName=Ubuntu\nExec=proot-distro login ubuntu -- startxfce4\n"
                + "X-MagicDesk-X11Mode=desktop\nX-MagicDesk-Package=evil.app\nX-MagicDesk-Default=true\nX-MagicDesk-Intent=evil\n");
        assertNotNull(app); assertTrue(app.x11Desktop); assertNull(app.launchTarget); assertFalse(app.defaultLaunch);
        assertTrue(((DesktopApplicationShortcut) DesktopEntryFile.parse(DesktopEntryFile.encodeApplication(app))).x11Desktop);
        assertThrows(IllegalArgumentException.class, () -> android(new AppProfile(0, 5), "example.app").withX11Desktop(true));
    }

    @Test public void preparedLaunchRetainsOriginalRecipeNotExpandedArguments() {
        var app = x11("GIMP", "gimp %U");
        var request = DesktopLaunchRequest.from(app, DesktopLaunchArguments.empty(), "/gimp.desktop")
                .withPresentation(DesktopLaunchPresentation.forMode(DesktopLaunchMode.FULLSCREEN)).prepareExec();
        assertSame(app, request.sourceShortcut);
        assertEquals("gimp %U", request.sourceShortcut.exec);
        assertEquals("'gimp'", request.exec.command);
    }

    @Test public void androidRecentKeepsApplicationContextActionsAndItsRecipe() {
        var profile = new AppProfile(0, 5);
        var app = new AppItem(profile, "App", "example.app", true, AppItem.FULLSCREEN_REASON_NONE,
                null, AppLaunchTarget.packageDefault("example.app"));
        var recipe = entry(DesktopApplicationShortcut.forApp(app), "");
        var recent = StartMenuEntry.recent(recipe, List.of(app));
        assertSame(app, recent.app);
        assertSame(recipe, recent.recent);
        assertSame(recipe.shortcut(), recent.desktopApplication.shortcut);
    }
}
