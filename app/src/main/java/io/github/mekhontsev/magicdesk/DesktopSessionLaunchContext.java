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
            final Runnable onPrepared,
            final DesktopActivityLaunchResult.Completion completion) {
        if (request.androidShortcut != null) {
            if (onPrepared != null) {
                throw new IllegalArgumentException(
                        "app shortcut and Exec cannot share one launch request");
            }
            final AppItem app = mActivity.findOrLoadApp(
                    mActivity.getLauncherApps(),
                    application(request, request.androidShortcut.publisher),
                    request.androidShortcut.publisher);
            if (app == null) {
                return false;
            }
            for (final AppShortcutAction shortcut
                    : new AppShortcutRepository(mActivity)
                            .loadAll(application(request, request.androidShortcut.publisher), request.androidShortcut.publisher)) {
                if (request.androidShortcut.shortcutId.equals(shortcut.id)) {
                    mActivity.launchShortcut(
                            app,
                            shortcut,
                            request.presentation.mode,
                            completion);
                    return true;
                }
            }
            return false;
        }
        if (request.androidLaunch == null) {
            return false;
        }
        if (request.androidLaunch.kind == AndroidLaunchSpec.Kind.DEFAULT) {
            final AppItem app = mActivity.findOrLoadApp(
                    mActivity.getLauncherApps(),
                    application(request, request.androidLaunch.target),
                    request.androidLaunch.target);
            if (app == null) {
                return false;
            }
            mActivity.launchForPresentation(
                    app,
                    request.presentation,
                    onPrepared,
                    completion);
            return true;
        }
        if (onPrepared != null) {
            throw new IllegalArgumentException(
                    "Intent and Exec cannot share one launch request");
        }
        if (request.androidLaunch.kind
                == AndroidLaunchSpec.Kind.PENDING_ACTIVITY) {
            final AppLaunchTarget target = request.androidLaunch.target;
            final AppItem app = mActivity.findOrLoadApp(
                    mActivity.getLauncherApps(), application(request, target), target);
            if (app == null || request.androidLaunch.pendingIntent() == null) {
                return false;
            }
            mActivity.launchResolvedPendingActivity(
                    app,
                    request.name,
                    request.androidLaunch.pendingIntent(),
                    target,
                    request.presentation,
                    completion);
            return true;
        }
        final Intent intent = request.androidLaunch.resolve(
                mActivity.getPackageManager());
        if (intent == null) {
            return false;
        }
        final ComponentName component = intent.getComponent();
        final AppLaunchTarget target = request.androidLaunch.target == null
                && component != null
                ? AppLaunchTarget.explicit(
                        component.getPackageName(),
                        component.getClassName(),
                        intent.getAction())
                : request.androidLaunch.target;
        if (target == null
                || (request.androidLaunch.delivery
                        == AndroidLaunchSpec.Delivery.SHELL_INTENT
                        && component == null)) {
            return false;
        }
        final AppItem app = mActivity.findOrLoadApp(
                mActivity.getLauncherApps(), application(request, target), target);
        if (app == null) {
            return false;
        }
        mActivity.launchResolvedAndroidIntent(
                app,
                request.name,
                intent,
                target,
                request.presentation,
                request.androidLaunch.delivery,
                completion);
        return true;
    }

    private AppIdentity application(final DesktopLaunchRequest request, final AppLaunchTarget target) {
        final AppIdentity application = request.application == null
                ? mActivity.appProfile().application(target.packageName) : request.application;
        application.requireProfile(mActivity.appProfile());
        if (request.androidShortcut != null && request.androidShortcut.application != null
                && !application.equals(request.androidShortcut.application)) {
            throw new IllegalArgumentException("shortcut profile mismatch");
        }
        if (!application.packageName.equals(target.packageName)) {
            throw new IllegalArgumentException("application launch target mismatch");
        }
        if (request.androidLaunch != null && request.androidLaunch.pendingIntent() != null
                && !mActivity.appProfile().owns(FrameworkUserApi.userId(
                        request.androidLaunch.pendingIntent().getCreatorUserHandle()))) {
            throw new IllegalArgumentException("pending activity belongs to an unavailable profile");
        }
        return application;
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
