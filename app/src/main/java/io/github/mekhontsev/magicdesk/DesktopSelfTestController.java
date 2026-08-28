package io.github.mekhontsev.magicdesk;

import static io.github.mekhontsev.magicdesk.DesktopSelfTestSteps.failAndAbort;
import static io.github.mekhontsev.magicdesk.DesktopSelfTestSteps.require;
import static io.github.mekhontsev.magicdesk.DesktopSelfTestSteps.usefulMessage;
import static io.github.mekhontsev.magicdesk.DesktopSelfTestTasks.POLL_MILLIS;
import static io.github.mekhontsev.magicdesk.DesktopSelfTestTasks.STEP_TIMEOUT_MILLIS;
import static io.github.mekhontsev.magicdesk.DesktopSelfTestTasks.findTaskOnAnyDisplay;
import static io.github.mekhontsev.magicdesk.DesktopSelfTestTasks.waitForFrontTask;

import android.app.KeyguardManager;
import android.content.Context;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.Display;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.mekhontsev.magicdesk.DesktopSelfTestSteps.AbortSelfTest;

/** Runs a manually requested black-box test on a selected desktop display. */
final class DesktopSelfTestController {
    private static final AtomicBoolean RUNNING = new AtomicBoolean();

    private DesktopSelfTestController() {
    }

    static boolean isRunning() {
        return RUNNING.get();
    }

    static DesktopSelfTestResult run(final Context context) {
        return run(context, DesktopSelfTestTarget.SIMULATED);
    }

    static DesktopSelfTestResult run(
            final Context context,
            final DesktopSelfTestTarget requestedTarget) {
        return run(context, requestedTarget, false);
    }

    static DesktopSelfTestResult run(
            final Context context,
            final DesktopSelfTestTarget requestedTarget,
            final boolean restoreExternalMirror) {
        return run(context, requestedTarget, restoreExternalMirror, -1);
    }

    static DesktopSelfTestResult run(
            final Context context,
            final DesktopSelfTestTarget requestedTarget,
            final boolean restoreExternalMirror,
            final int resultTaskId) {
        return run(
                context,
                requestedTarget,
                restoreExternalMirror,
                resultTaskId,
                DesktopSelfTestExecutionPolicy.FULL);
    }

