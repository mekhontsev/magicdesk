package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/** Launch context for an ordinary destination, independent of the displaying Activity. */
final class StandaloneDesktopLaunchContext implements DesktopLaunchContext {
    private final Context mContext;
    private final int mDisplayId;
    private final String mUniqueId;
    private final android.os.CancellationSignal mCancellation;

    StandaloneDesktopLaunchContext(Context activity, int displayId, String uniqueId) {
        this(activity, displayId, uniqueId, null);
    }

    StandaloneDesktopLaunchContext(Context activity, int displayId, String uniqueId, android.os.CancellationSignal cancellation) {
        mContext = activity;
        mCancellation = cancellation;
        mDisplayId = displayId;
        mUniqueId = uniqueId;
    }

    @Override
    public Context context() {
        return mContext;
    }

    @Override public ToolLaunchTarget destination() {
        return ToolLaunchTarget.resolve("display", mDisplayId, DesktopRuntimeBridge.workspaceDisplayIds());
    }

    @Override public String destinationUniqueId() { return mUniqueId; }

    private int displayId() {
        return mDisplayId;
    }

    @Override
    public void hideTransientUi() {
    }

    @Override
    public boolean launchAndroid(
            final DesktopLaunchRequest request,
            final Runnable onPrepared,
            final DesktopActivityLaunchResult.Completion completion) {
        if (request.androidLaunch == null && request.androidShortcut == null) { return false; }
        OrdinaryActivityLaunch.requirePresentation(request.presentation);
        TaskCommandQueue.execute(() -> {
            try {
                if (isUnavailable()) { return; }
                ToolLaunchTarget.resolve("display", mDisplayId, DesktopRuntimeBridge.workspaceDisplayIds());
                InteractiveActivityLaunch.requireDestination(mContext, mDisplayId, mUniqueId);
                if (request.androidShortcut != null) {
                    AndroidIntegrationGateway.requireShortcutPresentation(request.presentation);
                    final AndroidShortcutSpec shortcut = request.androidShortcut;
                    AppProfile.requireCurrent(mContext, shortcut.application);
                    OrdinaryActivityLaunch.send(ShellAccess.getShortcutLaunchIntent(
                            shortcut.publisher.packageName, shortcut.shortcutId), mDisplayId, request.presentation);
                } else {
                    final Intent source = request.androidLaunch.resolve(mContext.getPackageManager());
                    if (source == null) { throw new IllegalStateException("Activity is unavailable"); }
                    final Intent intent = request.presentation.instancePolicy.applyTo(mContext.getPackageManager(), source);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    InteractiveActivityLaunch.launch(mContext, intent, request.androidLaunch.delivery,
                            mDisplayId, request.presentation);
                }
                onMain(() -> launched(onPrepared, completion));
            } catch (java.io.IOException | RuntimeException error) {
                onMain(() -> failed(request, error, completion));
            }
        });
        return true;
    }

    private void launched(Runnable onPrepared, DesktopActivityLaunchResult.Completion completion) {
        if (isUnavailable()) { return; }
        if (onPrepared != null) { onPrepared.run(); }
        if (completion != null) {
            completion.onComplete(DesktopActivityLaunchResult.unmanagedAccepted(displayId()));
        }
    }

    private void failed(DesktopLaunchRequest request, Throwable error,
            DesktopActivityLaunchResult.Completion completion) {
        if (completion != null) { completion.onComplete(DesktopActivityLaunchResult.failed(error)); }
        else onFailure(request, error);
    }

    @Override
    public boolean isUnavailable() {
        return mCancellation != null && mCancellation.isCanceled() || mContext instanceof Activity activity
                && (activity.isFinishing() || activity.isDestroyed());
    }

    @Override public android.os.CancellationSignal cancellation() { return mCancellation; }

    @Override
    public void onStarted(final DesktopLaunchRequest request) {
    }

    @Override
    public void onCompleted(final DesktopLaunchRequest request) {
    }

    @Override
    public void onUnavailable(final DesktopLaunchRequest request) {
        show(mContext.getString(
                R.string.status_desktop_launch_unavailable,
                request.name));
    }

    @Override
    public void onFailure(
            final DesktopLaunchRequest request,
            final Throwable error) {
        show(mContext.getString(
                R.string.status_desktop_exec_failed,
                request.name));
    }

    private void show(final String message) {
        if (!isUnavailable()) {
            Toast.makeText(
                    mContext, message, Toast.LENGTH_LONG).show();
        }
    }
}
