package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;

import java.lang.ref.WeakReference;

/** Owns preparation and execution independently of the diagnostics Activity. */
final class DesktopSelfTestLauncher {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static DesktopSelfTestLauncher sActive;

    private final Context mContext;
    private final long mRunId;
    private final int mResultTaskId;
    private final DesktopSelfTestTarget mTarget;
    private final DesktopDisplayTarget.Kind mDisplayKind;
    private final DesktopSelfTestExecutionPolicy mPolicy;
    private WeakReference<Activity> mActivity;
    private DisplayManager mDisplayManager;
    private DisplayManager.DisplayListener mWirelessListener;
    private boolean mWirelessProbePending;
    private boolean mFinishingPreparation;

    private DesktopSelfTestLauncher(final Activity activity, final long runId,
            final DesktopSelfTestTarget target, final DesktopDisplayTarget.Kind kind,
            final DesktopSelfTestExecutionPolicy policy) {
        mContext = activity.getApplicationContext();
        mResultTaskId = activity.getTaskId();
        mActivity = new WeakReference<>(activity);
        mRunId = runId;
        mTarget = target;
        mDisplayKind = kind;
        mPolicy = policy;
    }

    static void attach(final Activity activity) {
        if (sActive != null && activity.getTaskId() == sActive.mResultTaskId) {
            sActive.mActivity = new WeakReference<>(activity);
        }
    }

    static boolean start(final Activity activity, final DesktopSelfTestTarget target,
            final DesktopDisplayTarget.Kind kind, final DesktopSelfTestExecutionPolicy policy,
            final long requestedRunId) {
        if (sActive != null || DesktopSelfTestController.isRunning()) {
            return false;
        }
        final String targetName = kind == null
                ? target.name().toLowerCase(java.util.Locale.ROOT)
                : kind.name().toLowerCase(java.util.Locale.ROOT);
        final long runId = requestedRunId > 0L ? requestedRunId
                : DesktopSelfTestRunState.beginRequest(
                        targetName, policy, System.currentTimeMillis());
        if (!DesktopSelfTestRunState.isStarting(runId)) {
            return false;
        }
        final DesktopSelfTestLauncher launcher =
                new DesktopSelfTestLauncher(activity, runId, target, kind, policy);
        sActive = launcher;
        DesktopSelfTestRunState.registerPreparationCancellationHandler(
                runId, () -> MAIN.post(() -> launcher.finishPreparation(
                        true, "cancelled during preparation")));
        MAIN.post(launcher::prepare);
        return true;
    }

    private boolean preparing() {
        return sActive == this && !mFinishingPreparation
                && DesktopSelfTestRunState.isStarting(mRunId)
                && !DesktopSelfTestRunState.snapshot().cancellationRequested;
    }

    private void prepare() {
        if (!preparing()) {
            return;
        }
        final String issue = DesktopSelfTestController.unavailableReason(mContext);
        if (issue != null) {
            finishPreparation(false, issue);
            return;
        }
        if (DesktopRuntimeBridge.getActiveDesktopDisplayId() != Display.INVALID_DISPLAY) {
            // A rejected launch must not close a user's existing session.
            completePreparation(false, "close the active desktop first");
            return;
        }
        DesktopSelfTestHostObserver.begin(mRunId);
        if (mTarget == DesktopSelfTestTarget.SIMULATED) {
            run();
        } else if (mTarget == DesktopSelfTestTarget.PHONE) {
            // Use the production start queue: service readiness and HOME
            // acquisition must complete off the diagnostics UI thread.
            DesktopOperations.showDesktop(
                    DesktopDisplayTarget.phone(), DesktopSessionPolicy.ISOLATED_SELF_TEST);
            waitForDesktop(null);
        } else {
            probeExternal();
        }
    }

    private void probeExternal() {
        new Thread(() -> {
            final int wired = ExternalDisplayController.findExternalDisplayId();
            final int wireless = ExternalDisplayController.findWirelessDisplayId();
            MAIN.post(() -> {
                if (!preparing()) {
                    return;
                }
                if (mDisplayKind != DesktopDisplayTarget.Kind.WIRELESS && wired > 0) {
                    DesktopOperations.showWiredDesktop(DesktopSessionPolicy.ISOLATED_SELF_TEST);
                    waitForDesktop(DesktopDisplayTarget.Kind.WIRED);
                } else if (mDisplayKind == DesktopDisplayTarget.Kind.WIRED) {
                    finishPreparation(false, "connected wired display is unavailable");
                } else if (wireless > 0) {
                    showWireless(wireless);
                } else {
                    awaitWireless();
                }
            });
        }, "MagicDeskSelfTestDisplayProbe").start();
    }

