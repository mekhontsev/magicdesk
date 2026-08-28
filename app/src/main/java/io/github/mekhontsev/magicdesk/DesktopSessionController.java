package io.github.mekhontsev.magicdesk;

import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Focuses or creates the desktop after its target display is ready. */
final class DesktopSessionController {
    private static final String TAG = "MagicDeskDesktopSession";
    private static final String AM = "/system/bin/am";
    private static final String DESKTOP_COMPONENT =
            "io.github.mekhontsev.magicdesk/.DesktopActivity";
    private static final int ACTIVITY_TYPE_HOME = 2;
    private static final String TASK_CONTROL_COMMAND =
            "io.github.mekhontsev.magicdesk.TaskControlCommand";
    private static final Pattern DESKTOP_TASK_ID_PATTERN =
            Pattern.compile("desktop-task-id=(-?\\d+)");

    private DesktopSessionController() {
    }

    static final class ShowResult {
        final boolean ready;
        final boolean created;

        ShowResult(final boolean ready, final boolean created) {
            this.ready = ready;
            this.created = created;
        }
    }

    static ShowResult show(final DesktopDisplayTarget target)
            throws IOException {
        return show(target, DesktopSessionPolicy.USER);
    }

    static ShowResult show(
            final DesktopDisplayTarget target,
            final DesktopSessionPolicy policy) throws IOException {
        if (target == null) {
            throw new IllegalArgumentException("display target is required");
        }
        final DesktopDisplayTarget preparedTarget =
                DisplayProfileController.prepareTarget(
                        MagicDeskApplication.applicationContext(), target);
        DesktopStateStore.load();
        if (!ConsoleDisplayController.displayExists(preparedTarget.displayId)) {
            throw new IOException(
                    "desktop display no longer exists: "
                            + preparedTarget.displayId);
        }
        final DesktopSessionPolicy resolvedPolicy = policy == null
                ? DesktopSessionPolicy.USER : policy;
        DesktopRuntimeBridge.noteDesktopTarget(
                preparedTarget, resolvedPolicy);
        try {
            prepareDisplayWindowing(preparedTarget);
            final Boolean visibleTaskSnapshot =
                    MagicDeskRuntime.hasVisibleAppTaskSnapshot(
                            preparedTarget.displayId);
            final boolean restoreWindows = resolvedPolicy.restoreWorkspace
                    && visibleTaskSnapshot != null
                    && !visibleTaskSnapshot.booleanValue();
            final int desktopTaskId = findDesktopTask(preparedTarget.displayId);
            if (desktopTaskId >= 0) {
                Log.i(TAG, "restoring desktop kind=" + preparedTarget.kind
                        + " display=" + preparedTarget.displayId
                        + " task=" + desktopTaskId);
                if (!resolvedPolicy.restoreWorkspace) {
                    Log.i(TAG, "isolated desktop reuses host without restoring"
                            + " display=" + preparedTarget.displayId);
                } else if (restoreWindows) {
                    MagicDeskRuntime.restoreLastVisibleWindows();
                } else {
                    MagicDeskRuntime.restoreSessionWorkspace(
                            preparedTarget.displayId,
                            java.util.Collections.singletonList(
                                    Integer.valueOf(desktopTaskId)),
                            null);
                }
                if (resolvedPolicy.restoreWorkspace) {
                    MagicDeskRuntime.restoreParkedDesktopTasksWhenReady(
                            preparedTarget);
                }
                return new ShowResult(true, false);
            }

            final String output = ShellAccess.run(
                    AM + " start -W --display " + preparedTarget.displayId
                            + " --windowingMode 1"
                            + " --activityType " + ACTIVITY_TYPE_HOME
                            + " -f 0x18000000"
                            + " -a android.intent.action.MAIN"
                            + " -c android.intent.category.HOME"
                            + " --ei "
                            + DesktopShellActivity.EXTRA_EXPECTED_DISPLAY_ID
                            + " " + preparedTarget.displayId
                            + " --ei "
                            + DesktopShellActivity.EXTRA_PROFILE_DISPLAY_ID
                            + " " + preparedTarget.profileDisplayId
                            + " --es "
                            + DesktopShellActivity.EXTRA_PROFILE_KEY
                            + " "
                            + ShellCommandLine.quote(preparedTarget.profileKey)
                            + " --es "
                            + DesktopShellActivity.EXTRA_TARGET_KIND
                            + " " + preparedTarget.kind.name()
                            + " --es "
                            + DesktopShellActivity.EXTRA_ACTIVATION_SOURCE
                            + " " + preparedTarget.activationSource.name()
                            + " --es "
                            + DesktopShellActivity.EXTRA_SESSION_POLICY
                            + " " + resolvedPolicy.name()
                            + (restoreWindows
                                    ? " --es " + DesktopShellActivity.EXTRA_ACTION
                                            + " "
                                            + DesktopShellActivity
                                                    .ACTION_RESTORE_WINDOWS
                                    : "")
                            + " -n " + DESKTOP_COMPONENT)
                    .trim();
            if (output.startsWith("Error:")
                    || output.contains(
                            "Exception occurred while executing")) {
                throw new IOException(output);
            }
            Log.i(TAG, "launched desktop kind=" + preparedTarget.kind
                    + " display=" + preparedTarget.displayId
                    + " output=" + output.replace('\n', ' '));
            final boolean ready = waitForDesktopReady(preparedTarget.displayId);
            if (!ready) {
                DesktopRuntimeBridge.clearDesktopTarget(preparedTarget);
                MagicDeskRuntime.reconcileFailedDesktopLaunch(
                        preparedTarget.displayId);
            }
            if (ready && resolvedPolicy.restoreWorkspace) {
                MagicDeskRuntime.restoreParkedDesktopTasksWhenReady(
                        preparedTarget);
            }
            return new ShowResult(ready, true);
        } catch (IOException | RuntimeException error) {
            DesktopRuntimeBridge.clearDesktopTarget(preparedTarget);
            MagicDeskRuntime.reconcileFailedDesktopLaunch(
                    preparedTarget.displayId);
            throw error;
        }
    }

    private static void prepareDisplayWindowing(
            final DesktopDisplayTarget target) throws IOException {
        if (target.displayId <= 0
                || !PlatformDrivers.current().windowing()
                        .requiresMirrorInputFocusSynchronization()) {
            return;
        }
        ShellAccess.run(AppProcessCommand.run(
                DisplayWindowingModeCommand.class.getName(),
                Integer.toString(target.displayId)));
    }

    private static int findDesktopTask(final int displayId)
            throws IOException {
        final String output = ShellAccess.run(
                AppProcessCommand.run(
                        TASK_CONTROL_COMMAND,
                        "desktop-task-id " + displayId));
        final Matcher matcher = DESKTOP_TASK_ID_PATTERN.matcher(output);
        if (!matcher.find()) {
            throw new IOException(
                    "could not query MagicDesk desktop task: "
                            + output.trim());
        }
        return Integer.parseInt(matcher.group(1));
    }

    private static boolean waitForDesktopReady(final int displayId)
            throws IOException {
        final long deadline = SystemClock.uptimeMillis()
                + ConsoleDisplayController.START_TIMEOUT_MS;
        while (SystemClock.uptimeMillis() < deadline) {
            if (!ConsoleDisplayController.displayExists(displayId)) {
                return false;
            }
            if (findDesktopTask(displayId) >= 0) {
                return true;
            }
            BoundedStateAwaiter.pause(
                    BoundedStateAwaiter.Reason.DISPLAY_STATE,
                    ConsoleDisplayController.STATE_POLL_MS);
        }
        return false;
    }
}
