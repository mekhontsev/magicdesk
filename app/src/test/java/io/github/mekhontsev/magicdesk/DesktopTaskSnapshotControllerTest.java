package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static io.github.mekhontsev.magicdesk.DesktopWorkspaceScene.*;

import android.graphics.Rect;

import org.junit.Test;

import java.util.Arrays;

public final class DesktopTaskSnapshotControllerTest {
    @Test
    public void auxiliaryHomeDoesNotCoverDesktopOrDisableChrome() {
        final TaskRepository.TaskEntry home = desktopHost(true);
        final String component = BuildConfig.APPLICATION_ID + "/.PhoneHomeActivity";
        final TaskRepository.TaskEntry delegate = new TaskRepository.TaskEntry(
                91, 92, home.displayId, BuildConfig.APPLICATION_ID, component,
                component, "fullscreen", new Rect(0, 0, 1920, 1080),
                FrameworkTaskSnapshot.ACTIVITY_TYPE_HOME, 160,
                true, true, false, 0, 20001);
        assertTrue(DesktopInfrastructureTasks.isTask(delegate));
        assertFalse(DesktopTaskController.isDesktopHostTask(delegate));
        assertEquals(HOME, resolve(Arrays.asList(delegate, home)));
    }

    @Test
    public void desktopHostWinsOverNominallyVisibleFullscreenTaskBelowIt() {
        assertEquals(HOME, resolve(Arrays.asList(desktopHost(true), app(true))));
    }

    @Test
    public void applicationAboveDesktopHostRemainsForeground() {
        assertEquals(FULLSCREEN, resolve(Arrays.asList(app(true), desktopHost(true))));
    }

    @Test
    public void taskAreaBackstopDoesNotHideForegroundDesktopHost() {
        assertEquals(HOME, resolve(Arrays.asList(backstop(true), desktopHost(true))));
    }

    @Test
    public void taskAreaBackstopDoesNotHideForegroundApplication() {
        assertEquals(FULLSCREEN, resolve(Arrays.asList(backstop(true), app(true), desktopHost(true))));
    }

    @Test
    public void taskbarPlaneDoesNotHideForegroundDesktopHost() {
        assertEquals(HOME, resolve(Arrays.asList(taskbar(true), desktopHost(true))));
    }

    @Test
    public void taskbarPlaneDoesNotHideForegroundApplication() {
        assertEquals(FULLSCREEN, resolve(Arrays.asList(taskbar(true), app(true), desktopHost(true))));
    }

    @Test
    public void ordinaryHomeTaskIsNotDesktopHost() {
        assertFalse(DesktopTaskController.isDesktopHostTask(
                new TaskRepository.TaskEntry(
                        1,
                        1,
                        0,
                        "example.launcher",
                        "example.launcher/.HomeActivity",
                        "example.launcher/.HomeActivity",
                        "fullscreen",
                        new Rect(0, 0, 1920, 1080),
                        true,
                        true,
                        false)));
    }

    @Test
    public void fullscreenPlaneOccludesNominallyVisibleFreeformTask() {
        assertEquals(FULLSCREEN, resolve(
                Arrays.asList(
                        app(true),
                        freeform(true),
                        desktopHost(true))));
    }

    @Test
    public void freeformAboveFullscreenPlaneKeepsTaskbarVisible() {
        assertEquals(FREEFORM, resolve(
                Arrays.asList(
                        freeform(true),
                        app(true),
                        desktopHost(true))));
    }

    @Test
    public void structuralBackstopDoesNotOccludeFreeformTask() {
        assertEquals(FREEFORM, resolve(
                Arrays.asList(
                        backstop(true),
                        freeform(true),
                        app(true))));
    }

    @Test
    public void fullscreenTaskRemainsVisibleWithoutActiveFlag() {
        assertEquals(FULLSCREEN, resolve(
                Arrays.asList(app(false, true), desktopHost(true))));
    }

    @Test
    public void freeformAboveFullscreenOwnsTaskbarVisibility() {
        assertEquals(FREEFORM, resolve(
                Arrays.asList(
                        freeform(true),
                        app(false, true),
                        desktopHost(true))));
    }

    @Test
    public void foreignFullscreenForegroundRemainsPartOfChromePolicy() {
        final TaskRepository.TaskEntry foreignFullscreen = app(true, true);
        final TaskRepository.TaskEntry host = desktopHost(false);
        final java.util.List<TaskRepository.TaskEntry> displayTasks =
                Arrays.asList(taskbar(true), foreignFullscreen, host);
        final java.util.List<TaskRepository.TaskEntry> desktopTasks =
                Arrays.asList(taskbar(true), host);

        assertEquals(FULLSCREEN, resolve(displayTasks));
        assertEquals(UNKNOWN, resolve(desktopTasks));
    }

    @Test
    public void unmanageableFreeformStillKeepsTaskbarVisibleAndAvailable() {
        final TaskRepository.TaskEntry window = unmanageable("freeform", true);
        final TaskRepository.TaskEntry host = desktopHost(true);
        final java.util.List<TaskRepository.TaskEntry> displayTasks =
                Arrays.asList(taskbar(true), window, host);

        assertFalse(DesktopManagedTaskPolicy.isManagedApplicationTask(window));
        assertEquals(FREEFORM, resolve(displayTasks));
    }

