package io.github.mekhontsev.magicdesk;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Display;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runs a manually requested black-box desktop test on an Android overlay display. */
final class DesktopSelfTestController {
    private static final String PACKAGE_NAME =
            "io.github.mekhontsev.magicdesk";
    private static final String FIXTURE_CLASS =
            PACKAGE_NAME + ".DesktopSelfTestActivity";
    private static final String DESKTOP_CLASS =
            PACKAGE_NAME + ".DesktopActivity";
    private static final int EXPECTED_WIDTH = 1920;
    private static final int EXPECTED_HEIGHT = 1080;
    private static final int EXPECTED_DENSITY = 160;
    private static final int WINDOWING_MODE_FULLSCREEN = 1;
    private static final int WINDOWING_MODE_FREEFORM = 5;
    private static final int RESIZE_EDGE_OUTSET_PX = 8;
    // Android 16 WMShell caption dimensions on the fixed 160 dpi display.
    private static final int CAPTION_BUTTON_CENTER_Y_PX = 20;
    private static final int MAXIMIZE_BUTTON_CENTER_FROM_RIGHT_PX = 82;
    private static final int SNAP_LEFT_CENTER_FROM_MENU_RIGHT_DP = 96;
    private static final int SNAP_RIGHT_CENTER_FROM_MENU_RIGHT_DP = 44;
    private static final int SNAP_BUTTON_CENTER_FROM_MENU_TOP_DP = 46;
    private static final int PLACEMENT_ALIGNMENT_TOLERANCE_PX = 100;
    private static final long STEP_TIMEOUT_MILLIS = 10_000L;
    private static final long POLL_MILLIS = 100L;
    private static final AtomicBoolean RUNNING = new AtomicBoolean();

    private DesktopSelfTestController() {
    }

    static boolean isRunning() {
        return RUNNING.get();
    }

