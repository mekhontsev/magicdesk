package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.graphics.Rect;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.After;
import org.junit.Test;

public final class DesktopTaskStateStoreTest {
    private static final int DISPLAY_ID = 71;

    @After
    public void clearState() {
        DesktopTaskStateStore.clear(DISPLAY_ID);
    }

    @Test
    public void successfulFullscreenTransitionPreservesPreviousWorkspace() {
        final TaskRepository.TaskEntry first = task(1);
        final TaskRepository.TaskEntry fullscreen = task(2);
        DesktopTaskStateStore.publish(
                DISPLAY_ID, Arrays.asList(first, fullscreen), true);

        DesktopTaskStateStore.beginFullscreenTransition(
                DISPLAY_ID,
                DesktopTaskStateStore.getVisibleTasks(DISPLAY_ID),
                fullscreen.taskId);
        DesktopTaskStateStore.publish(
                DISPLAY_ID, Collections.singletonList(task(3)), true);
        DesktopTaskStateStore.finishFullscreenTransition(DISPLAY_ID, true);

        assertTaskIds(
                DesktopTaskStateStore.getLastVisibleTasks(DISPLAY_ID), 1);
    }

    @Test
    public void failedFullscreenTransitionUsesCurrentVisibleWorkspace() {
        final TaskRepository.TaskEntry fullscreen = task(2);
        DesktopTaskStateStore.publish(
                DISPLAY_ID,
                Arrays.asList(task(1), fullscreen),
                true);

        DesktopTaskStateStore.beginFullscreenTransition(
                DISPLAY_ID,
                DesktopTaskStateStore.getVisibleTasks(DISPLAY_ID),
                fullscreen.taskId);
        DesktopTaskStateStore.publish(
                DISPLAY_ID, Collections.singletonList(task(3)), true);
        DesktopTaskStateStore.finishFullscreenTransition(DISPLAY_ID, false);

        assertTaskIds(
                DesktopTaskStateStore.getLastVisibleTasks(DISPLAY_ID), 3);
    }

    @Test
    public void completedTransitionAllowsWorkspaceUpdatesAgain() {
        DesktopTaskStateStore.publish(
                DISPLAY_ID, Collections.singletonList(task(1)), true);
        DesktopTaskStateStore.beginFullscreenTransition(
                DISPLAY_ID,
                DesktopTaskStateStore.getVisibleTasks(DISPLAY_ID),
                -1);
        DesktopTaskStateStore.finishFullscreenTransition(DISPLAY_ID, true);

        DesktopTaskStateStore.publish(
                DISPLAY_ID, Collections.singletonList(task(4)), true);

        assertTaskIds(
                DesktopTaskStateStore.getLastVisibleTasks(DISPLAY_ID), 4);
    }

    @Test
    public void clearRemovesDisplayState() {
        DesktopTaskStateStore.publish(
                DISPLAY_ID, Collections.singletonList(task(1)), true);

        DesktopTaskStateStore.clear(DISPLAY_ID);

        assertNull(DesktopTaskStateStore.getVisibleTasks(DISPLAY_ID));
        assertNull(DesktopTaskStateStore.hasVisibleAppTask(DISPLAY_ID));
        assertEquals(
                Collections.emptyList(),
                DesktopTaskStateStore.getLastVisibleTasks(DISPLAY_ID));
    }

    private static void assertTaskIds(
            final List<TaskRepository.TaskEntry> tasks,
            final int... expectedTaskIds) {
        assertEquals(expectedTaskIds.length, tasks.size());
        for (int index = 0; index < expectedTaskIds.length; index++) {
            assertEquals(expectedTaskIds[index], tasks.get(index).taskId);
        }
    }

    private static TaskRepository.TaskEntry task(final int taskId) {
        return new TaskRepository.TaskEntry(
                taskId,
                taskId,
                DISPLAY_ID,
                "example.app" + taskId,
                "example.app" + taskId + "/.MainActivity",
                "example.app" + taskId + "/.MainActivity",
                "freeform",
                new Rect(20 * taskId, 20, 400, 500),
                false,
                true,
                taskId == 1);
    }
}
