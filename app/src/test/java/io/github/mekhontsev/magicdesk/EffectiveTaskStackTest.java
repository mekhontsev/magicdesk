package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class EffectiveTaskStackTest {
    @Test
    public void activeTopFreeformDemotes() {
        final TaskRepository.TaskEntry target = task(
                10, "freeform", true, true);

        assertFalse(EffectiveTaskStack.shouldActivateTaskbarTarget(
                snapshot(target, host()), target, Collections.emptySet()));
    }

    @Test
    public void activeTopFullscreenDemotes() {
        final TaskRepository.TaskEntry target = task(
                10, "fullscreen", true, true);

        assertFalse(EffectiveTaskStack.shouldActivateTaskbarTarget(
                snapshot(target, host()), target, Collections.emptySet()));
    }

    @Test
    public void visibleBlockerMakesSyntheticActiveTaskActivate() {
        final TaskRepository.TaskEntry blocker = task(
                11, "freeform", true, false);
        final TaskRepository.TaskEntry target = task(
                10, "freeform", true, true);

        assertTrue(EffectiveTaskStack.shouldActivateTaskbarTarget(
                snapshot(blocker, target, host()),
                target,
                Collections.emptySet()));
    }

    @Test
    public void freeformAboveFullscreenMakesFullscreenActivate() {
        final TaskRepository.TaskEntry blocker = task(
                11, "freeform", true, true);
        final TaskRepository.TaskEntry target = task(
                10, "fullscreen", true, false);

        assertTrue(EffectiveTaskStack.shouldActivateTaskbarTarget(
                snapshot(blocker, host(), target),
                target,
                Collections.emptySet()));
    }

    @Test
    public void concealedTargetAlwaysActivates() {
        final TaskRepository.TaskEntry target = task(
                10, "fullscreen", true, true);

        assertTrue(EffectiveTaskStack.shouldActivateTaskbarTarget(
                snapshot(target, host()),
                target,
                Collections.singleton(Integer.valueOf(target.taskId))));
    }

    @Test
    public void pendingFreeformFocusOverridesStaleFullscreenActiveFlag() {
        final TaskRepository.TaskEntry fullscreen = task(
                10, "fullscreen", true, true);
        final TaskRepository.TaskEntry freeform = task(
                11, "freeform", true, false);

        assertTrue(EffectiveTaskStack.shouldActivateTaskbarTarget(
                snapshot(fullscreen, host(), freeform),
                fullscreen,
                Collections.emptySet(),
                freeform.taskId));
    }

    @Test
    public void repeatedPendingFocusDemotesBeforeSnapshotCatchesUp() {
        final TaskRepository.TaskEntry target = task(
                10, "freeform", true, false);
        final TaskRepository.TaskEntry staleActive = task(
                11, "fullscreen", true, true);

        assertFalse(EffectiveTaskStack.shouldActivateTaskbarTarget(
                snapshot(staleActive, host(), target),
                target,
                Collections.emptySet(),
                target.taskId));
    }

    @Test
    public void concealedAndInvisibleBlockersDoNotCoverForeground() {
        final TaskRepository.TaskEntry concealedBlocker = task(
                12, "freeform", true, false);
        final TaskRepository.TaskEntry invisibleBlocker = task(
                11, "freeform", false, false);
        final TaskRepository.TaskEntry target = task(
                10, "freeform", true, true);

        assertTrue(EffectiveTaskStack.isEffectiveForeground(
                snapshot(concealedBlocker, invisibleBlocker, target, host())
                        .tasks,
                target,
                Collections.singleton(
                        Integer.valueOf(concealedBlocker.taskId))));
    }

    @Test
    public void blockersPreserveTopFirstOrderAndIgnoreDesktopHost() {
        final TaskRepository.TaskEntry top = task(
                12, "freeform", true, false);
        final TaskRepository.TaskEntry lower = task(
                11, "fullscreen", true, false);
        final TaskRepository.TaskEntry target = task(
                10, "fullscreen", true, false);

        final List<TaskRepository.TaskEntry> blockers =
                EffectiveTaskStack.foregroundBlockersTopFirst(
                        snapshot(top, host(), lower, target).tasks,
                        target,
                        Collections.emptySet());

        assertTrue(blockers.equals(Arrays.asList(top, lower)));
    }

    @Test
    public void missingTargetHasNoActionableBlockers() {
        final TaskRepository.TaskEntry target = task(
                10, "fullscreen", false, false);

        assertTrue(EffectiveTaskStack.foregroundBlockersTopFirst(
                snapshot(task(11, "freeform", true, true), host()).tasks,
                target,
                Collections.emptySet()).isEmpty());
    }

    private static TaskRepository.Snapshot snapshot(
            final TaskRepository.TaskEntry... tasks) {
        return new TaskRepository.Snapshot(Arrays.asList(tasks), true, "");
    }

    private static TaskRepository.TaskEntry host() {
        return new TaskRepository.TaskEntry(
                99,
                99,
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
            final String mode,
            final boolean visible,
            final boolean active) {
        final String packageName = "com.example.task" + taskId;
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
        return new Rect(0, 0, 1920, 1080);
    }
}