    static DesktopSelfTestResult run(final Context context) {
        final DesktopSelfTestResult result =
                new DesktopSelfTestResult(System.currentTimeMillis());
        if (context == null) {
            result.add(DesktopSelfTestResult.State.FAIL,
                    "SELFTEST-001", "Self-test context", "unavailable");
            return finish(result, null);
        }
        final Context appContext = context.getApplicationContext();
        if (!RUNNING.compareAndSet(false, true)) {
            result.add(DesktopSelfTestResult.State.FAIL,
                    "SELFTEST-002", "Concurrent self-test",
                    "another desktop self-test is already running");
            return finish(result, appContext);
        }

        int displayId = Display.INVALID_DISPLAY;
        SimulatedDisplayLease lease = null;
        try {
            require(result, "API-SHELL-001", "Shizuku command service", () -> {
                final int uid = ShellAccess.connectAndGetUid();
                if (!ShellAccess.isSupportedServiceUid(uid)) {
                    throw new IOException("unsupported service uid=" + uid);
                }
                return "uid=" + uid;
            });
            if (!DesktopSelfTestCapabilityAudit.run(appContext, result)) {
                throw new AbortSelfTest();
            }
            requireNoActiveDesktop(result);
            requireNoConfiguredOverlay(result);
            requireNoStaleDesktopRepositories(result);

            lease = require(result,
                    "DISPLAY-001", "Create simulated display lease", () -> {
                        final SimulatedDisplayLease opened =
                                SimulatedDisplayLease.open();
                        return opened;
                    }, "owned Shizuku stream with automatic restoration");
            displayId = require(result,
                    "DISPLAY-002", "Create simulated display", () -> {
                        final int created = waitForOverlayDisplay();
                        if (created <= Display.DEFAULT_DISPLAY) {
                            throw new IOException(
                                    "Android did not create "
                                            + SimulatedDisplayLease.SPEC);
                        }
                        return Integer.valueOf(created);
                    }, null).intValue();
            verifyDisplayGeometry(appContext, displayId, result);

            final int targetDisplayId = displayId;
            require(result, "DESKTOP-001", "Launch desktop session", () -> {
                if (!DesktopSessionController.show(
                        DesktopDisplayTarget.simulated(targetDisplayId)).ready) {
                    throw new IOException("desktop did not become ready");
                }
                final TaskStackParser.Entry desktop = waitForTask(
                        targetDisplayId, DESKTOP_CLASS, null);
                return "display=" + targetDisplayId + ", task=" + desktop.taskId;
            });
            final TaskStackParser.Entry desktopTask = require(result,
                    "DESKTOP-002", "Configure desktop host", () -> {
                        final TaskStackParser.Entry task = waitForTask(
                                targetDisplayId, DESKTOP_CLASS,
                                entry -> "fullscreen".equals(entry.windowingMode));
                        return task;
                    }, "fullscreen host ready");
            verifyDesktopViewport(targetDisplayId, result);
            require(result, "DESKTOP-005", "Recreate desktop activity", () -> {
                final int previousHostIdentity =
                        DesktopRuntimeBridge.getDesktopHostIdentity(
                                targetDisplayId);
                if (previousHostIdentity == 0) {
                    throw new IOException("desktop activity is unavailable");
                }
                if (!DesktopRuntimeBridge.recreateShellOnDisplay(
                        targetDisplayId)) {
                    throw new IOException(
                            "desktop activity was unavailable for recreation");
                }
                final long deadline = SystemClock.uptimeMillis()
                        + STEP_TIMEOUT_MILLIS;
                do {
                    final int hostIdentity =
                            DesktopRuntimeBridge.getDesktopHostIdentity(
                                    targetDisplayId);
                    if (hostIdentity != 0
                            && hostIdentity != previousHostIdentity
                            && DesktopRuntimeBridge.isDesktopReadyOnDisplay(
                                    targetDisplayId)) {
                        final TaskStackParser.Entry recreated = waitForTask(
                                targetDisplayId,
                                DESKTOP_CLASS,
                                entry -> "fullscreen".equals(
                                        entry.windowingMode));
                        return "task=" + recreated.taskId
                                + ", host=" + hostIdentity;
                    }
                    SystemClock.sleep(POLL_MILLIS);
                } while (SystemClock.uptimeMillis() < deadline);
                throw new IOException(
                        "desktop activity did not become ready after recreation");
            });
            verifyDesktopWallpaper(targetDisplayId, result);
            require(result, "WINDOW-000", "Clear stale self-test windows", () -> {
                removeFixtureTasks();
                return "ready";
            });
            clearTextMarker(appContext);
            final String token = Long.toHexString(System.nanoTime());
            final Rect windowBounds = new Rect(160, 120, 960, 720);
            final DesktopTaskLaunchProbe.Observation initialLaunch = require(
                    result,
                    "WINDOW-001", "Launch test window through task display area",
                    () -> launchFixtureAndObserve(
                            targetDisplayId, token, windowBounds));
            final int targetFixtureTaskId = initialLaunch.taskId;
            result.add(initialLaunch.windowingMode == WINDOWING_MODE_FREEFORM
                            && equalsBounds(initialLaunch, windowBounds)
                            ? DesktopSelfTestResult.State.PASS
                            : DesktopSelfTestResult.State.FAIL,
                    "WINDOW-007", "Initial task window state",
                    initialLaunch.toString());
            require(result,
                    "WINDOW-010",
                    "Verify direct launch settles as freeform",
                    () -> {
                        final TaskStackParser.Entry task = waitForTask(
                                targetDisplayId,
                                FIXTURE_CLASS,
                                entry -> entry.taskId == targetFixtureTaskId
                                        && "freeform".equals(
                                                entry.windowingMode)
                                        && equalsBounds(
                                                entry.bounds,
                                                windowBounds));
                        return "task=" + task.taskId
                                + ", bounds=" + formatBounds(task.bounds);
                    });
            require(result,
                    "WINDOW-009",
                    "Move existing task to phone fullscreen",
                    () -> reopenTask(
                            Display.DEFAULT_DISPLAY,
                            targetFixtureTaskId,
                            null));
            require(result,
                    "WINDOW-008",
                    "Move phone task directly to external freeform",
                    () -> reopenTaskAsFreeform(
                            targetDisplayId,
                            targetFixtureTaskId,
                            desktopTask.taskId,
                            windowBounds));
            check(result,
                    "WINDOW-013",
                    "Keep desktop background rendered after task move",
                    () -> {
                        if (!DesktopRuntimeBridge.isDesktopWallpaperRendered(
                                targetDisplayId)) {
                            throw new IOException(
                                    "desktop wallpaper is not rendered");
                        }
                        return "rendered";
                    });
            require(result, "WINDOW-002", "Apply freeform bounds", () -> {
                ShellAccess.run(TaskRepository.createFreeformTransitionCommand(
                        targetDisplayId, targetFixtureTaskId, windowBounds));
                ShellAccess.run(TaskFocusCommands.createShellCommand(
                        Collections.singletonList(
                                Integer.valueOf(targetFixtureTaskId))));
                final TaskStackParser.Entry task = waitForTask(
                        targetDisplayId, FIXTURE_CLASS,
                        entry -> "freeform".equals(entry.windowingMode)
                                && entry.visible
                                && equalsBounds(entry.bounds, windowBounds));
                return formatBounds(task.bounds);
            });
            check(result, "CAPTION-001", "Verify native caption structure", () ->
                    inspectCaptionStructure(
                            targetFixtureTaskId, windowBounds));
            check(result, "INPUT-001", "Route input to simulated display", () -> {
                clearTextMarker(appContext);
                final int x = windowBounds.centerX();
                final int y = windowBounds.centerY();
                requirePointerHover(targetDisplayId, x, y);
                ShellAccess.run("/system/bin/input mouse -d "
                        + targetDisplayId + " tap " + x + " " + y);
                waitForTaskInputFocus(
                        targetDisplayId, targetFixtureTaskId);
                sendTestKey(targetDisplayId, "0");
                waitForMarker(appContext,
                        DesktopSelfTestActivity.TEXT_MARKER_FILE,
                        token + "|" + targetDisplayId + "|0",
                        targetDisplayId);
                return "tap=" + x + "," + y;
            });
            verifyNativeInputWindows(
                    result,
                    targetDisplayId,
                    targetFixtureTaskId,
                    windowBounds);
            verifyResizeCursor(
                    result,
                    targetDisplayId,
                    targetFixtureTaskId);
            require(result, "WINDOW-003", "Enter true fullscreen", () -> {
                ShellAccess.run(TaskRepository.createFullscreenTransitionCommand(
                        targetDisplayId, targetFixtureTaskId));
                final TaskStackParser.Entry task = waitForTask(
                        targetDisplayId, FIXTURE_CLASS,
                        entry -> "fullscreen".equals(entry.windowingMode));
                return "task=" + task.taskId;
            });
            require(result, "WINDOW-004", "Restore freeform window", () -> {
                ShellAccess.run(TaskRepository.createFreeformTransitionCommand(
                        targetDisplayId, targetFixtureTaskId, windowBounds));
                final TaskStackParser.Entry task = waitForTask(
                        targetDisplayId, FIXTURE_CLASS,
                        entry -> "freeform".equals(entry.windowingMode)
                                && equalsBounds(entry.bounds, windowBounds));
                return formatBounds(task.bounds);
            });
            check(result, "CAPTION-002", "Verify restored caption structure", () ->
                    inspectCaptionStructure(
                            targetFixtureTaskId, windowBounds));
            require(result, "WINDOW-005", "Minimize window behind desktop", () -> {
                ShellAccess.run(AppProcessCommand.run(
                        "io.github.mekhontsev.magicdesk.TaskWindowingCommand",
                        "minimize " + targetDisplayId + " "
                                + targetFixtureTaskId + " " + desktopTask.taskId));
                waitForWindowFocus(targetDisplayId, true);
                return "task=" + targetFixtureTaskId;
            });
            require(result, "WINDOW-006", "Restore minimized window", () -> {
                ShellAccess.run(AppProcessCommand.run(
                        "io.github.mekhontsev.magicdesk.TaskWindowingCommand",
                        "restore " + targetDisplayId + " " + targetFixtureTaskId));
                waitForTask(targetDisplayId, FIXTURE_CLASS,
                        entry -> "freeform".equals(entry.windowingMode));
                waitForWindowFocus(targetDisplayId, false);
                return "task=" + targetFixtureTaskId;
            });
            runTwoWindowFocusTests(
                    appContext,
                    result,
                    targetDisplayId,
                    targetFixtureTaskId,
                    token);
        } catch (AbortSelfTest ignored) {
            // The failing required step has already been added to the result.
        } catch (RuntimeException error) {
            result.add(DesktopSelfTestResult.State.FAIL,
                    "SELFTEST-003", "Unexpected self-test failure",
                    usefulMessage(error));
        } finally {
            cleanup(result, displayId, lease);
            RUNNING.set(false);
        }
        return finish(result, appContext);
    }

    private static void requireNoActiveDesktop(
            final DesktopSelfTestResult result) throws AbortSelfTest {
        final int activeDisplay = DesktopRuntimeBridge.getActiveDesktopDisplayId();
        if (activeDisplay >= 0) {
            failAndAbort(result, "SELFTEST-PRECONDITION-001",
                    "No active desktop session",
                    "close the desktop on display " + activeDisplay + " first");
        }
        try {
            final TaskStackParser.Entry desktop = findTaskOnAnyDisplay(
                    ShellAccess.run("/system/bin/cmd activity stack list"),
                    DESKTOP_CLASS);
            if (desktop != null) {
                failAndAbort(result, "SELFTEST-PRECONDITION-001",
                        "No active desktop session",
                        "close desktop task " + desktop.taskId
                                + " on display " + desktop.displayId + " first");
            }
        } catch (IOException error) {
            failAndAbort(result, "SELFTEST-PRECONDITION-001",
                    "Inspect desktop tasks", usefulMessage(error));
        }
        result.add(DesktopSelfTestResult.State.PASS,
                "SELFTEST-PRECONDITION-001", "No active desktop session", "ready");
    }

    private static void requireNoConfiguredOverlay(
            final DesktopSelfTestResult result) throws AbortSelfTest {
        try {
            final String previous = ShellAccess.run(
                    "/system/bin/settings get global "
                            + SimulatedDisplayLease.SETTING).trim();
            if (!previous.isEmpty() && !"null".equals(previous)) {
                failAndAbort(result, "SELFTEST-PRECONDITION-002",
                        "No existing simulated display",
                        SimulatedDisplayLease.SETTING + "=" + previous);
            }
            result.add(DesktopSelfTestResult.State.PASS,
                    "SELFTEST-PRECONDITION-002",
                    "No existing simulated display", "ready");
        } catch (IOException error) {
            failAndAbort(result, "SELFTEST-PRECONDITION-002",
                    "Inspect simulated display setting", usefulMessage(error));
        }
    }

