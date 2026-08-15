package io.github.mekhontsev.magicdesk;

import static io.github.mekhontsev.magicdesk.DesktopSelfTestSteps.check;
import static io.github.mekhontsev.magicdesk.DesktopSelfTestSteps.require;
import static io.github.mekhontsev.magicdesk.DesktopSelfTestSteps.usefulMessage;
import static io.github.mekhontsev.magicdesk.DesktopSelfTestTasks.POLL_MILLIS;
import static io.github.mekhontsev.magicdesk.DesktopSelfTestTasks.STEP_TIMEOUT_MILLIS;
import static io.github.mekhontsev.magicdesk.DesktopSelfTestTasks.findTaskOnAnyDisplay;
import static io.github.mekhontsev.magicdesk.DesktopSelfTestTasks.waitForFrontTask;
import static io.github.mekhontsev.magicdesk.DesktopSelfTestTasks.waitForTask;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Display;

import java.io.IOException;
import java.util.Collections;

import io.github.mekhontsev.magicdesk.DesktopSelfTestSteps.AbortSelfTest;
import io.github.mekhontsev.magicdesk.DesktopSelfTestSteps.CheckedSupplier;

/** Exercises window lifecycle, placement, and display transfers. */
final class DesktopSelfTestWindowSuite {
    private static final String PACKAGE_NAME =
            DesktopSelfTestComponents.PACKAGE_NAME;
    private static final String FIXTURE_CLASS =
            DesktopSelfTestComponents.FIXTURE_CLASS;
    private static final String DESKTOP_CLASS =
            DesktopSelfTestComponents.DESKTOP_CLASS;
    private static final int SIMULATED_WIDTH = 1920;
    private static final int SIMULATED_HEIGHT = 1080;
    private static final int SIMULATED_DENSITY = 160;
    private static final int WINDOWING_MODE_FULLSCREEN = 1;
    private static final int WINDOWING_MODE_FREEFORM = 5;

    private DesktopSelfTestWindowSuite() {
    }

