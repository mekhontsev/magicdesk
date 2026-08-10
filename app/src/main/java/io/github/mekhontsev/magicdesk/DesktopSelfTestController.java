package io.github.mekhontsev.magicdesk;

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
import java.util.concurrent.atomic.AtomicBoolean;

/** Runs a manually requested black-box desktop test on an Android overlay display. */
final class DesktopSelfTestController {
    private static final String PACKAGE_NAME =
            "io.github.mekhontsev.magicdesk";
    private static final String FIXTURE_CLASS =
            PACKAGE_NAME + ".DesktopSelfTestActivity";
    private static final String DESKTOP_CLASS =
            PACKAGE_NAME + ".DesktopActivity";
    private static final String LAUNCH_ANCHOR_CLASS =
            PACKAGE_NAME + ".FreeformLaunchAnchorActivity";
    private static final int EXPECTED_WIDTH = 1920;
    private static final int EXPECTED_HEIGHT = 1080;
    private static final int EXPECTED_DENSITY = 160;
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
        int fixtureTaskId = -1;
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
            require(result, "DESKTOP-004", "Wait for window launch anchor", () -> {
                final TaskStackParser.Entry anchor = waitForLaunchAnchor(
                        targetDisplayId, desktopTask.taskId);
                return "task=" + anchor.taskId;
            });

            require(result, "WINDOW-000", "Clear stale self-test windows", () -> {
                removeFixtureTasks();
                return "ready";
            });
            appContext.deleteFile(DesktopSelfTestActivity.MARKER_FILE);
            final String token = Long.toHexString(System.nanoTime());
            fixtureTaskId = require(result,
                    "WINDOW-001", "Launch freeform test window", () -> {
                        launchFixture(targetDisplayId, token);
                        final TaskStackParser.Entry fixture = waitForTask(
                                targetDisplayId, FIXTURE_CLASS, null);
                        return Integer.valueOf(fixture.taskId);
                    }, null).intValue();