    private static void requireNoStaleDesktopRepositories(
            final DesktopSelfTestResult result) throws AbortSelfTest {
        try {
            final Map<Integer, Set<Integer>> tasksByDisplay =
                    SystemUiDesktopRepositoryParser.parseTaskIdsByDisplay(
                            ShellAccess.run(
                                    PhoneDesktopTaskRecovery
                                            .repositoryDumpCommand()));
            tasksByDisplay.entrySet().removeIf(entry ->
                    entry.getKey().intValue() <= Display.DEFAULT_DISPLAY
                            || entry.getValue().isEmpty());
            if (!tasksByDisplay.isEmpty()) {
                failAndAbort(result, "SELFTEST-PRECONDITION-003",
                        "No stale external desktop tasks",
                        tasksByDisplay.toString());
            }
            result.add(DesktopSelfTestResult.State.PASS,
                    "SELFTEST-PRECONDITION-003",
                    "No stale external desktop tasks", "ready");
        } catch (IOException error) {
            failAndAbort(result, "SELFTEST-PRECONDITION-003",
                    "Inspect external desktop tasks", usefulMessage(error));
        }
    }

    private static void verifyDisplayGeometry(
            final Context context, final int displayId,
            final DesktopSelfTestResult result) throws AbortSelfTest {
        require(result, "DISPLAY-003", "Verify simulated display geometry", () -> {
            final DisplayManager manager = context.getSystemService(DisplayManager.class);
            if (manager == null) {
                throw new IOException("DisplayManager unavailable");
            }
            final long deadline = SystemClock.uptimeMillis() + STEP_TIMEOUT_MILLIS;
            do {
                final Display display = manager.getDisplay(displayId);
                if (display != null) {
                    final DisplayMetrics metrics = new DisplayMetrics();
                    display.getRealMetrics(metrics);
                    if (metrics.widthPixels == EXPECTED_WIDTH
                            && metrics.heightPixels == EXPECTED_HEIGHT
                            && metrics.densityDpi == EXPECTED_DENSITY) {
                        return metrics.widthPixels + "x" + metrics.heightPixels
                                + "/" + metrics.densityDpi;
                    }
                }
                SystemClock.sleep(POLL_MILLIS);
            } while (SystemClock.uptimeMillis() < deadline);
            throw new IOException("expected " + SimulatedDisplayLease.SPEC);
        });
    }

    private static void verifyDesktopViewport(
            final int displayId, final DesktopSelfTestResult result)
            throws AbortSelfTest {
        require(result, "DESKTOP-003", "Verify desktop viewport and taskbar", () -> {
            final long deadline = SystemClock.uptimeMillis() + STEP_TIMEOUT_MILLIS;
            do {
                final DesktopViewport viewport =
                        DesktopRuntimeBridge.getDesktopViewport(displayId);
                final Rect workArea =
                        DesktopRuntimeBridge.getDesktopWorkAreaBounds(displayId);
                if (viewport != null && workArea != null) {
                    final Rect display = viewport.displayBounds();
                    if (display.width() == EXPECTED_WIDTH
                            && display.height() == EXPECTED_HEIGHT
                            && workArea.left == display.left
                            && workArea.right == display.right
                            && workArea.bottom < display.bottom) {
                        return viewport + ", work=" + workArea.toShortString();
                    }
                }
                SystemClock.sleep(POLL_MILLIS);
            } while (SystemClock.uptimeMillis() < deadline);
            throw new IOException("desktop viewport did not settle");
        });
    }

    private static void verifyDesktopWallpaper(
            final int displayId,
            final DesktopSelfTestResult result) throws AbortSelfTest {
        require(result,
                "DESKTOP-004",
                "Render desktop background",
                () -> {
                    final long deadline = SystemClock.uptimeMillis()
                            + STEP_TIMEOUT_MILLIS;
                    do {
                        if (DesktopRuntimeBridge.isDesktopWallpaperRendered(
                                displayId)) {
                            return "rendered";
                        }
                        SystemClock.sleep(POLL_MILLIS);
                    } while (SystemClock.uptimeMillis() < deadline);
                    throw new IOException(
                            "desktop wallpaper did not finish rendering");
                });
    }

    private static DesktopTaskLaunchProbe.Observation launchFixtureAndObserve(
            final int displayId,
            final String token,
            final Rect bounds) throws IOException {
        final ComponentName component =
                new ComponentName(PACKAGE_NAME, FIXTURE_CLASS);
        try (DesktopTaskLaunchProbe probe =
                     DesktopTaskLaunchProbe.open(-1, component)) {
            final String output = ShellAccess.run(
                    TaskDisplayAreaLaunchCommand
                            .createSelfTestLaunchCommand(
                                    displayId, token, bounds));
            if (!output.contains("task-display-area-launch=")) {
                throw new IOException(output.trim());
            }
            final DesktopTaskLaunchProbe.Observation observation =
                    probe.awaitObservation();
            if (observation.displayId != displayId) {
                throw new IOException(
                        "test window launched on the wrong display: "
                                + observation);
            }
            return observation;
        }
    }

    private static DesktopTaskLaunchProbe.Observation reopenTaskAsFreeform(
            final int displayId,
            final int taskId,
            final int desktopTaskId,
            final Rect bounds) throws IOException {
        ShellAccess.run(TaskFocusCommands.createShellCommand(
                Collections.singletonList(Integer.valueOf(desktopTaskId))));
        waitForWindowFocus(displayId, true);
        return reopenTask(displayId, taskId, bounds);
    }

    private static DesktopTaskLaunchProbe.Observation reopenTask(
            final int displayId,
            final int taskId,
            final Rect bounds) throws IOException {
        final ComponentName component =
                new ComponentName(PACKAGE_NAME, FIXTURE_CLASS);
        final boolean freeform = bounds != null;
        final TaskStackParser.Entry currentTask = findTaskOnAnyDisplay(
                ShellAccess.run("/system/bin/cmd activity stack list"),
                FIXTURE_CLASS);
        if (currentTask == null || currentTask.taskId != taskId) {
            throw new IOException("task " + taskId + " is unavailable");
        }
        if (!freeform) {
            if (currentTask.displayId != displayId) {
                ShellAccess.run(
                        "/system/bin/cmd activity display move-stack "
                                + currentTask.rootTaskId + " " + displayId);
                waitForTask(
                        displayId,
                        FIXTURE_CLASS,
                        entry -> entry.taskId == taskId);
            }
            ShellAccess.run(TaskRepository.createFullscreenTransitionCommand(
                    displayId, taskId));
            final TaskStackParser.Entry fullscreenTask = waitForTask(
                    displayId,
                    FIXTURE_CLASS,
                    entry -> entry.taskId == taskId
                            && "fullscreen".equals(entry.windowingMode));
            return new DesktopTaskLaunchProbe.Observation(
                    taskId,
                    displayId,
                    WINDOWING_MODE_FULLSCREEN,
                    fullscreenTask.bounds.left,
                    fullscreenTask.bounds.top,
                    fullscreenTask.bounds.right,
                    fullscreenTask.bounds.bottom);
        }
        try (DesktopTaskLaunchProbe probe =
                     DesktopTaskLaunchProbe.open(taskId, component)) {
            final String output = ShellAccess.run(
                    TaskDisplayAreaLaunchCommand.createMoveCommand(
                            taskId,
                            currentTask.displayId,
                            displayId,
                            bounds));
            final String expectedOutput =
                    "task-freeform-move=" + taskId;
            if (!output.contains(expectedOutput)) {
                throw new IOException(output.trim());
            }
            final DesktopTaskLaunchProbe.Observation observation =
                    probe.awaitObservation();
            final int expectedMode = freeform
                    ? WINDOWING_MODE_FREEFORM : WINDOWING_MODE_FULLSCREEN;
            if (observation.taskId != taskId
                    || observation.displayId != displayId
                    || observation.windowingMode != expectedMode
                    || (freeform && !equalsBounds(observation, bounds))) {
                throw new IOException(
                        "unexpected task front-state: " + observation);
            }
            return observation;
        }
    }