    static DesktopSelfTestResult run(
            final Context context,
            final DesktopSelfTestTarget requestedTarget,
            final boolean restoreExternalMirror,
            final int resultTaskId,
            final DesktopSelfTestExecutionPolicy executionPolicy) {
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
        final String phoneUiIssue = phoneUiUnavailableReason(appContext);
        if (phoneUiIssue != null) {
            result.add(DesktopSelfTestResult.State.FAIL,
                    "SELFTEST-PRECONDITION-000",
                    "Phone is unlocked and awake",
                    phoneUiIssue);
            RUNNING.set(false);
            return finish(result, appContext);
        }
        result.add(DesktopSelfTestResult.State.PASS,
                "SELFTEST-PRECONDITION-000",
                "Phone is unlocked and awake", "ready");

        DesktopSelfTestTaskStackGuard.cancel();
        final DesktopSelfTestTarget target = requestedTarget == null
                ? DesktopSelfTestTarget.SIMULATED : requestedTarget;
        if (!DesktopSelfTestHostObserver.isActive()) {
            DesktopSelfTestHostObserver.begin();
        }
        int displayId = Display.INVALID_DISPLAY;
        SimulatedDisplayLease lease = null;
        WorkspaceIsolationLease workspaceLease = null;
        PhoneOrientationLease orientationLease = null;
        boolean observationsRecorded = false;
        boolean displayRemovalRecorded = false;
        Map<String, Integer> staleTransitionBaseline = Collections.emptyMap();
        result.arm(executionPolicy);
        try {
            result.add(DesktopSelfTestResult.State.PASS,
                    "SELFTEST-TARGET-001",
                    "Selected test display",
                    target.name());
            require(result, "API-SHELL-001", "Shizuku command service", () -> {
                final int uid = ShellAccess.connectAndGetUid();
                if (!ShellAccess.isSupportedServiceUid(uid)) {
                    throw new IOException("unsupported service uid=" + uid);
                }
                return "uid=" + uid;
            });
            orientationLease = lockPhoneOrientation(appContext, result);
            result.add(DesktopSelfTestResult.State.PASS,
                    "SELFTEST-WORKSPACE-001",
                    "Isolate the self-test workspace",
                    "saved workspace restore and persistence disabled");
            workspaceLease = WorkspaceIsolationLease.open();
            staleTransitionBaseline = inspectWindowTransitionPrecondition(
                    appContext, result);
            if (target == DesktopSelfTestTarget.PHONE) {
                preparePhoneSystemPanel(result);
            }
            if (!DesktopSelfTestCapabilityAudit.run(
                    appContext, result, target)) {
                throw new AbortSelfTest();
            }
            if (target == DesktopSelfTestTarget.SIMULATED) {
                requireNoActiveDesktop(appContext, result);
                final boolean existingOverlay =
                        inspectConfiguredOverlay(result);
                requireNoStaleDesktopRepositories(result);
                if (!existingOverlay) {
                    lease = require(result,
                            "DISPLAY-001",
                            "Create simulated display lease",
                            () -> SimulatedDisplayLease.open(),
                            "owned Shizuku stream with automatic restoration");
                } else {
                    result.add(DesktopSelfTestResult.State.NOT_TESTED,
                            "DISPLAY-001",
                            "Create simulated display lease",
                            "using the pre-existing overlay display");
                }
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
            } else {
                displayId = requirePreparedDisplay(target, result);
                workspaceLease.adoptPreparedSession(displayId);
            }
            DesktopSelfTestWindowSuite.run(
                    appContext, target, displayId, result, workspaceLease);
            if (target == DesktopSelfTestTarget.SIMULATED) {
                // Expected display destruction must not be classified as host
                // or phone-UI instability by the lifecycle observers.
                recordDesktopHostObservation(result, displayId);
                recordPhoneUiObservation(result, displayId);
                observationsRecorded = true;
                if (lease != null) {
                    DesktopSelfTestDisplayRemovalSuite.run(
                            result, displayId, lease);
                } else {
                    DesktopSelfTestDisplayRemovalSuite.addNotTested(
                            result,
                            "the pre-existing simulated display is not "
                                    + "owned by the self-test");
                }
            } else {
                DesktopSelfTestDisplayRemovalSuite.addNotTested(
                        result,
                        "the selected display is not owned by the self-test");
            }
            displayRemovalRecorded = true;
        } catch (DesktopSelfTestResult.StopAfterFirstFailure stopped) {
            result.add(DesktopSelfTestResult.State.NOT_TESTED,
                    "SELFTEST-FAIL-FAST-001",
                    "Remaining self-test workflow",
                    "stopped after " + stopped.code
                            + "; cleanup and final diagnostics still run");
        } catch (AbortSelfTest ignored) {
            // The failing required step has already been added to the result.
        } catch (RuntimeException error) {
            result.disarm();
            result.add(DesktopSelfTestResult.State.FAIL,
                    "SELFTEST-003", "Unexpected self-test failure",
                    usefulMessage(error));
        } finally {
            result.disarm();
            try {
                try {
                    DesktopSelfTestTaskStackGuard.finish(result);
                    if (!observationsRecorded) {
                        recordDesktopHostObservation(result, displayId);
                        recordPhoneUiObservation(result, displayId);
                    }
                    if (!displayRemovalRecorded) {
                        DesktopSelfTestDisplayRemovalSuite.addNotTested(
                                result,
                                "window workflow did not reach display removal");
                    }
                } finally {
                    DesktopSelfTestCleanup.run(result,
                            target,
                            displayId,
                            lease,
                            restoreExternalMirror);
                }
            } finally {
                try {
                    if (workspaceLease != null) {
                        workspaceLease.close();
                    }
                } finally {
                    restorePhoneOrientation(result, orientationLease);
                    try {
                        recordWindowTransitionHealth(
                                appContext,
                                result,
                                staleTransitionBaseline);
                        recordWindowTransitionLogHealth(result, displayId);
                        restoreResultTask(result, target, resultTaskId);
                    } finally {
                        RUNNING.set(false);
                    }
                }
            }
        }
        return finish(result, appContext);
    }

