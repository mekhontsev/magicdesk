package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.content.Intent;
import android.widget.Toast;

/** Launch context for an ordinary destination, independent of the displaying Activity. */
final class StandaloneDesktopLaunchContext implements DesktopLaunchContext {
    private final Activity mActivity;
    private final int mDisplayId;
    private final String mUniqueId;

    StandaloneDesktopLaunchContext(Activity activity, int displayId, String uniqueId) {
        mActivity = activity;
        mDisplayId = displayId;
        mUniqueId = uniqueId;
    }

    @Override
    public Activity activity() {
        return mActivity;
    }

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
                    final AndroidShortcutSpec shortcut = request.androidShortcut;
                    AppProfile.requireCurrent(mActivity, shortcut.application);
                    ShellAccess.sendActivityOnDisplay(ShellAccess.getShortcutLaunchIntent(
                            shortcut.publisher.packageName, shortcut.shortcutId), mDisplayId);
                } else {
                    final Intent intent = request.androidLaunch.resolve(mActivity.getPackageManager());
                    if (intent == null) { throw new IllegalStateException("Activity is unavailable"); }
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    if (mDisplayId == 0 && mActivity.getDisplay() != null
                            && mActivity.getDisplay().getDisplayId() == 0) {
                        mActivity.runOnUiThread(() -> {
                            if (isUnavailable()) { return; }
                            try {
                                ToolLaunchTarget.resolve("display", mDisplayId,
                                        DesktopRuntimeBridge.workspaceDisplayIds());
                                final android.app.ActivityOptions options = android.app.ActivityOptions.makeBasic();
                                options.setLaunchDisplayId(mDisplayId);
                                mActivity.startActivity(intent, options.toBundle());
                                launched(onPrepared, completion);
                            } catch (RuntimeException error) { failed(request, error, completion); }
                        });
                        return;
                    }
                    OrdinaryActivityLaunch.launch(mActivity, intent, request.androidLaunch.delivery, mDisplayId);
                }
                mActivity.runOnUiThread(() -> launched(onPrepared, completion));
            } catch (java.io.IOException | RuntimeException error) {
                mActivity.runOnUiThread(() -> failed(request, error, completion));
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
        BuiltInWindowLauncher.launch(mActivity,
                CommandConsoleActivity.createPreparedCommandIntent(
                        mActivity,
                        request.exec.command,
                        request.exec.workingDirectory,
                        request.exec.backend),
                CommandConsoleActivity.launchTarget(),
                ToolLaunchTarget.resolve("display", mDisplayId, DesktopRuntimeBridge.workspaceDisplayIds()),
                mUniqueId, error -> {
                    if (error != null) { onFailure(request, error); }
                });
    }

    @Override
    public boolean isUnavailable() {
        return mActivity.isFinishing() || mActivity.isDestroyed();
    }

    @Override
    public void onStarted(final DesktopLaunchRequest request) {
    }

    @Override
    public void onCompleted(final DesktopLaunchRequest request) {
    }

    @Override
    public void onUnavailable(final DesktopLaunchRequest request) {
        show(mActivity.getString(
                R.string.status_desktop_launch_unavailable,
                request.name));
    }

    @Override
    public void onFailure(
            final DesktopLaunchRequest request,
            final Throwable error) {
        show(mActivity.getString(
                R.string.status_desktop_exec_failed,
                request.name));
    }

    private void show(final String message) {
        if (!isUnavailable()) {
            Toast.makeText(
                    mActivity, message, Toast.LENGTH_LONG).show();
        }
    }
}
