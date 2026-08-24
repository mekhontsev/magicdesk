package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;

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
        if (request.androidLaunch.kind == AndroidLaunchSpec.Kind.DEFAULT) {
            final AppItem app = mActivity.findOrLoadApp(
                    mActivity.getLauncherApps(),
                    request.androidLaunch.target);
            if (app == null) {
                return false;
            }
            mActivity.launchForMode(app, request.launchMode, onPrepared);
            return true;
        }
        if (onPrepared != null) {
            throw new IllegalArgumentException(
                    "Intent and Exec cannot share one launch request");
        }
        final Intent intent = request.androidLaunch.resolve(
                mActivity.getPackageManager());
        if (intent == null || intent.getComponent() == null) {
            return false;
        }
        final ComponentName component = intent.getComponent();
        final AppLaunchTarget target = AppLaunchTarget.explicit(
                component.getPackageName(),
                component.getClassName(),
                intent.getAction());
        final AppItem app = mActivity.findOrLoadApp(
                mActivity.getLauncherApps(), target);
        if (app == null) {
            return false;
        }
        mActivity.launchResolvedDesktopShortcut(
                app,
                new DesktopApplicationShortcut(
                        request.name,
                        request.icon,
                        "",
                        target,
                        request.androidLaunch.intentUri,
                        request.launchMode,
                        false,
                        DesktopExecBackend.SHELL,
                        false));
        return true;
    }

    @Override
    public void launchConsole(
            final DesktopLaunchRequest request) {
        mActivity.launchInternalWindow(
                CommandConsoleActivity.createPreparedCommandIntent(
                        mActivity,
                        request.exec.command,
                        request.exec.workingDirectory,
                        request.exec.backend),
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