    @Test
    public void unmanageableFullscreenOccludesFreeformWithoutAcquiringOwnership() {
        final TaskRepository.TaskEntry window = unmanageable("fullscreen", true);
        final java.util.List<TaskRepository.TaskEntry> displayTasks =
                Arrays.asList(window, freeform(true), desktopHost(true));

        assertFalse(DesktopManagedTaskPolicy.isManagedApplicationTask(window));
        assertEquals(FULLSCREEN, resolve(displayTasks));
    }

    @Test
    public void unmanageableFreeformAboveForeignFullscreenKeepsChromeAvailable() {
        final java.util.List<TaskRepository.TaskEntry> displayTasks = Arrays.asList(
                unmanageable("freeform", true), app(true), desktopHost(false));
        assertEquals(FREEFORM, resolve(displayTasks));
    }

    @Test
    public void coveredUnmanageableFreeformDoesNotDefineTheScene() {
        final java.util.List<TaskRepository.TaskEntry> displayTasks = Arrays.asList(
                app(true), unmanageable("freeform", true), desktopHost(false));
        assertEquals(FULLSCREEN, resolve(displayTasks));
    }

    @Test
    public void hiddenUnmanageableWindowDoesNotAffectChrome() {
        final java.util.List<TaskRepository.TaskEntry> displayTasks = Arrays.asList(
                unmanageable("fullscreen", false), freeform(true), desktopHost(true));
        assertEquals(FREEFORM, resolve(displayTasks));
    }

    @Test
    public void excludedUnmanageableFreeformDoesNotMaskFullscreen() {
        final TaskRepository.TaskEntry window = unmanageable("freeform", true);
        assertEquals(FULLSCREEN, resolve(
                Arrays.asList(window, app(true), desktopHost(true)), window.taskId));
    }

    @Test
    public void pictureInPictureDoesNotOwnScene() {
        final TaskRepository.TaskEntry pip = unmanageable("2", true);
        assertEquals(HOME, resolve(Arrays.asList(pip, desktopHost(true))));
        assertEquals(FREEFORM, resolve(Arrays.asList(pip, freeform(true), desktopHost(true))));
        assertEquals(FULLSCREEN, resolve(Arrays.asList(pip, app(false, true), freeform(true))));
    }

    @Test
    public void missingSceneDoesNotBecomeFullscreenFromActiveFlag() {
        assertEquals(UNKNOWN, resolve(null));
        assertEquals(UNKNOWN, resolve(java.util.Collections.emptyList()));
        assertEquals(UNKNOWN, resolve(Arrays.asList(null, taskbar(true), backstop(true),
                unmanageable("2", true), app(true, false))));
        assertEquals(UNKNOWN, resolve(Arrays.asList(unmanageable("unknown", true))));
    }

    private static TaskRepository.TaskEntry unmanageable(
            final String mode, final boolean visible) {
        final String component = BuildConfig.APPLICATION_ID + "/.ControlActivity";
        return new TaskRepository.TaskEntry(40, 40, 2, BuildConfig.APPLICATION_ID,
                component, component, mode, new Rect(100, 100, 900, 700),
                false, visible, true);
    }

    private static TaskRepository.TaskEntry desktopHost(
            final boolean visible) {
        return new TaskRepository.TaskEntry(
                10,
                11,
                2,
                BuildConfig.APPLICATION_ID,
                BuildConfig.APPLICATION_ID + "/.DesktopActivity",
                BuildConfig.APPLICATION_ID + "/.DesktopActivity",
                "fullscreen",
                new Rect(0, 0, 1920, 1080),
                true,
                visible,
                false);
    }

    private static TaskRepository.TaskEntry app(final boolean visible) {
        return app(true, visible);
    }

    private static TaskRepository.TaskEntry app(
            final boolean active,
            final boolean visible) {
        return new TaskRepository.TaskEntry(
                20,
                20,
                2,
                "example.app",
                "example.app/.MainActivity",
                "example.app/.MainActivity",
                "fullscreen",
                new Rect(0, 0, 1920, 1080),
                false,
                visible,
                active);
    }

    private static TaskRepository.TaskEntry freeform(final boolean visible) {
        return new TaskRepository.TaskEntry(
                21,
                21,
                2,
                "example.window",
                "example.window/.MainActivity",
                "example.window/.MainActivity",
                "freeform",
                new Rect(100, 100, 900, 700),
                false,
                visible,
                false);
    }

    private static TaskRepository.TaskEntry backstop(
            final boolean visible) {
        return new TaskRepository.TaskEntry(
                30,
                30,
                2,
                BuildConfig.APPLICATION_ID,
                BuildConfig.APPLICATION_ID + "/.TaskAreaBackstopActivity",
                BuildConfig.APPLICATION_ID + "/.TaskAreaBackstopActivity",
                "fullscreen",
                new Rect(0, 0, 1920, 1080),
                true,
                visible,
                false);
    }

    private static TaskRepository.TaskEntry taskbar(final boolean visible) {
        return new TaskRepository.TaskEntry(
                31,
                31,
                2,
                BuildConfig.APPLICATION_ID,
                BuildConfig.APPLICATION_ID + "/.DesktopChromeActivity",
                BuildConfig.APPLICATION_ID + "/.DesktopChromeActivity",
                "fullscreen",
                new Rect(0, 1000, 1920, 1080),
                false,
                visible,
                false);
    }
}
