package io.github.mekhontsev.magicdesk;

import static io.github.mekhontsev.magicdesk.DesktopSelfTestSteps.check;
import static io.github.mekhontsev.magicdesk.DesktopSelfTestSteps.usefulMessage;
import static io.github.mekhontsev.magicdesk.DesktopSelfTestTasks.POLL_MILLIS;
import static io.github.mekhontsev.magicdesk.DesktopSelfTestTasks.STEP_TIMEOUT_MILLIS;
import static io.github.mekhontsev.magicdesk.DesktopSelfTestTasks.waitForFrontTask;
import static io.github.mekhontsev.magicdesk.DesktopSelfTestTasks.waitForTask;
import static io.github.mekhontsev.magicdesk.DesktopSelfTestTasks.waitForTaskAbsent;

import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Exercises native input windows, pointer behavior, and task focus. */
final class DesktopSelfTestInputSuite {
    private static final String FIXTURE_CLASS =
            DesktopSelfTestComponents.FIXTURE_CLASS;
    private static final int RESIZE_EDGE_OUTSET_PX = 8;
    // Current WMShell caption geometry, using 160 dpi as the baseline.
    private static final int CAPTION_BUTTON_CENTER_Y_PX = 20;
    private static final int MAXIMIZE_BUTTON_CENTER_FROM_RIGHT_PX = 82;
    private static final int SNAP_LEFT_CENTER_FROM_MENU_RIGHT_DP = 96;
    private static final int SNAP_RIGHT_CENTER_FROM_MENU_RIGHT_DP = 44;
    private static final int SNAP_BUTTON_CENTER_FROM_MENU_TOP_DP = 46;

    private enum InputCoordinateSpace {
        DISPLAY,
        NATURAL,
        TASK_LOCAL
    }

    private DesktopSelfTestInputSuite() {
    }

    static void runInitialWindowChecks(
            final DesktopSelfTestResult result,
            final Context context,
            final int displayId,
            final DisplayCaptureSource captureSource,
            final int taskId,
            final String token,
            final Rect bounds,
            final DesktopSelfTestGeometry geometry,
            final CaptionReference captionReference) {
        verifyCaptionStructure(
                result,
                "CAPTION-001",
                "Verify native caption structure",
                taskId,
                bounds);
        verifyCaptionSurface(
                result,
                "CAPTION-SURFACE-001",
                "Verify native caption surface",
                taskId);
        verifyCaptionRendering(
                result,
                "CAPTION-003",
                "Verify native caption rendering",
                captureSource,
                taskId,
                bounds,
                captionReference);
        check(result, "INPUT-001", "Route input to selected display", () -> {
            DesktopSelfTestFixtureState.clearText(context);
            final int x = bounds.centerX();
            final int y = bounds.centerY();
            requirePointerHover(displayId, x, y);
            ShellAccess.run("/system/bin/input mouse -d "
                    + displayId + " tap " + x + " " + y);
            typeAndVerifyText(context, displayId, taskId, token, "0");
            return "tap=" + x + "," + y;
        });
        verifyNativeInputWindows(
                result, displayId, taskId, bounds, geometry);
        verifyResizeCursor(result, displayId, taskId, geometry);
    }

    static void verifyCaptionStructure(
            final DesktopSelfTestResult result,
            final String code,
            final String label,
            final int taskId,
            final Rect bounds) {
        check(result, code, label, () ->
                awaitCaptionStructure(taskId, bounds));
    }

    static void verifyCaptionSurface(
            final DesktopSelfTestResult result,
            final String code,
            final String label,
            final int taskId) {
        check(result, code, label, () -> waitForVisibleCaptionSurface(taskId));
    }

    static void verifyCaptionRendering(
            final DesktopSelfTestResult result,
            final String code,
            final String label,
            final DisplayCaptureSource captureSource,
            final int taskId,
            final Rect bounds,
            final CaptionReference reference) {
        DesktopSelfTestHostObserver.stage(code);
        final long deadline = SystemClock.uptimeMillis()
                + STEP_TIMEOUT_MILLIS;
        String lastDetail = "caption remained visually uniform";
        do {
            final ShellAccess.CommandResult response;
            try {
                response = ShellAccess.executeForConsole(
                        TaskCaptionRenderCommand.createCommand(
                                captureSource,
                                taskId,
                                bounds,
                                reference.crop));
            } catch (IOException error) {
                result.add(DesktopSelfTestResult.State.FAIL,
                        code, label, usefulMessage(error));
                return;
            }
            if (response.exitCode == 2) {
                result.add(DesktopSelfTestResult.State.NOT_TESTED,
                        code, label, response.output.trim());
                return;
            }
            if (response.exitCode == 0) {
                try {
                    final TaskCaptionRenderCommand.Observation observation =
                            TaskCaptionRenderCommand.parseObservation(
                                    response.output);
                    final DisplayPixelProbe.RegionDifference difference =
                            reference.observation == null
                                    ? null : observation.signature.compare(
                                            reference.observation.signature);
                    lastDetail = observation.detail()
                            + reference.detail(difference);
                    if (observation.visuallyVaried
                            && (difference == null
                                    || difference.materiallyDifferent)) {
                        result.add(DesktopSelfTestResult.State.PASS,
                                code, label, lastDetail);
                        return;
                    }
                } catch (IOException error) {
                    lastDetail = usefulMessage(error);
                }
            } else {
                lastDetail = response.output.trim();
            }
            SystemClock.sleep(POLL_MILLIS);
        } while (SystemClock.uptimeMillis() < deadline);
        result.add(DesktopSelfTestResult.State.FAIL,
                code, label, lastDetail);
    }

    static CaptionReference captureCaptionReference(
            final DisplayCaptureSource captureSource,
            final Rect windowBounds,
            final DesktopSelfTestGeometry geometry) {
        final Rect crop = geometry.captionRenderSample(windowBounds);
        try {
            final ShellAccess.CommandResult response =
                    ShellAccess.executeForConsole(
                            TaskCaptionRenderCommand.createReferenceCommand(
                                    captureSource, crop));
            if (response.exitCode != 0) {
                return CaptionReference.unavailable(
                        crop, response.output.trim());
            }
            return CaptionReference.available(
                    crop,
                    TaskCaptionRenderCommand.parseObservation(
                            response.output));
        } catch (IOException | IllegalArgumentException error) {
            return CaptionReference.unavailable(
                    crop, usefulMessage(error));
        }
    }

    static CaptionReference alignCaptionReference(
            final CaptionReference reference,
            final Rect windowBounds,
            final DesktopSelfTestGeometry geometry) {
        final Rect crop = geometry.captionRenderSample(windowBounds);
        if (reference != null && crop.equals(reference.crop)) {
            return reference;
        }
        return CaptionReference.unavailable(
                crop,
                "window bounds changed after the desktop background sample");
    }

    static String restoreTaskFocus(
            final int displayId,
            final int taskId) throws IOException {
        focusTaskThroughDesktop(displayId, taskId);
        waitForTask(displayId, FIXTURE_CLASS,
                entry -> entry.taskId == taskId
                        && "freeform".equals(entry.windowingMode));
        waitForFrontTask(displayId, taskId);
        waitForTaskInputFocus(displayId, taskId);
        return "task=" + taskId;
    }

    static final class CaptionReference {
        final Rect crop;
        final TaskCaptionRenderCommand.Observation observation;
        final String error;

        private CaptionReference(
                final Rect crop,
                final TaskCaptionRenderCommand.Observation observation,
                final String error) {
            this.crop = new Rect(crop);
            this.observation = observation;
            this.error = error == null ? "" : error;
        }

        static CaptionReference available(
                final Rect crop,
                final TaskCaptionRenderCommand.Observation observation) {
            return new CaptionReference(crop, observation, "");
        }

