package io.github.mekhontsev.magicdesk;

import static io.github.mekhontsev.magicdesk.DesktopSelfTestSteps.check;
import static io.github.mekhontsev.magicdesk.DesktopSelfTestSteps.usefulMessage;
import static io.github.mekhontsev.magicdesk.DesktopSelfTestTasks.POLL_MILLIS;
import static io.github.mekhontsev.magicdesk.DesktopSelfTestTasks.STEP_TIMEOUT_MILLIS;
import static io.github.mekhontsev.magicdesk.DesktopSelfTestTasks.waitForFrontTask;
import static io.github.mekhontsev.magicdesk.DesktopSelfTestTasks.waitForTask;

import android.content.Context;
import android.graphics.Rect;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.ViewConfiguration;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Exercises native input windows, pointer behavior, and task focus. */
final class DesktopSelfTestInputSuite {
    private static final String FIXTURE_CLASS =
            DesktopSelfTestComponents.FIXTURE_CLASS;
    private static final int RESIZE_EDGE_OUTSET_PX = 8;
    // Android 16 WMShell caption dimensions, using 160 dpi as the baseline.
    private static final int CAPTION_BUTTON_CENTER_Y_PX = 20;
    private static final int MAXIMIZE_BUTTON_CENTER_FROM_RIGHT_PX = 82;
    private static final int SNAP_LEFT_CENTER_FROM_MENU_RIGHT_DP = 96;
    private static final int SNAP_RIGHT_CENTER_FROM_MENU_RIGHT_DP = 44;
    private static final int SNAP_BUTTON_CENTER_FROM_MENU_TOP_DP = 46;

    private DesktopSelfTestInputSuite() {
    }

    static void runInitialWindowChecks(
            final DesktopSelfTestResult result,
            final Context context,
            final int displayId,
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
        verifyCaptionRendering(
                result,
                "CAPTION-003",
                "Verify native caption rendering",
                displayId,
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
        verifyNativeInputWindows(result, displayId, taskId, bounds);
        verifyResizeCursor(result, displayId, taskId, geometry);
    }

    static void verifyCaptionStructure(
            final DesktopSelfTestResult result,
            final String code,
            final String label,
            final int taskId,
            final Rect bounds) {
        check(result, code, label, () ->
                inspectCaptionStructure(taskId, bounds));
    }

    static void verifyCaptionRendering(
            final DesktopSelfTestResult result,
            final String code,
            final String label,
            final int displayId,
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
                                displayId, taskId, bounds, reference.crop));
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
            final int displayId,
            final Rect windowBounds,
            final DesktopSelfTestGeometry geometry) {
        final Rect crop = geometry.captionRenderSample(windowBounds);
        try {
            final ShellAccess.CommandResult response =
                    ShellAccess.executeForConsole(
                            TaskCaptionRenderCommand.createReferenceCommand(
                                    displayId, crop));
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
        final Rect leftBounds = toRect(left.bounds);
        final Rect rightBounds = toRect(right.bounds);
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
            if (!equalsBounds(before.bounds, captionBounds)) {
                ShellAccess.run(TaskRepository.createBoundsTransactionCommand(
                        displayId, taskId, captionBounds));
                before = waitForTask(
                        displayId,
                        FIXTURE_CLASS,
                        task -> task.taskId == taskId
                                && "freeform".equals(task.windowingMode)
                                && task.visible
                                && equalsBounds(task.bounds, captionBounds));
            }
            focusTaskThroughDesktop(displayId, taskId);
            waitForFrontTask(displayId, taskId);
            waitForCaptionInputFrame(displayId, taskId, captionBounds);
            final Rect beforeBounds = toRect(before.bounds);
            openNativeMaximizeMenu(
                    displayId, before.bounds, geometry);
            final TaskInputWindowParser.Entry menu = waitForMaximizeMenu(
                    displayId, taskId);
            // The detached SystemUI menu is laid out in phone-display density.
            final float menuDensity = defaultDisplayDensity();
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
                                && geometry.isSnapped(
                                        toRect(task.bounds), left));
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
        ShellAccess.run(pointerCommand(
                "click " + displayId + " " + x + " " + y));
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

    private static void typeAndVerifyText(
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

    private static void waitForCaptionInputFrame(
            final int displayId,
            final int taskId,
            final Rect bounds) throws IOException {
        final long deadline = SystemClock.uptimeMillis()
                + STEP_TIMEOUT_MILLIS;
        TaskInputWindowParser.Entry lastCaption = null;
        do {
            final TaskInputWindowParser.Entry caption =
                    TaskInputWindowParser.findCaption(
                            ShellAccess.run("/system/bin/dumpsys input"),
                            taskId);
            lastCaption = caption;
            if (caption != null
                    && caption.displayId == displayId
                    && caption.hasInputChannel()
                    && caption.hasTouchableRegion()
                    && ((caption.frame.left == bounds.left
                            && caption.frame.top == bounds.top
                            && caption.frame.right == bounds.right)
                        || (caption.frame.left == 0
                            && caption.frame.top == 0
                            && caption.frame.right == bounds.width()))) {
                return;
            }
            SystemClock.sleep(POLL_MILLIS);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new IOException("caption did not settle for task " + taskId
                + " at " + bounds + "; last="
                + (lastCaption == null
                        ? "unavailable"
                        : "display=" + lastCaption.displayId
                                + ", frame=" + lastCaption.frame
                                + ", channel="
                                + lastCaption.hasInputChannel()
                                + ", touchable="
                                + lastCaption.hasTouchableRegion()));
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
        final CountDownLatch complete = new CountDownLatch(1);
        final AtomicBoolean success = new AtomicBoolean();
        final StringBuilder message = new StringBuilder();
        // The fixture shares MagicDesk's package, which focusStack excludes
        // along with the desktop host. Exercise the same display-targeted
        // focus transaction without the user-app filter.
        DesktopTaskController.focusDesktopTask(
                displayId,
                targetTask.taskId,
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

    private static void requireTouchLongPress(
            final int displayId,
            final int x,
            final int y) throws IOException {
        final long duration = ViewConfiguration.getLongPressTimeout() + 200L;
        ShellAccess.run(pointerCommand(
                "long-press " + displayId + " " + x + " " + y
                        + " " + duration));
    }

    private static String pointerCommand(final String arguments) {
        return AppProcessCommand.run(
                "io.github.mekhontsev.magicdesk.DesktopPointerCommand",
                arguments);
    }

    private static boolean equalsBounds(
            final TaskStackParser.Bounds actual, final Rect expected) {
        return actual != null
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

}
