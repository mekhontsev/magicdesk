package io.github.mekhontsev.magicdesk;

import static io.github.mekhontsev.magicdesk.DesktopSelfTestSteps.check;
import static io.github.mekhontsev.magicdesk.DesktopSelfTestSteps.usefulMessage;
import static io.github.mekhontsev.magicdesk.DesktopSelfTestTasks.findTaskOnAnyDisplay;
import static io.github.mekhontsev.magicdesk.DesktopSelfTestTasks.sendSystemBack;
import static io.github.mekhontsev.magicdesk.DesktopSelfTestTasks.waitForFrontTask;
import static io.github.mekhontsev.magicdesk.DesktopSelfTestTasks.waitForReadyDesktopHost;
import static io.github.mekhontsev.magicdesk.DesktopSelfTestTasks.waitForTask;
import static io.github.mekhontsev.magicdesk.DesktopSelfTestTasks.waitForTaskAbsent;

import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;

import java.io.IOException;

/** Exercises app-owned child results and system Back at each stack depth. */
final class DesktopSelfTestBackNavigationSuite {
    private static final String FIXTURE_CLASS =
            DesktopSelfTestComponents.FIXTURE_CLASS;

    private DesktopSelfTestBackNavigationSuite() {
    }

    static void run(
            final Context context,
            final DesktopSelfTestResult result,
            final int displayId,
            final DesktopSelfTestGeometry geometry) {
        final DesktopSessionSnapshot session =
                DesktopRuntimeBridge.getSessionSnapshot();
        if (session.activeDisplayId() != displayId
                || session.hostTaskId() < 0) {
            addUnavailableResults(result, "desktop host is unavailable");
            return;
        }
        final int hostTaskId = session.hostTaskId();

        check(result,
                "BACK-NAVIGATION-001",
                "Ignore system Back on an empty desktop",
                () -> verifyEmptyDesktopBack(displayId, hostTaskId));

        check(result,
                "ACTIVITY-RESULT-001",
                "Launch a child Activity and return its result within freeform",
                () -> verifyActivityResult(context, displayId, hostTaskId,
                        geometry.primaryWindow()));

        final boolean singleWindowPassed = runSingleWindowBack(
                context,
                result,
                displayId,
                hostTaskId,
                geometry.primaryWindow());
        if (!singleWindowPassed) {
            cleanupFixturesBestEffort();
        }

        final Fixture survivor = runTwoWindowBack(
                context,
                result,
                displayId,
                geometry.leftWindow(),
                geometry.rightWindow());
        if (survivor == null) {
            result.add(DesktopSelfTestResult.State.NOT_TESTED,
                    "BACK-NAVIGATION-004",
                    "Transfer input focus to the surviving window",
                    "two-window Back transition failed");
            result.add(DesktopSelfTestResult.State.NOT_TESTED,
                    "BACK-NAVIGATION-005",
                    "Return from the survivor without hiding the taskbar",
                    "two-window Back transition failed");
            cleanupFixturesBestEffort();
            return;
        }

        check(result,
                "BACK-NAVIGATION-004",
                "Transfer input focus to the surviving window",
                () -> verifySurvivorInput(
                        context, displayId, survivor));
        check(result,
                "BACK-NAVIGATION-005",
                "Return from the survivor without hiding the taskbar",
                () -> finishBackSequence(
                        displayId,
                        hostTaskId,
                        survivor.taskId));
    }

    private static String verifyEmptyDesktopBack(
            final int displayId,
            final int hostTaskId) throws IOException {
        DesktopSelfTestCleanup.removeFixtureTasks();
        requireNoFixtureTasks();
        waitForReadyDesktopHost(displayId, hostTaskId);
        final int taskbarGeneration =
                DesktopSelfTestHostObserver.taskbarHiddenGeneration();
        sendSystemBack(displayId);
        requireNoFixtureTasks();
        final String host = waitForReadyDesktopHost(displayId, hostTaskId);
        requireTaskbarStayedVisible(taskbarGeneration);
        return host;
    }