    private static String inspectCaptionStructure(
            final int taskId, final Rect bounds) throws IOException {
        return ShellAccess.run(AppProcessCommand.run(
                "io.github.mekhontsev.magicdesk.TaskCaptionStructureCommand",
                taskId + " "
                        + bounds.left + " " + bounds.top + " "
                        + bounds.right + " " + bounds.bottom)).trim();
    }

    private static void verifyNativeInputWindows(
            final DesktopSelfTestResult result,
            final int displayId,
            final int taskId,
            final Rect bounds) {
        final String dump;
        try {
            dump = ShellAccess.run("/system/bin/dumpsys input");
        } catch (IOException error) {
            final String detail = usefulMessage(error);
            result.add(DesktopSelfTestResult.State.FAIL,
                    "INPUT-002", "Verify native caption input window", detail);
            result.add(DesktopSelfTestResult.State.FAIL,
                    "INPUT-003", "Verify native resize input window", detail);
            return;
        }
        check(result, "INPUT-002", "Verify native caption input window", () ->
                inspectCaptionInputWindow(dump, displayId, taskId, bounds));
        check(result, "INPUT-003", "Verify native resize input window", () ->
                inspectResizeInputWindow(dump, displayId, taskId, bounds));
    }

    private static void verifyResizeCursor(
            final DesktopSelfTestResult result,
            final int displayId,
            final int taskId) {
        final TaskStackParser.Entry task;
        try {
            task = waitForTask(displayId, FIXTURE_CLASS,
                    entry -> entry.taskId == taskId
                            && "freeform".equals(entry.windowingMode)
                            && entry.visible
                            && !entry.bounds.isEmpty());
        } catch (IOException error) {
            result.add(DesktopSelfTestResult.State.FAIL,
                    "INPUT-004", "Show native mouse resize cursor",
                    usefulMessage(error));
            return;
        }
        final int centerX = (task.bounds.left + task.bounds.right) / 2;
        final int centerY = (task.bounds.top + task.bounds.bottom) / 2;
        try (DesktopCursorTraceProbe probe = DesktopCursorTraceProbe.open()) {
            requirePointerHover(displayId, centerX, centerY);
            requirePointerHover(displayId,
                    task.bounds.right + RESIZE_EDGE_OUTSET_PX, centerY);
            SystemClock.sleep(POLL_MILLIS);
            final String transition = probe.readPointerTransition();
            if (transition == null) {
                result.add(DesktopSelfTestResult.State.NOT_TESTED,
                        "INPUT-004", "Show native mouse resize cursor",
                        "simulated display exposes no visual cursor state and "
                                + "WMShell did not expose a transition trace");
            } else if (!DesktopCursorTraceProbe.isPointerType(
                    transition,
                    DesktopCursorTraceProbe.HORIZONTAL_RESIZE_POINTER_TYPE)) {
                result.add(DesktopSelfTestResult.State.FAIL,
                        "INPUT-004", "Show native mouse resize cursor",
                        "unexpected cursor transition: " + transition);
            } else {
                result.add(DesktopSelfTestResult.State.PASS,
                    "INPUT-004", "Show native mouse resize cursor", transition);
            }
        } catch (IOException | RuntimeException error) {
            result.add(DesktopSelfTestResult.State.NOT_TESTED,
                    "INPUT-004", "Show native mouse resize cursor",
                    usefulMessage(error));
        }
    }

    private static void runTwoWindowFocusTests(
            final Context context,
            final DesktopSelfTestResult result,
            final int displayId,
            final int firstTaskId,
            final String firstToken) throws AbortSelfTest {
        final Rect leftBounds = new Rect(80, 100, 940, 900);
        final Rect rightBounds = new Rect(980, 100, 1840, 900);
        final String secondToken = Long.toHexString(System.nanoTime());
        final DesktopTaskLaunchProbe.Observation secondLaunch = require(
                result,
                "WINDOW-011",
                "Launch second freeform test window",
                () -> {
                    final DesktopTaskLaunchProbe.Observation observation =
                            launchFixtureAndObserve(
                                    displayId, secondToken, rightBounds);
                    if (observation.taskId == firstTaskId) {
                        throw new IOException(
                                "Android reused task " + firstTaskId);
                    }
                    return observation;
                });
        final int secondTaskId = secondLaunch.taskId;
        require(result, "WINDOW-012", "Place two freeform test windows", () -> {
            ShellAccess.run(TaskRepository.createBoundsTransactionCommand(
                    displayId, firstTaskId, leftBounds));
            ShellAccess.run(TaskRepository.createBoundsTransactionCommand(
                    displayId, secondTaskId, rightBounds));
            waitForTask(displayId, FIXTURE_CLASS,
                    entry -> entry.taskId == firstTaskId
                            && "freeform".equals(entry.windowingMode)
                            && entry.visible
                            && equalsBounds(entry.bounds, leftBounds));
            waitForTask(displayId, FIXTURE_CLASS,
                    entry -> entry.taskId == secondTaskId
                            && "freeform".equals(entry.windowingMode)
                            && entry.visible
                            && equalsBounds(entry.bounds, rightBounds));
            return "left=" + firstTaskId + ", right=" + secondTaskId;
        });

        check(result,
                "FOCUS-001",
                "Activate and focus right text window",
                () -> focusFieldThroughDesktop(
                        context,
                        displayId,
                        secondTaskId,
                        secondToken,
                        "1"));
        check(result,
                "FOCUS-002",
                "Activate and focus left text window",
                () -> focusFieldThroughMouse(
                        context,
                        displayId,
                        firstTaskId,
                        firstToken,
                        leftBounds,
                        "2"));
        check(result,
                "FOCUS-003",
                "Restore right text focus through desktop focus service",
                () -> focusFieldThroughDesktop(
                        context, displayId, secondTaskId, secondToken, "3"));
        check(result,
                "FOCUS-004",
                "Restore left text focus through desktop focus service",
                () -> focusFieldThroughDesktop(
                        context, displayId, firstTaskId, firstToken, "4"));
        check(result,
                "FOCUS-005",
                "Switch mouse focus back to right window",
                () -> focusFieldThroughMouse(
                        context, displayId, secondTaskId,
                        secondToken, rightBounds, "5"));

        runNativeCaptionPlacementFocusTests(
                context,
                result,
                displayId,
                firstTaskId,
                firstToken,
                secondTaskId,
                secondToken);
    }

