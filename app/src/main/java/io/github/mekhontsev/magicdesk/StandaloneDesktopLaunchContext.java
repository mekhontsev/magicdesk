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

    StandaloneDesktopLaunchContext(Context activity, int displayId, String uniqueId) {
        mContext = activity;
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
                DesktopDisplayCatalog.require(mDisplayId, mUniqueId);
                if (request.androidShortcut != null) {
                    AndroidIntegrationGateway.requireShortcutPresentation(request.presentation);
                    final AndroidShortcutSpec shortcut = request.androidShortcut;
                    AppProfile.requireCurrent(mContext, shortcut.application);
                    ShellAccess.sendActivityOnDisplay(ShellAccess.getShortcutLaunchIntent(
                            shortcut.publisher.packageName, shortcut.shortcutId), mDisplayId);
                } else {
                    final Intent source = request.androidLaunch.resolve(mContext.getPackageManager());
                    if (source == null) { throw new IllegalStateException("Activity is unavailable"); }
                    final Intent intent = request.presentation.instancePolicy.applyTo(source);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    OrdinaryActivityLaunch.launch(mContext, intent, request.androidLaunch.delivery, mDisplayId);
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
        onFailure(request, error);
        if (completion != null) { completion.onComplete(DesktopActivityLaunchResult.failed(error)); }
    }

    @Override
    public void launchConsole(
            final DesktopLaunchRequest request) {
        BuiltInWindowLauncher.launch(mContext,
                CommandConsoleActivity.createPreparedCommandIntent(
                        mContext,
                        request.exec.command,
                        request.exec.workingDirectory,
                        request.exec.backend),
                CommandConsoleActivity.launchTarget(),
                ToolLaunchTarget.resolve("display", mDisplayId, DesktopRuntimeBridge.workspaceDisplayIds()),
                mUniqueId, request.presentation.withInstancePolicy(DesktopTaskInstancePolicy.CREATE_NEW), error -> {
                    if (error != null) { onFailure(request, error); }
                });
    }

    @Override
    public boolean isUnavailable() {
        return mContext instanceof Activity activity
                && (activity.isFinishing() || activity.isDestroyed());
    }

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
