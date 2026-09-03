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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
            final DesktopSelfTestResult result,
            final WorkspaceIsolationLease workspaceLease)
            throws AbortSelfTest {
        verifyDisplayGeometry(appContext, displayId, target, result);

        final int targetDisplayId = displayId;
        DesktopSelfTestPhoneUiObserver.begin(targetDisplayId);
        samplePhoneUiBestEffort();
        final TaskStackParser.Entry preparedDesktop = require(
                result, "DESKTOP-001", "Prepare desktop session", () -> {
            if (target == DesktopSelfTestTarget.SIMULATED) {
                // Exercise the same display policy as a user-started session,
                // including profiles and the phone-side touchpad.
                if (workspaceLease == null) {
                    throw new IOException(
                            "workspace isolation lease is unavailable");
                }
                workspaceLease.showReady(
                        null,
                        DesktopDisplayTarget.simulated(targetDisplayId));
            }
            final DesktopSessionSnapshot session =
                    DesktopRuntimeBridge.getSessionSnapshot();
            if (session.activeDisplayId() != targetDisplayId
                    || session.hostTaskId() < 0) {
                throw new IOException(
                        "desktop runtime has no host on display "
                                + targetDisplayId);
            }
            return waitForTask(
                    targetDisplayId, session.hostTaskId(), null);
        });
        final TaskStackParser.Entry desktopTask = require(result,
                "DESKTOP-002", "Configure desktop host", () -> {
                    final TaskStackParser.Entry task = waitForTask(
                            targetDisplayId, preparedDesktop.taskId,
                            entry -> "fullscreen".equals(entry.windowingMode));
                    return task;
                }, "fullscreen host ready");
        if (target != DesktopSelfTestTarget.PHONE) {
            require(result,
                    "PHONEUI-000",
                    "Protect the phone during the self-test",
                    () -> DesktopSelfTestPhoneInputGuard.begin(
                            appContext, result.runId()));
        }
        final DisplayCaptureSource captureSource =
                DesktopDisplayDrivers.captureSource(
                targetDisplayId);
        DesktopSelfTestPhoneUiObserver.refreshTouchpadExpectation(
                targetDisplayId);
        samplePhoneUiBestEffort();
        final DesktopSelfTestGeometry geometry = verifyDesktopViewport(
                appContext, targetDisplayId, captureSource, result);
        verifyDesktopWallpaper(targetDisplayId, result);
        DesktopSelfTestHostObserver.markReady();
        DesktopSelfTestTaskStackGuard.begin(
                targetDisplayId, desktopTask.taskId, "WINDOW-000");
        require(result, "WINDOW-000", "Clear stale self-test windows", () -> {
            DesktopSelfTestCleanup.removeFixtureTasks();
            return "ready";
        });
        final SurfaceReferenceResult surfaceReference =
                captureSurfaceReference(captureSource, geometry);
        final DesktopTransitionSurfaceProbe.Observation
                initialSurfaceObservation = beginSurfaceObservation(
                        surfaceReference);
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
                        requestedWindowBounds,
                        DesktopSelfTestFixtureAppearance.PRIMARY)));
        sampleDesktopSurface(
                initialSurfaceObservation, surfaceReference, "front");
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
                    sampleDesktopSurface(
                            initialSurfaceObservation,
                            surfaceReference,
                            "first-frame");
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
                    sampleDesktopSurface(
                            initialSurfaceObservation,
                            surfaceReference,
                            "settled");
                    return task;
                });
        recordDesktopSurfaceObservation(
                result,
                "WINDOW-SURFACE-001",
                "Keep desktop background during initial window launch",
                surfaceReference,
                initialSurfaceObservation);
        final Rect windowBounds = DesktopSelfTestGeometry.toRect(
                settledWindow.bounds);
        final DesktopSelfTestGeometry settledGeometry =
                geometry.withObservedWindow(windowBounds);
        runFullscreenPlaneExitPreflight(
                result,
                targetDisplayId,
                targetFixtureTaskId,
                windowBounds,
                surfaceReference);
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
                final boolean hiddenRootTransferPreparation =
                        taskTransfer.hiddenRootTransferPreparation;
                final boolean directFreeformFront =
                        !hiddenRootTransferPreparation
                                && taskTransfer.firstFront.windowingMode
                                        == WINDOWING_MODE_FREEFORM
                                && taskTransfer.firstFront.displayId
                                        == targetDisplayId
                                && equalsObservationBounds(
                                        taskTransfer.firstFront,
                                        windowBounds);
                result.add(!taskTransfer.surfaceChanged
                            && (hiddenRootTransferPreparation
                                    || directFreeformFront)
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
        require(result,
                "TASKBAR-003",
                "Keep the concealed taskbar plane out of fullscreen",
                () -> DesktopSelfTestInputSuite
                        .verifyConcealedTaskbarSurface(
                                targetDisplayId,
                                captureSource,
                                DesktopSelfTestFixtureAppearance.PRIMARY
                                        .color()));
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
                windowBounds,
                browserBounds,
                captureSource,
                browserSurfaceReference,
                browserCaptionReference,
                settledGeometry);
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
                captureSource,
                targetFixtureTaskId,
                token,
                settledGeometry);
        DesktopSelfTestTaskStackGuard.finish(result);
    }

    private static void verifyAppRequestedFullscreenRestore(
            final Context appContext,
            final DesktopSelfTestResult result,
            final int displayId,
            final int peerTaskId,
            final Rect peerRestoreBounds,
            final Rect expectedBounds,
            final DisplayCaptureSource captureSource,
            final SurfaceReferenceResult surfaceReference,
            final DesktopSelfTestInputSuite.CaptionReference captionReference,
            final DesktopSelfTestGeometry geometry)
            throws AbortSelfTest {
        final String token = Long.toHexString(System.nanoTime());
        final SettledWindowLaunch launch = require(
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
                    if (observation.taskId == peerTaskId) {
                        throw new IOException(
                                "Android reused the primary test task");
                    }
                    final TaskStackParser.Entry settled = waitForTask(
                            displayId,
                            BROWSER_FIXTURE_CLASS,
                            entry -> entry.taskId == observation.taskId
                                    && "freeform".equals(
                                            entry.windowingMode)
                                    && DesktopSelfTestGeometry.matches(
                                            entry.bounds, expectedBounds));
                    return new SettledWindowLaunch(observation, settled);
                });
        final int immersiveTaskId = launch.settled.taskId;
        DesktopSelfTestPhoneUiObserver.allowPhoneFixtureTask(
                immersiveTaskId);
        int additionalPeerTaskId = -1;
        boolean peersPrepared = false;
        boolean restored = false;
        try {
            final String additionalPeerToken =
                    Long.toHexString(System.nanoTime());
            final SettledWindowLaunch additionalPeerLaunch = require(
                    result,
                    "WINDOW-020-PEER",
                    "Launch another fullscreen switching peer",
                    () -> {
                        final DesktopTaskLaunchProbe.Observation observation =
                                preservePhoneTouchpad(() ->
                                        launchFixtureAndObserve(
                                                displayId,
                                                additionalPeerToken,
                                                expectedBounds,
                                                DesktopSelfTestFixtureAppearance
                                                        .SECONDARY));
                        if (observation.taskId == peerTaskId
                                || observation.taskId == immersiveTaskId) {
                            throw new IOException(
                                    "Android reused an existing test task");
                        }
                        final TaskStackParser.Entry settled = waitForTask(
                                displayId,
                                FIXTURE_CLASS,
                                entry -> entry.taskId == observation.taskId
                                        && "freeform".equals(
                                                entry.windowingMode)
                                        && DesktopSelfTestGeometry.matches(
                                                entry.bounds,
                                                expectedBounds));
                        return new SettledWindowLaunch(observation, settled);
                    });
            additionalPeerTaskId = additionalPeerLaunch.settled.taskId;
            final int secondPeerTaskId = additionalPeerTaskId;
            DesktopSelfTestPhoneUiObserver.allowPhoneFixtureTask(
                    secondPeerTaskId);
            require(result,
                    "WINDOW-020-PREPARE",
                    "Prepare two fullscreen peers around app fullscreen",
                    () -> prepareAppFullscreenPeers(
                            displayId,
                            new int[]{peerTaskId, secondPeerTaskId},
                            new Rect[]{peerRestoreBounds, expectedBounds}));
            peersPrepared = true;
            require(result,
                    "WINDOW-015",
                    "Enter application-requested fullscreen",
                    () -> {
                        final Rect expectedDisplayBounds = new Rect(
                                geometry.displayBounds);
                        final int expectedRotation = displayRotation(
                                appContext, displayId);
                        DesktopSelfTestInputSuite.focusTaskThroughDesktop(
                                displayId, immersiveTaskId);
                        waitForTask(
                                displayId,
                                BROWSER_FIXTURE_CLASS,
                                entry -> entry.taskId == immersiveTaskId
                                        && entry.visible
                                        && "freeform".equals(
                                                entry.windowingMode));
                        waitForFrontTask(displayId, immersiveTaskId);
                        DesktopSelfTestFixtureState.clearImmersive(appContext);
                        setFixtureImmersive(token, true);
                        DesktopSelfTestFixtureState.awaitImmersive(
                                appContext, token, displayId, true);
                        final Rect immersiveSurfaceBounds =
                                DesktopSelfTestFixtureState
                                        .awaitImmersiveSurface(
                                                appContext,
                                                token,
                                                displayId);
                        final TaskStackParser.Entry task = waitForTask(
                                displayId,
                                BROWSER_FIXTURE_CLASS,
                                entry -> entry.taskId == immersiveTaskId
                                        && "fullscreen".equals(
                                                entry.windowingMode)
                                        && DesktopSelfTestGeometry.matches(
                                                entry.bounds,
                                                expectedDisplayBounds));
                        final Rect actualDisplayBounds =
                                currentDesktopBounds(displayId);
                        final int actualRotation = displayRotation(
                                appContext, displayId);
                        if (!expectedDisplayBounds.equals(actualDisplayBounds)
                                || actualRotation != expectedRotation) {
                            throw new IOException(
                                    "application orientation request changed"
                                            + " desktop viewport: expected="
                                            + DesktopSelfTestGeometry.format(
                                                    expectedDisplayBounds)
                                            + "/rotation=" + expectedRotation
                                            + ", actual="
                                            + DesktopSelfTestGeometry.format(
                                                    actualDisplayBounds)
                                            + "/rotation=" + actualRotation);
                        }
                        final String surface = verifyFullscreenFixtureSurface(
                                captureSource,
                                new Rect(
                                        task.bounds.left,
                                        task.bounds.top,
                                        task.bounds.right,
                                        task.bounds.bottom),
                                immersiveSurfaceBounds);
                        // The application request and MagicDesk's task
                        // transition are asynchronous. Exiting before both
                        // settle creates an artificial transition race that a
                        // real browser video does not exercise.
                        return "task=" + task.taskId
                                + ", mode=" + task.windowingMode
                                + ", bounds="
                                + DesktopSelfTestGeometry.format(task.bounds)
                                + ", rotation=" + actualRotation
                                + ", " + surface;
                    });
            DesktopSelfTestFixtureState.clearWindowModeTransitions(
                    appContext);
            require(result,
                    "WINDOW-020",
                    "Preserve application fullscreen across three tasks",
                    () -> verifyAppFullscreenTaskSwitch(
                            appContext,
                            token,
                            displayId,
                            immersiveTaskId,
                            new int[]{peerTaskId, secondPeerTaskId},
                            captureSource));
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
                            surfaceReference,
                            DesktopSelfTestFixtureAppearance.SECONDARY.color(),
                            2));
            require(result,
                    "WINDOW-020-CLEANUP",
                    "Restore fullscreen peers after app fullscreen",
                    () -> restoreAppFullscreenPeers(
                            displayId,
                            immersiveTaskId,
                            expectedBounds,
                            new int[]{peerTaskId, secondPeerTaskId},
                            new Rect[]{peerRestoreBounds, expectedBounds}));
            peersPrepared = false;
            restored = true;
            verifyDesktopSurfaceMatches(
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
            if (peersPrepared) {
                restorePeerTasksBestEffort(
                        displayId,
                        new int[]{peerTaskId, additionalPeerTaskId},
                        new Rect[]{peerRestoreBounds, expectedBounds});
            }
            if (!restored) {
                try {
                    setFixtureImmersive(token, false);
                } catch (IOException ignored) {
                    // Removing the temporary task also clears this request.
                }
            }
            removeFixtureTaskBestEffort(immersiveTaskId);
            if (additionalPeerTaskId >= 0) {
                removeFixtureTaskBestEffort(additionalPeerTaskId);
            }
        }
    }

    private static String prepareAppFullscreenPeers(
            final int displayId,
            final int[] peerTaskIds,
            final Rect[] peerRestoreBounds) throws IOException {
        int prepared = 0;
        try {
            for (final int peerTaskId : peerTaskIds) {
                DesktopSelfTestInputSuite.enterFullscreenThroughShortcut(
                        displayId, peerTaskId);
                waitForTask(
                        displayId,
                        FIXTURE_CLASS,
                        entry -> entry.taskId == peerTaskId
                                && entry.visible
                                && "fullscreen".equals(
                                        entry.windowingMode));
                waitForFrontTask(displayId, peerTaskId);
                prepared++;
            }
            return "tasks=" + formatTaskIds(peerTaskIds)
                    + "/fullscreen";
        } catch (IOException error) {
            restorePeerTasksBestEffort(
                    displayId,
                    peerTaskIds,
                    peerRestoreBounds,
                    prepared);
            throw error;
        }
    }

    private static String verifyAppFullscreenTaskSwitch(
            final Context appContext,
            final String token,
            final int displayId,
            final int immersiveTaskId,
            final int[] peerTaskIds,
            final DisplayCaptureSource captureSource) throws IOException {
        for (int index = 0; index < peerTaskIds.length; index++) {
            final int peerTaskId = peerTaskIds[index];
            DesktopSelfTestHostObserver.stage(
                    "WINDOW-020-PEER-" + (index + 1));
            DesktopSelfTestInputSuite.focusTaskThroughDesktop(
                    displayId, peerTaskId);
            waitForFrontTask(displayId, peerTaskId);
            waitForTask(
                    displayId,
                    FIXTURE_CLASS,
                    entry -> entry.taskId == peerTaskId
                            && entry.visible
                            && "fullscreen".equals(entry.windowingMode));
            DesktopSelfTestInputSuite.waitForTaskInputFocus(
                    displayId, peerTaskId);
            requireFullscreenTasks(
                    displayId, immersiveTaskId, peerTaskIds);
        }

        DesktopSelfTestHostObserver.stage("WINDOW-020-RETURN");
        DesktopSelfTestInputSuite.focusTaskThroughDesktop(
                displayId, immersiveTaskId);
        final TaskStackParser.Entry restored = waitForTask(
                displayId,
                BROWSER_FIXTURE_CLASS,
                entry -> entry.taskId == immersiveTaskId
                        && entry.visible
                        && "fullscreen".equals(entry.windowingMode));
        waitForFrontTask(displayId, immersiveTaskId);
        DesktopSelfTestInputSuite.waitForTaskInputFocus(
                displayId, immersiveTaskId);
        requireFullscreenTasks(displayId, immersiveTaskId, peerTaskIds);
        DesktopSelfTestFixtureState.awaitImmersive(
                appContext, token, displayId, true);
        final Rect immersiveSurfaceBounds = DesktopSelfTestFixtureState
                .awaitImmersiveSurface(appContext, token, displayId);
        DesktopSelfTestFixtureState.assertNoWindowModeTransition(
                appContext, token, displayId);
        final String surface = verifyFullscreenFixtureSurface(
                captureSource,
                new Rect(
                        restored.bounds.left,
                        restored.bounds.top,
                        restored.bounds.right,
                        restored.bounds.bottom),
                immersiveSurfaceBounds);
        return "task=" + restored.taskId + "/fullscreen/visible"
                + ", peers=" + formatTaskIds(peerTaskIds)
                + "/fullscreen, " + surface;
    }

    private static void requireFullscreenTasks(
            final int displayId,
            final int immersiveTaskId,
            final int[] peerTaskIds) throws IOException {
        waitForTask(
                displayId,
                BROWSER_FIXTURE_CLASS,
                entry -> entry.taskId == immersiveTaskId
                        && "fullscreen".equals(entry.windowingMode));
        for (final int peerTaskId : peerTaskIds) {
            waitForTask(
                    displayId,
                    FIXTURE_CLASS,
                    entry -> entry.taskId == peerTaskId
                            && "fullscreen".equals(entry.windowingMode));
        }
    }

    private static String restoreAppFullscreenPeers(
            final int displayId,
            final int immersiveTaskId,
            final Rect immersiveBounds,
            final int[] peerTaskIds,
            final Rect[] peerRestoreBounds) throws IOException {
        DesktopSelfTestHostObserver.stage("WINDOW-020-CLEANUP");
        for (int index = peerTaskIds.length - 1; index >= 0; index--) {
            final int peerTaskId = peerTaskIds[index];
            final Rect restoreBounds = peerRestoreBounds[index];
            DesktopSelfTestInputSuite.restoreFullscreenTaskThroughDesktop(
                    displayId, peerTaskId);
            waitForTask(
                    displayId,
                    FIXTURE_CLASS,
                    entry -> entry.taskId == peerTaskId
                            && "freeform".equals(entry.windowingMode)
                            && DesktopSelfTestGeometry.matches(
                                    entry.bounds, restoreBounds));
        }
        // Restoring a peer correctly brings that peer forward, which may
        // detach the hidden target's caption surface. Reactivate the target
        // through the same focus route a user action takes before inspecting
        // its visible native decoration.
        DesktopSelfTestInputSuite.focusTaskThroughDesktop(
                displayId, immersiveTaskId);
        waitForTask(
                displayId,
                BROWSER_FIXTURE_CLASS,
                entry -> entry.taskId == immersiveTaskId
                        && entry.visible
                        && "freeform".equals(entry.windowingMode)
                        && DesktopSelfTestGeometry.matches(
                                entry.bounds, immersiveBounds));
        waitForFrontTask(displayId, immersiveTaskId);
        DesktopSelfTestInputSuite.waitForTaskInputFocus(
                displayId, immersiveTaskId);
        return "peers=" + formatTaskIds(peerTaskIds)
                + "/freeform, task=" + immersiveTaskId
                + "/freeform/visible";
    }

    private static void restorePeerTasksBestEffort(
            final int displayId,
            final int[] peerTaskIds,
            final Rect[] peerRestoreBounds) {
        restorePeerTasksBestEffort(
                displayId,
                peerTaskIds,
                peerRestoreBounds,
                peerTaskIds.length);
    }

    private static void restorePeerTasksBestEffort(
            final int displayId,
            final int[] peerTaskIds,
            final Rect[] peerRestoreBounds,
            final int count) {
        for (int index = Math.min(count, peerTaskIds.length) - 1;
                index >= 0;
                index--) {
            final int peerTaskId = peerTaskIds[index];
            if (peerTaskId < 0) {
                continue;
            }
            try {
                DesktopSelfTestInputSuite.restoreFullscreenTaskThroughDesktop(
                        displayId, peerTaskId);
                final Rect restoreBounds = peerRestoreBounds[index];
                waitForTask(
                        displayId,
                        FIXTURE_CLASS,
                        entry -> entry.taskId == peerTaskId
                                && "freeform".equals(entry.windowingMode)
                                && DesktopSelfTestGeometry.matches(
                                        entry.bounds, restoreBounds));
            } catch (IOException ignored) {
                // Global self-test cleanup removes any remaining fixture task.
            }
        }
    }

    private static String formatTaskIds(final int[] taskIds) {
        final StringBuilder result = new StringBuilder();
        for (final int taskId : taskIds) {
            if (result.length() > 0) {
                result.append(',');
            }
            result.append(taskId);
        }
        return result.toString();
    }

    private static String repeatAppRequestedFullscreenRestore(
            final Context appContext,
            final String token,
            final int displayId,
            final int taskId,
            final Rect expectedBounds,
            final SurfaceReferenceResult surfaceReference,
            final int underlyingSurfaceColor,
            final int repetitions) throws IOException {
        final StringBuilder surfaceSamples = new StringBuilder();
        for (int index = 0; index < repetitions; index++) {
            DesktopSelfTestHostObserver.stage(
                    "WINDOW-019-ENTER-" + (index + 1));
            DesktopSelfTestInputSuite.focusTaskThroughDesktop(
                    displayId, taskId);
            waitForFrontTask(displayId, taskId);
            DesktopSelfTestInputSuite.waitForTaskInputFocus(
                    displayId, taskId);
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
                            && entry.visible
                            && "freeform".equals(entry.windowingMode)
                            && DesktopSelfTestGeometry.matches(
                                    entry.bounds, expectedBounds));
            waitForFrontTask(displayId, taskId);
            DesktopSelfTestInputSuite.waitForTaskInputFocus(
                    displayId, taskId);
            appendStableUnderlyingSurfaceSample(
                    surfaceSamples,
                    surfaceReference,
                    underlyingSurfaceColor,
                    "restore-" + (index + 1));
        }
        return "task=" + taskId + ", cycles=" + repetitions
                + ", bounds="
                + DesktopSelfTestGeometry.format(expectedBounds)
                + (surfaceSamples.length() == 0
                        ? ", surface=" + surfaceReference.error
                        : ", surface=" + surfaceSamples);
    }

    private static void appendStableUnderlyingSurfaceSample(
            final StringBuilder samples,
            final SurfaceReferenceResult surfaceReference,
            final int expectedColor,
            final String stage) throws IOException {
        if (surfaceReference.reference == null) {
            return;
        }
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
        if (!DesktopTransitionSurfaceProbe.sameColor(
                expectedColor, actual.color)) {
            throw new IOException("underlying fullscreen surface changed after "
                    + stage
                    + ": expected="
                    + DesktopTransitionSurfaceProbe.formatColor(expectedColor)
                    + ", actual="
                    + DesktopTransitionSurfaceProbe.formatColor(actual.color));
        }
        if (samples.length() > 0) {
            samples.append(',');
        }
        samples.append(stage).append(':').append(
                DesktopTransitionSurfaceProbe.formatColor(actual.color));
    }

    private static String verifyFullscreenFixtureSurface(
            final DisplayCaptureSource captureSource,
            final Rect fullscreenBounds,
            final Rect contentBounds) throws IOException {
        if (fullscreenBounds == null || fullscreenBounds.isEmpty()
                || contentBounds == null || contentBounds.isEmpty()) {
            throw new IOException(
                    "fullscreen fixture or content has no bounds");
        }
        final int edgeTolerance = 2;
        if (contentBounds.left > fullscreenBounds.left + edgeTolerance
                || contentBounds.right
                        < fullscreenBounds.right - edgeTolerance
                || contentBounds.top < fullscreenBounds.top - edgeTolerance
                || contentBounds.bottom
                        > fullscreenBounds.bottom + edgeTolerance) {
            throw new IOException("fullscreen fixture content is outside its"
                    + " task: task="
                    + DesktopSelfTestGeometry.format(fullscreenBounds)
                    + ", content="
                    + DesktopSelfTestGeometry.format(contentBounds));
        }
        final int contentX = contentBounds.left
                + Math.max(1, contentBounds.width() / 4);
        final int markerX = contentBounds.left
                + Math.max(1, contentBounds.width() * 7 / 8);
        final int contentCenterY = contentBounds.centerY();
        final int contentTopY = contentBounds.top
                + Math.min(20, Math.max(1, contentBounds.height() / 8));
        final int topGap = Math.max(
                0, contentBounds.top - fullscreenBounds.top);
        final int bottomGap = Math.max(
                0, fullscreenBounds.bottom - contentBounds.bottom);
        final int unoccupiedHeight = topGap + bottomGap;
        final int letterboxThreshold = Math.max(
                edgeTolerance, fullscreenBounds.height() / 8);
        final boolean letterboxed = unoccupiedHeight > letterboxThreshold;
        if (letterboxed && fullscreenBounds.width()
                >= fullscreenBounds.height()) {
            throw new IOException("landscape desktop unexpectedly letterboxed"
                    + " landscape fixture: task="
                    + DesktopSelfTestGeometry.format(fullscreenBounds)
                    + ", content="
                    + DesktopSelfTestGeometry.format(contentBounds));
        }
        final int gapStart;
        final int gapEnd;
        if (topGap >= bottomGap) {
            gapStart = fullscreenBounds.top;
            gapEnd = contentBounds.top;
        } else {
            gapStart = contentBounds.bottom;
            gapEnd = fullscreenBounds.bottom;
        }
        int contentTopColor = 0;
        int contentCenterColor = 0;
        int markerColor = 0;
        int frameTopColor = 0;
        int gapFirstColor = 0;
        int gapSecondColor = 0;
        final long deadline = SystemClock.uptimeMillis()
                + STEP_TIMEOUT_MILLIS;
        do {
            contentTopColor = captureSurfaceColor(
                    captureSource, contentX, contentTopY);
            contentCenterColor = captureSurfaceColor(
                    captureSource, contentX, contentCenterY);
            markerColor = captureSurfaceColor(
                    captureSource, markerX, contentCenterY);
            boolean settled = isTransitionFixtureColor(contentCenterColor)
                    && DesktopTransitionSurfaceProbe.sameColor(
                            contentTopColor, contentCenterColor)
                    && isSurfaceMarkerColor(markerColor);
            if (settled && !letterboxed) {
                final int frameTopY = fullscreenBounds.top
                        + Math.min(20, Math.max(
                                1, fullscreenBounds.height() / 8));
                frameTopColor = captureSurfaceColor(
                        captureSource, contentX, frameTopY);
                settled = isTransitionFixtureColor(frameTopColor);
            }
            if (settled && letterboxed) {
                final int gapLength = gapEnd - gapStart;
                final int firstY = gapStart + Math.max(1, gapLength / 3);
                final int secondY = gapStart
                        + Math.max(1, gapLength * 2 / 3);
                gapFirstColor = captureSurfaceColor(
                        captureSource, fullscreenBounds.centerX(), firstY);
                gapSecondColor = captureSurfaceColor(
                        captureSource, fullscreenBounds.centerX(), secondY);
                settled = !DesktopTransitionSurfaceProbe.sameColor(
                                contentCenterColor, gapFirstColor)
                        && DesktopTransitionSurfaceProbe.sameColor(
                                gapFirstColor, gapSecondColor);
            }
            if (settled) {
                return "surface=content:"
                        + DesktopTransitionSurfaceProbe.formatColor(
                                contentCenterColor)
                        + ",marker:"
                        + DesktopTransitionSurfaceProbe.formatColor(
                                markerColor)
                        + (letterboxed
                                ? ",letterbox:"
                                        + DesktopTransitionSurfaceProbe
                                                .formatColor(gapFirstColor)
                                        + ",contentBounds="
                                        + DesktopSelfTestGeometry.format(
                                                contentBounds)
                                : ",full,frameTop:"
                                        + DesktopTransitionSurfaceProbe
                                                .formatColor(frameTopColor));
            }
            BoundedStateAwaiter.pause(
                    BoundedStateAwaiter.Reason.TASK_VISIBILITY,
                    POLL_MILLIS);
        } while (SystemClock.uptimeMillis() < deadline);
        if (!isTransitionFixtureColor(contentCenterColor)) {
            throw new IOException("fullscreen fixture content is not rendered: "
                    + DesktopTransitionSurfaceProbe.formatColor(
                            contentCenterColor)
                    + " at " + DesktopSelfTestGeometry.format(contentBounds));
        }
        if (!DesktopTransitionSurfaceProbe.sameColor(
                contentCenterColor, contentTopColor)) {
            throw new IOException("fullscreen fixture leaves a top gap: top="
                    + DesktopTransitionSurfaceProbe.formatColor(
                            contentTopColor)
                    + ", center="
                    + DesktopTransitionSurfaceProbe.formatColor(
                            contentCenterColor));
        }
        if (!isSurfaceMarkerColor(markerColor)) {
            throw new IOException("fullscreen fixture surface marker is not"
                    + " rendered: "
                    + DesktopTransitionSurfaceProbe.formatColor(markerColor));
        }
        if (!letterboxed && !isTransitionFixtureColor(frameTopColor)) {
            throw new IOException("fullscreen fixture leaves a visible top"
                    + " gap: top="
                    + DesktopTransitionSurfaceProbe.formatColor(frameTopColor)
                    + ", content="
                    + DesktopTransitionSurfaceProbe.formatColor(
                            contentCenterColor));
        }
        if (letterboxed) {
            throw new IOException("fixed-orientation letterbox is not uniform: "
                    + DesktopTransitionSurfaceProbe.formatColor(gapFirstColor)
                    + " vs "
                    + DesktopTransitionSurfaceProbe.formatColor(
                            gapSecondColor));
        }
        throw new IOException("fullscreen fixture surface did not settle");
    }

    private static boolean isTransitionFixtureColor(final int color) {
        final int red = (color >>> 16) & 0xFF;
        final int green = (color >>> 8) & 0xFF;
        final int blue = color & 0xFF;
        return blue >= green + 24 && green >= red + 16;
    }

    private static boolean isSurfaceMarkerColor(final int color) {
        return ((color >>> 16) & 0xFF) >= 200
                && ((color >>> 8) & 0xFF) >= 200
                && (color & 0xFF) >= 200;
    }

    private static int captureSurfaceColor(
            final DisplayCaptureSource captureSource,
            final int x,
            final int y) throws IOException {
        final String output = ShellAccess.run(
                DesktopTransitionSurfaceProbe.createCaptureCommand(
                        captureSource, x, y));
        return DesktopTransitionSurfaceProbe.parseReference(
                captureSource, x, y, output).color;
    }

    private static void verifyDesktopSurfaceMatches(
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

    private static DesktopTransitionSurfaceProbe.Observation
            beginSurfaceObservation(
                    final SurfaceReferenceResult surfaceReference) {
        return surfaceReference.reference == null
                ? null : DesktopTransitionSurfaceProbe.begin(
                        surfaceReference.reference);
    }

    private static void sampleDesktopSurface(
            final DesktopTransitionSurfaceProbe.Observation observation,
            final SurfaceReferenceResult surfaceReference,
            final String stage) {
        if (observation == null || surfaceReference.reference == null) {
            return;
        }
        final DesktopTransitionSurfaceProbe.Reference expected =
                surfaceReference.reference;
        try {
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
            observation.sample(stage, actual.color);
        } catch (IOException | IllegalArgumentException error) {
            observation.recordError(usefulMessage(error));
        }
    }

    private static void recordDesktopSurfaceObservation(
            final DesktopSelfTestResult result,
            final String code,
            final String label,
            final SurfaceReferenceResult surfaceReference,
            final DesktopTransitionSurfaceProbe.Observation observation) {
        DesktopSelfTestHostObserver.stage(code);
        if (observation == null || surfaceReference.reference == null) {
            result.add(DesktopSelfTestResult.State.NOT_TESTED,
                    code, label, surfaceReference.error);
            return;
        }
        final DesktopTransitionSurfaceProbe.Result captured =
                observation.finish();
        final String details = String.join(",", captured.samples);
        if (!captured.error.isEmpty()) {
            result.add(DesktopSelfTestResult.State.FAIL,
                    code, label, captured.error + "; " + details);
            return;
        }
        result.add(captured.surfaceChanged
                        ? DesktopSelfTestResult.State.FAIL
                        : DesktopSelfTestResult.State.PASS,
                code,
                label,
                details);
    }

    private static boolean isStableDesktopPixel(
            final int expected,
            final int actual) {
        // A recomposed wallpaper can move a few RGB levels through color
        // conversion. A broken task transition replaces it with a different
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
                BoundedStateAwaiter.pause(
                        BoundedStateAwaiter.Reason.DISPLAY_STATE,
                        POLL_MILLIS);
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
        final boolean preserve = DesktopOperations.isTouchpadVisible();
        if (preserve) {
            MagicDeskRuntime.expectTouchpadDisplacement();
        }
        try {
            return operation.run();
        } finally {
            if (preserve) {
                MagicDeskRuntime.finishTouchpadPreservation();
                DesktopOperations.restoreTouchpadIfMissing();
            }
        }
    }

    private static DesktopSelfTestGeometry verifyDesktopViewport(
            final Context context,
            final int displayId,
            final DisplayCaptureSource captureSource,
            final DesktopSelfTestResult result)
            throws AbortSelfTest {
        return require(result,
                "DESKTOP-003",
                "Verify desktop viewport and taskbar",
                () -> {
            final long deadline = SystemClock.uptimeMillis() + STEP_TIMEOUT_MILLIS;
            String lastDetail = "desktop runtime state unavailable";
            do {
                final DesktopViewport viewport =
                        DesktopRuntimeBridge.getDesktopViewport(displayId);
                final Rect workArea =
                        DesktopRuntimeBridge.getDesktopWorkAreaBounds(displayId);
                final DesktopUiSnapshot ui =
                        DesktopRuntimeBridge.getAutomationUiSnapshot(displayId);
                if (viewport != null && workArea != null && ui != null) {
                    final Rect display = viewport.displayBounds();
                    final Rect taskbar = ui.taskbarBounds;
                    final int densityDpi = displayDensity(context, displayId);
                    final int rotation = displayRotation(context, displayId);
                    lastDetail = "display="
                            + DesktopSelfTestGeometry.format(display)
                            + ", content="
                            + DesktopSelfTestGeometry.format(
                                    viewport.contentBounds())
                            + ", insets=" + viewport.insetLeft()
                            + "," + viewport.insetTop()
                            + "," + viewport.insetRight()
                            + "," + viewport.insetBottom()
                            + ", work="
                            + DesktopSelfTestGeometry.format(workArea)
                            + ", taskbar="
                            + DesktopSelfTestGeometry.format(taskbar)
                            + ", uiAvailable=" + ui.available
                            + ", uiVisible=" + ui.taskbarVisible
                            + ", density=" + densityDpi
                            + ", rotation=" + rotation;
                    if (display.width() > 0
                            && display.height() > 0
                            && ui.available
                            && ui.taskbarVisible
                            && taskbar != null
                            && !taskbar.isEmpty()
                            && display.contains(taskbar)
                            && taskbar.left == viewport.contentLeft()
                            && taskbar.right == viewport.contentRight()
                            && taskbar.bottom == viewport.contentBottom()
                            && workArea.left == viewport.contentLeft()
                            && workArea.right == viewport.contentRight()
                            && workArea.top == viewport.contentTop()
                            && workArea.bottom == taskbar.top
                            && densityDpi > 0
                            && rotation >= 0) {
                        return DesktopSelfTestViewportProbe.withCaptureOutput(
                                context,
                                displayId,
                                captureSource,
                                new DesktopSelfTestGeometry(
                                        display,
                                        workArea,
                                        densityDpi,
                                        rotation));
                    }
                }
                BoundedStateAwaiter.pause(
                        BoundedStateAwaiter.Reason.DISPLAY_STATE,
                        POLL_MILLIS);
            } while (SystemClock.uptimeMillis() < deadline);
            throw new IOException("desktop viewport did not settle: "
                    + lastDetail);
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

    private static Rect currentDesktopBounds(final int displayId) {
        final DesktopViewport viewport =
                DesktopRuntimeBridge.getDesktopViewport(displayId);
        return viewport == null ? new Rect() : viewport.displayBounds();
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
                        BoundedStateAwaiter.pause(
                                BoundedStateAwaiter.Reason.DISPLAY_STATE,
                                POLL_MILLIS);
                    } while (SystemClock.uptimeMillis() < deadline);
                    throw new IOException(
                            "desktop wallpaper did not finish rendering");
                });
    }

    private static DesktopTaskLaunchProbe.Observation launchFixtureAndObserve(
            final int displayId,
            final String token,
            final Rect bounds,
            final DesktopSelfTestFixtureAppearance appearance)
            throws IOException {
        return launchFixtureAndObserve(
                displayId,
                bounds,
                FIXTURE_CLASS,
                TaskDisplayAreaLaunchCommand.createSelfTestIntent(
                        displayId, token, false, appearance));
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
                        displayId,
                        token,
                        true,
                        DesktopSelfTestFixtureAppearance.TRANSITION));
    }

    private static DesktopTaskLaunchProbe.Observation launchFixtureAndObserve(
            final int displayId,
            final Rect bounds,
            final String fixtureClass,
            final Intent launchIntent) throws IOException {
        return DesktopSelfTestTasks.launchWindowedAndObserve(
                displayId, bounds, fixtureClass, launchIntent);
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
        if (DesktopTaskTransfer.usesDirectRoot(displayId)) {
            return reopenRootTask(
                    displayId, taskId, bounds, surfaceReference);
        }
        return reopenTask(displayId, taskId, bounds, surfaceReference);
    }

    private static TaskTransferObservation reopenRootTask(
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
                    DesktopTaskTransfer.createDirectRootCommand(
                            taskId,
                            currentTask.rootTaskId,
                            currentTask.displayId,
                            displayId,
                            DesktopTaskTransfer.Mode.FREEFORM,
                            bounds,
                            surfaceReference.reference));
            if (!output.contains("task-freeform-move=" + taskId)) {
                throw new IOException(output.trim());
            }
            if (!output.contains("source-prepared-visible=false")) {
                throw new IOException(
                        "source task preparation was not hidden");
            }
            if (!output.contains("target-prepared-visible=false")) {
                throw new IOException(
                        "target task preparation was not hidden");
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
                    true);
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
                final String output = DesktopTaskTransfer.move(
                        taskId,
                        currentTask.rootTaskId,
                        currentTask.displayId,
                        displayId,
                        DesktopTaskTransfer.Mode.FULLSCREEN,
                        null);
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
            final String output = DesktopTaskTransfer.move(
                    taskId,
                    currentTask.rootTaskId,
                    currentTask.displayId,
                    displayId,
                    DesktopTaskTransfer.Mode.FREEFORM,
                    bounds);
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
        final boolean hiddenRootTransferPreparation;

        TaskTransferObservation(
                final DesktopTaskLaunchProbe.Observation firstFront,
                final boolean surfaceChanged,
                final String pixelSamples,
                final String probeError) {
            this(firstFront, surfaceChanged, pixelSamples, probeError, false);
        }

        TaskTransferObservation(
                final DesktopTaskLaunchProbe.Observation firstFront,
                final boolean surfaceChanged,
                final String pixelSamples,
                final String probeError,
                final boolean hiddenRootTransferPreparation) {
            this.firstFront = firstFront;
            this.surfaceChanged = surfaceChanged;
            this.pixelSamples = pixelSamples == null ? "" : pixelSamples;
            this.probeError = probeError == null ? "" : probeError;
            this.hiddenRootTransferPreparation =
                    hiddenRootTransferPreparation;
        }

        @Override
        public String toString() {
            return firstFront + ", pixels=" + pixelSamples
                    + (hiddenRootTransferPreparation
                            ? ", source+target-prepared=hidden" : "")
                    + (probeError.isEmpty()
                            ? "" : ", probe-error=" + probeError);
        }
    }

    private static final class SettledWindowLaunch {
        final DesktopTaskLaunchProbe.Observation firstFront;
        final TaskStackParser.Entry settled;

        SettledWindowLaunch(
                final DesktopTaskLaunchProbe.Observation observedFirstFront,
                final TaskStackParser.Entry observedSettled) {
            firstFront = observedFirstFront;
            settled = observedSettled;
        }

        @Override
        public String toString() {
            return "first-front=" + firstFront
                    + ", settled=task=" + settled.taskId
                    + "/display=" + settled.displayId
                    + "/mode=" + settled.windowingMode
                    + "/bounds="
                    + DesktopSelfTestGeometry.format(settled.bounds);
        }
    }

    private static SurfaceReferenceResult captureSurfaceReference(
            final DisplayCaptureSource captureSource,
            final DesktopSelfTestGeometry geometry) {
        return captureSurfaceReference(
                captureSource,
                geometry.workArea.centerX(),
                geometry.workArea.top
                        + geometry.workArea.height() * 90 / 100);
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

    private static void runFullscreenPlaneExitPreflight(
            final DesktopSelfTestResult result,
            final int displayId,
            final int taskId,
            final Rect restoreBounds,
            final SurfaceReferenceResult surfaceReference)
            throws AbortSelfTest {
        final DesktopSelfTestTaskHierarchy.Snapshot initial = require(
                result,
                "FULLSCREEN-PLANE-EXIT-001",
                "Capture initial task hierarchy",
                () -> DesktopSelfTestTaskHierarchy.inspect(
                        displayId, taskId));
        final DesktopTransitionSurfaceProbe.Observation surfaceObservation =
                beginSurfaceObservation(surfaceReference);
        sampleDesktopSurface(
                surfaceObservation, surfaceReference, "before");

        final DesktopSelfTestTaskHierarchy.Snapshot firstFullscreen = require(
                result,
                "FULLSCREEN-PLANE-EXIT-002",
                "Enter fullscreen through the desktop transition gateway",
                () -> arrangeTaskAndWaitForHierarchy(
                        displayId,
                        taskId,
                        DesktopTaskController.SHORTCUT_FULLSCREEN,
                        WINDOWING_MODE_FULLSCREEN,
                        null,
                        null));
        final DesktopSelfTestTaskHierarchy.Snapshot firstRestored = require(
                result,
                "FULLSCREEN-PLANE-EXIT-003",
                "Leave fullscreen and restore the original task parent",
                () -> arrangeTaskAndWaitForHierarchy(
                        displayId,
                        taskId,
                        DesktopTaskController.SHORTCUT_RESTORE,
                        WINDOWING_MODE_FREEFORM,
                        restoreBounds,
                        Integer.valueOf(initial.featureId)));
        sampleDesktopSurface(
                surfaceObservation, surfaceReference, "first-restored");

        require(
                result,
                "FULLSCREEN-PLANE-EXIT-004",
                "Repeat fullscreen parent creation and release",
                () -> {
                    final DesktopSelfTestTaskHierarchy.Snapshot fullscreen =
                            arrangeTaskAndWaitForHierarchy(
                                    displayId,
                                    taskId,
                                    DesktopTaskController.SHORTCUT_FULLSCREEN,
                                    WINDOWING_MODE_FULLSCREEN,
                                    null,
                                    null);
                    DesktopSelfTestHostObserver.stage(
                            "FULLSCREEN-PLANE-EXIT-004-RESTORE");
                    final DesktopSelfTestTaskHierarchy.Snapshot restored =
                            arrangeTaskAndWaitForHierarchy(
                                    displayId,
                                    taskId,
                                    DesktopTaskController.SHORTCUT_RESTORE,
                                    WINDOWING_MODE_FREEFORM,
                                    restoreBounds,
                                    Integer.valueOf(initial.featureId));
                    if (!DesktopRuntimeBridge.isDesktopWallpaperRendered(
                            displayId)) {
                        throw new IOException(
                                "desktop wallpaper is not rendered after"
                                        + " fullscreen plane release");
                    }
                    return "first=" + firstFullscreen.featureId
                            + "->" + firstRestored.featureId
                            + ", second=" + fullscreen.featureId
                            + "->" + restored.featureId
                            + ", initial=" + initial.featureId;
                });
        sampleDesktopSurface(
                surfaceObservation, surfaceReference, "second-restored");
        recordDesktopSurfaceObservation(
                result,
                "FULLSCREEN-PLANE-EXIT-SURFACE-001",
                "Preserve the desktop surface across fullscreen plane release",
                surfaceReference,
                surfaceObservation);
    }

    private static DesktopSelfTestTaskHierarchy.Snapshot
            arrangeTaskAndWaitForHierarchy(
                    final int displayId,
                    final int taskId,
                    final int shortcut,
                    final int expectedMode,
                    final Rect expectedBounds,
                    final Integer expectedFeatureId) throws IOException {
        if (!MagicDeskRuntime.arrangeTask(taskId, shortcut)) {
            throw new IOException("desktop task transition is unavailable");
        }
        waitForTask(
                displayId,
                FIXTURE_CLASS,
                entry -> entry.taskId == taskId
                        && (expectedMode == WINDOWING_MODE_FULLSCREEN
                                ? "fullscreen".equals(entry.windowingMode)
                                : "freeform".equals(entry.windowingMode))
                        && (expectedBounds == null
                                || DesktopSelfTestGeometry.matches(
                                        entry.bounds, expectedBounds)));
        waitForFrontTask(displayId, taskId);
        // An organized child can own the display's input focus while Nubia
        // still reports TaskInfo.isFocused on its structural backstop. Verify
        // the user-visible focus contract against InputDispatcher instead.
        DesktopSelfTestInputSuite.waitForTaskInputFocus(displayId, taskId);

        final long deadline = SystemClock.uptimeMillis()
                + STEP_TIMEOUT_MILLIS;
        DesktopSelfTestTaskHierarchy.Snapshot observed = null;
        do {
            observed = DesktopSelfTestTaskHierarchy.inspect(
                    displayId, taskId);
            if (observed.displayId == displayId
                    && observed.windowingMode == expectedMode
                    && observed.visible
                    && (expectedFeatureId == null
                            || observed.featureId
                                    == expectedFeatureId.intValue())) {
                return observed;
            }
            BoundedStateAwaiter.pause(
                    BoundedStateAwaiter.Reason.TASK_HIERARCHY,
                    POLL_MILLIS);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new IOException("task hierarchy did not settle: observed="
                + observed
                + ", expected-mode=" + expectedMode
                + (expectedFeatureId == null
                        ? "" : ", expected-feature=" + expectedFeatureId));
    }

    private static void runTwoWindowFocusTests(
            final Context context,
            final DesktopSelfTestResult result,
            final int displayId,
            final DisplayCaptureSource captureSource,
            final int firstTaskId,
            final String firstToken,
            final DesktopSelfTestGeometry geometry) throws AbortSelfTest {
        final DesktopSelfTestGeometry currentInputGeometry = require(
                result,
                "DISPLAY-004",
                "Refresh input viewport after application fullscreen",
                () -> DesktopSelfTestViewportProbe.awaitInputViewport(
                        context, displayId, captureSource, geometry));
        final Rect leftBounds = geometry.leftWindow();
        final Rect rightBounds = geometry.rightWindow();
        final SurfaceReferenceResult surfaceReference =
                captureSurfaceReference(captureSource, geometry);
        final DesktopTransitionSurfaceProbe.Observation surfaceObservation =
                beginSurfaceObservation(surfaceReference);
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
                                            rightBounds,
                                            DesktopSelfTestFixtureAppearance
                                                    .SECONDARY));
                    if (observation.taskId == firstTaskId) {
                        throw new IOException(
                                "Android reused task " + firstTaskId);
                    }
                    return observation;
                });
        sampleDesktopSurface(
                surfaceObservation, surfaceReference, "front");
        final int secondTaskId = secondLaunch.taskId;
        require(result, "WINDOW-012", "Place two freeform test windows", () -> {
            DesktopSelfTestFixtureState.awaitFirstFrame(
                    context, secondToken, displayId);
            sampleDesktopSurface(
                    surfaceObservation, surfaceReference, "first-frame");
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
            sampleDesktopSurface(
                    surfaceObservation, surfaceReference, "settled");
            return "left=" + firstTaskId + ", right=" + secondTaskId;
        });
        recordDesktopSurfaceObservation(
                result,
                "WINDOW-SURFACE-002",
                "Keep desktop background during second window launch",
                surfaceReference,
                surfaceObservation);

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
                currentInputGeometry);
        final FocusWindowPair freshPair = require(
                result,
                "WINDOW-021",
                "Recreate ordinary freeform windows after native snap",
                () -> recreateFocusWindowPair(
                        context,
                        displayId,
                        firstTaskId,
                        secondTaskId,
                        currentInputGeometry));
        DesktopSelfTestInputSuite.runMaximizedAndFullscreenTests(
                context,
                result,
                displayId,
                freshPair.firstTaskId,
                freshPair.firstToken,
                freshPair.secondTaskId,
                freshPair.secondToken,
                currentInputGeometry);
        DesktopSelfTestBackNavigationSuite.run(
                context, result, displayId, geometry);
        DesktopSelfTestInputSuite.runMixedFullscreenFreeformTest(
                context,
                result,
                displayId,
                captureSource,
                currentInputGeometry);
    }

    private static FocusWindowPair recreateFocusWindowPair(
            final Context context,
            final int displayId,
            final int previousFirstTaskId,
            final int previousSecondTaskId,
            final DesktopSelfTestGeometry geometry) throws Exception {
        // Native snap state belongs to WMShell. Close the old pair through the
        // same desktop lifecycle as a user action before creating ordinary
        // freeform tasks; the emergency cleanup route intentionally performs
        // stronger phone-repository recovery and is not a normal close.
        closeTaskThroughDesktop(displayId, previousSecondTaskId);
        closeTaskThroughDesktop(displayId, previousFirstTaskId);
        DesktopSelfTestFixtureState.clearLaunchMarkers(context);

        final String firstToken = Long.toHexString(System.nanoTime());
        final Rect firstBounds = geometry.captionControlsWindow(false);
        final DesktopTaskLaunchProbe.Observation first =
                preservePhoneTouchpad(() -> launchFixtureAndObserve(
                        displayId,
                        firstToken,
                        firstBounds,
                        DesktopSelfTestFixtureAppearance.PRIMARY));
        DesktopSelfTestPhoneUiObserver.allowPhoneFixtureTask(first.taskId);
        DesktopSelfTestFixtureState.awaitFirstFrame(
                context, firstToken, displayId);

        final String secondToken = Long.toHexString(System.nanoTime());
        final Rect secondBounds = geometry.captionControlsWindow(true);
        final DesktopTaskLaunchProbe.Observation second =
                preservePhoneTouchpad(() -> launchFixtureAndObserve(
                        displayId,
                        secondToken,
                        secondBounds,
                        DesktopSelfTestFixtureAppearance.SECONDARY));
        if (second.taskId == first.taskId) {
            throw new IOException("Android reused fresh task " + first.taskId);
        }
        DesktopSelfTestPhoneUiObserver.allowPhoneFixtureTask(second.taskId);
        DesktopSelfTestFixtureState.awaitFirstFrame(
                context, secondToken, displayId);
        waitForTask(
                displayId,
                FIXTURE_CLASS,
                task -> task.taskId == first.taskId
                        && task.visible
                        && "freeform".equals(task.windowingMode)
                        && DesktopSelfTestGeometry.matches(
                                task.bounds, firstBounds));
        waitForTask(
                displayId,
                FIXTURE_CLASS,
                task -> task.taskId == second.taskId
                        && task.visible
                        && "freeform".equals(task.windowingMode)
                        && DesktopSelfTestGeometry.matches(
                                task.bounds, secondBounds));
        return new FocusWindowPair(
                first.taskId,
                firstToken,
                second.taskId,
                secondToken);
    }

    private static void closeTaskThroughDesktop(
            final int displayId,
            final int taskId) throws IOException {
        final TaskRepository.Snapshot snapshot =
                TaskRepository.loadNow(displayId);
        if (!snapshot.available) {
            throw new IOException("desktop task snapshot unavailable: "
                    + snapshot.error);
        }
        TaskRepository.TaskEntry target = null;
        for (final TaskRepository.TaskEntry task : snapshot.tasks) {
            if (task.taskId == taskId) {
                target = task;
                break;
            }
        }
        if (target == null) {
            throw new IOException("desktop task " + taskId
                    + " disappeared before close");
        }

        final CountDownLatch completion = new CountDownLatch(1);
        final TaskRepository.ActionResult[] action =
                new TaskRepository.ActionResult[1];
        MagicDeskRuntime.closeTask(target, result -> {
            action[0] = result;
            completion.countDown();
        });
        try {
            if (!completion.await(
                    STEP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                throw new IOException(
                        "desktop task close timed out: " + taskId);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException(
                    "desktop task close interrupted: " + taskId,
                    error);
        }
        if (action[0] == null || !action[0].success) {
            throw new IOException("desktop task close failed: " + taskId
                    + (action[0] == null || action[0].message.isEmpty()
                            ? "" : ": " + action[0].message));
        }
        waitForTaskAbsent(taskId);
    }

    private static final class FocusWindowPair {
        final int firstTaskId;
        final String firstToken;
        final int secondTaskId;
        final String secondToken;

        FocusWindowPair(
                final int firstTaskId,
                final String firstToken,
                final int secondTaskId,
                final String secondToken) {
            this.firstTaskId = firstTaskId;
            this.firstToken = firstToken;
            this.secondTaskId = secondTaskId;
            this.secondToken = secondToken;
        }

        @Override
        public String toString() {
            return "first=" + firstTaskId + ", second=" + secondTaskId;
        }
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
            BoundedStateAwaiter.pause(
                    BoundedStateAwaiter.Reason.INPUT_FOCUS,
                    POLL_MILLIS);
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
