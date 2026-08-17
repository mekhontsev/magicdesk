package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.graphics.Rect;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public final class DesktopDisplayTaskStateTest {
    private static final int DISPLAY_ID = 71;

    private final DesktopDisplayTaskState mState =
            new DesktopDisplayTaskState();

    @Test
    public void successfulFullscreenTransitionPreservesPreviousWorkspace() {
        final TaskRepository.TaskEntry first = task(1);
        final TaskRepository.TaskEntry fullscreen = task(2);
        mState.publish(Arrays.asList(first, fullscreen), true);

        mState.beginFullscreenTransition(
                mState.visibleTasks(), fullscreen.taskId);
        mState.publish(Collections.singletonList(task(3)), true);
        mState.finishFullscreenTransition(true);

        assertTaskIds(mState.lastVisibleTasks(), 1);
    }

    @Test
    public void failedFullscreenTransitionUsesCurrentVisibleWorkspace() {
        final TaskRepository.TaskEntry fullscreen = task(2);
        mState.publish(Arrays.asList(task(1), fullscreen), true);

        mState.beginFullscreenTransition(
                mState.visibleTasks(), fullscreen.taskId);
        mState.publish(Collections.singletonList(task(3)), true);
        mState.finishFullscreenTransition(false);

        assertTaskIds(mState.lastVisibleTasks(), 3);
    }

    @Test
    public void completedTransitionAllowsWorkspaceUpdatesAgain() {
        mState.publish(Collections.singletonList(task(1)), true);
        mState.beginFullscreenTransition(mState.visibleTasks(), -1);
        mState.finishFullscreenTransition(true);

        mState.publish(Collections.singletonList(task(4)), true);

        assertTaskIds(mState.lastVisibleTasks(), 4);
    }

    @Test
    public void clearRemovesDisplayState() {
        mState.publish(Collections.singletonList(task(1)), true);

        mState.clear();

        assertNull(mState.visibleTasks());
        assertNull(mState.hasVisibleAppTask());
        assertEquals(Collections.emptyList(), mState.lastVisibleTasks());
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
