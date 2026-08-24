package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;
import android.widget.Toast;

/** Launch host used by Files when no desktop session owns its display. */
final class StandaloneDesktopLaunchContext implements DesktopLaunchContext {
    private final Activity mActivity;

    StandaloneDesktopLaunchContext(final Activity activity) {
        mActivity = activity;
    }

    @Override
    public Activity activity() {
        return mActivity;
    }

    private int displayId() {
        return mActivity.getDisplay() == null
                ? 0 : mActivity.getDisplay().getDisplayId();
    }

    @Override
    public void hideTransientUi() {
    }

    @Override
    public boolean launchAndroid(
            final DesktopLaunchRequest request,
            final Runnable onPrepared) {
        if (request.androidLaunch == null) {
            return false;
        }
        final Intent intent = request.androidLaunch.resolve(
                mActivity.getPackageManager());
        if (intent == null) {
            return false;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mActivity.startActivity(intent, options().toBundle());
        if (onPrepared != null) {
            onPrepared.run();
        }
        return true;
    }

    @Override
    public void launchConsole(
            final DesktopLaunchRequest request) {
        mActivity.startActivity(
                CommandConsoleActivity.createPreparedCommandIntent(
                        mActivity,
                        request.exec.command,
                        request.exec.workingDirectory,
                        request.exec.backend),
                options().toBundle());
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

    private ActivityOptions options() {
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(displayId());
        return options;
    }

    private void show(final String message) {
        if (!isUnavailable()) {
            Toast.makeText(
                    mActivity, message, Toast.LENGTH_LONG).show();
        }
    }
}
