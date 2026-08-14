package io.github.mekhontsev.magicdesk;

import static io.github.mekhontsev.magicdesk.DesktopSelfTestSteps.failAndAbort;
import static io.github.mekhontsev.magicdesk.DesktopSelfTestSteps.require;
import static io.github.mekhontsev.magicdesk.DesktopSelfTestSteps.usefulMessage;
import static io.github.mekhontsev.magicdesk.DesktopSelfTestTasks.POLL_MILLIS;
import static io.github.mekhontsev.magicdesk.DesktopSelfTestTasks.STEP_TIMEOUT_MILLIS;
import static io.github.mekhontsev.magicdesk.DesktopSelfTestTasks.findTaskOnAnyDisplay;

import android.content.Context;
import android.os.SystemClock;
import android.view.Display;

import java.io.IOException;
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

        final DesktopSelfTestTarget target = requestedTarget == null
                ? DesktopSelfTestTarget.SIMULATED : requestedTarget;
        if (!DesktopSelfTestHostObserver.isActive()) {
            DesktopSelfTestHostObserver.begin();
        }
        int displayId = Display.INVALID_DISPLAY;
        SimulatedDisplayLease lease = null;
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
            if (!DesktopSelfTestCapabilityAudit.run(
                    appContext, result, target)) {
                throw new AbortSelfTest();
            }
            if (target == DesktopSelfTestTarget.SIMULATED) {
                requireNoActiveDesktop(appContext, result);
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
            } else {
                displayId = requirePreparedDisplay(target, result);
            }
            DesktopSelfTestWindowSuite.run(
                    appContext, target, displayId, result);
        } catch (AbortSelfTest ignored) {
            // The failing required step has already been added to the result.
        } catch (RuntimeException error) {
            result.add(DesktopSelfTestResult.State.FAIL,
                    "SELFTEST-003", "Unexpected self-test failure",
                    usefulMessage(error));
        } finally {
            recordDesktopHostObservation(result, displayId);
            recordPhoneUiObservation(result, displayId);
            DesktopSelfTestCleanup.run(result,
                    target,
                    displayId,
                    lease,
                    restoreExternalMirror);
            RUNNING.set(false);
        }
        return finish(result, appContext);
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
        if (result.hasFailures()) {
            CompatibilityDiagnostics.record(
                    "SELFTEST-004",
                    "The built-in desktop self-test reported a failure",
                    result.summary());
        }
        return result;
    }

}