    private static void restorePhoneOrientation(
            final DesktopSelfTestResult result,
            final PhoneOrientationLease lease) {
        if (lease == null) {
            return;
        }
        try {
            final String detail = lease.detail();
            lease.close();
            result.add(DesktopSelfTestResult.State.PASS,
                    "SELFTEST-ORIENTATION-002",
                    "Restore phone orientation policy",
                    detail);
        } catch (IOException | RuntimeException error) {
            result.add(DesktopSelfTestResult.State.FAIL,
                    "SELFTEST-ORIENTATION-002",
                    "Restore phone orientation policy",
                    usefulMessage(error));
        }
    }

    private static PhoneOrientationLease lockPhoneOrientation(
            final Context context,
            final DesktopSelfTestResult result) throws AbortSelfTest {
        DesktopSelfTestHostObserver.stage("SELFTEST-ORIENTATION-001");
        try {
            final PhoneOrientationLease lease =
                    PhoneOrientationLease.open(context);
            result.add(DesktopSelfTestResult.State.PASS,
                    "SELFTEST-ORIENTATION-001",
                    "Lock phone orientation for the self-test",
                    lease.detail());
            return lease;
        } catch (IOException | RuntimeException error) {
            failAndAbort(
                    result,
                    "SELFTEST-ORIENTATION-001",
                    "Lock phone orientation for the self-test",
                    usefulMessage(error));
            throw new AssertionError("unreachable");
        }
    }

    private static void restoreResultTask(
            final DesktopSelfTestResult result,
            final DesktopSelfTestTarget target,
            final int resultTaskId) {
        if (target != DesktopSelfTestTarget.PHONE || resultTaskId < 0) {
            return;
        }
        try {
            // The phone desktop owns a temporary task display area. Focus the
            // report only after cleanup, through the same shell transition as
            // other tasks, so a stale vendor parent cannot crash the app UI.
            ShellAccess.run(TaskFocusCommands.createShellCommand(
                    Display.DEFAULT_DISPLAY,
                    Collections.singletonList(Integer.valueOf(resultTaskId))));
            waitForFrontTask(Display.DEFAULT_DISPLAY, resultTaskId);
            result.add(DesktopSelfTestResult.State.PASS,
                    "SELFTEST-UI-001",
                    "Return to diagnostics after phone self-test",
                    "task=" + resultTaskId);
        } catch (IOException | RuntimeException error) {
            result.add(DesktopSelfTestResult.State.FAIL,
                    "SELFTEST-UI-001",
                    "Return to diagnostics after phone self-test",
                    usefulMessage(error));
        }
    }

    static String phoneUiUnavailableReason(final Context context) {
        if (context == null) {
            return "application context is unavailable";
        }
        final PowerManager powerManager =
                context.getSystemService(PowerManager.class);
        if (powerManager != null && !powerManager.isInteractive()) {
            return "wake and unlock the phone before starting the test";
        }
        final KeyguardManager keyguardManager =
                context.getSystemService(KeyguardManager.class);
        if (keyguardManager != null && keyguardManager.isKeyguardLocked()) {
            return "unlock the phone before starting the test";
        }
        return null;
    }