    private static String verifyActivityResult(
            final Context context, final int displayId,
            final int hostTaskId, final Rect bounds) throws IOException {
        try {
            final Fixture parent = launchFixture(context, displayId, bounds,
                    DesktopSelfTestFixtureAppearance.PRIMARY);
            final String childToken = parent.token + "-child";
            DesktopSelfTestFixtureState.clearLaunchMarkers(context);
            DesktopSelfTestFixtureState.clearChildResult(context);
            final int taskbarGeneration =
                    DesktopSelfTestHostObserver.taskbarHiddenGeneration();
            ShellAccess.run("/system/bin/am broadcast --user 0 -a "
                    + ShellCommandLine.quote(DesktopSelfTestActivity.ACTION_LAUNCH_CHILD)
                    + " -p " + ShellCommandLine.quote(BuildConfig.APPLICATION_ID)
                    + " --es " + ShellCommandLine.quote(
                            DesktopSelfTestActivity.EXTRA_TARGET_TOKEN)
                    + " " + ShellCommandLine.quote(parent.token)
                    + " --es " + ShellCommandLine.quote(
                            DesktopSelfTestActivity.EXTRA_CHILD_TOKEN)
                    + " " + ShellCommandLine.quote(childToken));
            DesktopSelfTestFixtureState.awaitFirstFrame(
                    context, childToken, displayId);
            waitForTask(displayId, FIXTURE_CLASS,
                    task -> task.taskId == parent.taskId && task.visible
                            && "freeform".equals(task.windowingMode)
                            && DesktopSelfTestGeometry.matches(task.bounds, bounds));
            waitForFrontTask(displayId, parent.taskId);
            sendSystemBack(displayId);
            DesktopSelfTestFixtureState.awaitChildResult(
                    context, parent.token, childToken, displayId);
            waitForTask(displayId, FIXTURE_CLASS,
                    task -> task.taskId == parent.taskId && task.visible
                            && "freeform".equals(task.windowingMode)
                            && DesktopSelfTestGeometry.matches(task.bounds, bounds));
            verifySurvivorInput(context, displayId, parent);
            requireTaskbarStayedVisible(taskbarGeneration);
            sendSystemBack(displayId);
            waitForTaskAbsent(parent.taskId);
            waitForReadyDesktopHost(displayId, hostTaskId);
            return "task=" + parent.taskId
                    + ", child=result-ok, mode=freeform, bounds=unchanged"
                    + ", parent-input=received, taskbar-hidden-events=0";
        } finally {
            cleanupFixturesBestEffort();
        }
    }

    private static boolean runSingleWindowBack(
            final Context context,
            final DesktopSelfTestResult result,
            final int displayId,
            final int hostTaskId,
            final Rect bounds) {
        final String code = "BACK-NAVIGATION-002";
        try {
            DesktopSelfTestHostObserver.stage(
                    "BACK-NAVIGATION-PREPARE-002");
            final Fixture fixture = launchFixture(
                    context,
                    displayId,
                    bounds,
                    DesktopSelfTestFixtureAppearance.PRIMARY);
            DesktopSelfTestHostObserver.stage(code);
            final int taskbarGeneration =
                    DesktopSelfTestHostObserver.taskbarHiddenGeneration();
            sendSystemBack(displayId);
            waitForTaskAbsent(fixture.taskId);
            final String host = waitForReadyDesktopHost(
                    displayId, hostTaskId);
            requireTaskbarStayedVisible(taskbarGeneration);
            result.add(DesktopSelfTestResult.State.PASS,
                    code,
                    "Return from one freeform window to the desktop",
                    "closed=" + fixture.taskId + ", " + host);
            return true;
        } catch (Exception error) {
            result.add(DesktopSelfTestResult.State.FAIL,
                    code,
                    "Return from one freeform window to the desktop",
                    usefulMessage(error));
            return false;
        }
    }

    private static Fixture runTwoWindowBack(
            final Context context,
            final DesktopSelfTestResult result,
            final int displayId,
            final Rect firstBounds,
            final Rect secondBounds) {
        final String code = "BACK-NAVIGATION-003";
        try {
            DesktopSelfTestHostObserver.stage(
                    "BACK-NAVIGATION-PREPARE-003");
            final Fixture first = launchFixture(
                    context,
                    displayId,
                    firstBounds,
                    DesktopSelfTestFixtureAppearance.PRIMARY);
            final Fixture second = launchFixture(
                    context,
                    displayId,
                    secondBounds,
                    DesktopSelfTestFixtureAppearance.SECONDARY);
            DesktopSelfTestHostObserver.stage(code);
            final int taskbarGeneration =
                    DesktopSelfTestHostObserver.taskbarHiddenGeneration();
            sendSystemBack(displayId);
            waitForTaskAbsent(second.taskId);
            final TaskStackParser.Entry survivor = waitForTask(
                    displayId,
                    FIXTURE_CLASS,
                    task -> task.taskId == first.taskId
                            && task.visible
                            && "freeform".equals(task.windowingMode)
                            && DesktopSelfTestGeometry.matches(
                                    task.bounds, first.bounds));
            requireTaskbarStayedVisible(taskbarGeneration);
            result.add(DesktopSelfTestResult.State.PASS,
                    code,
                    "Close only the active one of two freeform windows",
                    "closed=" + second.taskId
                            + ", survivor=" + survivor.taskId
                            + "/freeform/visible");
            return first;
        } catch (Exception error) {
            result.add(DesktopSelfTestResult.State.FAIL,
                    code,
                    "Close only the active one of two freeform windows",
                    usefulMessage(error));
            return null;
        }
    }

