package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class PhoneRecentAppsTest {
    private static final String PREVIOUS_HOME = "com.example.launcher";

    @Test
    public void selectsPhoneTasksRegardlessOfLaunchSource() {
        final List<AppItem> apps = Arrays.asList(app("com.example.phone"),
                app("com.example.notification"), app("com.example.external"));
        assertEquals(Arrays.asList("com.example.phone", "com.example.notification"),
                PhoneRecentApps.select(Arrays.asList(
                        task(1, 1, 0, "com.example.phone", "fullscreen", false),
                        task(2, 2, 0, "com.example.notification", "fullscreen", false),
                        task(3, 3, 4, "com.example.external", "fullscreen", false)),
                        apps, PREVIOUS_HOME));
    }

    @Test
    public void excludesHomeAndNonLaunchableTasks() {
        final List<AppItem> apps = Arrays.asList(app("com.example.home"),
                app(PREVIOUS_HOME));
        assertTrue(PhoneRecentApps.select(Arrays.asList(
                task(1, 1, 0, "com.example.home", "fullscreen", true),
                task(2, 2, 0, PREVIOUS_HOME, "fullscreen", false),
                task(4, 4, 0, "com.example.unlisted", "fullscreen", false),
                null), apps, PREVIOUS_HOME).isEmpty());
    }

    @Test
    public void phoneApplicationModeDoesNotMakeItAnExternalTask() {
        assertEquals(Collections.singletonList("com.example.floating"),
                PhoneRecentApps.select(Collections.singletonList(
                        task(1, 1, 0, "com.example.floating", "freeform", false)),
                        Collections.singletonList(app("com.example.floating")), PREVIOUS_HOME));
    }

    @Test
    public void keepsTopRootEntryAndDeduplicatesApplicationsInSnapshotOrder() {
        final List<AppItem> apps = Arrays.asList(app("com.example.first"),
                app("com.example.second"), app("com.example.child"));
        assertEquals(Arrays.asList("com.example.second", "com.example.first"),
                PhoneRecentApps.select(Arrays.asList(
                        task(2, 2, 0, "com.example.second", "fullscreen", false),
                        task(1, 1, 0, "com.example.first", "fullscreen", false),
                        task(1, 5, 0, "com.example.child", "fullscreen", false),
                        task(3, 3, 0, "com.example.second", "fullscreen", false)),
                        apps, PREVIOUS_HOME));
    }

    @Test
    public void externalInstanceOfSameApplicationDoesNotMakeItPhoneRecent() {
        final AppItem app = app("com.example.shared");
        assertTrue(PhoneRecentApps.select(Collections.singletonList(
                task(1, 1, 4, app.packageName, "freeform", false)),
                Collections.singletonList(app), PREVIOUS_HOME).isEmpty());
        assertEquals(Collections.singletonList(app.packageName),
                PhoneRecentApps.select(Arrays.asList(
                        task(1, 1, 4, app.packageName, "freeform", false),
                        task(2, 2, 0, app.packageName, "fullscreen", false)),
                        Collections.singletonList(app), PREVIOUS_HOME));
    }

    @Test
    public void keepsDistinctBuiltInPhoneAppsButNotDesktopShellSurfaces() {
        final AppItem files = app(BuiltInDesktopAppCatalog.filesTarget());
        final AppItem settings = app(BuiltInDesktopAppCatalog.settingsTarget());
        final List<String> selected = PhoneRecentApps.select(Arrays.asList(
                task(1, files.launchTarget), task(2, settings.launchTarget),
                task(3, 3, 0, BuildConfig.APPLICATION_ID, "fullscreen", true),
                task(4, 4, 0, BuildConfig.APPLICATION_ID, "multi-window", false)),
                Arrays.asList(files, settings), PREVIOUS_HOME);
        assertEquals(Arrays.asList(
                BuiltInDesktopAppCatalog.appIdentityKey(files.launchTarget),
                BuiltInDesktopAppCatalog.appIdentityKey(settings.launchTarget)), selected);
    }

    @Test
    public void emptyPhoneSnapshotDoesNotFallBackToApplicationCatalog() {
        assertTrue(PhoneRecentApps.select(Collections.emptyList(),
                Collections.singletonList(app("com.example.app")), PREVIOUS_HOME).isEmpty());
    }

    private static AppItem app(final String packageName) {
        return app(AppLaunchTarget.packageDefault(packageName));
    }

    private static AppItem app(final AppLaunchTarget target) {
        return new AppItem(target.packageName, target.packageName, false,
                AppItem.FULLSCREEN_REASON_NONE, null, target);
    }

    private static TaskRepository.TaskEntry task(final int id, final AppLaunchTarget target) {
        final String component = target.packageName + "/" + target.activityClassName;
        return new TaskRepository.TaskEntry(id, id, 0, target.packageName,
                component, component, "fullscreen", null, false, true, false);
    }

    private static TaskRepository.TaskEntry task(final int root, final int id,
            final int display, final String packageName, final String mode, final boolean home) {
        return new TaskRepository.TaskEntry(root, id, display, packageName,
                packageName + "/.MainActivity", packageName + "/.MainActivity",
                mode, null, home, true, false);
    }
}