    private static Map<String, Integer> inspectWindowTransitionPrecondition(
            final Context context,
            final DesktopSelfTestResult result) throws AbortSelfTest {
        final WindowTransitionHealthDiagnostics.Snapshot snapshot =
                WindowTransitionHealthDiagnostics.capture(context);
        if (!snapshot.available) {
            failAndAbort(
                    result,
                    "SELFTEST-SYSTEM-001",
                    "Window transition runtime health",
                    "cannot inspect SystemPerformanceHinter: "
                            + snapshot.error);
        }
        if (snapshot.hasStaleTransitions()) {
            result.add(
                    DesktopSelfTestResult.State.WARN,
                    "SELFTEST-SYSTEM-001",
                    "Window transition runtime health",
                    "pre-existing transition performance sessions reference "
                            + "missing displays: "
                            + snapshot.staleDetail()
                            + "; the test will continue and only newly created "
                            + "sessions will fail cleanup");
            return snapshot.staleTransitionCounts();
        }
        result.add(
                DesktopSelfTestResult.State.PASS,
                "SELFTEST-SYSTEM-001",
                "Window transition runtime health",
                "active=" + snapshot.sessions.size()
                        + ", stale=0");
        return Collections.emptyMap();
    }

    private static void recordWindowTransitionHealth(
            final Context context,
            final DesktopSelfTestResult result,
            final Map<String, Integer> staleTransitionBaseline) {
        final WindowTransitionHealthDiagnostics.Snapshot snapshot =
                WindowTransitionHealthDiagnostics.capture(context);
        if (!snapshot.available) {
            result.add(
                    DesktopSelfTestResult.State.FAIL,
                    "SELFTEST-SYSTEM-002",
                    "Window transition runtime after cleanup",
                    "cannot inspect SystemPerformanceHinter: "
                            + snapshot.error);
            return;
        }
        final Map<String, Integer> newStaleTransitions =
                snapshot.staleTransitionCountsAfter(
                        staleTransitionBaseline);
        if (!newStaleTransitions.isEmpty()) {
            result.add(
                    DesktopSelfTestResult.State.FAIL,
                    "SELFTEST-SYSTEM-002",
                    "Window transition runtime after cleanup",
                    "self-test left transition performance sessions for "
                            + "missing displays: "
                            + snapshot.staleDetail(newStaleTransitions)
                            + "; restart system_server or reboot");
            return;
        }
        result.add(
                DesktopSelfTestResult.State.PASS,
                "SELFTEST-SYSTEM-002",
                "Window transition runtime after cleanup",
                "active=" + snapshot.sessions.size()
                        + ", new-stale=0"
                        + (staleTransitionBaseline == null
                                || staleTransitionBaseline.isEmpty()
                                ? "" : ", pre-existing-stale="
                        + totalCount(staleTransitionBaseline)));
    }

    private static int totalCount(final Map<String, Integer> counts) {
        int total = 0;
        if (counts != null) {
            for (final Integer count : counts.values()) {
                total += count == null ? 0 : Math.max(0, count.intValue());
            }
        }
        return total;
    }

    private static void recordWindowTransitionLogHealth(
            final DesktopSelfTestResult result,
            final int displayId) {
        final WindowTransitionLogDiagnostics.Snapshot snapshot =
                WindowTransitionLogDiagnostics.capture(
                        result.startedAtMillis(), displayId);
        if (!snapshot.available) {
            result.add(
                    DesktopSelfTestResult.State.WARN,
                    "SELFTEST-SYSTEM-003",
                    "System transition ownership log",
                    snapshot.error);
            return;
        }
        if (snapshot.errorCount() > 0) {
            result.add(
                    DesktopSelfTestResult.State.WARN,
                    "SELFTEST-SYSTEM-003",
                    "System transition ownership log",
                    snapshot.detail());
            return;
        }
        result.add(
                DesktopSelfTestResult.State.PASS,
                "SELFTEST-SYSTEM-003",
                "System transition ownership log",
                "no ownership errors during the self-test");
    }