        static CaptionReference unavailable(
                final Rect crop,
                final String error) {
            return new CaptionReference(crop, null, error);
        }

        String detail(final DisplayPixelProbe.RegionDifference difference) {
            if (difference == null) {
                return error.isEmpty()
                        ? ", desktop comparison unavailable"
                        : ", desktop comparison unavailable: " + error;
            }
            return ", changed-from-desktop=" + difference.changedPixels
                    + "/" + difference.totalPixels;
        }
    }

    static String awaitCaptionStructure(
            final int taskId, final Rect bounds) throws IOException {
        final long deadline = SystemClock.uptimeMillis()
                + STEP_TIMEOUT_MILLIS;
        IOException lastFailure = null;
        do {
            try {
                return ShellAccess.run(AppProcessCommand.run(
                        "io.github.mekhontsev.magicdesk"
                                + ".TaskCaptionStructureCommand",
                        taskId + " "
                                + bounds.left + " " + bounds.top + " "
                                + bounds.right + " " + bounds.bottom)).trim();
            } catch (IOException error) {
                lastFailure = error;
            }
            SystemClock.sleep(POLL_MILLIS);
        } while (SystemClock.uptimeMillis() < deadline);
        throw lastFailure == null
                ? new IOException("caption structure is unavailable")
                : lastFailure;
    }

    private static String waitForVisibleCaptionSurface(
            final int taskId) throws IOException {
        final long deadline = SystemClock.uptimeMillis()
                + STEP_TIMEOUT_MILLIS;
        TaskCaptionSurfaceCommand.State lastState =
                TaskCaptionSurfaceCommand.State.ABSENT;
        do {
            final ShellAccess.CommandResult response =
                    ShellAccess.executeForConsole(
                            TaskCaptionSurfaceCommand.createCommand(taskId));
            if (response.exitCode != 0) {
                throw new IOException(response.output.trim());
            }
            lastState = TaskCaptionSurfaceCommand.parseResult(
                    response.output, taskId).get(Integer.valueOf(taskId));
            if (lastState == TaskCaptionSurfaceCommand.State.VISIBLE) {
                return "task=" + taskId + ", surface=" + lastState.label;
            }
            SystemClock.sleep(POLL_MILLIS);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new IOException("caption surface is " + lastState.label
                + " for task " + taskId);
    }

    private static void verifyNativeInputWindows(
            final DesktopSelfTestResult result,
            final int displayId,
            final int taskId,
            final Rect bounds,
            final DesktopSelfTestGeometry geometry) {
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
                inspectCaptionInputWindow(
                        dump, displayId, taskId, bounds, geometry));
        check(result, "INPUT-003", "Verify native resize input window", () ->
                inspectResizeInputWindow(
                        dump, displayId, taskId, bounds, geometry));
    }

