package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;

import org.junit.Test;

import java.util.Arrays;

public final class DesktopTaskSnapshotControllerTest {
    @Test
    public void desktopHostWinsOverNominallyVisibleFullscreenTaskBelowIt() {
        assertTrue(DesktopTaskSnapshotController.isDesktopHostForeground(
                Arrays.asList(desktopHost(true), app(true))));
    }

    @Test
    public void applicationAboveDesktopHostRemainsForeground() {
        assertFalse(DesktopTaskSnapshotController.isDesktopHostForeground(
                Arrays.asList(app(true), desktopHost(true))));
    }

    @Test
    public void taskAreaBackstopDoesNotHideForegroundDesktopHost() {
        assertTrue(DesktopTaskSnapshotController.isDesktopHostForeground(
                Arrays.asList(backstop(true), desktopHost(true))));
    }

    @Test
    public void taskAreaBackstopDoesNotHideForegroundApplication() {
        assertFalse(DesktopTaskSnapshotController.isDesktopHostForeground(
                Arrays.asList(backstop(true), app(true), desktopHost(true))));
    }

    @Test
    public void ordinaryHomeTaskIsNotDesktopHost() {
        assertFalse(DesktopTaskSnapshotController.isDesktopHostForeground(
                Arrays.asList(new TaskRepository.TaskEntry(
                        1,
                        1,
                        0,
                        "example.launcher",
                        "example.launcher/.HomeActivity",
                        "example.launcher/.HomeActivity",
                        "fullscreen",
                        new Rect(0, 0, 1920, 1080),
                        true,
                        true,
                        false))));
    }

    private static TaskRepository.TaskEntry desktopHost(
            final boolean visible) {
        return new TaskRepository.TaskEntry(
                10,
                11,
                2,
                BuildConfig.APPLICATION_ID,
                BuildConfig.APPLICATION_ID + "/.DesktopActivity",
                BuildConfig.APPLICATION_ID + "/.DesktopActivity",
                "fullscreen",
                new Rect(0, 0, 1920, 1080),
                true,
                visible,
                false);
    }

    private static TaskRepository.TaskEntry app(final boolean visible) {
        return new TaskRepository.TaskEntry(
                20,
                20,
                2,
                "example.app",
                "example.app/.MainActivity",
                "example.app/.MainActivity",
                "fullscreen",
                new Rect(0, 0, 1920, 1080),
                false,
                visible,
                true);
    }

    private static TaskRepository.TaskEntry backstop(
            final boolean visible) {
        return new TaskRepository.TaskEntry(
                30,
                30,
                2,
                BuildConfig.APPLICATION_ID,
                BuildConfig.APPLICATION_ID + "/.TaskAreaBackstopActivity",
                BuildConfig.APPLICATION_ID + "/.TaskAreaBackstopActivity",
                "fullscreen",
                new Rect(0, 0, 1920, 1080),
                true,
                visible,
                false);
    }
}
