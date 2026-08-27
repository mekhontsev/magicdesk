package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public final class DesktopTaskControllerTest {
    @Test
    public void identifiesOnlyDesktopActivityAsHost() {
        assertTrue(DesktopTaskController.isDesktopHostTask(task(
                "io.github.mekhontsev.magicdesk/.DesktopActivity")));
        assertTrue(DesktopTaskController.isDesktopHostTask(task(
                "io.github.mekhontsev.magicdesk/"
                        + "io.github.mekhontsev.magicdesk.DesktopActivity")));
        assertFalse(DesktopTaskController.isDesktopHostTask(task(
                "io.github.mekhontsev.magicdesk/.DesktopSelfTestActivity")));
        assertFalse(DesktopTaskController.isDesktopHostTask(task(
                "io.github.mekhontsev.magicdesk/.DiagnosticsActivity")));
    }

    @Test
    public void prefersActiveTaskOverVisibleTopFallback() {
        final TaskRepository.TaskEntry visibleTop = task(
                10, "com.example.top/.MainActivity", true, false);
        final TaskRepository.TaskEntry active = task(
                11, "com.example.active/.MainActivity", true, true);

        assertEquals(active, DesktopTaskController.selectTopVisibleTask(
                Arrays.asList(visibleTop, active), true));
    }

    @Test
    public void fallsBackToTopVisibleTaskWhenActiveFlagIsStale() {
        final TaskRepository.TaskEntry visibleTop = task(
                10, "com.example.top/.MainActivity", true, false);
        final TaskRepository.TaskEntry visibleBehind = task(
                11, "com.example.behind/.MainActivity", true, false);

        assertEquals(visibleTop, DesktopTaskController.selectTopVisibleTask(
                Arrays.asList(visibleTop, visibleBehind), true));
    }

    @Test
    public void keepsKnownFocusedTaskAcrossSyntheticActiveSnapshots() {
        final TaskRepository.TaskEntry syntheticActive = task(
                10, "com.example.previous/.MainActivity", true, true);
        final TaskRepository.TaskEntry knownFocused = task(
                11, "com.example.console/.MainActivity", true, false);

        assertEquals(knownFocused,
                DesktopTaskController.selectKnownOrTopVisibleTask(
                        Arrays.asList(syntheticActive, knownFocused), 11));
    }

    @Test
    public void replacesKnownFocusedTaskAfterItBecomesHidden() {
        final TaskRepository.TaskEntry visible = task(
                10, "com.example.visible/.MainActivity", true, true);
        final TaskRepository.TaskEntry hidden = task(
                11, "com.example.hidden/.MainActivity", false, false);

        assertEquals(visible,
                DesktopTaskController.selectKnownOrTopVisibleTask(
                        Arrays.asList(visible, hidden), 11));
    }

    @Test
    public void shortcutKeepsExplicitlyFocusedTaskAcrossStaleSnapshot() {
        final TaskRepository.TaskEntry staleActive = task(
                10, "com.example.previous/.MainActivity", true, true);
        final TaskRepository.TaskEntry focused = task(
                11, "com.example.focused/.MainActivity", true, false);

        assertEquals(focused, DesktopTaskController.selectShortcutTask(
                Arrays.asList(staleActive, focused), 11, true));
    }

    @Test
    public void shortcutFallsBackWhenFocusedTaskDisappeared() {
        final TaskRepository.TaskEntry active = task(
                10, "com.example.active/.MainActivity", true, true);

        assertEquals(active, DesktopTaskController.selectShortcutTask(
                Arrays.asList(active), 11, true));
    }

    @Test
    public void cachedShortcutDoesNotGuessWhenFocusedTaskIsMissing() {
        final TaskRepository.TaskEntry staleVisible = task(
                10, "com.example.previous/.MainActivity", true, true);

        assertNull(DesktopTaskController.findKnownShortcutTask(
                Arrays.asList(staleVisible), 11, true));
    }

    @Test
    public void boundedShortcutRejectsFullscreenFocusedTask() {
        final TaskRepository.TaskEntry fallback = task(
                10, "com.example.window/.MainActivity", true, false);
        final TaskRepository.TaskEntry fullscreen = task(
                11,
                "com.example.fullscreen/.MainActivity",
                "fullscreen",
                true,
                true);

        assertEquals(fallback, DesktopTaskController.selectShortcutTask(
                Arrays.asList(fallback, fullscreen), 11, true));
    }

    @Test
    public void closeSelectsNextVisibleTaskBeforeDesktopHost() {
        final TaskRepository.TaskEntry closing = task(
                10, "com.example.top/.MainActivity", true, true);
        final TaskRepository.TaskEntry survivor = task(
                11, "com.example.behind/.MainActivity", true, false);

        assertEquals(11, DesktopTaskController.selectCloseSurvivorTaskId(
                Arrays.asList(closing, survivor), 10, 99));
    }

    @Test
    public void closeFallsBackToDesktopHostWithoutAnotherVisibleTask() {
        assertEquals(99, DesktopTaskController.selectCloseSurvivorTaskId(
                null, 10, 99));
        assertEquals(99, DesktopTaskController.selectCloseSurvivorTaskId(
                Arrays.asList(task(
                        10,
                        "com.example.top/.MainActivity",
                        true,
                        true)),
                10,
                99));
    }

    @Test
    public void forceStopSkipsEveryTaskOwnedByPackage() {
        final TaskRepository.TaskEntry firstPackageTask = task(
                10, "com.example.target/.FirstActivity", true, true);
        final TaskRepository.TaskEntry secondPackageTask = task(
                11, "com.example.target/.SecondActivity", true, false);
        final TaskRepository.TaskEntry survivor = task(
                12, "com.example.other/.MainActivity", true, false);

        assertEquals(12,
                DesktopTaskController.selectPackageRemovalSurvivorTaskId(
                        Arrays.asList(
                                firstPackageTask,
                                secondPackageTask,
                                survivor),
                        "com.example.target",
                        99));
    }

    @Test
    public void forceStopFallsBackToDesktopHostForLastPackage() {
        assertEquals(99,
                DesktopTaskController.selectPackageRemovalSurvivorTaskId(
                        Arrays.asList(task(
                                10,
                                "com.example.target/.MainActivity",
                                true,
                                true)),
                        "com.example.target",
                        99));
    }

    @Test
    public void showDesktopSelectsTheNextActiveUndemotedTask() {
        final TaskRepository.TaskEntry first = task(
                10, "com.example.first/.MainActivity", true, true);
        final TaskRepository.TaskEntry second = task(
                11, "com.example.second/.MainActivity", true, true);
        final TaskRepository.TaskEntry host = task(
                "io.github.mekhontsev.magicdesk/.DesktopActivity");

        assertEquals(
                11,
                DesktopTaskController.selectShowDesktopDemotionTask(
                        Arrays.asList(first, second, host),
                        2,
                        1,
                        Collections.singleton(Integer.valueOf(10))).taskId);
    }

    @Test
    public void showDesktopRestoreDropsClosedTasksWithoutReordering() {
        final TaskRepository.TaskEntry bottom = task(
                12, "com.example.bottom/.MainActivity", true, false);
        final TaskRepository.TaskEntry top = task(
                10, "com.example.top/.MainActivity", true, true);

        assertEquals(
                Arrays.asList(12, 10),
                DesktopTaskController.liveTaskOrder(
                        Arrays.asList(top, bottom),
                        2,
                        Arrays.asList(12, 11, 10)));
    }

    private static TaskRepository.TaskEntry task(final String componentName) {
        return new TaskRepository.TaskEntry(
                1,
                1,
                2,
                "io.github.mekhontsev.magicdesk",
                componentName,
                componentName,
                "fullscreen",
                new Rect(0, 0, 100, 100),
                false,
                true,
                true);
    }

    private static TaskRepository.TaskEntry task(
            final int taskId,
            final String componentName,
            final boolean visible,
            final boolean active) {
        return task(taskId, componentName, "freeform", visible, active);
    }

    private static TaskRepository.TaskEntry task(
            final int taskId,
            final String componentName,
            final String windowingMode,
            final boolean visible,
            final boolean active) {
        final String packageName = componentName.substring(
                0, componentName.indexOf('/'));
        final TaskRepository.TaskEntry task = new TaskRepository.TaskEntry(
                taskId,
                taskId,
                2,
                packageName,
                componentName,
                componentName,
                windowingMode,
                new Rect(0, 0, 100, 100),
                false,
                visible,
                active);
        // Android's local JVM stub does not copy Rect constructor fields.
        task.bounds.right = 100;
        task.bounds.bottom = 100;
        return task;
    }
}