    private void awaitWireless() {
        final Activity activity = mActivity.get();
        mDisplayManager = mContext.getSystemService(DisplayManager.class);
        if (activity == null || activity.isFinishing() || activity.isDestroyed()
                || mDisplayManager == null) {
            finishPreparation(false, "wireless connection UI is unavailable");
            return;
        }
        mWirelessListener = new DisplayManager.DisplayListener() {
            @Override public void onDisplayAdded(final int id) { probeWireless(); }
            @Override public void onDisplayChanged(final int id) { probeWireless(); }
            @Override public void onDisplayRemoved(final int id) { }
        };
        mDisplayManager.registerDisplayListener(mWirelessListener, MAIN);
        DesktopSelfTestRunState.stage(
                mRunId, "PREPARE", "Waiting for a wireless display");
        if (!PlatformDrivers.current().projection().openWirelessConnectionUi(activity)) {
            finishPreparation(false, "external display is unavailable");
        }
    }

    private void probeWireless() {
        if (!preparing() || mWirelessListener == null || mWirelessProbePending) {
            return;
        }
        mWirelessProbePending = true;
        new Thread(() -> {
            final int displayId = ExternalDisplayController.findWirelessDisplayId();
            MAIN.post(() -> {
                mWirelessProbePending = false;
                if (preparing() && mWirelessListener != null && displayId > 0) {
                    showWireless(displayId);
                }
            });
        }, "MagicDeskSelfTestWirelessProbe").start();
    }

    private void showWireless(final int displayId) {
        stopWirelessObservation();
        DesktopOperations.showDesktop(DesktopDisplayTarget.wireless(displayId),
                DesktopSessionPolicy.ISOLATED_SELF_TEST);
        waitForDesktop(DesktopDisplayTarget.Kind.WIRELESS);
    }

    private void waitForDesktop(final DesktopDisplayTarget.Kind kind) {
        new Thread(() -> {
            final long deadline = SystemClock.uptimeMillis()
                    + ExternalDisplayController.START_TIMEOUT_MS * 2L;
            boolean ready = false;
            do {
                if (!DesktopSelfTestRunState.isStarting(mRunId)
                        || DesktopSelfTestRunState.snapshot().cancellationRequested) {
                    return;
                }
                final int id = DesktopRuntimeBridge.getActiveDesktopDisplayId();
                final DesktopDisplayTarget display = DesktopRuntimeBridge.getDesktopTarget(id);
                if (mTarget.matchesDisplay(id, display)
                        && (kind == null || display.kind == kind)) {
                    ready = true;
                    break;
                }
                BoundedStateAwaiter.pause(BoundedStateAwaiter.Reason.DISPLAY_STATE,
                        ExternalDisplayController.STATE_POLL_MS);
            } while (SystemClock.uptimeMillis() < deadline);
            final boolean prepared = ready;
            MAIN.post(() -> {
                if (preparing()) {
                    if (prepared) {
                        run();
                    } else {
                        finishPreparation(false, "desktop preparation timed out");
                    }
                }
            });
        }, "MagicDeskSelfTestDesktopWait").start();
    }

    private void run() {
        if (!preparing()) {
            return;
        }
        stopWirelessObservation();
        DesktopSelfTestRunState.clearPreparationCancellationHandler(mRunId);
        new Thread(() -> {
            try {
                DesktopSelfTestController.run(mContext, mTarget, mResultTaskId, mPolicy, mRunId);
            } finally {
                MAIN.post(() -> {
                    if (sActive == this) {
                        sActive = null;
                    }
                    // Cleanup has verified the real HOME/Control destination.
                    // Only now may the harness bring its report back.
                    if (mTarget != DesktopSelfTestTarget.PHONE) {
                        DiagnosticsActivity.showResults(mContext, mRunId);
                    }
                });
            }
        }, "MagicDeskDesktopSelfTest").start();
    }

    private void finishPreparation(final boolean cancelled, final String detail) {
        if (sActive != this || mFinishingPreparation
                || !DesktopSelfTestRunState.isStarting(mRunId)) {
            return;
        }
        mFinishingPreparation = true;
        stopWirelessObservation();
        final int id = DesktopRuntimeBridge.getActiveDesktopDisplayId();
        final DesktopDisplayTarget display = DesktopRuntimeBridge.getDesktopTarget(id);
        if (mTarget.matchesDisplay(id, display)
                && DesktopRuntimeBridge.getSessionSnapshot().policy()
                        == DesktopSessionPolicy.ISOLATED_SELF_TEST) {
            DesktopOperations.closeDesktop(display, DesktopCloseMode.HOME,
                    ignored -> MAIN.post(() -> completePreparation(cancelled, detail)));
        } else {
            completePreparation(cancelled, detail);
        }
    }

    private void completePreparation(final boolean cancelled, final String detail) {
        DesktopSelfTestRunState.clearPreparationCancellationHandler(mRunId);
        stopWirelessObservation();
        DesktopSelfTestHostObserver.cancel();
        if (sActive == this) {
            sActive = null;
        }
        DesktopSelfTestRunState.complete(mRunId, cancelled, cancelled,
                System.currentTimeMillis(), detail,
                DesktopSelfTestResult.lastModifiedMillis(mContext));
    }

    private void stopWirelessObservation() {
        if (mDisplayManager != null && mWirelessListener != null) {
            mDisplayManager.unregisterDisplayListener(mWirelessListener);
        }
        mWirelessListener = null;
        mDisplayManager = null;
    }
}
