package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;

import org.junit.Test;

import java.util.Arrays;
public final class DesktopTaskParkingControllerTest {
    @Test
    public void parkingIncludesLiveAppsAndBuiltInsButNotInfrastructure() {
        final TaskRepository.TaskEntry app = task(
                10,
                "org.example.app",
                "org.example.app/.MainActivity",
                "freeform",
                new Rect(500, 100, 1500, 900),
                true);
        final TaskRepository.TaskEntry files = task(
                11,
                BuildConfig.APPLICATION_ID,
                BuildConfig.APPLICATION_ID + "/.FileManagerActivity",
                "fullscreen",
                new Rect(0, 0, 2000, 1000),
                false);
        final TaskRepository.TaskEntry desktop = task(
                12,
                BuildConfig.APPLICATION_ID,
                BuildConfig.APPLICATION_ID + "/.DesktopActivity",
                "fullscreen",
                new Rect(0, 0, 2000, 1000),
                true);

        assertTrue(DesktopTaskParkingController.shouldParkTask(app));
        assertTrue(DesktopTaskParkingController.shouldParkTask(files));
        assertFalse(DesktopTaskParkingController.shouldParkTask(desktop));
    }

    @Test
    public void liveMatchRequiresSameTaskIdAndPackage() {
        final DesktopTaskParkingController.ParkedTask parked =
                new DesktopTaskParkingController.ParkedTask(
                        20,
                        "org.example.app",
                        false,
                        true,
                        null);
        final TaskRepository.TaskEntry live = task(
                20,
                "org.example.app",
                "org.example.app/.SecondActivity",
                "fullscreen",
                new Rect(0, 0, 2000, 1000),
                true);

        assertSame(live, DesktopTaskParkingController.findLiveTask(
                Arrays.asList(live), parked));
        assertNull(DesktopTaskParkingController.findLiveTask(
                Arrays.asList(task(
                        20,
                        "org.example.other",
                        "org.example.other/.MainActivity",
                        "fullscreen",
                        new Rect(0, 0, 2000, 1000),
                        true)),
                parked));
        assertNull(DesktopTaskParkingController.findLiveTask(
                Arrays.asList(task(
                        21,
                        "org.example.app",
                        "org.example.app/.MainActivity",
                        "fullscreen",
                        new Rect(0, 0, 2000, 1000),
                        true)),
                parked));
    }

    private static TaskRepository.TaskEntry task(
            final int taskId,
            final String packageName,
            final String component,
            final String mode,
            final Rect bounds,
            final boolean visible) {
        return new TaskRepository.TaskEntry(
                taskId,
                taskId,
                3,
                packageName,
                component,
                component,
                mode,
                bounds,
                false,
                visible,
                visible);
    }
}
