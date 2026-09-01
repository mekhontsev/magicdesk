package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import android.graphics.Rect;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public final class PhoneOverviewTaskPolicyTest {
    private static final String MAGICDESK =
            "io.github.mekhontsev.magicdesk";
    private static final String PREVIOUS_HOME =
            "com.zte.mifavor.launcher";

    @Test
    public void selectsOnlyFullscreenPhoneApplicationTasks() {
        final TaskRepository.TaskEntry phoneApp = task(
                10, 10, 0, "com.example.phone", "fullscreen", false);
        final List<TaskRepository.TaskEntry> selected =
                PhoneOverviewTaskPolicy.select(
                        Arrays.asList(
                                phoneApp,
                                task(11, 11, 0, "com.example.floating",
                                        "freeform", false),
                                task(12, 12, 4, "com.example.external",
                                        "freeform", false),
                                task(13, 13, 0, MAGICDESK,
                                        "fullscreen", false),
                                task(14, 14, 0, PREVIOUS_HOME,
                                        "fullscreen", true)),
                        MAGICDESK,
                        PREVIOUS_HOME);

        assertEquals(1, selected.size());
        assertEquals(phoneApp, selected.get(0));
    }

    @Test
    public void keepsOnlyTopEntryForOneRootTask() {
        final List<TaskRepository.TaskEntry> selected =
                PhoneOverviewTaskPolicy.select(
                        Arrays.asList(
                                task(20, 22, 0, "com.example.top",
                                        "fullscreen", false),
                                task(20, 21, 0, "com.example.child",
                                        "fullscreen", false)),
                        MAGICDESK,
                        PREVIOUS_HOME);

        assertEquals(1, selected.size());
        assertEquals(22, selected.get(0).taskId);
    }

    private static TaskRepository.TaskEntry task(
            final int rootTaskId,
            final int taskId,
            final int displayId,
            final String packageName,
            final String mode,
            final boolean home) {
        return new TaskRepository.TaskEntry(
                rootTaskId,
                taskId,
                displayId,
                packageName,
                packageName + "/.MainActivity",
                packageName + "/.MainActivity",
                mode,
                new Rect(0, 0, 100, 100),
                home,
                true,
                false);
    }
}