    private static void preparePhoneSystemPanel(
            final DesktopSelfTestResult result) throws AbortSelfTest {
        try {
            if (!isPhoneSystemPanelVisible()) {
                result.add(DesktopSelfTestResult.State.PASS,
                        "SELFTEST-PRECONDITION-002",
                        "Close the phone notification shade",
                        "already closed");
                return;
            }
            ShellAccess.run("/system/bin/cmd statusbar collapse");
            final long deadline = SystemClock.uptimeMillis()
                    + STEP_TIMEOUT_MILLIS;
            while (SystemClock.uptimeMillis() < deadline) {
                if (!isPhoneSystemPanelVisible()) {
                    result.add(DesktopSelfTestResult.State.PASS,
                            "SELFTEST-PRECONDITION-002",
                            "Close the phone notification shade",
                            "closed");
                    return;
                }
                BoundedStateAwaiter.pause(
                        BoundedStateAwaiter.Reason.DISPLAY_STATE,
                        POLL_MILLIS);
            }
            failAndAbort(result,
                    "SELFTEST-PRECONDITION-002",
                    "Close the phone notification shade",
                    "SystemUI did not close the notification shade or "
                            + "Quick Settings");
        } catch (IOException error) {
            failAndAbort(result,
                    "SELFTEST-PRECONDITION-002",
                    "Close the phone notification shade",
                    usefulMessage(error));
        }
    }

    private static boolean isPhoneSystemPanelVisible() throws IOException {
        return TaskInputWindowParser.hasVisibleNotificationPanel(
                FrameworkInputSnapshotSource.readRemote(),
                Display.DEFAULT_DISPLAY);
    }

