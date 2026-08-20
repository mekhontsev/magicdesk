package io.github.mekhontsev.magicdesk;

/** Coordinates Desktop Entry commands with desktop task preparation. */
final class DesktopExecController {
    private final DesktopShellActivity mActivity;

    DesktopExecController(final DesktopShellActivity activity) {
        mActivity = activity;
    }

    void openTermuxX11(final AppItem app) {
        openTermuxX11(
                app,
                MagicDeskSettings.load().termuxX11StartupCommand,
                DesktopLaunchMode.AUTO);
    }

    boolean open(
            final AppItem app,
            final DesktopApplicationShortcut shortcut) {
        if (shortcut == null || !shortcut.hasExecLaunch()) {
            return false;
        }
        if (TermuxX11Integration.handlesExecLaunch(app, shortcut)) {
            openTermuxX11(
                    app,
                    DesktopExecCommand.prepare(shortcut.exec),
                    shortcut.launchMode);
            return true;
        }
        mActivity.hideAllPanels();
        if (shortcut.terminal) {
            openTerminal(shortcut);
        } else {
            openBackground(shortcut);
        }
        return true;
    }

    private void openTermuxX11(
            final AppItem app,
            final String command,
            final DesktopLaunchMode launchMode) {
        mActivity.hideAllPanels();
        if (!TermuxX11Integration.handlesDefaultLaunch(mActivity, app)) {
            mActivity.setErrorStatus(
                    "TERMUX-X11-001",
                    mActivity.getString(R.string.status_termux_x11_unavailable));
            return;
        }
        try {
            if (!TermuxX11Integration.ensureRunCommandPermission(mActivity)) {
                return;
            }
            // The viewer uses the shared task path; only its server command is
            // delegated to the selected Exec backend after task preparation.
            mActivity.launchForMode(
                    app,
                    launchMode,
                    () -> TaskCommandQueue.execute(() ->
                            TermuxX11Integration.startOrReconnect(
                                    mActivity, command)));
        } catch (RuntimeException error) {
            mActivity.setErrorStatus(
                    "TERMUX-X11-002",
                    mActivity.getString(R.string.status_termux_x11_failed),
                    "display=" + mActivity.getCurrentDisplayId(),
                    error);
        }
    }

    private void openTerminal(
            final DesktopApplicationShortcut shortcut) {
        if (shortcut.execBackend == DesktopExecBackend.SHELL) {
            try {
                mActivity.launchInternalWindow(
                        CommandConsoleActivity.createCommandIntent(
                                mActivity, shortcut.exec),
                        CommandConsoleActivity.launchTarget(),
                        shortcut.name);
            } catch (RuntimeException error) {
                showFailure(shortcut, error);
            }
            return;
        }
        if (!TermuxIntegration.isInstalled(mActivity)) {
            showUnavailable(shortcut);
            return;
        }
        final AppItem termux = mActivity.findOrLoadApp(
                mActivity.getLauncherApps(), TermuxIntegration.PACKAGE_NAME);
        if (termux == null) {
            showUnavailable(shortcut);
            return;
        }
        if (!TermuxIntegration.ensureRunCommandPermission(mActivity)) {
            return;
        }
        final String command;
        try {
            command = DesktopExecCommand.prepare(shortcut.exec);
        } catch (IllegalArgumentException error) {
            showFailure(shortcut, error);
            return;
        }
        mActivity.launchForMode(
                termux,
                shortcut.launchMode,
                () -> TaskCommandQueue.execute(() -> {
                    try {
                        TermuxIntegration.runForegroundShellCommand(
                                mActivity, command, shortcut.name);
                    } catch (RuntimeException error) {
                        mActivity.runOnUiThread(() ->
                                showFailure(shortcut, error));
                    }
                }));
    }

    private void openBackground(
            final DesktopApplicationShortcut shortcut) {
        final DesktopExecRunner.StartResult result;
        try {
            result = DesktopExecRunner.runBackground(
                    mActivity,
                    shortcut.execBackend,
                    shortcut.exec,
                    shortcut.name,
                    (commandResult, error) -> {
                        if (mActivity.isActivityUnavailable()) {
                            return;
                        }
                        if (error != null) {
                            showFailure(shortcut, error);
                        } else if (commandResult != null
                                && commandResult.exitCode != 0) {
                            showFailure(
                                    shortcut,
                                    new IllegalStateException(
                                            "command exited "
                                                    + commandResult.exitCode));
                        } else {
                            mActivity.setStatus(mActivity.getString(
                                    R.string.status_desktop_exec_complete,
                                    shortcut.name));
                        }
                    });
        } catch (RuntimeException error) {
            showFailure(shortcut, error);
            return;
        }
        if (result == DesktopExecRunner.StartResult.UNAVAILABLE) {
            showUnavailable(shortcut);
        } else if (result == DesktopExecRunner.StartResult.STARTED) {
            mActivity.setStatus(mActivity.getString(
                    R.string.status_desktop_exec_started,
                    shortcut.name));
        }
    }

    private void showUnavailable(
            final DesktopApplicationShortcut shortcut) {
        if (!mActivity.isActivityUnavailable()) {
            mActivity.setErrorStatus(
                    "DESKTOP-EXEC-001",
                    mActivity.getString(
                            R.string.status_desktop_exec_unavailable,
                            shortcut.name));
        }
    }

    private void showFailure(
            final DesktopApplicationShortcut shortcut,
            final Throwable error) {
        if (!mActivity.isActivityUnavailable()) {
            mActivity.setErrorStatus(
                    "DESKTOP-EXEC-002",
                    mActivity.getString(
                            R.string.status_desktop_exec_failed,
                            shortcut.name),
                    "backend=" + shortcut.execBackend.wireName,
                    error);
        }
    }
}
