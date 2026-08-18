package io.github.mekhontsev.magicdesk;

import static io.github.mekhontsev.magicdesk.DesktopSelfTestSteps.check;
import static io.github.mekhontsev.magicdesk.DesktopSelfTestSteps.require;
import static io.github.mekhontsev.magicdesk.DesktopSelfTestSteps.usefulMessage;
import static io.github.mekhontsev.magicdesk.DesktopSelfTestTasks.POLL_MILLIS;
import static io.github.mekhontsev.magicdesk.DesktopSelfTestTasks.STEP_TIMEOUT_MILLIS;
import static io.github.mekhontsev.magicdesk.DesktopSelfTestTasks.findTaskOnAnyDisplay;
import static io.github.mekhontsev.magicdesk.DesktopSelfTestTasks.waitForFrontTask;
import static io.github.mekhontsev.magicdesk.DesktopSelfTestTasks.waitForTask;
import static io.github.mekhontsev.magicdesk.DesktopSelfTestTasks.waitForTaskAbsent;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
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
    private static final String BROWSER_FIXTURE_CLASS =
            DesktopSelfTestComponents.BROWSER_FIXTURE_CLASS;
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
        DesktopSelfTestTaskStackGuard.begin(
                targetDisplayId, desktopTask.taskId, "WINDOW-000");
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
                            + DesktopSelfTestGeometry.format(
                                    requestedWindowBounds);
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
                                            DesktopSelfTestGeometry.toRect(
                                                    entry.bounds)));
                    return task;
                });
        final Rect windowBounds = DesktopSelfTestGeometry.toRect(
                settledWindow.bounds);
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
                            && equalsObservationBounds(
                                    taskTransfer.firstFront,
                                    windowBounds)
                            ? DesktopSelfTestResult.State.PASS
                            : DesktopSelfTestResult.State.FAIL,
                    "WINDOW-014",
                    "Preserve desktop surface during task transfer",
                    "first-front=" + taskTransfer.firstFront
                            + ", pixels=" + taskTransfer.pixelSamples
                            + ", requested="
                            + DesktopSelfTestGeometry.format(windowBounds));
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
                            && DesktopSelfTestGeometry.matches(
                                    entry.bounds, windowBounds));
            waitForFrontTask(
                    targetDisplayId, targetFixtureTaskId);
            return DesktopSelfTestGeometry.format(task.bounds);
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
                            && DesktopSelfTestGeometry.matches(
                                    entry.bounds, windowBounds));
            return DesktopSelfTestGeometry.format(task.bounds);
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
        final Rect browserBounds = settledGeometry.browserWindow();
        final SurfaceReferenceResult browserSurfaceReference =
                target == DesktopSelfTestTarget.PHONE
                        ? SurfaceReferenceResult.unavailable(
                                "the selected desktop uses display 0")
                        : captureSurfaceReferenceOutsideWindow(
                                captureSource,
                                settledGeometry,
                                browserBounds);
        final DesktopSelfTestInputSuite.CaptionReference
                browserCaptionReference =
                DesktopSelfTestInputSuite.alignCaptionReference(
                        captionReference,
                        browserBounds,
                        settledGeometry);
        verifyAppRequestedFullscreenRestore(
                appContext,
                result,
                targetDisplayId,
                targetFixtureTaskId,
                browserBounds,
                captureSource,
                browserSurfaceReference,
                browserCaptionReference);
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
        DesktopSelfTestTaskStackGuard.finish(result);
    }

    private static void verifyAppRequestedFullscreenRestore(
            final Context appContext,
            final DesktopSelfTestResult result,
            final int displayId,
            final int taskId,
            final Rect expectedBounds,
            final DisplayCaptureSource captureSource,
            final SurfaceReferenceResult surfaceReference,
            final DesktopSelfTestInputSuite.CaptionReference captionReference)
            throws AbortSelfTest {
        final String token = Long.toHexString(System.nanoTime());
        final DesktopTaskLaunchProbe.Observation launch = require(
                result,
                "WINDOW-017",
                "Launch application fullscreen test window",
                () -> {
                    final DesktopTaskLaunchProbe.Observation observation =
                            preservePhoneTouchpad(() ->
                                    launchBrowserFixtureAndObserve(
                                            displayId,
                                            token,
                                            expectedBounds));
                    if (observation.taskId == taskId) {
                        throw new IOException(
                                "Android reused the primary test task");
                    }
                    return observation;
                });
        final int immersiveTaskId = launch.taskId;
        DesktopSelfTestPhoneUiObserver.allowPhoneFixtureTask(
                immersiveTaskId);
        boolean restored = false;
        try {
            require(result,
                    "WINDOW-015",
                    "Enter application-requested fullscreen",
                    () -> {
                        DesktopSelfTestFixtureState.clearImmersive(appContext);
                        setFixtureImmersive(token, true);
                        DesktopSelfTestFixtureState.awaitImmersive(
                                appContext, token, displayId, true);
                        final TaskStackParser.Entry task = waitForTask(
                                displayId,
                                BROWSER_FIXTURE_CLASS,
                                entry -> entry.taskId == immersiveTaskId
                                        && "fullscreen".equals(
                                                entry.windowingMode));
                        // The application request and MagicDesk's task
                        // transition are asynchronous. Exiting before both
                        // settle creates an artificial transition race that a
                        // real browser video does not exercise.
                        return "task=" + task.taskId
                                + ", mode=" + task.windowingMode
                                + ", bounds="
                                + DesktopSelfTestGeometry.format(task.bounds);
                    });
            require(result,
                    "WINDOW-016",
                    "Restore application-requested window bounds",
                    () -> {
                        DesktopSelfTestFixtureState.clearImmersive(appContext);
                        setFixtureImmersive(token, false);
                        DesktopSelfTestFixtureState.awaitImmersive(
                                appContext, token, displayId, false);
                        final TaskStackParser.Entry task = waitForTask(
                                displayId,
                                BROWSER_FIXTURE_CLASS,
                                entry -> entry.taskId == immersiveTaskId
                                        && "freeform".equals(
                                                entry.windowingMode)
                                        && DesktopSelfTestGeometry.matches(
                                                entry.bounds,
                                                expectedBounds));
                        final String caption = DesktopSelfTestInputSuite
                                .awaitCaptionStructure(
                                        immersiveTaskId, expectedBounds);
                        return DesktopSelfTestGeometry.format(task.bounds)
                                + ", " + caption;
                    });
            require(result,
                    "WINDOW-019",
                    "Repeat application fullscreen restoration",
                    () -> repeatAppRequestedFullscreenRestore(
                            appContext,
                            token,
                            displayId,
                            immersiveTaskId,
                            expectedBounds,
                            2));
            restored = true;
            verifyDesktopSurfaceRestored(
                    result,
                    "WINDOW-018",
                    "Restore desktop surface after application fullscreen",
                    surfaceReference);
            DesktopSelfTestInputSuite.verifyCaptionStructure(
                    result,
                    "CAPTION-005",
                    "Verify application fullscreen restored caption",
                    immersiveTaskId,
                    expectedBounds);
            DesktopSelfTestInputSuite.verifyCaptionSurface(
                    result,
                    "CAPTION-SURFACE-003",
                    "Verify application fullscreen restored caption surface",
                    immersiveTaskId);
            DesktopSelfTestInputSuite.verifyCaptionRendering(
                    result,
                    "CAPTION-006",
                    "Verify application fullscreen restored caption rendering",
                    captureSource,
                    immersiveTaskId,
                    expectedBounds,
                    captionReference);
        } finally {
            if (!restored) {
                try {
                    setFixtureImmersive(token, false);
                } catch (IOException ignored) {
                    // Removing the temporary task also clears this request.
                }
            }
            removeFixtureTaskBestEffort(immersiveTaskId);
        }
    }

    private static String repeatAppRequestedFullscreenRestore(
            final Context appContext,
            final String token,
            final int displayId,
            final int taskId,
            final Rect expectedBounds,
            final int repetitions) throws IOException {
        for (int index = 0; index < repetitions; index++) {
            DesktopSelfTestHostObserver.stage(
                    "WINDOW-019-ENTER-" + (index + 1));
            DesktopSelfTestFixtureState.clearImmersive(appContext);
            setFixtureImmersive(token, true);
            DesktopSelfTestFixtureState.awaitImmersive(
                    appContext, token, displayId, true);
            waitForTask(
                    displayId,
                    BROWSER_FIXTURE_CLASS,
                    entry -> entry.taskId == taskId
                            && "fullscreen".equals(entry.windowingMode));

            DesktopSelfTestHostObserver.stage(
                    "WINDOW-019-RESTORE-" + (index + 1));
            DesktopSelfTestFixtureState.clearImmersive(appContext);
            setFixtureImmersive(token, false);
            DesktopSelfTestFixtureState.awaitImmersive(
                    appContext, token, displayId, false);
            waitForTask(
                    displayId,
                    BROWSER_FIXTURE_CLASS,
                    entry -> entry.taskId == taskId
                            && "freeform".equals(entry.windowingMode)
                            && DesktopSelfTestGeometry.matches(
                                    entry.bounds, expectedBounds));
        }
        return "task=" + taskId + ", cycles=" + repetitions
                + ", bounds="
                + DesktopSelfTestGeometry.format(expectedBounds);
    }

    private static void verifyDesktopSurfaceRestored(
            final DesktopSelfTestResult result,
            final String code,
            final String label,
            final SurfaceReferenceResult surfaceReference) {
        DesktopSelfTestHostObserver.stage(code);
        if (surfaceReference.reference == null) {
            result.add(DesktopSelfTestResult.State.NOT_TESTED,
                    code, label, surfaceReference.error);
            return;
        }
        try {
            final DesktopTransitionSurfaceProbe.Reference expected =
                    surfaceReference.reference;
            final String output = ShellAccess.run(
                    DesktopTransitionSurfaceProbe.createCaptureCommand(
                            expected.captureSource,
                            expected.x,
                            expected.y));
            final DesktopTransitionSurfaceProbe.Reference actual =
                    DesktopTransitionSurfaceProbe.parseReference(
                            expected.captureSource,
                            expected.x,
                            expected.y,
                            output);
            final boolean restored = isStableDesktopPixel(
                    expected.color, actual.color);
            result.add(restored
                            ? DesktopSelfTestResult.State.PASS
                            : DesktopSelfTestResult.State.FAIL,
                    code,
                    label,
                    "expected=" + DesktopTransitionSurfaceProbe.formatColor(
                            expected.color)
                            + ", actual="
                            + DesktopTransitionSurfaceProbe.formatColor(
                                    actual.color));
        } catch (IOException | IllegalArgumentException error) {
            result.add(DesktopSelfTestResult.State.FAIL,
                    code, label, usefulMessage(error));
        }
    }

    private static boolean isStableDesktopPixel(
            final int expected,
            final int actual) {
        // A recomposed wallpaper can move a few RGB levels through color
        // conversion. The broken Firefox path replaces it with a different
        // solid surface, which is far outside this tolerance.
        final int tolerance = 12;
        return Math.abs(((expected >>> 16) & 0xFF)
                        - ((actual >>> 16) & 0xFF)) <= tolerance
                && Math.abs(((expected >>> 8) & 0xFF)
                        - ((actual >>> 8) & 0xFF)) <= tolerance
                && Math.abs((expected & 0xFF) - (actual & 0xFF))
                        <= tolerance;
    }

    private static void setFixtureImmersive(
            final String token,
            final boolean enabled)
            throws IOException {
        ShellAccess.run("/system/bin/am broadcast --user 0 -a "
                + ShellCommandLine.quote(
                        DesktopSelfTestActivity.ACTION_SET_IMMERSIVE)
                + " -p " + ShellCommandLine.quote(PACKAGE_NAME)
                + " --ez "
                + ShellCommandLine.quote(
                        DesktopSelfTestActivity.EXTRA_IMMERSIVE)
                + " " + enabled
                + " --es "
                + ShellCommandLine.quote(
                        DesktopSelfTestActivity.EXTRA_IMMERSIVE_TOKEN)
                + " " + ShellCommandLine.quote(token));
    }

    private static void removeFixtureTaskBestEffort(final int taskId) {
        try {
            ShellAccess.run(AppProcessCommand.run(
                    "io.github.mekhontsev.magicdesk.TaskControlCommand",
                    "remove " + taskId));
            waitForTaskAbsent(taskId);
        } catch (IOException ignored) {
            // The global self-test cleanup removes any remaining fixture.
        }
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
            MagicDeskRuntime.expectTouchpadDisplacement();
        }
        try {
            return operation.run();
        } finally {
            if (preserve) {
                MagicDeskRuntime.finishTouchpadPreservation();
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
                    final int rotation = displayRotation(context, displayId);
                    if (display.width() > 0
                            && display.height() > 0
                            && workArea.left == display.left
                            && workArea.right == display.right
                            && workArea.top >= display.top
                            && workArea.bottom < display.bottom
                            && densityDpi > 0
                            && rotation >= 0) {
                        return new DesktopSelfTestGeometry(
                                display, workArea, densityDpi, rotation);
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

    private static int displayRotation(
            final Context context, final int displayId) {
        final DisplayManager manager = context.getSystemService(
                DisplayManager.class);
        final Display display = manager == null
                ? null : manager.getDisplay(displayId);
        return display == null ? -1 : display.getRotation();
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
        return launchFixtureAndObserve(
                displayId,
                bounds,
                FIXTURE_CLASS,
                TaskDisplayAreaLaunchCommand.createSelfTestIntent(
                        displayId, token, false),
                TaskDisplayAreaLaunchCommand.createSelfTestLaunchCommand(
                        displayId, token, bounds));
    }

    private static DesktopTaskLaunchProbe.Observation
            launchBrowserFixtureAndObserve(
                    final int displayId,
                    final String token,
                    final Rect bounds) throws IOException {
        return launchFixtureAndObserve(
                displayId,
                bounds,
                BROWSER_FIXTURE_CLASS,
                TaskDisplayAreaLaunchCommand.createSelfTestIntent(
                        displayId, token, true),
                TaskDisplayAreaLaunchCommand
                        .createBrowserSelfTestLaunchCommand(
                                displayId, token, bounds));
    }

    private static DesktopTaskLaunchProbe.Observation launchFixtureAndObserve(
            final int displayId,
            final Rect bounds,
            final String fixtureClass,
            final Intent launchIntent,
            final String launchCommand) throws IOException {
        final ComponentName component =
                new ComponentName(PACKAGE_NAME, fixtureClass);
        try (DesktopTaskLaunchProbe probe =
                     DesktopTaskLaunchProbe.open(-1, component)) {
            final DesktopTaskAreaPolicy policy =
                    DesktopDisplayDrivers.activeTaskAreaPolicy(displayId);
            final int launchedTaskId;
            if (policy == DesktopTaskAreaPolicy.SESSION) {
                launchedTaskId = MagicDeskRuntime.launchTaskInDesktopArea(
                        displayId, launchIntent, bounds);
            } else {
                final String output = ShellAccess.run(launchCommand);
                if (!output.contains("task-display-area-launch=")) {
                    throw new IOException(output.trim());
                }
                launchedTaskId = -1;
            }
            final DesktopTaskLaunchProbe.Observation observation =
                    probe.awaitObservation();
            if (observation.displayId != displayId
                    || (launchedTaskId >= 0
                            && observation.taskId != launchedTaskId)) {
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
                final String output = ShellAccess.run(
                        TaskFullscreenMoveCommand.createMoveCommand(
                                taskId,
                                currentTask.rootTaskId,
                                currentTask.displayId,
                                displayId));
                if (!output.contains("task-fullscreen-move=" + taskId)) {
                    throw new IOException(output.trim());
                }
                waitForTask(
                        displayId,
                        FIXTURE_CLASS,
                        entry -> entry.taskId == taskId
                                && "fullscreen".equals(
                                        entry.windowingMode));
            } else {
                ShellAccess.run(
                        TaskRepository.createFullscreenTransitionCommand(
                                displayId, taskId));
            }
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
            final DesktopTaskAreaPolicy policy =
                    DesktopDisplayDrivers.activeTaskAreaPolicy(displayId);
            final String output;
            if (policy == DesktopTaskAreaPolicy.SESSION) {
                MagicDeskRuntime.placeTaskInDesktopArea(
                        taskId,
                        currentTask.displayId,
                        displayId,
                        bounds);
                output = "task-freeform-move=" + taskId;
            } else {
                output = ShellAccess.run(
                        surfaceReference.reference != null
                                ? TaskDisplayAreaLaunchCommand
                                        .createObservedMoveCommand(
                                                taskId,
                                                currentTask.displayId,
                                                displayId,
                                                bounds,
                                                surfaceReference.reference)
                                : TaskDisplayAreaLaunchCommand
                                        .createMoveCommand(
                                                taskId,
                                                currentTask.displayId,
                                                displayId,
                                                bounds));
            }
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
                    || (freeform && !equalsObservationBounds(
                            observation, bounds))) {
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
        return captureSurfaceReference(
                captureSource,
                geometry.displayBounds.left
                        + geometry.displayBounds.width() * 3 / 4,
                geometry.workArea.centerY());
    }

    private static SurfaceReferenceResult captureSurfaceReferenceOutsideWindow(
            final DisplayCaptureSource captureSource,
            final DesktopSelfTestGeometry geometry,
            final Rect windowBounds) {
        if (windowBounds == null
                || windowBounds.right >= geometry.workArea.right) {
            return SurfaceReferenceResult.unavailable(
                    "no desktop sample area remains outside the test window");
        }
        return captureSurfaceReference(
                captureSource,
                windowBounds.right
                        + (geometry.workArea.right - windowBounds.right) / 2,
                windowBounds.centerY());
    }

    private static SurfaceReferenceResult captureSurfaceReference(
            final DisplayCaptureSource captureSource,
            final int x,
            final int y) {
        try {
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
                            && DesktopSelfTestGeometry.matches(
                                    entry.bounds, leftBounds));
            waitForTask(displayId, FIXTURE_CLASS,
                    entry -> entry.taskId == secondTaskId
                            && "freeform".equals(entry.windowingMode)
                            && entry.visible
                            && DesktopSelfTestGeometry.matches(
                                    entry.bounds, rightBounds));
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

    private static boolean equalsObservationBounds(
            final DesktopTaskLaunchProbe.Observation actual,
            final Rect expected) {
        return actual != null
                && expected != null
                && actual.left == expected.left
                && actual.top == expected.top
                && actual.right == expected.right
                && actual.bottom == expected.bottom;
    }

}