    private static void runNativeCaptionPlacementFocusTests(
            final Context context,
            final DesktopSelfTestResult result,
            final int displayId,
            final int firstTaskId,
            final String firstToken,
            final int secondTaskId,
            final String secondToken) {
        final TaskStackParser.Entry left = captionSnap(
                context,
                result,
                "NATIVE-SNAP-001",
                "Place first window left through native caption",
                displayId,
                firstTaskId,
                true);
        final TaskStackParser.Entry right = captionSnap(
                context,
                result,
                "NATIVE-SNAP-002",
                "Place second window right through native caption",
                displayId,
                secondTaskId,
                false);
        if (left == null || right == null) {
            result.add(DesktopSelfTestResult.State.NOT_TESTED,
                    "FOCUS-006",
                    "Switch focus after native caption placement",
                    "native caption placement was unavailable");
            result.add(DesktopSelfTestResult.State.NOT_TESTED,
                    "FOCUS-007",
                    "Restore focus after native caption placement",
                    "native caption placement was unavailable");
            return;
        }
        final Rect leftBounds = toRect(left.bounds);
        final Rect rightBounds = toRect(right.bounds);
        check(result,
                "NATIVE-SNAP-003",
                "Verify native side-by-side placement",
                () -> inspectSideBySidePlacement(
                        leftBounds, rightBounds));
        check(result,
                "FOCUS-006",
                "Switch mouse focus after native caption placement",
                () -> focusFieldThroughMouse(
                        context,
                        displayId,
                        firstTaskId,
                        firstToken,
                        leftBounds,
                        "6"));
        check(result,
                "FOCUS-007",
                "Restore focus after native caption placement",
                () -> focusFieldThroughDesktop(
                        context,
                        displayId,
                        secondTaskId,
                        secondToken,
                        "7"));
    }

    private static TaskStackParser.Entry captionSnap(
            final Context context,
            final DesktopSelfTestResult result,
            final String code,
            final String label,
            final int displayId,
            final int taskId,
            final boolean left) {
        try {
            final TaskStackParser.Entry before = waitForTask(
                    displayId,
                    FIXTURE_CLASS,
                    task -> task.taskId == taskId
                            && "freeform".equals(task.windowingMode)
                            && task.visible);
            final Rect beforeBounds = toRect(before.bounds);
            openNativeMaximizeMenu(displayId, before.bounds);
            final TaskInputWindowParser.Entry menu = waitForMaximizeMenu(
                    displayId, taskId);
            // The detached SystemUI menu uses phone density even though its
            // caption belongs to the 160 dpi simulated display.
            final float menuDensity = defaultDisplayDensity(context);
            final int x = menu.frame.right - Math.round(menuDensity * (left
                    ? SNAP_LEFT_CENTER_FROM_MENU_RIGHT_DP
                    : SNAP_RIGHT_CENTER_FROM_MENU_RIGHT_DP));
            final int y = menu.frame.top
                    + Math.round(menuDensity
                            * SNAP_BUTTON_CENTER_FROM_MENU_TOP_DP);
            ShellAccess.run(pointerCommand(
                    "click " + displayId + " " + x + " " + y));
            final TaskStackParser.Entry placed;
            try {
                placed = waitForTask(
                        displayId,
                        FIXTURE_CLASS,
                        task -> task.taskId == taskId
                                && "freeform".equals(task.windowingMode)
                                && task.visible
                                && !equalsBounds(task.bounds, beforeBounds)
                                && isSnapped(task.bounds, left));
            } catch (IOException error) {
                throw new IOException(error.getMessage()
                        + "; menu=" + menu.frame
                        + ", click=" + x + "," + y);
            }
            result.add(DesktopSelfTestResult.State.PASS,
                    code,
                    label,
                    "task=" + taskId + ", bounds="
                            + formatBounds(placed.bounds));
            return placed;
        } catch (Exception error) {
            result.add(DesktopSelfTestResult.State.FAIL,
                    code, label, usefulMessage(error));
            return null;
        }
    }

    private static void openNativeMaximizeMenu(
            final int displayId,
            final TaskStackParser.Bounds bounds) throws IOException {
        final int x = bounds.right
                - MAXIMIZE_BUTTON_CENTER_FROM_RIGHT_PX;
        final int y = bounds.top + CAPTION_BUTTON_CENTER_Y_PX;
        requirePointerHover(displayId, x, y);
    }

    private static float defaultDisplayDensity(final Context context)
            throws IOException {
        final DisplayManager displayManager = context.getSystemService(
                DisplayManager.class);
        final Display defaultDisplay = displayManager == null
                ? null : displayManager.getDisplay(Display.DEFAULT_DISPLAY);
        if (defaultDisplay == null) {
            throw new IOException("default display is unavailable");
        }
        final float density = context.createDisplayContext(defaultDisplay)
                .getResources().getDisplayMetrics().density;
        if (density <= 0.0f) {
            throw new IOException("default display density is unavailable");
        }
        return density;
    }