    private static String verifySurvivorInput(
            final Context context,
            final int displayId,
            final Fixture survivor) throws IOException {
        waitForFrontTask(displayId, survivor.taskId);
        DesktopSelfTestFixtureState.clearText(context);
        DesktopSelfTestInputSuite.typeAndVerifyText(
                context,
                displayId,
                survivor.taskId,
                survivor.token,
                "6");
        return "task=" + survivor.taskId + ", token=" + survivor.token;
    }

    private static String finishBackSequence(
            final int displayId,
            final int hostTaskId,
            final int survivorTaskId) throws IOException {
        final int taskbarGeneration =
                DesktopSelfTestHostObserver.taskbarHiddenGeneration();
        sendSystemBack(displayId);
        waitForTaskAbsent(survivorTaskId);
        final String host = waitForReadyDesktopHost(displayId, hostTaskId);
        requireTaskbarStayedVisible(taskbarGeneration);
        return "closed=" + survivorTaskId + ", " + host
                + ", taskbar-hidden-events=0";
    }

    private static void requireTaskbarStayedVisible(
            final int initialGeneration) throws IOException {
        final int hiddenCount = DesktopSelfTestHostObserver
                .taskbarHiddenGeneration() - initialGeneration;
        if (hiddenCount != 0) {
            throw new IOException("taskbar became hidden "
                    + hiddenCount + " time(s) during Back navigation");
        }
    }

    private static Fixture launchFixture(
            final Context context,
            final int displayId,
            final Rect bounds,
            final DesktopSelfTestFixtureAppearance appearance)
            throws IOException {
        DesktopSelfTestFixtureState.clearLaunchMarkers(context);
        final String token = "back-"
                + Long.toHexString(System.nanoTime());
        final Intent intent = TaskDisplayAreaLaunchCommand
                .createSelfTestIntent(
                        displayId, token, false, appearance);
        final DesktopTaskLaunchProbe.Observation observation =
                DesktopSelfTestTasks.launchWindowedAndObserve(
                        displayId, bounds, FIXTURE_CLASS, intent);
        DesktopSelfTestPhoneUiObserver.allowPhoneFixtureTask(
                observation.taskId);
        DesktopSelfTestFixtureState.awaitFirstFrame(
                context, token, displayId);
        waitForTask(
                displayId,
                FIXTURE_CLASS,
                task -> task.taskId == observation.taskId
                        && "freeform".equals(task.windowingMode)
                        && DesktopSelfTestGeometry.matches(
                                task.bounds, bounds));
        DesktopSelfTestInputSuite.restoreTaskFocus(
                displayId, observation.taskId);
        return new Fixture(observation.taskId, token, new Rect(bounds));
    }

    private static void requireNoFixtureTasks() throws IOException {
        final String stack = ShellAccess.run(
                "/system/bin/cmd activity stack list");
        final TaskStackParser.Entry fixture = findTaskOnAnyDisplay(
                stack, FIXTURE_CLASS);
        final TaskStackParser.Entry browserFixture = findTaskOnAnyDisplay(
                stack, DesktopSelfTestComponents.BROWSER_FIXTURE_CLASS);
        final TaskStackParser.Entry remaining = fixture == null
                ? browserFixture : fixture;
        if (remaining != null) {
            throw new IOException("fixture task " + remaining.taskId
                    + " remained on display " + remaining.displayId);
        }
    }

    private static void cleanupFixturesBestEffort() {
        try {
            DesktopSelfTestCleanup.removeFixtureTasks();
        } catch (IOException ignored) {
            // The global self-test cleanup reports any remaining fixture.
        }
    }

    private static void addUnavailableResults(
            final DesktopSelfTestResult result,
            final String reason) {
        result.add(DesktopSelfTestResult.State.NOT_TESTED,
                "ACTIVITY-RESULT-001",
                "Launch a child Activity and return its result within freeform", reason);
        result.add(DesktopSelfTestResult.State.NOT_TESTED,
                "BACK-NAVIGATION-001",
                "Ignore system Back on an empty desktop", reason);
        result.add(DesktopSelfTestResult.State.NOT_TESTED,
                "BACK-NAVIGATION-002",
                "Return from one freeform window to the desktop", reason);
        result.add(DesktopSelfTestResult.State.NOT_TESTED,
                "BACK-NAVIGATION-003",
                "Close only the active one of two freeform windows", reason);
        result.add(DesktopSelfTestResult.State.NOT_TESTED,
                "BACK-NAVIGATION-004",
                "Transfer input focus to the surviving window", reason);
        result.add(DesktopSelfTestResult.State.NOT_TESTED,
                "BACK-NAVIGATION-005",
                "Return from the survivor without hiding the taskbar", reason);
    }

    private static final class Fixture {
        final int taskId;
        final String token;
        final Rect bounds;

        Fixture(final int taskId, final String token, final Rect bounds) {
            this.taskId = taskId;
            this.token = token;
            this.bounds = bounds;
        }
    }
}