    static void run(
            final Context appContext,
            final DesktopSelfTestTarget target,
            final int displayId,
            final DesktopSelfTestResult result) throws AbortSelfTest {
        verifyDisplayGeometry(appContext, displayId, target, result);

        final int targetDisplayId = displayId;
        DesktopSelfTestPhoneUiObserver.begin(targetDisplayId);
        samplePhoneUiBestEffort();
        require(result, "DESKTOP-001", "Prepare desktop session", () -> {
            if (target == DesktopSelfTestTarget.SIMULATED) {
                // Exercise the same display policy as a user-started session,
                // including profiles and the phone-side touchpad.
                DesktopDisplayDrivers
                        .forKind(DesktopDisplayTarget.Kind.SIMULATED)
                        .show(null, targetDisplayId);
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
        final DisplayCaptureSource captureSource =
                DesktopDisplayDrivers.captureSource(
                targetDisplayId);
        DesktopSelfTestPhoneUiObserver.refreshTouchpadExpectation(
                targetDisplayId);
        samplePhoneUiBestEffort();
        final DesktopSelfTestGeometry geometry = verifyDesktopViewport(
                appContext, targetDisplayId, result);
        verifyDesktopWallpaper(targetDisplayId, result);
        DesktopSelfTestHostObserver.markReady();
        require(result, "WINDOW-000", "Clear stale self-test windows", () -> {
            DesktopSelfTestCleanup.removeFixtureTasks();
            return "ready";
        });
        final SurfaceReferenceResult surfaceReference =
                target == DesktopSelfTestTarget.PHONE
                        ? SurfaceReferenceResult.unavailable(
                                "the selected desktop uses display 0")
                        : captureSurfaceReference(
                                captureSource, geometry);
        DesktopSelfTestFixtureState.clearLaunchMarkers(appContext);
        final String token = Long.toHexString(System.nanoTime());
        final Rect requestedWindowBounds = geometry.primaryWindow();
        final DesktopSelfTestInputSuite.CaptionReference
                requestedCaptionReference =
                DesktopSelfTestInputSuite.captureCaptionReference(
                        captureSource,
                        requestedWindowBounds,
                        geometry);
        final DesktopTaskLaunchProbe.Observation initialLaunch = require(
                result,
                "WINDOW-001", "Launch test window directly as freeform",
                () -> preservePhoneTouchpad(() -> launchFixtureAndObserve(
                        targetDisplayId,
                        token,
                        requestedWindowBounds)));
        final int targetFixtureTaskId = initialLaunch.taskId;
        DesktopSelfTestPhoneUiObserver.allowPhoneFixtureTask(
                targetFixtureTaskId);
        check(result,
                "WINDOW-007",
                "Initial rendered task window state",
                () -> {
                    final String expected = token + "|"
                            + targetDisplayId + "|freeform";
                    DesktopSelfTestFixtureState.awaitFirstFrame(
                            appContext, token, targetDisplayId);
                    return "first-frame=" + expected
                            + ", first-callback=" + initialLaunch
                            + ", requested="
                            + formatBounds(requestedWindowBounds);
                });
        final TaskStackParser.Entry settledWindow = require(result,
                "WINDOW-010",
                "Verify direct launch settles as freeform",
                () -> {
                    final TaskStackParser.Entry task = waitForTask(
                            targetDisplayId,
                            FIXTURE_CLASS,
                            entry -> entry.taskId == targetFixtureTaskId
                                    && "freeform".equals(
                                            entry.windowingMode)
                                    && geometry.containsWindow(
                                            toRect(entry.bounds)));
                    return task;
                });
        final Rect windowBounds = toRect(settledWindow.bounds);
        final DesktopSelfTestGeometry settledGeometry =
                geometry.withObservedWindow(windowBounds);
        final DesktopSelfTestInputSuite.CaptionReference captionReference =
                DesktopSelfTestInputSuite.alignCaptionReference(
                        requestedCaptionReference,
                        windowBounds,
                        settledGeometry);
        if (target == DesktopSelfTestTarget.PHONE) {
            result.add(DesktopSelfTestResult.State.NOT_TESTED,
                    "WINDOW-009",
                    "Move existing task to phone fullscreen",
                    "the selected desktop already uses display 0");
            result.add(DesktopSelfTestResult.State.NOT_TESTED,
                    "WINDOW-008",
                    "Move phone task directly to external freeform",
                    "an external display was not selected");
            result.add(DesktopSelfTestResult.State.NOT_TESTED,
                    "WINDOW-014",
                    "Preserve desktop surface during task transfer",
                    "an external display was not selected");
        } else {
            require(result,
                    "WINDOW-009",
                    "Move existing task to phone fullscreen",
                    () -> preservePhoneTouchpad(() -> reopenTask(
                            Display.DEFAULT_DISPLAY,
                            targetFixtureTaskId,
                            null)));
            samplePhoneUiBestEffort();
            final TaskTransferObservation taskTransfer = require(result,
                    "WINDOW-008",
                    "Move phone task directly to external freeform",
                    () -> preservePhoneTouchpad(() -> observeTaskTransfer(
                            targetDisplayId,
                            targetFixtureTaskId,
                            desktopTask.taskId,
                            windowBounds,
                            surfaceReference)));
            samplePhoneUiBestEffort();
            if (!taskTransfer.probeError.isEmpty()) {
                result.add(DesktopSelfTestResult.State.NOT_TESTED,
                        "WINDOW-014",
                        "Preserve desktop surface during task transfer",
                        taskTransfer.probeError);
            } else {
                result.add(!taskTransfer.surfaceChanged
                            && taskTransfer.firstFront.windowingMode
                                    == WINDOWING_MODE_FREEFORM
                            && taskTransfer.firstFront.displayId
                                    == targetDisplayId
                            && equalsBounds(
                                    taskTransfer.firstFront,
                                    windowBounds)
                            ? DesktopSelfTestResult.State.PASS
                            : DesktopSelfTestResult.State.FAIL,
                    "WINDOW-014",
                    "Preserve desktop surface during task transfer",
                    "first-front=" + taskTransfer.firstFront
                            + ", pixels=" + taskTransfer.pixelSamples
                            + ", requested="
                            + formatBounds(windowBounds));
            }
        }
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
                    targetDisplayId,
                    Collections.singletonList(
                            Integer.valueOf(targetFixtureTaskId))));
            final TaskStackParser.Entry task = waitForTask(
                    targetDisplayId, FIXTURE_CLASS,
                    entry -> "freeform".equals(entry.windowingMode)
                            && entry.visible
                            && equalsBounds(entry.bounds, windowBounds));
            waitForFrontTask(
                    targetDisplayId, targetFixtureTaskId);
            return formatBounds(task.bounds);
        });
        DesktopSelfTestInputSuite.runInitialWindowChecks(
                result,
                appContext,
                targetDisplayId,
                captureSource,
                targetFixtureTaskId,
                token,
                windowBounds,
                settledGeometry,
                captionReference);
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
        DesktopSelfTestInputSuite.verifyCaptionStructure(
                result,
                "CAPTION-002",
                "Verify restored caption structure",
                targetFixtureTaskId,
                windowBounds);
        DesktopSelfTestInputSuite.verifyCaptionSurface(
                result,
                "CAPTION-SURFACE-002",
                "Verify restored caption surface",
                targetFixtureTaskId);
        DesktopSelfTestInputSuite.verifyCaptionRendering(
                result,
                "CAPTION-004",
                "Verify restored caption rendering",
                captureSource,
                targetFixtureTaskId,
                windowBounds,
                captionReference);
        require(result, "WINDOW-005", "Minimize window behind desktop", () -> {
            ShellAccess.run(AppProcessCommand.run(
                    "io.github.mekhontsev.magicdesk.TaskWindowingCommand",
                    "minimize " + targetDisplayId + " "
                            + targetFixtureTaskId + " " + desktopTask.taskId));
            waitForWindowFocus(targetDisplayId, true);
            return "task=" + targetFixtureTaskId;
        });
        require(result, "WINDOW-006", "Restore minimized window", () -> {
            return DesktopSelfTestInputSuite.restoreTaskFocus(
                    targetDisplayId, targetFixtureTaskId);
        });
        runTwoWindowFocusTests(
                appContext,
                result,
                targetDisplayId,
                targetFixtureTaskId,
                token,
                settledGeometry);
    }

    private static void verifyDisplayGeometry(
            final Context context,
            final int displayId,
            final DesktopSelfTestTarget target,
            final DesktopSelfTestResult result) throws AbortSelfTest {
        require(result, "DISPLAY-003", "Verify selected display geometry", () -> {
            final DisplayManager manager = context.getSystemService(DisplayManager.class);
            if (manager == null) {
                throw new IOException("DisplayManager unavailable");
            }
            final int expectedDensityDpi = expectedDisplayDensity(
                    context, displayId, target);
            final long deadline = SystemClock.uptimeMillis() + STEP_TIMEOUT_MILLIS;
            do {
                final Display display = manager.getDisplay(displayId);
                if (display != null) {
                    final DisplayMetrics metrics = new DisplayMetrics();
                    display.getRealMetrics(metrics);
                    final boolean expected =
                            target != DesktopSelfTestTarget.SIMULATED
                            || (metrics.widthPixels == SIMULATED_WIDTH
                                    && metrics.heightPixels
                                            == SIMULATED_HEIGHT);
                    if (metrics.widthPixels > 0
                            && metrics.heightPixels > 0
                            && metrics.densityDpi > 0
                            && (expectedDensityDpi <= 0
                                    || metrics.densityDpi
                                            == expectedDensityDpi)
                            && expected) {
                        return metrics.widthPixels + "x" + metrics.heightPixels
                                + "/" + metrics.densityDpi
                                + " name=" + display.getName();
                    }
                }
                SystemClock.sleep(POLL_MILLIS);
            } while (SystemClock.uptimeMillis() < deadline);
            throw new IOException(target == DesktopSelfTestTarget.SIMULATED
                    ? "expected " + SimulatedDisplayLease.SPEC
                    : "selected display geometry or density is unavailable"
                            + (expectedDensityDpi > 0
                                    ? "; expected density="
                                            + expectedDensityDpi
                                    : ""));
        });
    }

    private static int expectedDisplayDensity(
            final Context context,
            final int displayId,
            final DesktopSelfTestTarget target) {
        if (target == DesktopSelfTestTarget.SIMULATED) {
            return SIMULATED_DENSITY;
        }
        if (target != DesktopSelfTestTarget.EXTERNAL) {
            return 0;
        }
        final DisplayProfileStore.Profile profile =
                DisplayProfileController.loadPreparedProfile(
                        context,
                        DesktopRuntimeBridge.getDesktopTarget(displayId));
        return profile == null ? 0 : profile.dpi;
    }

    private static void samplePhoneUiBestEffort() {
        try {
            DesktopSelfTestPhoneUiObserver.sampleCurrentTasks();
        } catch (IOException ignored) {
            // Runtime task callbacks continue observing phone visibility.
        }
    }

    private static <T> T preservePhoneTouchpad(
            final CheckedSupplier<T> operation) throws Exception {
        final boolean preserve = ConsoleModeSwitcher.isTouchpadVisible();
        if (preserve) {
            DesktopTaskController.expectTouchpadDisplacement();
        }
        try {
            return operation.run();
        } finally {
            if (preserve) {
                DesktopTaskController.finishTouchpadPreservation();
                ConsoleModeSwitcher.restoreTouchpadIfMissing();
            }
        }
    }

    private static DesktopSelfTestGeometry verifyDesktopViewport(
            final Context context,
            final int displayId,
            final DesktopSelfTestResult result)
            throws AbortSelfTest {
        return require(result,
                "DESKTOP-003",
                "Verify desktop viewport and taskbar",
                () -> {
            final long deadline = SystemClock.uptimeMillis() + STEP_TIMEOUT_MILLIS;
            do {
                final DesktopViewport viewport =
                        DesktopRuntimeBridge.getDesktopViewport(displayId);
                final Rect workArea =
                        DesktopRuntimeBridge.getDesktopWorkAreaBounds(displayId);
                if (viewport != null && workArea != null) {
                    final Rect display = viewport.displayBounds();
                    final int densityDpi = displayDensity(context, displayId);
                    if (display.width() > 0
                            && display.height() > 0
                            && workArea.left == display.left
                            && workArea.right == display.right
                            && workArea.top >= display.top
                            && workArea.bottom < display.bottom
                            && densityDpi > 0) {
                        return new DesktopSelfTestGeometry(
                                display, workArea, densityDpi);
                    }
                }
                SystemClock.sleep(POLL_MILLIS);
            } while (SystemClock.uptimeMillis() < deadline);
            throw new IOException("desktop viewport did not settle");
        }, null);
    }

    private static int displayDensity(
            final Context context, final int displayId) {
        final DisplayManager manager = context.getSystemService(
                DisplayManager.class);
        final Display display = manager == null
                ? null : manager.getDisplay(displayId);
        if (display == null) {
            return 0;
        }
        return context.createDisplayContext(display)
                .getResources().getDisplayMetrics().densityDpi;
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
                            if (DesktopRuntimeBridge
                                    .isUsingFallbackDesktopWallpaper(
                                            displayId)) {
                                throw new IOException(
                                        "emergency solid-color fallback rendered"
                                                + " instead of wallpaper");
                            }
                            return "wallpaper rendered";
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
                                    displayId,
                                    token,
                                    bounds));
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

    private static TaskTransferObservation observeTaskTransfer(
            final int displayId,
            final int taskId,
            final int desktopTaskId,
            final Rect bounds,
            final SurfaceReferenceResult surfaceReference) throws IOException {
        ShellAccess.run(TaskFocusCommands.createShellCommand(
                displayId,
                Collections.singletonList(Integer.valueOf(desktopTaskId))));
        waitForWindowFocus(displayId, true);
        if (DesktopDisplayDrivers.forActiveDisplay(displayId)
                .features().rootTaskTransfer) {
            return reopenPhysicalTask(
                    displayId, taskId, bounds, surfaceReference);
        }
        return reopenTask(displayId, taskId, bounds, surfaceReference);
    }

    private static TaskTransferObservation reopenPhysicalTask(
            final int displayId,
            final int taskId,
            final Rect bounds,
            final SurfaceReferenceResult surfaceReference) throws IOException {
        final ComponentName component =
                new ComponentName(PACKAGE_NAME, FIXTURE_CLASS);
        final TaskStackParser.Entry currentTask = findTaskOnAnyDisplay(
                ShellAccess.run("/system/bin/cmd activity stack list"),
                FIXTURE_CLASS);
        if (currentTask == null || currentTask.taskId != taskId) {
            throw new IOException("task " + taskId + " is unavailable");
        }
        try (DesktopTaskLaunchProbe probe =
                     DesktopTaskLaunchProbe.open(
                             taskId, component, displayId)) {
            final String output = ShellAccess.run(
                    TaskDisplayAreaLaunchCommand.createPhysicalMoveCommand(
                            taskId,
                            currentTask.rootTaskId,
                            currentTask.displayId,
                            displayId,
                            bounds,
                            surfaceReference.reference));
            if (!output.contains("task-freeform-move=" + taskId)) {
                throw new IOException(output.trim());
            }
            if (!output.contains("source-prepared-visible=false")) {
                throw new IOException(
                        "source freeform preparation was not hidden");
            }
            final DesktopTaskLaunchProbe.Observation firstFront =
                    probe.awaitObservation();
            waitForTask(
                    displayId,
                    FIXTURE_CLASS,
                    entry -> entry.taskId == taskId
                            && "freeform".equals(entry.windowingMode));
            final TaskTransferObservation observation =
                    buildTaskTransferObservation(
                            firstFront, surfaceReference, output);
            return new TaskTransferObservation(
                    observation.firstFront,
                    observation.surfaceChanged,
                    observation.pixelSamples,
                    observation.probeError,
                    "hidden");
        }
    }

    private static DesktopTaskLaunchProbe.Observation reopenTask(
            final int displayId,
            final int taskId,
            final Rect bounds) throws IOException {
        return reopenTask(
                displayId,
                taskId,
                bounds,
                SurfaceReferenceResult.unavailable(
                        "surface observation was not requested")).firstFront;
    }

    private static TaskTransferObservation reopenTask(
            final int displayId,
            final int taskId,
            final Rect bounds,
            final SurfaceReferenceResult surfaceReference) throws IOException {
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
            return new TaskTransferObservation(
                    new DesktopTaskLaunchProbe.Observation(
                            taskId,
                            displayId,
                            WINDOWING_MODE_FULLSCREEN,
                            fullscreenTask.bounds.left,
                            fullscreenTask.bounds.top,
                            fullscreenTask.bounds.right,
                            fullscreenTask.bounds.bottom),
                    false,
                    "",
                    "");
        }
        try (DesktopTaskLaunchProbe probe =
                     DesktopTaskLaunchProbe.open(taskId, component)) {
            final String output = ShellAccess.run(
                    surfaceReference.reference != null
                            ? TaskDisplayAreaLaunchCommand.createObservedMoveCommand(
                                    taskId,
                                    currentTask.displayId,
                                    displayId,
                                    bounds,
                                    surfaceReference.reference)
                            : TaskDisplayAreaLaunchCommand.createMoveCommand(
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
            return buildTaskTransferObservation(
                    observation, surfaceReference, output);
        }
    }

    private static TaskTransferObservation buildTaskTransferObservation(
            final DesktopTaskLaunchProbe.Observation firstFront,
            final SurfaceReferenceResult surfaceReference,
            final String output) throws IOException {
        if (surfaceReference.reference == null) {
            return new TaskTransferObservation(
                    firstFront, false, "", surfaceReference.error);
        }
        final DesktopTransitionSurfaceProbe.Reference reference =
                surfaceReference.reference;
        boolean changed = DesktopTransitionSurfaceProbe
                .parseReportedSurfaceChange(output);
        String samples = DesktopTransitionSurfaceProbe
                .parseReportedSamples(output);
        String error = DesktopTransitionSurfaceProbe
                .parseReportedError(output);
        if (error.isEmpty()) {
            try {
                final String visibleOutput = ShellAccess.run(
                        DesktopTransitionSurfaceProbe.createCaptureCommand(
                                reference.captureSource,
                                reference.x,
                                reference.y));
                final DesktopTransitionSurfaceProbe.Reference visible =
                        DesktopTransitionSurfaceProbe.parseReference(
                                reference.captureSource,
                                reference.x,
                                reference.y,
                                visibleOutput);
                samples += ",visible:"
                        + DesktopTransitionSurfaceProbe.formatColor(
                                visible.color);
                changed |= !DesktopTransitionSurfaceProbe.sameColor(
                        reference.color, visible.color);
            } catch (IOException captureError) {
                error = "visible desktop pixel unavailable: "
                        + usefulMessage(captureError);
            }
        }
        return new TaskTransferObservation(
                firstFront, changed, samples, error);
    }

    private static final class TaskTransferObservation {
        final DesktopTaskLaunchProbe.Observation firstFront;
        final boolean surfaceChanged;
        final String pixelSamples;
        final String probeError;
        final String sourcePreparation;

        TaskTransferObservation(
                final DesktopTaskLaunchProbe.Observation firstFront,
                final boolean surfaceChanged,
                final String pixelSamples,
                final String probeError) {
            this(firstFront, surfaceChanged, pixelSamples, probeError, "");
        }

        TaskTransferObservation(
                final DesktopTaskLaunchProbe.Observation firstFront,
                final boolean surfaceChanged,
                final String pixelSamples,
                final String probeError,
                final String sourcePreparation) {
            this.firstFront = firstFront;
            this.surfaceChanged = surfaceChanged;
            this.pixelSamples = pixelSamples == null ? "" : pixelSamples;
            this.probeError = probeError == null ? "" : probeError;
            this.sourcePreparation = sourcePreparation == null
                    ? "" : sourcePreparation;
        }

        @Override
        public String toString() {
            return firstFront + ", pixels=" + pixelSamples
                    + (sourcePreparation.isEmpty()
                            ? "" : ", source-prepared=" + sourcePreparation)
                    + (probeError.isEmpty()
                            ? "" : ", probe-error=" + probeError);
        }
    }

    private static SurfaceReferenceResult captureSurfaceReference(
            final DisplayCaptureSource captureSource,
            final DesktopSelfTestGeometry geometry) {
        try {
            final int x = geometry.displayBounds.left
                    + geometry.displayBounds.width() * 3 / 4;
            final int y = geometry.workArea.centerY();
            final String output = ShellAccess.run(
                    DesktopTransitionSurfaceProbe.createCaptureCommand(
                            captureSource, x, y));
            return SurfaceReferenceResult.available(
                    DesktopTransitionSurfaceProbe.parseReference(
                            captureSource, x, y, output));
        } catch (IOException | IllegalArgumentException
                | IllegalStateException error) {
            return SurfaceReferenceResult.unavailable(
                    "desktop pixel observation unavailable: "
                            + usefulMessage(error));
        }
    }

    private static final class SurfaceReferenceResult {
        final DesktopTransitionSurfaceProbe.Reference reference;
        final String error;

        private SurfaceReferenceResult(
                final DesktopTransitionSurfaceProbe.Reference reference,
                final String error) {
            this.reference = reference;
            this.error = error == null ? "" : error;
        }

        static SurfaceReferenceResult available(
                final DesktopTransitionSurfaceProbe.Reference reference) {
            return new SurfaceReferenceResult(reference, "");
        }

        static SurfaceReferenceResult unavailable(final String error) {
            return new SurfaceReferenceResult(null, error);
        }
    }

    private static void runTwoWindowFocusTests(
            final Context context,
            final DesktopSelfTestResult result,
            final int displayId,
            final int firstTaskId,
            final String firstToken,
            final DesktopSelfTestGeometry geometry) throws AbortSelfTest {
        final Rect leftBounds = geometry.leftWindow();
        final Rect rightBounds = geometry.rightWindow();
        final String secondToken = Long.toHexString(System.nanoTime());
        final DesktopTaskLaunchProbe.Observation secondLaunch = require(
                result,
                "WINDOW-011",
                "Launch second freeform test window",
                () -> {
                    final DesktopTaskLaunchProbe.Observation observation =
                            preservePhoneTouchpad(() ->
                                    launchFixtureAndObserve(
                                            displayId,
                                            secondToken,
                                            rightBounds));
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

        DesktopSelfTestInputSuite.runWindowFocusTests(
                context,
                result,
                displayId,
                firstTaskId,
                firstToken,
                leftBounds,
                secondTaskId,
                secondToken,
                rightBounds,
                geometry);
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

    private static String formatBounds(final Rect bounds) {
        return "[" + bounds.left + "," + bounds.top + "]["
                + bounds.right + "," + bounds.bottom + "]";
    }

}
