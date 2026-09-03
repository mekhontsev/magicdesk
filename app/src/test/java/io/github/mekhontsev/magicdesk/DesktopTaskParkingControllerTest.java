package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    @Test
    public void explicitParkingWinsOverOlderObservedFallback() {
        final DesktopTaskParkingController.ParkedTask exact =
                new DesktopTaskParkingController.ParkedTask(
                        20,
                        "org.example.app",
                        false,
                        true,
                        new RelativeWindowBounds(1000, 2000, 5000, 6000));
        final DesktopTaskParkingController.ParkedTask observed =
                new DesktopTaskParkingController.ParkedTask(
                        20,
                        "org.example.app",
                        false,
                        true,
                        new RelativeWindowBounds(3000, 4000, 5000, 6000));
        final Map<Integer, DesktopTaskParkingController.ParkedTask> parked =
                new LinkedHashMap<>();

        DesktopTaskParkingController.mergePreservedTasks(
                parked, Arrays.asList(exact), true);
        DesktopTaskParkingController.mergePreservedTasks(
                parked, Arrays.asList(observed), false);

        assertSame(exact, parked.get(Integer.valueOf(20)));
        assertEquals(1, parked.size());
    }

    @Test
    public void phoneParkingIncludesOnlyDesktopOwnedTasks() {
        final TaskRepository.TaskEntry desktopApp = task(
                30,
                "org.example.desktop",
                "org.example.desktop/.MainActivity",
                "freeform",
                new Rect(200, 100, 1200, 900),
                true);
        final TaskRepository.TaskEntry ordinaryPhoneApp = task(
                31,
                "org.example.phone",
                "org.example.phone/.MainActivity",
                "fullscreen",
                new Rect(0, 0, 2000, 1000),
                true);

        final List<DesktopTaskParkingController.ParkedTask> captured =
                DesktopTaskParkingController.captureTasks(
                        Arrays.asList(desktopApp, ordinaryPhoneApp),
                        rect(0, 0, 2000, 1000),
                        new HashSet<>(Arrays.asList(Integer.valueOf(30))));

        assertEquals(1, captured.size());
        assertEquals(30, captured.get(0).taskId);
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

    private static Rect rect(
            final int left,
            final int top,
            final int right,
            final int bottom) {
        final Rect rect = new Rect();
        rect.left = left;
        rect.top = top;
        rect.right = right;
        rect.bottom = bottom;
        return rect;
    }
}