    private static void requireNoActiveDesktop(
            final Context context,
            final DesktopSelfTestResult result) throws AbortSelfTest {
        final int activeDisplay = findBlockingDesktopDisplay(
                DesktopRuntimeBridge.getActiveDesktopDisplayId(),
                PlatformDrivers.current().projection()
                        .activeDesktopDisplayId(context));
        if (activeDisplay >= 0) {
            failAndAbort(result, "SELFTEST-PRECONDITION-001",
                    "No active desktop session",
                    "close the desktop on display " + activeDisplay + " first");
        }
        try {
            final TaskStackParser.Entry desktop = findTaskOnAnyDisplay(
                    ShellAccess.run("/system/bin/cmd activity stack list"),
                    DesktopSelfTestComponents.DESKTOP_CLASS);
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

    static int findBlockingDesktopDisplay(
            final int runtimeDisplay,
            final int platformDisplay) {
        if (runtimeDisplay >= Display.DEFAULT_DISPLAY) {
            return runtimeDisplay;
        }
        return platformDisplay > Display.DEFAULT_DISPLAY
                ? platformDisplay : Display.INVALID_DISPLAY;
    }

    private static int requirePreparedDisplay(
            final DesktopSelfTestTarget target,
            final DesktopSelfTestResult result) throws AbortSelfTest {
        final int displayId = DesktopRuntimeBridge.getActiveDesktopDisplayId();
        final DesktopDisplayTarget displayTarget =
                DesktopRuntimeBridge.getDesktopTarget(displayId);
        if (!target.matchesDisplay(displayId, displayTarget)) {
            failAndAbort(result,
                    "SELFTEST-PRECONDITION-001",
                    "Selected desktop session is ready",
                    "active display=" + displayId + ", target=" + target);
        }
        if (target == DesktopSelfTestTarget.EXTERNAL) {
            if (displayTarget.kind != DesktopDisplayTarget.Kind.WIRED
                    && displayTarget.kind
                            != DesktopDisplayTarget.Kind.WIRELESS) {
                failAndAbort(result,
                        "SELFTEST-PRECONDITION-001",
                        "Selected desktop session is ready",
                        "external transport is unavailable: "
                                + displayTarget.kind);
            }
        }
        result.add(DesktopSelfTestResult.State.PASS,
                "SELFTEST-PRECONDITION-001",
                "Selected desktop session is ready",
                "target=" + target + ", display=" + displayId);
        return displayId;
    }

    private static boolean inspectConfiguredOverlay(
            final DesktopSelfTestResult result) throws AbortSelfTest {
        try {
            final String previous = ShellAccess.run(
                    "/system/bin/settings get global "
                            + SimulatedDisplayLease.SETTING).trim();
            if (!previous.isEmpty() && !"null".equals(previous)) {
                result.add(DesktopSelfTestResult.State.WARN,
                        "SELFTEST-PRECONDITION-002",
                        "No existing simulated display",
                        SimulatedDisplayLease.SETTING + "=" + previous);
                return true;
            }
            result.add(DesktopSelfTestResult.State.PASS,
                    "SELFTEST-PRECONDITION-002",
                    "No existing simulated display", "ready");
            return false;
        } catch (IOException error) {
            failAndAbort(result, "SELFTEST-PRECONDITION-002",
                    "Inspect simulated display setting", usefulMessage(error));
            return false;
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
            final Set<Integer> liveTaskIds = new LinkedHashSet<>();
            for (final TaskStackParser.Entry task : TaskStackParser.parse(
                    ShellAccess.run("/system/bin/cmd activity stack list"))) {
                liveTaskIds.add(Integer.valueOf(task.taskId));
            }
            final Map<Integer, Set<Integer>> liveRepositoryTasks =
                    selectExternalRepositoryTasks(
                            tasksByDisplay, liveTaskIds, true);
            if (!liveRepositoryTasks.isEmpty()) {
                failAndAbort(result, "SELFTEST-PRECONDITION-003",
                        "No stale external desktop tasks",
                        liveRepositoryTasks.toString());
            }
            final Map<Integer, Set<Integer>> unavailableRepositoryTasks =
                    selectExternalRepositoryTasks(
                            tasksByDisplay, liveTaskIds, false);
            if (!unavailableRepositoryTasks.isEmpty()) {
                // Nubia can retain task IDs after both the task and its
                // simulated display are gone. They cannot affect a new
                // display, but are useful firmware diagnostics.
                result.add(DesktopSelfTestResult.State.WARN,
                        "SELFTEST-PRECONDITION-003",
                        "No stale external desktop tasks",
                        "ignored unavailable firmware entries "
                                + unavailableRepositoryTasks);
                return;
            }
            result.add(DesktopSelfTestResult.State.PASS,
                    "SELFTEST-PRECONDITION-003",
                    "No stale external desktop tasks", "ready");
        } catch (IOException error) {
            failAndAbort(result, "SELFTEST-PRECONDITION-003",
                    "Inspect external desktop tasks", usefulMessage(error));
        }
    }

    static Map<Integer, Set<Integer>> selectExternalRepositoryTasks(
            final Map<Integer, Set<Integer>> tasksByDisplay,
            final Set<Integer> liveTaskIds,
            final boolean selectLive) {
        final Map<Integer, Set<Integer>> selected = new LinkedHashMap<>();
        if (tasksByDisplay == null || liveTaskIds == null) {
            return selected;
        }
        for (final Map.Entry<Integer, Set<Integer>> entry
                : tasksByDisplay.entrySet()) {
            if (entry.getKey() == null
                    || entry.getKey().intValue() <= Display.DEFAULT_DISPLAY
                    || entry.getValue() == null) {
                continue;
            }
            final Set<Integer> taskIds = new LinkedHashSet<>();
            for (final Integer taskId : entry.getValue()) {
                if (taskId != null
                        && liveTaskIds.contains(taskId) == selectLive) {
                    taskIds.add(taskId);
                }
            }
            if (!taskIds.isEmpty()) {
                selected.put(entry.getKey(), taskIds);
            }
        }
        return selected;
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
            BoundedStateAwaiter.pause(
                    BoundedStateAwaiter.Reason.TASK_HIERARCHY,
                    POLL_MILLIS);
        } while (SystemClock.uptimeMillis() < deadline);
        return -1;
    }

    private static void recordDesktopHostObservation(
            final DesktopSelfTestResult result,
            final int displayId) {
        final DesktopSelfTestHostObserver.Observation observation =
                DesktopSelfTestHostObserver.finish(displayId);
        if (!observation.hostSeen) {
            result.add(DesktopSelfTestResult.State.NOT_TESTED,
                    "DESKTOP-006",
                    "Keep the desktop host stable and fullscreen",
                    "desktop host did not render during the test");
            return;
        }
        result.add(observation.renderedFreeform
                        || observation.recreated
                        || observation.lostReadyUi
                        ? DesktopSelfTestResult.State.FAIL
                        : DesktopSelfTestResult.State.PASS,
                "DESKTOP-006",
                "Keep the desktop host stable and fullscreen",
                observation.detail);
    }

    private static void recordPhoneUiObservation(
            final DesktopSelfTestResult result,
            final int displayId) {
        try {
            DesktopSelfTestPhoneUiObserver.sampleCurrentTasks();
        } catch (IOException ignored) {
            // Lifecycle and runtime task observations remain available.
        }
        final DesktopSelfTestPhoneUiObserver.Observation observation =
                DesktopSelfTestPhoneUiObserver.finish(displayId);
        if (!observation.observed
                || !observation.touchpadExpected
                || !observation.touchpadRequested) {
            result.add(DesktopSelfTestResult.State.NOT_TESTED,
                    "PHONEUI-001",
                    "Restore the phone touchpad after task transitions",
                    !observation.observed
                            ? "desktop phone UI was not observed"
                            : !observation.touchpadExpected
                                    ? "selected display does not use a phone touchpad"
                                    : "automatic phone touchpad is disabled");
        } else {
            result.add(observation.touchpadStable()
                            ? DesktopSelfTestResult.State.PASS
                            : DesktopSelfTestResult.State.FAIL,
                    "PHONEUI-001",
                    "Restore the phone touchpad after task transitions",
                    observation.detail);
        }
        if (!observation.observed || displayId <= Display.DEFAULT_DISPLAY) {
            result.add(DesktopSelfTestResult.State.NOT_TESTED,
                    "PHONEUI-002",
                    "Keep unrelated test windows invisible on the phone",
                    "the selected desktop uses display 0");
        } else {
            result.add(!observation.fixtureExposed
                            ? DesktopSelfTestResult.State.PASS
                            : DesktopSelfTestResult.State.FAIL,
                    "PHONEUI-002",
                    "Keep unrelated test windows invisible on the phone",
                    observation.detail);
        }
        if (!observation.observed) {
            result.add(DesktopSelfTestResult.State.NOT_TESTED,
                    "PHONEUI-003",
                    "Preserve phone task windowing modes",
                    "desktop phone UI was not observed");
        } else {
            result.add(!observation.phoneTaskModeChanged
                            ? DesktopSelfTestResult.State.PASS
                            : DesktopSelfTestResult.State.FAIL,
                    "PHONEUI-003",
                    "Preserve phone task windowing modes",
                    observation.detail);
        }
    }

    private static DesktopSelfTestResult finish(
            final DesktopSelfTestResult result, final Context context) {
        result.finish(System.currentTimeMillis());
        result.save(context);
        try {
            DesktopAutomationEventJournal.record(
                    "self_test",
                    "finished",
                    !result.hasFailures(),
                    result.summary(),
                    new org.json.JSONObject()
                            .put("failed", result.hasFailures())
                            .put("summary", result.summary())
                            .put("resultModifiedAtMillis",
                                    DesktopSelfTestResult.lastModifiedMillis(
                                            context)));
        } catch (org.json.JSONException ignored) {
            DesktopAutomationEventJournal.record(
                    "self_test", "finished", !result.hasFailures(),
                    result.summary());
        }
        if (result.hasFailures()) {
            CompatibilityDiagnostics.record(
                    "SELFTEST-004",
                    "The built-in desktop self-test reported a failure",
                    result.summary());
        }
        return result;
    }

}
