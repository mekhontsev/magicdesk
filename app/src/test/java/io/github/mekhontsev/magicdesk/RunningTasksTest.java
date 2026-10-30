package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.List;

public final class RunningTasksTest {
    private static final AppProfile PROFILE = new AppProfile(0, 0);
    private static final String PREVIOUS_HOME = "com.example.launcher";

    @Test public void includesApplicationsWithoutLauncherEntriesOnEveryDisplay() {
        final var phone = task(1, 1, 0, "com.example.notification", 1, false, 0);
        final var external = task(2, 2, 4, "com.example.unlisted", 1, false, 0);
        assertEquals(List.of(phone, external), RunningTasks.select(List.of(phone, external), PROFILE, -1, null));
        assertEquals(List.of(phone), RunningTasks.select(List.of(phone, external), PROFILE, 0, PREVIOUS_HOME));
        assertEquals(List.of(external), RunningTasks.select(List.of(phone, external), PROFILE, 4, PREVIOUS_HOME));
    }

    @Test public void keepsEveryWindowEvenForOneApplicationOrOrganizerRoot() {
        final var newest = task(10, 2, 0, "com.example.app", 1, false, 0);
        final var older = task(10, 1, 0, "com.example.app", 1, false, 0);
        final var external = task(10, 3, 4, "com.example.app", 1, false, 0);
        assertEquals(List.of(newest, older, external), RunningTasks.select(
                List.of(newest, older, newest, external), PROFILE, -1, null));
    }

    @Test public void includesAllUserFacingToolsWithoutExpandingTheLauncherCatalog() {
        int id = 10;
        for (final var tool : BuiltInDesktopAppCatalog.searchEntries()) {
            final var first = task(id++, tool.launchTarget);
            final var second = task(id++, tool.launchTarget);
            assertEquals(tool.launchTarget.stableKey(), List.of(first, second),
                    RunningTasks.select(List.of(first, second), PROFILE, -1, null));
        }
        for (String component : List.of("CommandConsoleActivity", "X11Activity", "WaylandActivity",
                "DisplayViewerActivity", "SettingsActivity", "DiagnosticsActivity")) {
            final var tool = BuiltInDesktopAppCatalog.findComponent(BuildConfig.APPLICATION_ID + "." + component);
            assertNotNull(component, tool);
            final var first = task(id++, tool.launchTarget);
            final var second = task(id++, tool.launchTarget);
            assertEquals(List.of(first, second), RunningTasks.select(List.of(first, second), PROFILE, 0, PREVIOUS_HOME));
        }
    }

    @Test public void excludesInfrastructureHomeAndNonApplicationTasks() {
        assertTrue(RunningTasks.select(Arrays.asList(null,
                task(1, 1, 0, "com.example.home", 2, true, 0),
                task(2, 2, 0, PREVIOUS_HOME, 1, false, 0),
                task(3, 3, 0, BuildConfig.APPLICATION_ID, 1, false, 0),
                task(4, 4, 0, "com.example.recents", 3, false, 0),
                task(5, 5, 0, "invalid", 1, false, 0),
                task(6, 6, -1, "com.example.app", 1, false, 0),
                task(-1, 7, 0, "com.example.app", 1, false, 0),
                task(8, -1, 0, "com.example.app", 1, false, 0)), PROFILE, -1, PREVIOUS_HOME).isEmpty());
    }

    @Test public void unknownAndOtherProfilesCannotLeakIntoRunning() {
        assertTrue(RunningTasks.select(List.of(
                task(1, 1, 0, "com.example.app", 1, false, -1),
                task(2, 2, 0, "com.example.app", 1, false, 10)), PROFILE, -1, null).isEmpty());
        final var console = task(3, BuiltInDesktopAppCatalog.consoleTarget());
        assertTrue(RunningTasks.select(List.of(console), new AppProfile(10, 19), -1, null).isEmpty());
    }

    @Test public void emptySnapshotStaysEmptyAndWindowingModeDoesNotFilterWindows() {
        assertTrue(RunningTasks.select(List.of(), PROFILE, 0, PREVIOUS_HOME).isEmpty());
        final var task = task(1, 1, 0, "com.example.app", 1, false, 0);
        assertEquals(List.of(task), RunningTasks.select(List.of(task), PROFILE, 0, PREVIOUS_HOME));
    }

    @Test public void selectionPreservesExactTaskIdentityInStart() {
        final var first = task(1, BuiltInDesktopAppCatalog.consoleTarget());
        final var second = task(2, BuiltInDesktopAppCatalog.consoleTarget());
        final var a = StartMenuEntry.task(null, first, "Terminal", "");
        final var b = StartMenuEntry.task(null, second, "Terminal", "");
        assertSame(first, a.task);
        assertNotEquals(a.stableKey(), b.stableKey());
    }

    private static TaskRepository.TaskEntry task(int id, AppLaunchTarget target) {
        final String component = target.packageName + "/" + target.activityClassName;
        return new TaskRepository.TaskEntry(id, id, 0, target.packageName, component, component,
                "fullscreen", null, 1, -1, false, true, false, 0);
    }

    private static TaskRepository.TaskEntry task(int root, int id, int display, String pkg,
            int activityType, boolean home, int user) {
        return new TaskRepository.TaskEntry(root, id, display, pkg, pkg + "/.MainActivity", pkg + "/.MainActivity",
                "freeform", null, activityType, -1, home, true, false, user);
    }
}