    private static void verifyResizeCursor(
            final DesktopSelfTestResult result,
            final int displayId,
            final int taskId,
            final DesktopSelfTestGeometry geometry) {
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
                    task.bounds.right
                            + geometry.scaleFrom160Dpi(
                                    RESIZE_EDGE_OUTSET_PX),
                    centerY);
            SystemClock.sleep(POLL_MILLIS);
            final String transition = probe.readPointerTransition();
            if (transition == null) {
                result.add(DesktopSelfTestResult.State.NOT_TESTED,
                        "INPUT-004", "Show native mouse resize cursor",
                        "the selected display exposes no visual cursor state and "
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

    static void runWindowFocusTests(
            final Context context,
            final DesktopSelfTestResult result,
            final int displayId,
            final int firstTaskId,
            final String firstToken,
            final Rect leftBounds,
            final int secondTaskId,
            final String secondToken,
            final Rect rightBounds,
            final DesktopSelfTestGeometry geometry) {
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
                secondToken,
                geometry);
        check(result,
                "FOCUS-008",
                "Switch text focus through Alt+Tab",
                () -> focusFieldThroughAltTab(
                        context,
                        displayId,
                        firstTaskId,
                        firstToken,
                        "8"));
        check(result,
                "FOCUS-009",
                "Switch text focus back through Alt+Tab",
                () -> focusFieldThroughAltTab(
                        context,
                        displayId,
                        secondTaskId,
                        secondToken,
                        "9"));
        runFullscreenTaskbarTest(
                context,
                result,
                displayId,
                firstTaskId,
                secondTaskId,
                secondToken);
        runMaximizedAltTabTests(
                context,
                result,
                displayId,
                firstTaskId,
                firstToken,
                secondTaskId,
                secondToken,
                geometry);
        runFullscreenAltTabTests(
                context,
                result,
                displayId,
                firstTaskId,
                firstToken,
                secondTaskId,
                secondToken,
                geometry);
    }

    private static void runMaximizedAltTabTests(
            final Context context,
            final DesktopSelfTestResult result,
            final int displayId,
            final int firstTaskId,
            final String firstToken,
            final int secondTaskId,
            final String secondToken,
            final DesktopSelfTestGeometry geometry) {
        final String preparationCode = "MAXIMIZED-ALT-TAB-001";
        DesktopSelfTestHostObserver.stage(preparationCode);
        final MaximizedTaskPair pair;
        try {
            // The application-fullscreen fixture immediately before this
            // phase changes system-bar visibility. Native caption maximize
            // uses the resulting WMS work area, so discard the geometry
            // captured before that transition once the live viewport settles.
            final DesktopSelfTestGeometry currentGeometry =
                    DesktopSelfTestViewportProbe.await(
                            context, displayId, geometry);
            pair = prepareMaximizedPair(
                    displayId,
                    firstTaskId,
                    secondTaskId,
                    currentGeometry);
            result.add(DesktopSelfTestResult.State.PASS,
                    preparationCode,
                    "Prepare two maximized windows",
                    pair.describe());
        } catch (Exception error) {
            result.add(DesktopSelfTestResult.State.FAIL,
                    preparationCode,
                    "Prepare two maximized windows",
                    usefulMessage(error));
            result.add(DesktopSelfTestResult.State.NOT_TESTED,
                    "MAXIMIZED-ALT-TAB-002",
                    "Switch between two maximized windows",
                    "maximized pair preparation failed");
            result.add(DesktopSelfTestResult.State.NOT_TESTED,
                    "MAXIMIZED-ALT-TAB-003",
                    "Switch back between two maximized windows",
                    "maximized pair preparation failed");
            return;
        }
        check(result,
                "MAXIMIZED-ALT-TAB-002",
                "Switch between two maximized windows",
                () -> focusMaximizedPairThroughAltTab(
                        context,
                        displayId,
                        firstTaskId,
                        firstToken,
                        secondTaskId,
                        pair.firstBounds,
                        pair.secondBounds,
                        "0"));
        check(result,
                "MAXIMIZED-ALT-TAB-003",
                "Switch back between two maximized windows",
                () -> focusMaximizedPairThroughAltTab(
                        context,
                        displayId,
                        secondTaskId,
                        secondToken,
                        firstTaskId,
                        pair.secondBounds,
                        pair.firstBounds,
                        "1"));
    }

    private static MaximizedTaskPair prepareMaximizedPair(
            final int displayId,
            final int firstTaskId,
            final int secondTaskId,
            final DesktopSelfTestGeometry geometry) throws IOException {
        // Native side-by-side placement can make the caption responsive and
        // collapse its trailing controls on narrow displays. Give each task a
        // full caption-controls baseline before clicking the real maximize
        // button so this phase does not inherit geometry from the snap test.
        setWindowedBounds(
                displayId,
                firstTaskId,
                geometry.captionControlsWindow(false));
        setWindowedBounds(
                displayId,
                secondTaskId,
                geometry.captionControlsWindow(true));
        maximizeThroughNativeCaption(
                displayId, firstTaskId, geometry);
        maximizeThroughNativeCaption(
                displayId, secondTaskId, geometry);
        waitForFrontTask(displayId, secondTaskId);
        waitForTaskInputFocus(displayId, secondTaskId);
        return waitForMaximizedPair(
                displayId,
                firstTaskId,
                secondTaskId,
                geometry);
    }

    private static void maximizeThroughNativeCaption(
            final int displayId,
            final int taskId,
            final DesktopSelfTestGeometry geometry) throws IOException {
        focusTaskThroughDesktop(displayId, taskId);
        final TaskStackParser.Entry task = waitForTask(
                displayId,
                FIXTURE_CLASS,
                entry -> entry.taskId == taskId
                        && entry.visible
                        && "freeform".equals(entry.windowingMode));
        waitForFrontTask(displayId, taskId);
        final Rect bounds = DesktopSelfTestGeometry.toRect(task.bounds);
        waitForCaptionInputFrame(displayId, taskId, bounds, geometry);
        final int x = bounds.right - geometry.scaleFrom160Dpi(
                MAXIMIZE_BUTTON_CENTER_FROM_RIGHT_PX);
        final int y = bounds.top + geometry.scaleFrom160Dpi(
                CAPTION_BUTTON_CENTER_Y_PX);
        requireProductionPointerClick(displayId, x, y);
        waitForTask(
                displayId,
                FIXTURE_CLASS,
                entry -> entry.taskId == taskId
                        && entry.visible
                        && "freeform".equals(entry.windowingMode)
                        && !DesktopSelfTestGeometry.matches(entry.bounds, bounds)
                        && isMaximizedBounds(
                                entry.bounds, geometry));
    }

    private static MaximizedTaskPair waitForMaximizedPair(
            final int displayId,
            final int firstTaskId,
            final int secondTaskId,
            final DesktopSelfTestGeometry geometry) throws IOException {
        final long deadline = SystemClock.uptimeMillis()
                + STEP_TIMEOUT_MILLIS;
        MaximizedTaskPair previous = null;
        do {
            final MaximizedTaskPair current = readMaximizedPair(
                    displayId,
                    firstTaskId,
                    secondTaskId,
                    geometry);
            if (current != null && current.sameBounds(previous)) {
                return current;
            }
            previous = current;
            SystemClock.sleep(POLL_MILLIS);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new IOException(
                "native caption did not leave both tasks maximized");
    }

    private static String focusMaximizedPairThroughAltTab(
            final Context context,
            final int displayId,
            final int targetTaskId,
            final String targetToken,
            final int otherTaskId,
            final Rect targetBounds,
            final Rect otherBounds,
            final String digit) throws IOException {
        final String focus = focusFieldThroughAltTab(
                context,
                displayId,
                targetTaskId,
                targetToken,
                digit);
        return focus + ", " + inspectMaximizedPair(
                displayId,
                targetTaskId,
                otherTaskId,
                targetBounds,
                otherBounds);
    }

    private static String inspectMaximizedPair(
            final int displayId,
            final int targetTaskId,
            final int otherTaskId,
            final Rect targetBounds,
            final Rect otherBounds) throws IOException {
        TaskStackParser.Entry target = null;
        TaskStackParser.Entry other = null;
        for (final TaskStackParser.Entry task : TaskStackParser.parse(
                ShellAccess.run("/system/bin/cmd activity stack list"))) {
            if (task.displayId != displayId) {
                continue;
            }
            if (task.taskId == targetTaskId) {
                target = task;
            } else if (task.taskId == otherTaskId) {
                other = task;
            }
        }
        if (target == null || other == null) {
            throw new IOException("maximized task pair is incomplete");
        }
        if (!"freeform".equals(target.windowingMode)
                || !"freeform".equals(other.windowingMode)) {
            throw new IOException("maximized modes changed: target="
                    + target.windowingMode + ", other="
                    + other.windowingMode);
        }
        if (!DesktopSelfTestGeometry.matches(target.bounds, targetBounds)
                || !DesktopSelfTestGeometry.matches(other.bounds, otherBounds)) {
            throw new IOException("maximized bounds changed: target="
                    + DesktopSelfTestGeometry.format(target.bounds) + ", other="
                    + DesktopSelfTestGeometry.format(other.bounds));
        }
        if (!target.visible) {
            throw new IOException("maximized target is not visible");
        }
        return "target=" + targetTaskId + "/maximized/visible"
                + ", other=" + otherTaskId + "/maximized";
    }

    private static MaximizedTaskPair readMaximizedPair(
            final int displayId,
            final int firstTaskId,
            final int secondTaskId,
            final DesktopSelfTestGeometry geometry) throws IOException {
        TaskStackParser.Entry first = null;
        TaskStackParser.Entry second = null;
        for (final TaskStackParser.Entry task : TaskStackParser.parse(
                ShellAccess.run("/system/bin/cmd activity stack list"))) {
            if (task.displayId != displayId) {
                continue;
            }
            if (task.taskId == firstTaskId) {
                first = task;
            } else if (task.taskId == secondTaskId) {
                second = task;
            }
        }
        if (first == null || second == null
                || !"freeform".equals(first.windowingMode)
                || !"freeform".equals(second.windowingMode)
                || !isMaximizedBounds(first.bounds, geometry)
                || !isMaximizedBounds(second.bounds, geometry)) {
            return null;
        }
        return new MaximizedTaskPair(
                DesktopSelfTestGeometry.toRect(first.bounds),
                DesktopSelfTestGeometry.toRect(second.bounds));
    }

    private static boolean isMaximizedBounds(
            final TaskStackParser.Bounds bounds,
            final DesktopSelfTestGeometry geometry) {
        if (bounds == null) {
            return false;
        }
        final Rect workArea = geometry.workArea;
        final int tolerance = geometry.placementAlignmentTolerance();
        return bounds.left <= workArea.left + tolerance
                && bounds.top <= workArea.top + tolerance
                && bounds.right >= workArea.right - tolerance
                && bounds.bottom >= workArea.bottom - tolerance;
    }

    private static void runFullscreenAltTabTests(
            final Context context,
            final DesktopSelfTestResult result,
            final int displayId,
            final int firstTaskId,
            final String firstToken,
            final int secondTaskId,
            final String secondToken,
            final DesktopSelfTestGeometry geometry) {
        final String preparationCode = "FULLSCREEN-ALT-TAB-001";
        DesktopSelfTestHostObserver.stage(preparationCode);
        try {
            result.add(DesktopSelfTestResult.State.PASS,
                    preparationCode,
                    "Prepare two fullscreen windows",
                    prepareFullscreenPair(
                            displayId,
                            firstTaskId,
                            secondTaskId,
                            geometry));
        } catch (Exception error) {
            result.add(DesktopSelfTestResult.State.FAIL,
                    preparationCode,
                    "Prepare two fullscreen windows",
                    usefulMessage(error));
            result.add(DesktopSelfTestResult.State.NOT_TESTED,
                    "FULLSCREEN-ALT-TAB-002",
                    "Switch between two fullscreen windows",
                    "fullscreen pair preparation failed");
            result.add(DesktopSelfTestResult.State.NOT_TESTED,
                    "FULLSCREEN-ALT-TAB-003",
                    "Switch back between two fullscreen windows",
                    "fullscreen pair preparation failed");
            return;
        }
        check(result,
                "FULLSCREEN-ALT-TAB-002",
                "Switch between two fullscreen windows",
                () -> focusFullscreenPairThroughAltTab(
                        context,
                        displayId,
                        firstTaskId,
                        firstToken,
                        secondTaskId,
                        "2"));
        check(result,
                "FULLSCREEN-ALT-TAB-003",
                "Switch back between two fullscreen windows",
                () -> focusFullscreenPairThroughAltTab(
                        context,
                        displayId,
                        secondTaskId,
                        secondToken,
                        firstTaskId,
                        "3"));
        runFullscreenTaskAreaLifecycleTests(
                context,
                result,
                displayId,
                firstTaskId,
                firstToken,
                secondTaskId,
                secondToken,
                geometry);
    }

    private static void runFullscreenTaskAreaLifecycleTests(
            final Context context,
            final DesktopSelfTestResult result,
            final int displayId,
            final int firstTaskId,
            final String firstToken,
            final int secondTaskId,
            final String secondToken,
            final DesktopSelfTestGeometry geometry) {
        final String restoreCode = "FULLSCREEN-LIFECYCLE-001";
        DesktopSelfTestHostObserver.stage(restoreCode);
        try {
            inspectFullscreenPair(displayId, secondTaskId, firstTaskId);
            if (!MagicDeskRuntime.handleActiveTaskShortcut(
                    DesktopTaskController.SHORTCUT_RESTORE)) {
                throw new IOException(
                        "MagicDesk fullscreen restore is unavailable");
            }
            final TaskStackParser.Entry restored = waitForTask(
                    displayId,
                    FIXTURE_CLASS,
                    task -> task.taskId == secondTaskId
                            && "freeform".equals(task.windowingMode)
                            && task.bounds != null
                            && !task.bounds.isEmpty());
            final TaskStackParser.Entry peer = waitForTask(
                    displayId,
                    FIXTURE_CLASS,
                    task -> task.taskId == firstTaskId
                            && "fullscreen".equals(task.windowingMode));
            result.add(DesktopSelfTestResult.State.PASS,
                    restoreCode,
                    "Restore one task without changing its fullscreen peer",
                    "restored=" + secondTaskId + "/freeform/"
                            + DesktopSelfTestGeometry.format(restored.bounds)
                            + ", peer=" + peer.taskId + "/fullscreen");
        } catch (Exception error) {
            result.add(DesktopSelfTestResult.State.FAIL,
                    restoreCode,
                    "Restore one task without changing its fullscreen peer",
                    usefulMessage(error));
            addSkippedFullscreenLifecycleResults(
                    result, "fullscreen restore failed");
            return;
        }

        final String closeCode = "FULLSCREEN-LIFECYCLE-002";
        DesktopSelfTestHostObserver.stage(closeCode);
        try {
            enterFullscreenThroughShortcut(displayId, secondTaskId);
            focusFullscreenPairThroughAltTab(
                    context,
                    displayId,
                    firstTaskId,
                    firstToken,
                    secondTaskId,
                    "4");
            if (!MagicDeskRuntime.handleActiveTaskShortcut(
                    DesktopTaskController.SHORTCUT_CLOSE)) {
                throw new IOException(
                        "MagicDesk fullscreen close is unavailable");
            }
            waitForTaskAbsent(firstTaskId);
            result.add(DesktopSelfTestResult.State.PASS,
                    closeCode,
                    "Close the active task inside a fullscreen task area",
                    "closed=" + firstTaskId);
        } catch (Exception error) {
            result.add(DesktopSelfTestResult.State.FAIL,
                    closeCode,
                    "Close the active task inside a fullscreen task area",
                    usefulMessage(error));
            result.add(DesktopSelfTestResult.State.NOT_TESTED,
                    "FULLSCREEN-LIFECYCLE-003",
                    "Keep the surviving fullscreen task focused",
                    "fullscreen close failed");
            result.add(DesktopSelfTestResult.State.NOT_TESTED,
                    "FULLSCREEN-LIFECYCLE-004",
                    "Return to the desktop after system Back",
                    "fullscreen close failed");
            result.add(DesktopSelfTestResult.State.NOT_TESTED,
                    "FULLSCREEN-LIFECYCLE-005",
                    "Launch a fullscreen task directly in the session",
                    "fullscreen close failed");
            result.add(DesktopSelfTestResult.State.NOT_TESTED,
                    "FULLSCREEN-LIFECYCLE-006",
                    "Return from a directly launched session fullscreen task",
                    "fullscreen close failed");
            return;
        }

        check(result,
                "FULLSCREEN-LIFECYCLE-003",
                "Keep the surviving fullscreen task focused",
                () -> {
                    waitForFrontTask(displayId, secondTaskId);
                    waitForTaskInputFocus(displayId, secondTaskId);
                    typeAndVerifyText(
                            context,
                            displayId,
                            secondTaskId,
                            secondToken,
                            "5");
                    final TaskStackParser.Entry survivor = waitForTask(
                            displayId,
                            FIXTURE_CLASS,
                            task -> task.taskId == secondTaskId
                                    && "fullscreen".equals(
                                            task.windowingMode));
                    return "task=" + survivor.taskId
                            + "/fullscreen/visible";
                });
        check(result,
                "FULLSCREEN-LIFECYCLE-004",
                "Return to the desktop after system Back",
                () -> finishFullscreenTaskThroughSystemBack(
                        displayId, secondTaskId));
        runDirectSessionFullscreenBackTest(result, displayId);
    }

    private static void addSkippedFullscreenLifecycleResults(
            final DesktopSelfTestResult result,
            final String reason) {
        result.add(DesktopSelfTestResult.State.NOT_TESTED,
                "FULLSCREEN-LIFECYCLE-002",
                "Close the active task inside a fullscreen task area",
                reason);
        result.add(DesktopSelfTestResult.State.NOT_TESTED,
                "FULLSCREEN-LIFECYCLE-003",
                "Keep the surviving fullscreen task focused",
                reason);
        result.add(DesktopSelfTestResult.State.NOT_TESTED,
                "FULLSCREEN-LIFECYCLE-004",
                "Return to the desktop after system Back",
                reason);
        result.add(DesktopSelfTestResult.State.NOT_TESTED,
                "FULLSCREEN-LIFECYCLE-005",
                "Launch a fullscreen task directly in the session",
                reason);
        result.add(DesktopSelfTestResult.State.NOT_TESTED,
                "FULLSCREEN-LIFECYCLE-006",
                "Return from a directly launched session fullscreen task",
                reason);
    }

    private static void runDirectSessionFullscreenBackTest(
            final DesktopSelfTestResult result,
            final int displayId) {
        if (DesktopDisplayDrivers.activeTaskAreaPolicy(displayId)
                != DesktopTaskAreaPolicy.SESSION) {
            result.add(DesktopSelfTestResult.State.NOT_TESTED,
                    "FULLSCREEN-LIFECYCLE-005",
                    "Launch a fullscreen task directly in the session",
                    "the selected display does not use a session task area");
            result.add(DesktopSelfTestResult.State.NOT_TESTED,
                    "FULLSCREEN-LIFECYCLE-006",
                    "Return from a directly launched session fullscreen task",
                    "the selected display does not use a session task area");
            return;
        }
        final String launchCode = "FULLSCREEN-LIFECYCLE-005";
        DesktopSelfTestHostObserver.stage(launchCode);
        final int taskId;
        try {
            final String token = "session-fullscreen-"
                    + Long.toHexString(System.nanoTime());
            final Intent intent = TaskDisplayAreaLaunchCommand
                    .createSelfTestIntent(
                            displayId,
                            token,
                            false,
                            DesktopSelfTestFixtureAppearance.TRANSITION);
            taskId = MagicDeskRuntime.launchFullscreenTaskInDesktopArea(
                    displayId, intent);
            waitForTask(
                    displayId,
                    FIXTURE_CLASS,
                    task -> task.taskId == taskId
                            && task.visible
                            && "fullscreen".equals(task.windowingMode));
            waitForFrontTask(displayId, taskId);
            result.add(DesktopSelfTestResult.State.PASS,
                    launchCode,
                    "Launch a fullscreen task directly in the session",
                    "task=" + taskId + "/fullscreen/visible");
        } catch (Exception error) {
            result.add(DesktopSelfTestResult.State.FAIL,
                    launchCode,
                    "Launch a fullscreen task directly in the session",
                    usefulMessage(error));
            result.add(DesktopSelfTestResult.State.NOT_TESTED,
                    "FULLSCREEN-LIFECYCLE-006",
                    "Return from a directly launched session fullscreen task",
                    "direct fullscreen launch failed");
            return;
        }
        check(result,
                "FULLSCREEN-LIFECYCLE-006",
                "Return from a directly launched session fullscreen task",
                () -> finishFullscreenTaskThroughSystemBack(
                        displayId, taskId));
    }

    private static String finishFullscreenTaskThroughSystemBack(
            final int displayId,
            final int taskId) throws IOException {
        final DesktopSessionSnapshot session =
                DesktopRuntimeBridge.getSessionSnapshot();
        if (session.activeDisplayId() != displayId
                || session.hostTaskId() < 0) {
            throw new IOException("desktop host is unavailable");
        }
        final int hostTaskId = session.hostTaskId();
        DesktopSelfTestTasks.sendSystemBack(displayId);
        waitForTaskAbsent(taskId);
        return "closed=" + taskId + ", "
                + DesktopSelfTestTasks.waitForReadyDesktopHost(
                        displayId, hostTaskId);
    }

    private static String prepareFullscreenPair(
            final int displayId,
            final int firstTaskId,
            final int secondTaskId,
            final DesktopSelfTestGeometry geometry) throws IOException {
        // Earlier phases intentionally leave both tasks maximized. Establish
        // a normal freeform baseline before Win+Up exercises true fullscreen.
        setWindowedBounds(
                displayId,
                firstTaskId,
                geometry.captionControlsWindow(false));
        setWindowedBounds(
                displayId,
                secondTaskId,
                geometry.captionControlsWindow(true));
        enterFullscreenThroughShortcut(displayId, firstTaskId);
        enterFullscreenThroughShortcut(displayId, secondTaskId);
        waitForFrontTask(displayId, secondTaskId);
        waitForTaskInputFocus(displayId, secondTaskId);
        return inspectFullscreenPair(
                displayId, secondTaskId, firstTaskId);
    }

    private static void setWindowedBounds(
            final int displayId,
            final int taskId,
            final Rect bounds) throws IOException {
        ShellAccess.run(TaskRepository.createBoundsTransactionCommand(
                displayId, taskId, bounds));
        try {
            waitForTask(
                    displayId,
                    FIXTURE_CLASS,
                    task -> task.taskId == taskId
                            && "freeform".equals(task.windowingMode)
                            && DesktopSelfTestGeometry.matches(task.bounds, bounds));
        } catch (IOException error) {
            throw new IOException("could not establish windowed bounds for task "
                    + taskId + ": " + usefulMessage(error));
        }
    }

    private static void enterFullscreenThroughShortcut(
            final int displayId,
            final int taskId) throws IOException {
        focusTaskThroughDesktop(displayId, taskId);
        waitForTask(
                displayId,
                FIXTURE_CLASS,
                entry -> entry.taskId == taskId
                        && entry.visible
                        && "freeform".equals(entry.windowingMode));
        waitForFrontTask(displayId, taskId);
        waitForTaskInputFocus(displayId, taskId);
        if (!MagicDeskRuntime.handleActiveTaskShortcut(
                DesktopTaskController.SHORTCUT_FULLSCREEN)) {
            throw new IOException(
                    "MagicDesk fullscreen shortcut is unavailable");
        }
        try {
            waitForTask(
                    displayId,
                    FIXTURE_CLASS,
                    entry -> entry.taskId == taskId
                            && "fullscreen".equals(entry.windowingMode));
        } catch (IOException error) {
            throw new IOException("task=" + taskId
                    + " did not enter fullscreen through MagicDesk Win+Up");
        }
    }

    private static String concealFullscreenBehindWorkspace(
            final Context context,
            final int displayId,
            final int fullscreenTaskId,
            final int windowedTaskId,
            final String windowedToken) throws IOException {
        final TaskRepository.Snapshot snapshot =
                TaskRepository.loadNow(displayId);
        final TaskRepository.TaskEntry activeFullscreen =
                DesktopShellActivity.findTask(snapshot, fullscreenTaskId);
        if (activeFullscreen == null
                || !activeFullscreen.active
                || !activeFullscreen.isFullscreen()) {
            throw new IOException(
                    "taskbar fullscreen task is not active: task="
                            + fullscreenTaskId);
        }
        final List<Integer> focusOrder =
                TaskbarTaskOrder.concealFullscreenTask(
                        snapshot,
                        fullscreenTaskId,
                        MagicDeskRuntime.getLastVisibleFreeformTasks(
                                displayId));
        if (focusOrder.size() < 3
                || !focusOrder.contains(Integer.valueOf(windowedTaskId))) {
            throw new IOException(
                    "saved freeform workspace is incomplete: " + focusOrder);
        }
        focusTasksThroughDesktop(displayId, focusOrder);

        waitForFrontTask(displayId, windowedTaskId);
        waitForTaskInputFocus(displayId, windowedTaskId);
        DesktopSelfTestFixtureState.clearText(context);
        typeAndVerifyText(
                context,
                displayId,
                windowedTaskId,
                windowedToken,
                "4");
        final TaskWindowSnapshot concealed =
                DesktopSelfTestTasks.waitForBackgroundFullscreenTask(
                        displayId, fullscreenTaskId);
        final TaskStackParser.Entry workspace = waitForTask(
                displayId,
                FIXTURE_CLASS,
                task -> task.taskId == windowedTaskId
                        && task.visible
                        && "freeform".equals(task.windowingMode)
                        && task.bounds != null
                        && !task.bounds.isEmpty());
        return "concealed=" + concealed.taskId + "/fullscreen/"
                + (concealed.visible ? "visible" : "hidden")
                + ", workspace=" + workspace.taskId + "/freeform"
                + ", order=" + focusOrder;
    }

    private static void runFullscreenTaskbarTest(
            final Context context,
            final DesktopSelfTestResult result,
            final int displayId,
            final int fullscreenTaskId,
            final int windowedTaskId,
            final String windowedToken) {
        check(result,
                "FULLSCREEN-TASKBAR-001",
                "Conceal fullscreen behind the saved window workspace",
                () -> {
                    try {
                        enterFullscreenThroughShortcut(
                                displayId, fullscreenTaskId);
                        DesktopSelfTestHostObserver.stage(
                                "FULLSCREEN-TASKBAR-001-CONCEAL");
                        return concealFullscreenBehindWorkspace(
                                context,
                                displayId,
                                fullscreenTaskId,
                                windowedTaskId,
                                windowedToken);
                    } finally {
                        // Leave the pair in the ordinary freeform baseline
                        // consumed by the following maximize/fullscreen tests.
                        DesktopSelfTestHostObserver.stage(
                                "FULLSCREEN-TASKBAR-001-RESTORE");
                        restoreFullscreenThroughShortcut(
                                displayId, fullscreenTaskId);
                    }
                });
    }

    private static void restoreFullscreenThroughShortcut(
            final int displayId,
            final int taskId) throws IOException {
        final TaskRepository.Snapshot snapshot =
                TaskRepository.loadNow(displayId);
        final TaskRepository.TaskEntry task =
                DesktopShellActivity.findTask(snapshot, taskId);
        if (task == null) {
            throw new IOException("fullscreen task is unavailable: " + taskId);
        }
        if (task.isFreeform()) {
            return;
        }
        if (!task.isFullscreen()) {
            throw new IOException("unexpected task mode during restore: "
                    + task.windowingMode);
        }
        focusTaskThroughDesktop(displayId, taskId);
        final TaskStackParser.Entry focused = waitForTask(
                displayId,
                FIXTURE_CLASS,
                entry -> entry.taskId == taskId
                        && entry.visible
                        && ("fullscreen".equals(entry.windowingMode)
                                || "freeform".equals(entry.windowingMode)));
        waitForFrontTask(displayId, taskId);
        waitForTaskInputFocus(displayId, taskId);
        if ("freeform".equals(focused.windowingMode)) {
            return;
        }
        if (!MagicDeskRuntime.handleActiveTaskShortcut(
                DesktopTaskController.SHORTCUT_RESTORE)) {
            throw new IOException(
                    "MagicDesk fullscreen restore is unavailable");
        }
        waitForTask(
                displayId,
                FIXTURE_CLASS,
                entry -> entry.taskId == taskId
                        && "freeform".equals(entry.windowingMode)
                        && entry.bounds != null
                        && !entry.bounds.isEmpty());
    }

    private static void focusTasksThroughDesktop(
            final int displayId,
            final List<Integer> taskIds) throws IOException {
        final CountDownLatch complete = new CountDownLatch(1);
        final AtomicBoolean success = new AtomicBoolean();
        final StringBuilder message = new StringBuilder();
        MagicDeskRuntime.focusDesktopTasks(
                displayId,
                taskIds,
                action -> {
                    success.set(action.success);
                    message.append(action.message);
                    complete.countDown();
                });
        try {
            if (!complete.await(
                    STEP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                throw new IOException("desktop task focus timed out");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("desktop task focus interrupted", error);
        }
        if (!success.get()) {
            throw new IOException(
                    "desktop task focus failed: " + message);
        }
    }

    private static String focusFullscreenPairThroughAltTab(
            final Context context,
            final int displayId,
            final int targetTaskId,
            final String targetToken,
            final int otherTaskId,
            final String digit) throws IOException {
        DesktopSelfTestFixtureState.clearText(context);
        final int panelGeneration =
                DesktopSelfTestHostObserver.altTabPanelGeneration();
        if (!DesktopRuntimeBridge.advanceAltTab(false)) {
            throw new IOException("desktop Alt+Tab is unavailable");
        }
        waitForAltTabPanel(panelGeneration);
        inspectFullscreenModes(
                displayId, targetTaskId, otherTaskId, "while Alt+Tab is open");
        if (!DesktopRuntimeBridge.finishAltTab()) {
            DesktopRuntimeBridge.cancelAltTab();
            throw new IOException("desktop Alt+Tab completion is unavailable");
        }
        final String focus;
        try {
            waitForFrontTask(displayId, targetTaskId);
            typeAndVerifyText(
                    context, displayId, targetTaskId, targetToken, digit);
            focus = "task=" + targetTaskId + ", token=" + targetToken;
        } catch (IOException error) {
            DesktopRuntimeBridge.cancelAltTab();
            throw error;
        }
        return focus + ", " + inspectFullscreenPair(
                displayId, targetTaskId, otherTaskId);
    }

    private static void waitForAltTabPanel(
            final int previousGeneration) throws IOException {
        final long deadline = SystemClock.uptimeMillis()
                + STEP_TIMEOUT_MILLIS;
        do {
            if (DesktopSelfTestHostObserver.altTabPanelGeneration()
                    > previousGeneration) {
                return;
            }
            SystemClock.sleep(POLL_MILLIS);
        } while (SystemClock.uptimeMillis() < deadline);
        DesktopRuntimeBridge.cancelAltTab();
        throw new IOException("Alt+Tab panel did not become visible");
    }

    private static void inspectFullscreenModes(
            final int displayId,
            final int targetTaskId,
            final int otherTaskId,
            final String stage) throws IOException {
        TaskStackParser.Entry target = null;
        TaskStackParser.Entry other = null;
        for (final TaskStackParser.Entry task : TaskStackParser.parse(
                ShellAccess.run("/system/bin/cmd activity stack list"))) {
            if (task.displayId != displayId) {
                continue;
            }
            if (task.taskId == targetTaskId) {
                target = task;
            } else if (task.taskId == otherTaskId) {
                other = task;
            }
        }
        if (target == null || other == null
                || !"fullscreen".equals(target.windowingMode)
                || !"fullscreen".equals(other.windowingMode)) {
            throw new IOException("fullscreen modes changed " + stage
                    + ": target="
                    + (target == null ? "missing" : target.windowingMode)
                    + ", other="
                    + (other == null ? "missing" : other.windowingMode));
        }
    }

    private static String inspectFullscreenPair(
            final int displayId,
            final int targetTaskId,
            final int otherTaskId) throws IOException {
        TaskStackParser.Entry target = null;
        TaskStackParser.Entry other = null;
        for (final TaskStackParser.Entry task : TaskStackParser.parse(
                ShellAccess.run("/system/bin/cmd activity stack list"))) {
            if (task.displayId != displayId) {
                continue;
            }
            if (task.taskId == targetTaskId) {
                target = task;
            } else if (task.taskId == otherTaskId) {
                other = task;
            }
        }
        if (target == null || other == null) {
            throw new IOException("fullscreen task pair is incomplete");
        }
        if (!"fullscreen".equals(target.windowingMode)
                || !"fullscreen".equals(other.windowingMode)) {
            throw new IOException("fullscreen modes changed: target="
                    + target.windowingMode + ", other="
                    + other.windowingMode);
        }
        if (!target.visible || other.visible) {
            throw new IOException("fullscreen visibility is invalid: target="
                    + target.visible + ", other=" + other.visible);
        }
        return "target=" + targetTaskId + "/fullscreen/visible"
                + ", other=" + otherTaskId + "/fullscreen/hidden";
    }

    private static void runNativeCaptionPlacementFocusTests(
            final Context context,
            final DesktopSelfTestResult result,
            final int displayId,
            final int firstTaskId,
            final String firstToken,
            final int secondTaskId,
            final String secondToken,
            final DesktopSelfTestGeometry geometry) {
        final TaskStackParser.Entry left = captionSnap(
                result,
                "NATIVE-SNAP-001",
                "Place first window left through native caption",
                displayId,
                firstTaskId,
                true,
                geometry);
        final TaskStackParser.Entry right = captionSnap(
                result,
                "NATIVE-SNAP-002",
                "Place second window right through native caption",
                displayId,
                secondTaskId,
                false,
                geometry);
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
        final Rect leftBounds = DesktopSelfTestGeometry.toRect(left.bounds);
        final Rect rightBounds = DesktopSelfTestGeometry.toRect(right.bounds);
        check(result,
                "NATIVE-SNAP-003",
                "Verify native side-by-side placement",
                () -> inspectSideBySidePlacement(
                        leftBounds, rightBounds, geometry));
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
            final DesktopSelfTestResult result,
            final String code,
            final String label,
            final int displayId,
            final int taskId,
            final boolean left,
            final DesktopSelfTestGeometry geometry) {
        try {
            TaskStackParser.Entry before = waitForTask(
                    displayId,
                    FIXTURE_CLASS,
                    task -> task.taskId == taskId
                            && "freeform".equals(task.windowingMode)
                            && task.visible);
            final Rect captionBounds = geometry.captionControlsWindow(!left);
            if (!DesktopSelfTestGeometry.matches(before.bounds, captionBounds)) {
                ShellAccess.run(TaskRepository.createBoundsTransactionCommand(
                        displayId, taskId, captionBounds));
                before = waitForTask(
                        displayId,
                        FIXTURE_CLASS,
                        task -> task.taskId == taskId
                                && "freeform".equals(task.windowingMode)
                                && task.visible
                                && DesktopSelfTestGeometry.matches(
                                        task.bounds, captionBounds));
            }
            focusTaskThroughDesktop(displayId, taskId);
            waitForFrontTask(displayId, taskId);
            final InputCoordinateSpace inputSpace = waitForCaptionInputFrame(
                    displayId, taskId, captionBounds, geometry);
            final Rect beforeBounds = DesktopSelfTestGeometry.toRect(
                    before.bounds);
            openNativeMaximizeMenu(
                    displayId, before.bounds, geometry);
            final TaskInputWindowParser.Entry menu = waitForMaximizeMenu(
                    displayId, taskId);
            final Rect menuFrame = geometry.inputFrame(menu.frame);
            // The detached SystemUI menu is laid out in phone-display density.
            final float menuDensity = defaultDisplayDensity();
            // A menu published in natural coordinates exposes its side
            // actions in natural screen-edge order. Once normalized into
            // display space, left and right are therefore reversed.
            final boolean menuLeft = inputSpace == InputCoordinateSpace.NATURAL
                    ? !left : left;
            final int x = menuFrame.right
                    - Math.round(menuDensity * (menuLeft
                    ? SNAP_LEFT_CENTER_FROM_MENU_RIGHT_DP
                    : SNAP_RIGHT_CENTER_FROM_MENU_RIGHT_DP));
            final int y = menuFrame.top
                    + Math.round(menuDensity
                            * SNAP_BUTTON_CENTER_FROM_MENU_TOP_DP);
            requireProductionPointerClick(displayId, x, y);
            final TaskStackParser.Entry placed;
            try {
                placed = waitForTask(
                        displayId,
                        FIXTURE_CLASS,
                        task -> task.taskId == taskId
                                && "freeform".equals(task.windowingMode)
                                && task.visible
                                && !DesktopSelfTestGeometry.matches(
                                        task.bounds, beforeBounds)
                                && geometry.isSnapped(
                                        DesktopSelfTestGeometry.toRect(
                                                task.bounds), left));
            } catch (IOException error) {
                throw new IOException(error.getMessage()
                        + "; menu=" + menu.frame
                        + ", input=" + inputSpace.name().toLowerCase()
                        + ", click=" + x + "," + y);
            }
            result.add(DesktopSelfTestResult.State.PASS,
                    code,
                    label,
                    "task=" + taskId + ", bounds="
                            + DesktopSelfTestGeometry.format(placed.bounds));
            return placed;
        } catch (Exception error) {
            result.add(DesktopSelfTestResult.State.FAIL,
                    code, label, usefulMessage(error));
            return null;
        }
    }

    private static void openNativeMaximizeMenu(
            final int displayId,
            final TaskStackParser.Bounds bounds,
            final DesktopSelfTestGeometry geometry) throws IOException {
        final int x = bounds.right
                - geometry.scaleFrom160Dpi(
                        MAXIMIZE_BUTTON_CENTER_FROM_RIGHT_PX);
        final int y = bounds.top + geometry.scaleFrom160Dpi(
                CAPTION_BUTTON_CENTER_Y_PX);
        requireTouchLongPress(displayId, x, y);
    }

    private static float defaultDisplayDensity() throws IOException {
        final String output = ShellAccess.run("/system/bin/wm density -d 0");
        int densityDpi = -1;
        for (final String line : output.split("\\r?\\n")) {
            final String trimmed = line.trim();
            final String prefix;
            if (trimmed.startsWith("Override density:")) {
                prefix = "Override density:";
            } else if (trimmed.startsWith("Physical density:")) {
                prefix = "Physical density:";
            } else {
                continue;
            }
            final int parsed;
            try {
                parsed = Integer.parseInt(
                        trimmed.substring(prefix.length()).trim());
            } catch (NumberFormatException ignored) {
                continue;
            }
            if (parsed <= 0) {
                continue;
            }
            densityDpi = parsed;
            if (trimmed.startsWith("Override density:")) {
                break;
            }
        }
        if (densityDpi <= 0) {
            throw new IOException("default display density is unavailable: "
                    + output.trim());
        }
        return densityDpi / (float) DisplayMetrics.DENSITY_DEFAULT;
    }

    private static TaskInputWindowParser.Entry waitForMaximizeMenu(
            final int displayId,
            final int taskId) throws IOException {
        final long deadline = SystemClock.uptimeMillis()
                + STEP_TIMEOUT_MILLIS;
        TaskInputWindowParser.Entry lastMenu = null;
        do {
            final TaskInputWindowParser.Entry menu =
                    TaskInputWindowParser.findMaximizeMenu(
                            ShellAccess.run("/system/bin/dumpsys input"),
                            taskId);
            lastMenu = menu;
            if (menu != null
                    && menu.displayId == displayId
                    && menu.hasInputChannel()
                    && menu.hasTouchableRegion()) {
                return menu;
            }
            if (menu != null
                    && menu.displayId != displayId
                    && menu.hasInputChannel()
                    && menu.hasTouchableRegion()) {
                // Stale SystemUI/WMShell organizer state can leave the caption
                // on the target while attaching this detached menu to display 0.
                throw new IOException("native maximize menu for task "
                        + taskId + " opened on display " + menu.displayId
                        + " instead of " + displayId + "; frame="
                        + menu.frame);
            }
            SystemClock.sleep(POLL_MILLIS);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new IOException("native maximize menu did not become usable for task "
                + taskId + " on display " + displayId + "; last="
                + (lastMenu == null
                        ? "unavailable"
                        : "display=" + lastMenu.displayId
                        + ", frame=" + lastMenu.frame
                        + ", channel=" + lastMenu.hasInputChannel()
                        + ", touchable=" + lastMenu.hasTouchableRegion()));
    }

    private static String inspectSideBySidePlacement(
            final Rect left,
            final Rect right,
            final DesktopSelfTestGeometry geometry) throws IOException {
        if (!geometry.isNativeSideBySide(left, right)) {
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
        DesktopSelfTestFixtureState.clearText(context);
        final int x = bounds.centerX();
        final int y = bounds.top + bounds.height() / 3;
        requireProductionPointerClick(displayId, x, y);
        typeAndVerifyText(context, displayId, taskId, token, digit);
        return "token=" + token + ", click=" + x + "," + y;
    }

    private static String focusFieldThroughDesktop(
            final Context context,
            final int displayId,
            final int taskId,
            final String token,
            final String digit) throws IOException {
        DesktopSelfTestFixtureState.clearText(context);
        focusTaskThroughDesktop(displayId, taskId);
        waitForFrontTask(displayId, taskId);
        typeAndVerifyText(context, displayId, taskId, token, digit);
        return "task=" + taskId + ", token=" + token;
    }

    private static String focusFieldThroughAltTab(
            final Context context,
            final int displayId,
            final int taskId,
            final String token,
            final String digit) throws IOException {
        DesktopSelfTestFixtureState.clearText(context);
        if (!DesktopRuntimeBridge.advanceAltTab(false)) {
            throw new IOException("desktop Alt+Tab is unavailable");
        }
        if (!DesktopRuntimeBridge.finishAltTab()) {
            DesktopRuntimeBridge.cancelAltTab();
            throw new IOException("desktop Alt+Tab completion is unavailable");
        }
        try {
            waitForFrontTask(displayId, taskId);
            typeAndVerifyText(context, displayId, taskId, token, digit);
            return "task=" + taskId + ", token=" + token;
        } catch (IOException error) {
            DesktopRuntimeBridge.cancelAltTab();
            throw error;
        }
    }

    static void typeAndVerifyText(
            final Context context,
            final int displayId,
            final int taskId,
            final String token,
            final String digit) throws IOException {
        waitForTaskInputFocus(displayId, taskId);
        sendTestKey(displayId, digit);
        DesktopSelfTestFixtureState.awaitText(
                context, token, displayId, digit);
    }

    static void waitForTaskInputFocus(
            final int displayId, final int taskId) throws IOException {
        final long deadline = SystemClock.uptimeMillis()
                + STEP_TIMEOUT_MILLIS;
        String lastDump = "";
        do {
            lastDump = ShellAccess.run("/system/bin/dumpsys input");
            if (TaskInputWindowParser.isTaskFocused(
                    lastDump, displayId, taskId)) {
                return;
            }
            SystemClock.sleep(POLL_MILLIS);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new IOException("input focus did not settle on task "
                + taskId + " on display " + displayId + "; "
                + TaskInputWindowParser.describeFocus(
                        lastDump, displayId));
    }

    private static InputCoordinateSpace waitForCaptionInputFrame(
            final int displayId,
            final int taskId,
            final Rect bounds,
            final DesktopSelfTestGeometry geometry) throws IOException {
        final long deadline = SystemClock.uptimeMillis()
                + STEP_TIMEOUT_MILLIS;
        TaskInputWindowParser.Entry lastCaption = null;
        do {
            final TaskInputWindowParser.Entry caption =
                    TaskInputWindowParser.findCaption(
                            ShellAccess.run("/system/bin/dumpsys input"),
                            taskId);
            lastCaption = caption;
            final Rect directFrame = caption == null
                    ? null : directFrame(caption.frame);
            final Rect transformedFrame = caption == null
                    ? null : geometry.inputFrame(caption.frame);
            if (caption != null
                    && caption.displayId == displayId
                    && caption.hasInputChannel()
                    && caption.hasTouchableRegion()) {
                if (captionMatches(directFrame, bounds)) {
                    return InputCoordinateSpace.DISPLAY;
                }
                if (captionMatches(transformedFrame, bounds)) {
                    return InputCoordinateSpace.NATURAL;
                }
                if (caption.frame.left == 0
                            && caption.frame.top == 0
                            && caption.frame.right == bounds.width()) {
                    return InputCoordinateSpace.TASK_LOCAL;
                }
            }
            SystemClock.sleep(POLL_MILLIS);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new IOException("caption did not settle for task " + taskId
                + " at " + bounds + "; last="
                + (lastCaption == null
                                ? "unavailable"
                                : "display=" + lastCaption.displayId
                                + ", frame=" + lastCaption.frame
                                + ", normalized="
                                + geometry.inputFrame(lastCaption.frame)
                                + ", channel="
                                + lastCaption.hasInputChannel()
                                + ", touchable="
                                + lastCaption.hasTouchableRegion()));
    }

    private static boolean captionMatches(
            final Rect frame, final Rect bounds) {
        return frame != null
                && frame.left == bounds.left
                && frame.top == bounds.top
                && frame.right == bounds.right;
    }

    private static Rect directFrame(
            final TaskInputWindowParser.Frame frame) {
        return new Rect(frame.left, frame.top, frame.right, frame.bottom);
    }

    private static void focusTaskThroughDesktop(
            final int displayId,
            final int taskId) throws IOException {
        final TaskRepository.Snapshot snapshot =
                TaskRepository.loadNow(displayId);
        if (!snapshot.available) {
            throw new IOException("desktop task stack unavailable: "
                    + snapshot.error);
        }
        TaskRepository.TaskEntry targetTask = null;
        for (final TaskRepository.TaskEntry task : snapshot.tasks) {
            if (task != null && task.taskId == taskId) {
                targetTask = task;
                break;
            }
        }
        if (targetTask == null) {
            throw new IOException("desktop task " + taskId + " is unavailable");
        }
        // The fixture shares MagicDesk's package, which focusStack excludes
        // along with the desktop host. Exercise the same display-targeted
        // focus transaction without the user-app filter.
        focusTasksThroughDesktop(
                displayId,
                Collections.singletonList(
                        Integer.valueOf(targetTask.taskId)));
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

    private static String inspectCaptionInputWindow(
            final String dump,
            final int displayId,
            final int taskId,
            final Rect bounds,
            final DesktopSelfTestGeometry geometry) throws IOException {
        final TaskInputWindowParser.Entry entry = requireInputWindow(
                TaskInputWindowParser.findCaption(dump, taskId),
                displayId,
                "caption");
        final Rect directFrame = directFrame(entry.frame);
        final Rect transformedFrame = geometry.inputFrame(entry.frame);
        final boolean directCoordinates = captionMatches(
                directFrame, bounds)
                && directFrame.bottom > directFrame.top
                && directFrame.bottom < bounds.bottom;
        final boolean transformedCoordinates = captionMatches(
                transformedFrame, bounds)
                && transformedFrame.bottom > transformedFrame.top
                && transformedFrame.bottom < bounds.bottom;
        final boolean taskCoordinates = entry.frame.left == 0
                && entry.frame.top == 0
                && entry.frame.right == bounds.width()
                && entry.frame.bottom > 0
                && entry.frame.bottom < bounds.height();
        if (!directCoordinates && !transformedCoordinates
                && !taskCoordinates) {
            throw new IOException("caption input frame is misaligned: "
                    + entry.frame + ", normalized=" + transformedFrame);
        }
        return inputWindowDetail(entry,
                directCoordinates ? "display"
                        : transformedCoordinates ? "natural" : "task-local")
                + (transformedCoordinates
                        ? ", normalized=" + transformedFrame : "");
    }

    private static String inspectResizeInputWindow(
            final String dump,
            final int displayId,
            final int taskId,
            final Rect bounds,
            final DesktopSelfTestGeometry geometry) throws IOException {
        final TaskInputWindowParser.Entry entry = requireInputWindow(
                TaskInputWindowParser.findResize(dump, taskId),
                displayId,
                "resize");
        if (!entry.hasConfig("SPY")) {
            throw new IOException("resize input window is not a spy window");
        }
        final Rect directFrame = directFrame(entry.frame);
        final Rect transformedFrame = geometry.inputFrame(entry.frame);
        final boolean directCoordinates = directFrame.equals(bounds);
        final boolean transformedCoordinates = transformedFrame.equals(bounds);
        final boolean taskCoordinates = entry.frame.left == 0
                && entry.frame.top == 0
                && entry.frame.right == bounds.width()
                && entry.frame.bottom == bounds.height();
        if (!directCoordinates && !transformedCoordinates
                && !taskCoordinates) {
            throw new IOException("resize input frame is misaligned: "
                    + entry.frame + ", normalized=" + transformedFrame);
        }
        return inputWindowDetail(entry,
                directCoordinates ? "display"
                        : transformedCoordinates ? "natural" : "task-local")
                + (transformedCoordinates
                        ? ", normalized=" + transformedFrame : "");
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
        if (!ShellAccess.injectPointerHoverAt(displayId, x, y)) {
            throw new IOException("production pointer route rejected hover");
        }
    }

    private static void requireProductionPointerClick(
            final int displayId,
            final int x,
            final int y) throws IOException {
        if (!ShellAccess.injectPointerClickAt(
                displayId, x, y, MotionEvent.BUTTON_PRIMARY)) {
            throw new IOException("production pointer route rejected click");
        }
    }

    private static void requireTouchLongPress(
            final int displayId,
            final int x,
            final int y) throws IOException {
        final long duration = ViewConfiguration.getLongPressTimeout() + 200L;
        // A long press is deliberately injected as an external gesture: the
        // production API has no command whose semantics are "open WMShell's
        // native maximize menu".
        ShellAccess.run(testPointerGestureCommand(
                "long-press " + displayId + " " + x + " " + y
                        + " " + duration));
    }

    private static String testPointerGestureCommand(final String arguments) {
        return AppProcessCommand.run(
                "io.github.mekhontsev.magicdesk.DesktopPointerCommand",
                arguments);
    }

    private static final class MaximizedTaskPair {
        final Rect firstBounds;
        final Rect secondBounds;

        MaximizedTaskPair(
                final Rect firstBounds,
                final Rect secondBounds) {
            this.firstBounds = new Rect(firstBounds);
            this.secondBounds = new Rect(secondBounds);
        }

        boolean sameBounds(final MaximizedTaskPair other) {
            return other != null
                    && firstBounds.equals(other.firstBounds)
                    && secondBounds.equals(other.secondBounds);
        }

        String describe() {
            return "first=" + firstBounds + ", second=" + secondBounds;
        }
    }

}
