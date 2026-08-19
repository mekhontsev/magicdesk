package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;

import org.junit.Test;

import java.util.Arrays;

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
        final String packageName = componentName.substring(
                0, componentName.indexOf('/'));
        final TaskRepository.TaskEntry task = new TaskRepository.TaskEntry(
                taskId,
                taskId,
                2,
                packageName,
                componentName,
                componentName,
                "freeform",
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