    private static TaskInputWindowParser.Entry waitForMaximizeMenu(
            final int displayId,
            final int taskId) throws IOException {
        final long deadline = SystemClock.uptimeMillis()
                + STEP_TIMEOUT_MILLIS;
        do {
            final TaskInputWindowParser.Entry menu =
                    TaskInputWindowParser.findMaximizeMenu(
                            ShellAccess.run("/system/bin/dumpsys input"),
                            taskId);
            if (menu != null
                    && menu.displayId == displayId
                    && menu.hasInputChannel()
                    && menu.hasTouchableRegion()) {
                return menu;
            }
            SystemClock.sleep(POLL_MILLIS);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new IOException(
                "native maximize menu did not open for task " + taskId);
    }

    private static boolean isSnapped(
            final TaskStackParser.Bounds bounds,
            final boolean left) {
        if (bounds == null || bounds.isEmpty()) {
            return false;
        }
        final int midpoint = EXPECTED_WIDTH / 2;
        final int edgeTolerance = EXPECTED_WIDTH / 10;
        return left
                ? bounds.left <= edgeTolerance
                        && Math.abs(bounds.right - midpoint) <= edgeTolerance
                : bounds.right >= EXPECTED_WIDTH - edgeTolerance
                        && Math.abs(bounds.left - midpoint) <= edgeTolerance;
    }

    private static String inspectSideBySidePlacement(
            final Rect left,
            final Rect right) throws IOException {
        if (left == null || right == null
                || left.isEmpty() || right.isEmpty()
                || left.left >= right.left
                || left.right > right.left
                || Math.abs(left.top - right.top)
                        > PLACEMENT_ALIGNMENT_TOLERANCE_PX
                || Math.abs(left.bottom - right.bottom)
                        > PLACEMENT_ALIGNMENT_TOLERANCE_PX) {
            throw new IOException("unexpected native placement: left="
                    + left + ", right=" + right);
        }
        return "left=" + left + ", right=" + right;
    }

    private static String focusFieldThroughMouse(
            final Context context,
            final int displayId,
            final int taskId,
            final String token,
            final Rect bounds,
            final String digit) throws IOException {
        clearTextMarker(context);
        final int x = bounds.centerX();
        final int y = bounds.top + bounds.height() / 3;
        ShellAccess.run(pointerCommand(
                "click " + displayId + " " + x + " " + y));
        waitForTaskInputFocus(displayId, taskId);
        sendTestKey(displayId, digit);
        waitForMarker(context,
                DesktopSelfTestActivity.TEXT_MARKER_FILE,
                token + "|" + displayId + "|" + digit,
                displayId);
        return "token=" + token + ", click=" + x + "," + y;
    }

    private static String focusFieldThroughDesktop(
            final Context context,
            final int displayId,
            final int taskId,
            final String token,
            final String digit) throws IOException {
        clearTextMarker(context);
        focusTaskThroughDesktop(displayId, taskId);
        waitForFrontTask(displayId, taskId);
        waitForTaskInputFocus(displayId, taskId);
        sendTestKey(displayId, digit);
        waitForMarker(context,
                DesktopSelfTestActivity.TEXT_MARKER_FILE,
                token + "|" + displayId + "|" + digit,
                displayId);
        return "task=" + taskId + ", token=" + token;
    }

    private static void waitForTaskInputFocus(
            final int displayId, final int taskId) throws IOException {
        final long deadline = SystemClock.uptimeMillis()
                + STEP_TIMEOUT_MILLIS;
        do {
            final String dump = ShellAccess.run("/system/bin/dumpsys input");
            if (TaskInputWindowParser.isTaskFocused(
                    dump, displayId, taskId)) {
                return;
            }
            SystemClock.sleep(POLL_MILLIS);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new IOException("input focus did not settle on task "
                + taskId + " on display " + displayId);
    }

    private static void focusTaskThroughDesktop(
            final int displayId,
            final int taskId) throws IOException {
        final CountDownLatch complete = new CountDownLatch(1);
        final AtomicBoolean success = new AtomicBoolean();
        final StringBuilder message = new StringBuilder();
        DesktopTaskController.focusDesktopTask(
                displayId,
                taskId,
                action -> {
                    success.set(action.success);
                    message.append(action.message);
                    complete.countDown();
                });
        try {
            if (!complete.await(STEP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                throw new IOException("taskbar focus timed out for task " + taskId);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("taskbar focus interrupted", error);
        }
        if (!success.get()) {
            throw new IOException("taskbar focus failed: " + message);
        }
    }

    private static void sendTestKey(
            final int displayId,
            final String digit) throws IOException {
        if (digit == null || digit.length() != 1
                || digit.charAt(0) < '0' || digit.charAt(0) > '9') {
            throw new IllegalArgumentException("invalid test digit");
        }
        ShellAccess.run("/system/bin/input -d " + displayId
                + " keyevent KEYCODE_" + digit);
    }

    private static void clearTextMarker(final Context context) {
        context.deleteFile(DesktopSelfTestActivity.TEXT_MARKER_FILE);
    }

    private static void waitForMarker(
            final Context context,
            final String fileName,
            final String expected,
            final int displayId) throws IOException {
        final File marker = new File(context.getFilesDir(), fileName);
        final long deadline = SystemClock.uptimeMillis() + STEP_TIMEOUT_MILLIS;
        String actual;
        do {
            actual = readFile(marker);
            if (expected.equals(actual)) {
                return;
            }
            SystemClock.sleep(POLL_MILLIS);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new IOException("expected marker=" + expected
                + ", actual=" + actual
                + ", tasks=" + taskStateDetail(displayId));
    }

    private static String taskStateDetail(final int displayId) {
        try {
            final StringBuilder detail = new StringBuilder();
            for (final TaskStackParser.Entry task : TaskStackParser.parse(
                    ShellAccess.run("/system/bin/cmd activity stack list"))) {
                if (task.displayId == displayId && !task.isHome()) {
                    if (detail.length() > 0) {
                        detail.append(';');
                    }
                    detail.append(task.taskId)
                            .append('/')
                            .append(task.componentName)
                            .append('/')
                            .append(task.windowingMode)
                            .append('/')
                            .append(task.visible ? "visible" : "hidden")
                            .append('/')
                            .append(formatBounds(task.bounds));
                }
            }
            return detail.length() == 0 ? "none" : detail.toString();
        } catch (IOException ignored) {
            return "unavailable";
        }
    }

    private static String inspectCaptionInputWindow(
            final String dump,
            final int displayId,
            final int taskId,
            final Rect bounds) throws IOException {
        final TaskInputWindowParser.Entry entry = requireInputWindow(
                TaskInputWindowParser.findCaption(dump, taskId),
                displayId,
                "caption");
        final boolean displayCoordinates = entry.frame.left == bounds.left
                && entry.frame.top == bounds.top
                && entry.frame.right == bounds.right
                && entry.frame.bottom > entry.frame.top
                && entry.frame.bottom < bounds.bottom;
        final boolean taskCoordinates = entry.frame.left == 0
                && entry.frame.top == 0
                && entry.frame.right == bounds.width()
                && entry.frame.bottom > 0
                && entry.frame.bottom < bounds.height();
        if (!displayCoordinates && !taskCoordinates) {
            throw new IOException("caption input frame is misaligned: "
                    + entry.frame);
        }
        return inputWindowDetail(entry,
                displayCoordinates ? "display" : "task-local");
    }

    private static String inspectResizeInputWindow(
            final String dump,
            final int displayId,
            final int taskId,
            final Rect bounds) throws IOException {
        final TaskInputWindowParser.Entry entry = requireInputWindow(
                TaskInputWindowParser.findResize(dump, taskId),
                displayId,
                "resize");
        if (!entry.hasConfig("SPY")) {
            throw new IOException("resize input window is not a spy window");
        }
        final boolean displayCoordinates = entry.frame.left == bounds.left
                && entry.frame.top == bounds.top
                && entry.frame.right == bounds.right
                && entry.frame.bottom == bounds.bottom;
        final boolean taskCoordinates = entry.frame.left == 0
                && entry.frame.top == 0
                && entry.frame.right == bounds.width()
                && entry.frame.bottom == bounds.height();
        if (!displayCoordinates && !taskCoordinates) {
            throw new IOException("resize input frame is misaligned: "
                    + entry.frame);
        }
        return inputWindowDetail(entry,
                displayCoordinates ? "display" : "task-local");
    }

    private static TaskInputWindowParser.Entry requireInputWindow(
            final TaskInputWindowParser.Entry entry,
            final int displayId,
            final String label) throws IOException {
        if (entry == null) {
            throw new IOException(label + " input window is absent");
        }
        if (entry.displayId != displayId) {
            throw new IOException(label + " input window is on display "
                    + entry.displayId + ", expected " + displayId);
        }
        if (entry.hasConfig("NOT_VISIBLE")) {
            throw new IOException(label + " input window is not visible");
        }
        if (!entry.hasConfig("TRUSTED_OVERLAY")) {
            throw new IOException(label + " input window is not trusted");
        }
        if (!entry.hasInputChannel()) {
            throw new IOException(label + " input channel is unavailable");
        }
        if (!entry.hasTouchableRegion()) {
            throw new IOException(label + " touchable region is empty");
        }
        return entry;
    }

    private static String inputWindowDetail(
            final TaskInputWindowParser.Entry entry,
            final String coordinates) {
        return "display=" + entry.displayId
                + ", frame=" + entry.frame
                + ", coordinates=" + coordinates
                + ", config=" + entry.inputConfig;
    }

    private static void requirePointerHover(
            final int displayId,
            final int x,
            final int y) throws IOException {
        ShellAccess.run(pointerCommand(
                "hover " + displayId + " " + x + " " + y));
    }

    private static String pointerCommand(final String arguments) {
        return AppProcessCommand.run(
                "io.github.mekhontsev.magicdesk.DesktopPointerCommand",
                arguments);
    }

    private static TaskStackParser.Entry waitForTask(
            final int displayId, final String className,
            final TaskPredicate predicate) throws IOException {
        final long deadline = SystemClock.uptimeMillis() + STEP_TIMEOUT_MILLIS;
        do {
            final TaskStackParser.Entry task = findTask(
                    ShellAccess.run("/system/bin/cmd activity stack list"),
                    displayId, className, predicate);
            if (task != null) {
                return task;
            }
            SystemClock.sleep(POLL_MILLIS);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new IOException("task " + className
                + " did not reach the expected state on display " + displayId);
    }

    private static TaskStackParser.Entry waitForFrontTask(
            final int displayId,
            final int taskId) throws IOException {
        final long deadline = SystemClock.uptimeMillis()
                + STEP_TIMEOUT_MILLIS;
        do {
            final TaskStackParser.Entry front = findFrontTask(
                    ShellAccess.run("/system/bin/cmd activity stack list"),
                    displayId);
            if (front != null && front.taskId == taskId) {
                return front;
            }
            SystemClock.sleep(POLL_MILLIS);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new IOException("task " + taskId
                + " did not receive front focus on display " + displayId);
    }

    static TaskStackParser.Entry findFrontTask(
            final String stack,
            final int displayId) {
        for (final TaskStackParser.Entry task : TaskStackParser.parse(stack)) {
            if (task.displayId == displayId
                    && task.visible
                    && !task.isHome()) {
                return task;
            }
        }
        return null;
    }

    private static void waitForWindowFocus(
            final int displayId,
            final boolean desktopFocused) throws IOException {
        final long deadline = SystemClock.uptimeMillis() + STEP_TIMEOUT_MILLIS;
        do {
            if (DesktopRuntimeBridge.isDesktopWindowFocused(displayId)
                    == desktopFocused) {
                return;
            }
            SystemClock.sleep(POLL_MILLIS);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new IOException(desktopFocused
                ? "desktop window did not receive focus"
                : "restored window did not receive focus");
    }

    static TaskStackParser.Entry findTask(
            final String stack, final int displayId, final String className) {
        return findTask(stack, displayId, className, null);
    }

    static TaskStackParser.Entry findTask(
            final String stack,
            final int displayId,
            final String className,
            final TaskPredicate predicate) {
        for (final TaskStackParser.Entry task : TaskStackParser.parse(stack)) {
            if (task.displayId == displayId
                    && (hasClass(task.componentName, className)
                            || hasClass(task.topActivityName, className))
                    && (predicate == null || predicate.test(task))) {
                return task;
            }
        }
        return null;
    }

    private static void cleanup(
            final DesktopSelfTestResult result,
            final int displayId,
            final SimulatedDisplayLease lease) {
        final StringBuilder detail = new StringBuilder();
        boolean clean = true;
        if (ShellAccess.isReady()) {
            try {
                DesktopSelfTestActivity.finishActiveTask();
                removeFixtureTasks();
                waitForTaskAbsent(FIXTURE_CLASS);
            } catch (IOException error) {
                clean = false;
                detail.append("fixture removal: ")
                        .append(usefulMessage(error)).append("; ");
            }
        }
        if (displayId > Display.DEFAULT_DISPLAY) {
            try {
                closeDesktopSession(displayId);
            } catch (IOException error) {
                clean = false;
                detail.append("desktop close: ")
                        .append(usefulMessage(error)).append("; ");
            }
            if (ShellAccess.isReady()) {
                try {
                    waitForTaskAbsent(DESKTOP_CLASS);
                    waitForDesktopRepositoryEmpty(displayId);
                } catch (IOException error) {
                    clean = false;
                    detail.append("desktop task quiescence: ")
                            .append(usefulMessage(error)).append("; ");
                }
            }
        }
        if (lease != null) {
            try {
                lease.close();
            } catch (IOException error) {
                clean = false;
                detail.append("display lease: ")
                        .append(usefulMessage(error)).append("; ");
            }
        }
        if (displayId > Display.DEFAULT_DISPLAY) {
            final long deadline = SystemClock.uptimeMillis() + STEP_TIMEOUT_MILLIS;
            boolean removed = false;
            do {
                if (!ConsoleDisplayController.displayExists(displayId)) {
                    removed = true;
                    break;
                }
                SystemClock.sleep(POLL_MILLIS);
            } while (SystemClock.uptimeMillis() < deadline);
            if (!removed) {
                clean = false;
                detail.append("display ").append(displayId)
                        .append(" remained; ");
            }
            if (ShellAccess.isReady()) {
                final String secondaryHomeClass = PhoneHomeComponents.resolve(
                        MagicDeskApplication.applicationContext())
                        .firstSecondaryClassName();
                if (!secondaryHomeClass.isEmpty()) {
                    try {
                        waitForTaskAbsentOnDisplay(
                                Display.DEFAULT_DISPLAY,
                                secondaryHomeClass);
                    } catch (IOException error) {
                        clean = false;
                        detail.append("phone launcher cleanup: ")
                                .append(usefulMessage(error)).append("; ");
                    }
                }
                try {
                    waitForDesktopRepositoryEmpty(displayId);
                } catch (IOException error) {
                    clean = false;
                    detail.append("desktop repository cleanup: ")
                            .append(usefulMessage(error)).append("; ");
                }
            }
        }
        if (ShellAccess.isReady()) {
            try {
                removeFixtureTasks();
            } catch (IOException error) {
                clean = false;
                detail.append("stale fixture cleanup: ")
                        .append(usefulMessage(error)).append("; ");
            }
            try {
                final String configured = ShellAccess.run(
                        "/system/bin/settings get global "
                                + SimulatedDisplayLease.SETTING).trim();
                if (!configured.isEmpty() && !"null".equals(configured)) {
                    clean = false;
                    detail.append(SimulatedDisplayLease.SETTING).append('=')
                            .append(configured).append("; ");
                }
            } catch (IOException error) {
                clean = false;
                detail.append("setting verification: ")
                        .append(usefulMessage(error)).append("; ");
            }
        }
        result.add(clean ? DesktopSelfTestResult.State.PASS
                        : DesktopSelfTestResult.State.FAIL,
                "CLEANUP-001", "Restore simulated-display state",
                clean ? "complete" : detail.toString());
    }

    private static void removeFixtureTasks() throws IOException {
        final String stack = ShellAccess.run("/system/bin/cmd activity stack list");
        for (final TaskStackParser.Entry task : TaskStackParser.parse(stack)) {
            if (!hasClass(task.componentName, FIXTURE_CLASS)
                    && !hasClass(task.topActivityName, FIXTURE_CLASS)) {
                continue;
            }
            ShellAccess.run(AppProcessCommand.run(
                    "io.github.mekhontsev.magicdesk.TaskControlCommand",
                    "remove " + task.taskId));
        }
    }

    private static void closeDesktopSession(final int displayId)
            throws IOException {
        final CountDownLatch complete = new CountDownLatch(1);
        final AtomicBoolean success = new AtomicBoolean();
        ConsoleModeSwitcher.closeDesktop(
                displayId,
                DesktopDisplayTarget.Kind.SIMULATED,
                false,
                closed -> {
                    success.set(closed);
                    complete.countDown();
                });
        try {
            if (!complete.await(STEP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                throw new IOException("desktop close timed out");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("desktop close interrupted", error);
        }
        if (!success.get()) {
            throw new IOException("desktop close failed");
        }
    }

    private static int waitForOverlayDisplay() throws IOException {
        final long deadline = SystemClock.uptimeMillis() + STEP_TIMEOUT_MILLIS;
        do {
            final int displayId = ConsoleDisplayController.findFirstDisplayId(
                    ShellAccess.run(
                            "/system/bin/cmd display get-displays"
                                    + " --ids-only --type overlay"));
            if (displayId > Display.DEFAULT_DISPLAY) {
                return displayId;
            }
            SystemClock.sleep(POLL_MILLIS);
        } while (SystemClock.uptimeMillis() < deadline);
        return -1;
    }

    private static void waitForTaskAbsent(final String className)
            throws IOException {
        final long deadline = SystemClock.uptimeMillis() + STEP_TIMEOUT_MILLIS;
        do {
            final TaskStackParser.Entry task = findTaskOnAnyDisplay(
                    ShellAccess.run("/system/bin/cmd activity stack list"),
                    className);
            if (task == null) {
                return;
            }
            SystemClock.sleep(POLL_MILLIS);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new IOException("task " + className + " remained after close");
    }

    private static void waitForTaskAbsentOnDisplay(
            final int displayId,
            final String className) throws IOException {
        final long deadline = SystemClock.uptimeMillis() + STEP_TIMEOUT_MILLIS;
        do {
            final TaskStackParser.Entry task = findTask(
                    ShellAccess.run("/system/bin/cmd activity stack list"),
                    displayId,
                    className);
            if (task == null) {
                return;
            }
            SystemClock.sleep(POLL_MILLIS);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new IOException("task " + className
                + " remained on display " + displayId + " after close");
    }

    private static void waitForDesktopRepositoryEmpty(
            final int displayId) throws IOException {
        final long deadline = SystemClock.uptimeMillis() + STEP_TIMEOUT_MILLIS;
        do {
            final String repository = ShellAccess.run(
                    PhoneDesktopTaskRecovery.repositoryDumpCommand());
            if (SystemUiDesktopRepositoryParser.parseTaskIds(
                    repository, displayId).isEmpty()) {
                return;
            }
            SystemClock.sleep(POLL_MILLIS);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new IOException("SystemUI retained tasks for display "
                + displayId);
    }

    private static TaskStackParser.Entry findTaskOnAnyDisplay(
            final String stack, final String className) {
        for (final TaskStackParser.Entry task : TaskStackParser.parse(stack)) {
            if (hasClass(task.componentName, className)
                    || hasClass(task.topActivityName, className)) {
                return task;
            }
        }
        return null;
    }

    private static <T> T require(
            final DesktopSelfTestResult result,
            final String code,
            final String label,
            final CheckedSupplier<T> operation) throws AbortSelfTest {
        return require(result, code, label, operation, null);
    }

    private static <T> T require(
            final DesktopSelfTestResult result,
            final String code,
            final String label,
            final CheckedSupplier<T> operation,
            final String successDetail) throws AbortSelfTest {
        try {
            final T value = operation.run();
            result.add(DesktopSelfTestResult.State.PASS,
                    code, label,
                    successDetail == null ? String.valueOf(value) : successDetail);
            return value;
        } catch (Exception error) {
            failAndAbort(result, code, label, usefulMessage(error));
            throw new AssertionError("unreachable");
        }
    }

    private static <T> void check(
            final DesktopSelfTestResult result,
            final String code,
            final String label,
            final CheckedSupplier<T> operation) {
        try {
            result.add(DesktopSelfTestResult.State.PASS,
                    code, label, String.valueOf(operation.run()));
        } catch (Exception error) {
            result.add(DesktopSelfTestResult.State.FAIL,
                    code, label, usefulMessage(error));
        }
    }

    private static void failAndAbort(
            final DesktopSelfTestResult result,
            final String code,
            final String label,
            final String detail) throws AbortSelfTest {
        result.add(DesktopSelfTestResult.State.FAIL, code, label, detail);
        throw new AbortSelfTest();
    }

    private static DesktopSelfTestResult finish(
            final DesktopSelfTestResult result, final Context context) {
        result.finish(System.currentTimeMillis());
        result.save(context);
        if (result.hasFailures()) {
            CompatibilityDiagnostics.record(
                    "SELFTEST-004",
                    "The built-in desktop self-test reported a failure",
                    result.summary());
        }
        return result;
    }

    private static boolean hasClass(
            final String component, final String className) {
        if (component == null || className == null) {
            return false;
        }
        final int separator = component.indexOf('/');
        if (separator < 0 || separator + 1 >= component.length()) {
            return false;
        }
        final String activity = component.substring(separator + 1);
        return className.equals(activity)
                || (activity.startsWith(".")
                        && className.equals(PACKAGE_NAME + activity));
    }

    private static boolean equalsBounds(
            final TaskStackParser.Bounds actual, final Rect expected) {
        return actual != null
                && actual.left == expected.left
                && actual.top == expected.top
                && actual.right == expected.right
                && actual.bottom == expected.bottom;
    }

    private static boolean equalsBounds(
            final DesktopTaskLaunchProbe.Observation actual,
            final Rect expected) {
        return actual != null
                && expected != null
                && actual.left == expected.left
                && actual.top == expected.top
                && actual.right == expected.right
                && actual.bottom == expected.bottom;
    }

    private static Rect toRect(final TaskStackParser.Bounds bounds) {
        return bounds == null ? null : new Rect(
                bounds.left, bounds.top, bounds.right, bounds.bottom);
    }

    private static String formatBounds(final TaskStackParser.Bounds bounds) {
        return "[" + bounds.left + "," + bounds.top + "]["
                + bounds.right + "," + bounds.bottom + "]";
    }

    private static String readFile(final File file) {
        if (file == null || !file.isFile()) {
            return "";
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            final String line = reader.readLine();
            return line == null ? "" : line.trim();
        } catch (IOException ignored) {
            return "";
        }
    }

    private static String usefulMessage(final Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        final String message = current.getMessage();
        return message == null || message.isEmpty()
                ? current.getClass().getSimpleName() : message;
    }

    interface CheckedSupplier<T> {
        T run() throws Exception;
    }

    interface TaskPredicate {
        boolean test(TaskStackParser.Entry task);
    }

    private static final class AbortSelfTest extends Exception {
        private static final long serialVersionUID = 1L;
    }
}
