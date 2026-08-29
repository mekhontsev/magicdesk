package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class TaskbarTaskOrderTest {
    @Test
    public void concealsFullscreenBelowHostAndRestoresCompleteWorkspace() {
        final TaskRepository.TaskEntry fullscreen = task(
                10, "com.example.fullscreen", "fullscreen", false, true);
        final TaskRepository.TaskEntry topWindow = task(
                11, "com.example.top", "freeform", false, false);
        final TaskRepository.TaskEntry lowerWindow = task(
                12, "com.example.lower", "freeform", false, false);
        final TaskRepository.TaskEntry host = host(99);
        final TaskRepository.Snapshot snapshot = snapshot(
                fullscreen, topWindow, lowerWindow, host);

        assertEquals(
                Arrays.asList(10, 99, 12, 11),
                TaskbarTaskOrder.concealActiveTask(
                        snapshot,
                        fullscreen.taskId,
                        Arrays.asList(topWindow, lowerWindow),
                        ids(10),
                        false));
    }

    @Test
    public void ignoresClosedSavedWindowsWithoutChangingLiveOrder() {
        final TaskRepository.TaskEntry fullscreen = task(
                10, "com.example.fullscreen", "fullscreen", false, true);
        final TaskRepository.TaskEntry topWindow = task(
                11, "com.example.top", "freeform", false, false);
        final TaskRepository.TaskEntry closedWindow = task(
                12, "com.example.closed", "freeform", false, false);
        final TaskRepository.TaskEntry host = host(99);

        assertEquals(
                Arrays.asList(10, 99, 11),
                TaskbarTaskOrder.concealActiveTask(
                        snapshot(fullscreen, topWindow, host),
                        fullscreen.taskId,
                        Arrays.asList(topWindow, closedWindow),
                        ids(10),
                        false));
    }

    @Test
    public void fallsBackToVisibleWorkspaceForExternalFullscreenRequest() {
        final TaskRepository.TaskEntry fullscreen = task(
                10, "com.example.fullscreen", "fullscreen", true, true);
        final TaskRepository.TaskEntry topWindow = task(
                11, "com.example.top", "freeform", true, false);
        final TaskRepository.TaskEntry lowerWindow = task(
                12, "com.example.lower", "freeform", true, false);

        assertEquals(
                Arrays.asList(10, 99, 12, 11),
                TaskbarTaskOrder.concealActiveTask(
                        snapshot(fullscreen, topWindow, lowerWindow, host(99)),
                        fullscreen.taskId,
                        Collections.emptyList(),
                        ids(10),
                        false));
    }

    @Test
    public void requiresLiveFullscreenTaskAndDesktopHost() {
        final TaskRepository.TaskEntry fullscreen = task(
                10, "com.example.fullscreen", "fullscreen", true, true);

        assertTrue(TaskbarTaskOrder.concealActiveTask(
                snapshot(fullscreen),
                fullscreen.taskId,
                Collections.emptyList(),
                ids(10),
                false).isEmpty());
    }

    @Test
    public void revealsFullscreenPeerWithoutChangingEitherMode() {
        final TaskRepository.TaskEntry active = task(
                10, "com.example.first", "fullscreen", true, true);
        final TaskRepository.TaskEntry peer = task(
                11, "com.example.second", "fullscreen", false, false);
        final TaskRepository.TaskEntry window = task(
                12, "com.example.window", "freeform", false, false);

        assertEquals(
                Arrays.asList(10, 99, 12, 11),
                TaskbarTaskOrder.concealActiveTask(
                        snapshot(active, peer, window, host(99)),
                        active.taskId,
                        Collections.emptyList(),
                        ids(10),
                        false));
    }

    @Test
    public void concealsOnlyTaskBehindDesktop() {
        final TaskRepository.TaskEntry active = task(
                10, "com.example.only", "freeform", true, true);

        assertEquals(
                Arrays.asList(10, 99),
                TaskbarTaskOrder.concealActiveTask(
                        snapshot(active, host(99)),
                        active.taskId,
                        Collections.emptyList(),
                        ids(10),
                        false));
    }

    @Test
    public void acceptsConfirmedFocusBeforeActiveFlagCatchesUp() {
        final TaskRepository.TaskEntry focused = task(
                10, "com.example.focused", "freeform", true, false);

        assertEquals(
                Arrays.asList(10, 99),
                TaskbarTaskOrder.concealActiveTask(
                        snapshot(focused, host(99)),
                        focused.taskId,
                        Collections.emptyList(),
                        ids(10),
                        false,
                        focused.taskId));
    }

    @Test
    public void revealsFullscreenPlaneFlattenedAfterDesktopHost() {
        final TaskRepository.TaskEntry activeWindow = task(
                10, "com.example.window", "freeform", true, true);
        final TaskRepository.TaskEntry fullscreenPeer = task(
                11, "com.example.fullscreen", "fullscreen", false, false);

        assertEquals(
                Arrays.asList(10, 99, 11),
                TaskbarTaskOrder.concealActiveTask(
                        snapshot(activeWindow, host(99), fullscreenPeer),
                        activeWindow.taskId,
                        Collections.emptyList(),
                        ids(10),
                        true));
    }

    @Test
    public void sessionLeavesFullscreenTasksAfterHostConcealed() {
        final TaskRepository.TaskEntry activeWindow = task(
                10, "com.example.window", "freeform", true, true);
        final TaskRepository.TaskEntry concealedFullscreen = task(
                11, "com.example.fullscreen", "fullscreen", false, false);

        assertEquals(
                Arrays.asList(10, 99),
                TaskbarTaskOrder.concealActiveTask(
                        snapshot(activeWindow, host(99), concealedFullscreen),
                        activeWindow.taskId,
                        Collections.emptyList(),
                        ids(10),
                        false));
    }

    @Test
    public void secondFullscreenConcealmentRevealsDesktopNotFirstTask() {
        final TaskRepository.TaskEntry first = task(
                10, "com.example.first", "fullscreen", false, false);
        final TaskRepository.TaskEntry activeSecond = task(
                11, "com.example.second", "fullscreen", true, true);

        assertEquals(
                Arrays.asList(11, 10, 99),
                TaskbarTaskOrder.concealActiveTask(
                        snapshot(activeSecond, first, host(99)),
                        activeSecond.taskId,
                        Collections.emptyList(),
                        ids(10, 11),
                        false));
    }

    private static Set<Integer> ids(final Integer... taskIds) {
        return new HashSet<>(Arrays.asList(taskIds));
    }

    private static TaskRepository.Snapshot snapshot(
            final TaskRepository.TaskEntry... tasks) {
        return new TaskRepository.Snapshot(
                Arrays.asList(tasks), true, "");
    }

    private static TaskRepository.TaskEntry host(final int taskId) {
        return new TaskRepository.TaskEntry(
                taskId,
                taskId,
                2,
                BuildConfig.APPLICATION_ID,
                BuildConfig.APPLICATION_ID + "/.DesktopActivity",
                BuildConfig.APPLICATION_ID + "/.DesktopActivity",
                "fullscreen",
                bounds(),
                true,
                true,
                false);
    }

    private static TaskRepository.TaskEntry task(
            final int taskId,
            final String packageName,
            final String mode,
            final boolean visible,
            final boolean active) {
        return new TaskRepository.TaskEntry(
                taskId,
                taskId,
                2,
                packageName,
                packageName + "/.MainActivity",
                packageName + "/.MainActivity",
                mode,
                bounds(),
                false,
                visible,
                active);
    }

    private static Rect bounds() {
        final Rect bounds = new Rect();
        bounds.right = 1920;
        bounds.bottom = 1080;
        return bounds;
    }
}
