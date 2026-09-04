package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;

import org.junit.Test;

import java.util.List;

public final class DesktopTaskLaunchObservationTest {
    private static final int DISPLAY_ID = 7;
    private static final LaunchActivityIdentity TARGET =
            LaunchActivityIdentity.packageScoped("com.example.app", null);

    @Test
    public void acceptsMatchingStandardTaskAndPrefersReceiptTask() {
        final TaskRepository.TaskEntry other = task(
                20, "freeform", 1, true, true);
        final TaskRepository.TaskEntry preferred = task(
                21, "freeform", 1, true, false);

        final DesktopTaskLaunchObservation.Evaluation result =
                DesktopTaskLaunchObservation.evaluate(
                        snapshot(other, preferred),
                        TARGET,
                        DesktopLaunchMode.WINDOWED,
                        DISPLAY_ID,
                        preferred.taskId);

        assertNotNull(result.task);
        assertEquals(preferred.taskId, result.task.taskId);
        assertTrue(result.error.isEmpty());
    }

    @Test
    public void rejectsSpecialActivityType() {
        final DesktopTaskLaunchObservation.Evaluation result =
                DesktopTaskLaunchObservation.evaluate(
                        snapshot(task(21, "freeform", 2, true, true)),
                        TARGET,
                        DesktopLaunchMode.WINDOWED,
                        DISPLAY_ID,
                        21);

        assertNull(result.task);
        assertTrue(result.error.contains("activityType=2"));
    }

    @Test
    public void rejectsWrongExplicitWindowingMode() {
        final DesktopTaskLaunchObservation.Evaluation result =
                DesktopTaskLaunchObservation.evaluate(
                        snapshot(task(21, "fullscreen", 1, true, true)),
                        TARGET,
                        DesktopLaunchMode.WINDOWED,
                        DISPLAY_ID,
                        21);

        assertNull(result.task);
        assertTrue(result.error.contains("observed mode=fullscreen"));
    }

    @Test
    public void doesNotSubstituteAnotherMatchingTaskForReceiptTask() {
        final TaskRepository.TaskEntry matching = task(
                20, "freeform", 1, true, true);
        final TaskRepository.TaskEntry wrongReceiptTask = new TaskRepository.TaskEntry(
                21,
                21,
                DISPLAY_ID,
                "com.example.other",
                "com.example.other/.MainActivity",
                "com.example.other/.MainActivity",
                "freeform",
                new Rect(10, 20, 300, 400),
                1,
                false,
                true,
                false);

        final DesktopTaskLaunchObservation.Evaluation result =
                DesktopTaskLaunchObservation.evaluate(
                        snapshot(matching, wrongReceiptTask),
                        TARGET,
                        DesktopLaunchMode.WINDOWED,
                        DISPLAY_ID,
                        wrongReceiptTask.taskId);

        assertNull(result.task);
        assertTrue(result.error.contains("identity"));
    }

    @Test
    public void indirectSurfaceRequiresTopologyButNotFinalIdentity() {
        final TaskRepository.TaskEntry chooser = new TaskRepository.TaskEntry(
                31,
                31,
                DISPLAY_ID,
                "android",
                "android/.ResolverActivity",
                "android/.ResolverActivity",
                "freeform",
                new Rect(10, 20, 300, 400),
                1,
                false,
                true,
                true);

        final DesktopTaskLaunchObservation.Evaluation result =
                DesktopTaskLaunchObservation.evaluate(
                        snapshot(chooser),
                        null,
                        DesktopLaunchMode.WINDOWED,
                        DISPLAY_ID,
                        chooser.taskId);

        assertNotNull(result.task);
        assertEquals(chooser.taskId, result.task.taskId);
    }

    private static TaskRepository.Snapshot snapshot(
            final TaskRepository.TaskEntry... tasks) {
        return new TaskRepository.Snapshot(List.of(tasks), true, "");
    }

    private static TaskRepository.TaskEntry task(
            final int taskId,
            final String mode,
            final int activityType,
            final boolean visible,
            final boolean active) {
        return new TaskRepository.TaskEntry(
                taskId,
                taskId,
                DISPLAY_ID,
                "com.example.app",
                "com.example.app/com.example.app.MainActivity",
                "com.example.app/com.example.app.MainActivity",
                mode,
                new Rect(10, 20, 300, 400),
                activityType,
                false,
                visible,
                active);
    }
}
