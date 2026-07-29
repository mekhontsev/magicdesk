package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;

import java.util.Arrays;
import java.util.Collections;

import org.junit.After;
import org.junit.Test;

public final class DesktopSpaceStateStoreTest {
    private static final int DISPLAY_ID = 17;
    private static final String DESKTOP_PACKAGE =
            "io.github.mekhontsev.magicdesk";

    @After
    public void clearState() {
        DesktopSpaceStateStore.clear(DISPLAY_ID);
    }

    @Test
    public void initiallyAssignsExistingTasksToFirstSpace() {
        final TaskRepository.TaskEntry task = task(1, true);
        DesktopSpaceStateStore.sync(
                DISPLAY_ID,
                Collections.singletonList(task),
                DESKTOP_PACKAGE);

        assertTrue(DesktopSpaceStateStore.isInActiveSpace(
                DISPLAY_ID, task, DESKTOP_PACKAGE));
    }

    @Test
    public void visibleTaskMovesIntoCurrentSpace() {
        final TaskRepository.TaskEntry first = task(1, true);
        DesktopSpaceStateStore.sync(
                DISPLAY_ID,
                Collections.singletonList(first),
                DESKTOP_PACKAGE);
        DesktopSpaceStateStore.setActiveSpace(DISPLAY_ID, 1);

        final TaskRepository.TaskEntry hidden = task(1, false);
        final TaskRepository.TaskEntry visible = task(2, true);
        DesktopSpaceStateStore.sync(
                DISPLAY_ID,
                Arrays.asList(hidden, visible),
                DESKTOP_PACKAGE);

        assertFalse(DesktopSpaceStateStore.isInActiveSpace(
                DISPLAY_ID, hidden, DESKTOP_PACKAGE));
        assertTrue(DesktopSpaceStateStore.isInActiveSpace(
                DISPLAY_ID, visible, DESKTOP_PACKAGE));
    }

    private static TaskRepository.TaskEntry task(
            final int taskId, final boolean visible) {
        return new TaskRepository.TaskEntry(
                taskId, taskId, DISPLAY_ID,
                "example.app" + taskId,
                "example.app" + taskId + "/.Main",
                "example.app" + taskId + "/.Main",
                "freeform",
                new Rect(0, 0, 100, 100),
                false, visible, visible);
    }
}