            final int targetFixtureTaskId = fixtureTaskId;
            final Rect windowBounds = new Rect(160, 120, 960, 720);
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
            require(result, "CAPTION-001", "Verify native caption structure", () ->
                    inspectCaptionStructure(
                            targetFixtureTaskId, windowBounds));
            require(result, "INPUT-001", "Route input to simulated display", () -> {
                final int x = windowBounds.centerX();
                final int y = windowBounds.centerY();
                ShellAccess.run("/system/bin/input touchscreen -d "
                        + targetDisplayId + " tap " + x + " " + y);
                waitForInputMarker(appContext, token, targetDisplayId);
                return "tap=" + x + "," + y;
            });
            require(result, "INPUT-002", "Drag native caption", () ->
                    dragCaption(
                            targetDisplayId,
                            targetFixtureTaskId,
                            windowBounds));
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
            require(result, "CAPTION-002", "Verify restored caption structure", () ->
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
        } catch (AbortSelfTest ignored) {
            // The failing required step has already been added to the result.
        } catch (RuntimeException error) {
            result.add(DesktopSelfTestResult.State.FAIL,
                    "SELFTEST-003", "Unexpected self-test failure",
                    usefulMessage(error));
        } finally {
            cleanup(result, displayId, fixtureTaskId, lease);
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

    private static void launchFixture(final int displayId, final String token)
            throws IOException {
        final String output = ShellAccess.run(
                "/system/bin/am start -W"
                        + " --display " + displayId
                        + " --windowingMode 5"
                        + " -f 0x18800000"
                        + " --ei " + DesktopSelfTestActivity.EXTRA_DISPLAY_ID
                        + " " + displayId
                        + " --es " + DesktopSelfTestActivity.EXTRA_TOKEN
                        + " " + token
                        + " -n " + PACKAGE_NAME + "/.DesktopSelfTestActivity");
        if (output.startsWith("Error:")
                || output.contains("Exception occurred while executing")) {
            throw new IOException(output.trim());
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

    private static String dragCaption(
            final int displayId,
            final int taskId,
            final Rect initialBounds) throws IOException {
        final int startX = initialBounds.centerX();
        final int startY = initialBounds.top + 16;
        final int deltaX = 180;
        final int deltaY = 120;
        long downTime = 0L;
        boolean dragStarted = false;
        try {
            requirePointerUpdate(
                    displayId,
                    startX,
                    startY,
                    DesktopPointerInjector.TOUCHPAD_HOVER,
                    0L);
            SystemClock.sleep(POLL_MILLIS);
            downTime = SystemClock.uptimeMillis();
            requirePointerUpdate(
                    displayId,
                    startX,
                    startY,
                    DesktopPointerInjector.TOUCHPAD_DRAG_START,
                    downTime);
            dragStarted = true;
            for (int step = 1; step <= 6; step++) {
                requirePointerUpdate(
                        displayId,
                        startX + deltaX * step / 6,
                        startY + deltaY * step / 6,
                        DesktopPointerInjector.TOUCHPAD_DRAG_MOVE,
                        downTime);
            }
        } finally {
            if (dragStarted) {
                ShellAccess.updateMousePosition(
                        displayId,
                        startX + deltaX,
                        startY + deltaY,
                        DesktopPointerInjector.TOUCHPAD_DRAG_END,
                        downTime);
            }
        }
        final TaskStackParser.Entry moved = waitForTask(
                displayId,
                FIXTURE_CLASS,
                entry -> "freeform".equals(entry.windowingMode)
                        && entry.visible
                        && entry.bounds.right - entry.bounds.left
                                == initialBounds.width()
                        && entry.bounds.bottom - entry.bounds.top
                                == initialBounds.height()
                        && Math.abs(entry.bounds.left - initialBounds.left)
                                >= deltaX / 2
                        && Math.abs(entry.bounds.top - initialBounds.top)
                                >= deltaY / 2);
        return formatBounds(moved.bounds);
    }

    private static void requirePointerUpdate(
            final int displayId,
            final int x,
            final int y,
            final int action,
            final long downTime) throws IOException {
        if (!ShellAccess.updateMousePosition(
                displayId, x, y, action, downTime)) {
            throw new IOException(
                    "touchpad pointer update was rejected: action=" + action);
        }
    }

    private static void waitForInputMarker(
            final Context context, final String token, final int displayId)
            throws IOException {
        final String expected = token + "|" + displayId;
        final File marker = new File(
                context.getFilesDir(), DesktopSelfTestActivity.MARKER_FILE);
        final long deadline = SystemClock.uptimeMillis() + STEP_TIMEOUT_MILLIS;
        do {
            final String value = readFile(marker);
            if (expected.equals(value)) {
                return;
            }
            SystemClock.sleep(POLL_MILLIS);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new IOException("test window did not receive targeted input");
    }

    private static TaskStackParser.Entry waitForTask(
            final int displayId, final String className,
            final TaskPredicate predicate) throws IOException {
        final long deadline = SystemClock.uptimeMillis() + STEP_TIMEOUT_MILLIS;
        do {
            final TaskStackParser.Entry task = findTask(
                    ShellAccess.run("/system/bin/cmd activity stack list"),
                    displayId, className);
            if (task != null && (predicate == null || predicate.test(task))) {
                return task;
            }
            SystemClock.sleep(POLL_MILLIS);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new IOException("task " + className
                + " did not reach the expected state on display " + displayId);
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

    private static TaskStackParser.Entry waitForLaunchAnchor(
            final int displayId, final int desktopTaskId) throws IOException {
        final long deadline = SystemClock.uptimeMillis() + STEP_TIMEOUT_MILLIS;
        TaskStackParser.Entry lastDesktop = null;
        TaskStackParser.Entry lastAnchor = null;
        boolean lastPrepared = false;
        do {
            final String stack = ShellAccess.run(
                    "/system/bin/cmd activity stack list");
            final TaskStackParser.Entry desktop = findTask(
                    stack, displayId, DESKTOP_CLASS);
            final TaskStackParser.Entry anchor = findTask(
                    stack, displayId, LAUNCH_ANCHOR_CLASS);
            final boolean prepared = FreeformLaunchAnchorActivity
                    .isPreparedForDisplay(displayId);
            lastDesktop = desktop;
            lastAnchor = anchor;
            lastPrepared = prepared;
            if (desktop != null
                    && desktop.taskId == desktopTaskId
                    && desktop.visible
                    && prepared
                    && isReadyAnchorTask(anchor)) {
                return anchor;
            }
            SystemClock.sleep(POLL_MILLIS);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new IOException("freeform launch anchor did not become ready: "
                + "desktop=" + describeTask(lastDesktop)
                + ", expectedDesktopTask=" + desktopTaskId
                + ", anchor=" + describeTask(lastAnchor)
                + ", prepared=" + lastPrepared);
    }

    static boolean isReadyAnchorTask(final TaskStackParser.Entry anchor) {
        return anchor != null
                && "freeform".equals(anchor.windowingMode)
                && !anchor.bounds.isEmpty();
    }

    static TaskStackParser.Entry findTask(
            final String stack, final int displayId, final String className) {
        for (final TaskStackParser.Entry task : TaskStackParser.parse(stack)) {
            if (task.displayId == displayId
                    && (hasClass(task.componentName, className)
                            || hasClass(task.topActivityName, className))) {
                return task;
            }
        }
        return null;
    }

    private static void cleanup(
            final DesktopSelfTestResult result,
            final int displayId,
            final int fixtureTaskId,
            final SimulatedDisplayLease lease) {
        final StringBuilder detail = new StringBuilder();
        boolean clean = true;
        if (fixtureTaskId >= 0) {
            try {
                ShellAccess.run(AppProcessCommand.run(
                        "io.github.mekhontsev.magicdesk.TaskControlCommand",
                        "remove " + fixtureTaskId));
            } catch (IOException error) {
                clean = false;
                detail.append("fixture removal: ")
                        .append(usefulMessage(error)).append("; ");
            }
        }
        if (displayId > Display.DEFAULT_DISPLAY) {
            DesktopRuntimeBridge.closeExternalDesktopSession(displayId);
            if (ShellAccess.isReady()) {
                try {
                    waitForTaskAbsent(DESKTOP_CLASS);
                } catch (IOException error) {
                    clean = false;
                    detail.append("desktop removal: ")
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

    private static String formatBounds(final TaskStackParser.Bounds bounds) {
        return "[" + bounds.left + "," + bounds.top + "]["
                + bounds.right + "," + bounds.bottom + "]";
    }

    private static String describeTask(final TaskStackParser.Entry task) {
        if (task == null) {
            return "missing";
        }
        return "task=" + task.taskId
                + "/mode=" + task.windowingMode
                + "/visible=" + task.visible
                + "/bounds=" + formatBounds(task.bounds);
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
