package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;

import org.junit.Test;

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
}
