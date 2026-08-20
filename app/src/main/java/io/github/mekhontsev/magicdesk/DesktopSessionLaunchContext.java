package io.github.mekhontsev.magicdesk;

import android.app.Activity;

/** Launch host backed by a live MagicDesk desktop session. */
final class DesktopSessionLaunchContext implements DesktopLaunchContext {
    private final DesktopShellActivity mActivity;

    DesktopSessionLaunchContext(final DesktopShellActivity activity) {
        mActivity = activity;
    }

    @Override
    public Activity activity() {
        return mActivity;
    }

    @Override
    public int displayId() {
        return mActivity.getCurrentDisplayId();
    }

    @Override
    public void hideTransientUi() {
        mActivity.hideAllPanels();
    }

    @Override
    public boolean launchAndroid(
            final DesktopLaunchRequest request,
            final Runnable onPrepared) {
        if (request.androidLaunch == null) {
            return false;
        }
        final AppItem app = mActivity.findOrLoadApp(
                mActivity.getLauncherApps(),
                request.androidLaunch.target);
        if (app == null) {
            return false;
        }
        if (request.androidLaunch.kind == AndroidLaunchSpec.Kind.DEFAULT) {
            mActivity.launchForMode(app, request.launchMode, onPrepared);
            return true;
        }
        if (onPrepared != null) {
            throw new IllegalArgumentException(
                    "Intent and Exec cannot share one launch request");
        }
        mActivity.launchResolvedDesktopShortcut(
                app,
                new DesktopApplicationShortcut(
                        request.name,
                        request.icon,
                        "",
                        request.androidLaunch.target,
                        request.androidLaunch.intentUri,
                        request.launchMode,
                        false,
                        DesktopExecBackend.SHELL,
                        false));
        return true;
    }

    @Override
    public void launchConsole(
            final DesktopLaunchRequest request,
            final String command) {
        mActivity.launchInternalWindow(
                CommandConsoleActivity.createCommandIntent(
                        mActivity, command),
                CommandConsoleActivity.launchTarget(),
                request.name);
    }

    @Override
    public boolean isUnavailable() {
        return mActivity.isActivityUnavailable();
    }

    @Override
    public void onStarted(final DesktopLaunchRequest request) {
        if (!isUnavailable()) {
            mActivity.setStatus(mActivity.getString(
                    R.string.status_desktop_exec_started,
                    request.name));
        }
    }

    @Override
    public void onCompleted(final DesktopLaunchRequest request) {
        if (!isUnavailable()) {
            mActivity.setStatus(mActivity.getString(
                    R.string.status_desktop_exec_complete,
                    request.name));
        }
    }

    @Override
    public void onUnavailable(final DesktopLaunchRequest request) {
        if (!isUnavailable()) {
            mActivity.setErrorStatus(
                    "DESKTOP-LAUNCH-001",
                    mActivity.getString(
                            R.string.status_desktop_launch_unavailable,
                            request.name));
        }
    }

    @Override
    public void onFailure(
            final DesktopLaunchRequest request,
            final Throwable error) {
        if (!isUnavailable()) {
            mActivity.setErrorStatus(
                    "DESKTOP-LAUNCH-002",
                    mActivity.getString(
                            R.string.status_desktop_exec_failed,
                            request.name),
                    request.exec == null
                            ? "android" : "backend="
                                    + request.exec.backend.wireName,
                    error);
        }
    }
}
