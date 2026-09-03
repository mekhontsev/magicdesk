package io.github.mekhontsev.magicdesk;

import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Focuses or creates the desktop after its target display is ready. */
final class DesktopSessionController {
    private static final String TAG = "MagicDeskDesktopSession";
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
        if (!ExternalDisplayController.displayExists(preparedTarget.displayId)) {
            throw new IOException(
                    "desktop display no longer exists: "
                            + preparedTarget.displayId);
        }
        final DesktopSessionPolicy resolvedPolicy = policy == null
                ? DesktopSessionPolicy.USER : policy;
        final DesktopHomeRoleLease.AcquireResult homeAcquisition =
                DesktopHomeRoleLease.acquire(preparedTarget, resolvedPolicy);
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
            if (preparedTarget.kind == DesktopDisplayTarget.Kind.PHONE) {
                return showPrimaryHome(
                        preparedTarget,
                        resolvedPolicy,
                        restoreWindows,
                        homeAcquisition,
                        desktopTaskId);
            }
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
                    MagicDeskRuntime.restoreDesktopWorkspace(
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

            final Intent intent = createExternalHomeIntent(
                    preparedTarget, resolvedPolicy, restoreWindows);
            final int hostTaskId = ShellAccess.launchDesktopHost(
                    preparedTarget.displayId, intent);
            Log.i(TAG, "launched desktop kind=" + preparedTarget.kind
                    + " display=" + preparedTarget.displayId
                    + " task=" + hostTaskId
                    + " as HOME");
            final boolean ready = waitForDesktopReady(preparedTarget.displayId);
            if (!ready) {
                DesktopHomeRoleLease.releaseAfterFailedStart(
                        homeAcquisition);
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
            try {
                DesktopHomeRoleLease.releaseAfterFailedStart(
                        homeAcquisition);
            } catch (IOException releaseError) {
                error.addSuppressed(releaseError);
            }
            DesktopRuntimeBridge.clearDesktopTarget(preparedTarget);
            MagicDeskRuntime.reconcileFailedDesktopLaunch(
                    preparedTarget.displayId);
            throw error;
        }
    }

    private static ShowResult showPrimaryHome(
            final DesktopDisplayTarget target,
            final DesktopSessionPolicy policy,
            final boolean restoreWindows,
            final DesktopHomeRoleLease.AcquireResult homeAcquisition,
            final int desktopTaskId) throws IOException {
        // Claiming HOME starts the selected primary host in Android's default
        // task area. Launching it again would race that HOME task.
        if (desktopTaskId < 0) {
            throw new IOException(
                    "primary HOME did not create the phone desktop task");
        }
        Log.i(TAG, (homeAcquisition.created ? "launched" : "restoring")
                + " primary Home desktop kind=" + target.kind
                + " display=" + target.displayId
                + " task=" + desktopTaskId);
        final boolean ready = waitForDesktopReady(target.displayId);
        if (!ready) {
            DesktopHomeRoleLease.releaseAfterFailedStart(homeAcquisition);
            DesktopRuntimeBridge.clearDesktopTarget(target);
            MagicDeskRuntime.reconcileFailedDesktopLaunch(target.displayId);
            return new ShowResult(false, homeAcquisition.created);
        }
        if (policy.restoreWorkspace) {
            if (restoreWindows) {
                MagicDeskRuntime.restoreLastVisibleWindows();
            } else if (!homeAcquisition.created) {
                MagicDeskRuntime.restoreDesktopWorkspace(
                        target.displayId,
                        java.util.Collections.singletonList(
                                Integer.valueOf(desktopTaskId)),
                        null);
            }
            MagicDeskRuntime.restoreParkedDesktopTasksWhenReady(target);
        }
        return new ShowResult(true, homeAcquisition.created);
    }

    private static Intent createExternalHomeIntent(
            final DesktopDisplayTarget target,
            final DesktopSessionPolicy policy,
            final boolean restoreWindows) {
        final Intent intent = DesktopActivity.createSecondaryHomeIntent(
                MagicDeskApplication.applicationContext())
                .putExtra(
                        DesktopShellActivity.EXTRA_EXPECTED_DISPLAY_ID,
                        target.displayId)
                .putExtra(
                        DesktopShellActivity.EXTRA_PROFILE_DISPLAY_ID,
                        target.profileDisplayId)
                .putExtra(
                        DesktopShellActivity.EXTRA_PROFILE_KEY,
                        target.profileKey)
                .putExtra(
                        DesktopShellActivity.EXTRA_TARGET_KIND,
                        target.kind.name())
                .putExtra(
                        DesktopShellActivity.EXTRA_ACTIVATION_SOURCE,
                        target.activationSource.name())
                .putExtra(
                        DesktopShellActivity.EXTRA_SESSION_POLICY,
                        policy.name());
        if (restoreWindows) {
            intent.putExtra(
                    DesktopShellActivity.EXTRA_ACTION,
                    DesktopShellActivity.ACTION_RESTORE_WINDOWS);
        }
        return intent;
    }

    private static void prepareDisplayWindowing(
            final DesktopDisplayTarget target) throws IOException {
        if (target.displayId <= 0
                || !PlatformDrivers.current().windowing()
                        .requiresDesktopInputFocusSynchronization()) {
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
                + ExternalDisplayController.START_TIMEOUT_MS;
        while (SystemClock.uptimeMillis() < deadline) {
            if (!ExternalDisplayController.displayExists(displayId)) {
                return false;
            }
            if (findDesktopTask(displayId) >= 0) {
                return true;
            }
            BoundedStateAwaiter.pause(
                    BoundedStateAwaiter.Reason.DISPLAY_STATE,
                    ExternalDisplayController.STATE_POLL_MS);
        }
        return false;
    }
}
