package io.github.mekhontsev.magicdesk;

/** Executes immutable launch requests through one host-independent pipeline. */
final class DesktopLaunchCoordinator {
    private final DesktopLaunchContext mContext;

    DesktopLaunchCoordinator(final DesktopLaunchContext context) {
        if (context == null) {
            throw new IllegalArgumentException("missing desktop launch context");
        }
        mContext = context;
    }

    boolean launchShortcut(
            final DesktopApplicationShortcut shortcut) {
        if (shortcut == null) {
            return false;
        }
        try {
            return launch(DesktopLaunchRequest.from(shortcut));
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    boolean launchIntegratedDefault(final AppItem app) {
        final DesktopLaunchRequest request =
                DesktopLaunchIntegrationRegistry.defaultRequest(
                        mContext.activity(), app);
        return request != null && launch(request);
    }

    boolean launch(final DesktopLaunchRequest source) {
        if (source == null) {
            return false;
        }
        final DesktopLaunchRequest request =
                DesktopLaunchIntegrationRegistry.prepare(
                        mContext.activity(), source);
        mContext.hideTransientUi();
        if (request.exec != null) {
            final DesktopExecRunner.StartResult availability =
                    DesktopExecRunner.prepareBackend(
                            mContext.activity(), request.exec.backend);
            if (availability == DesktopExecRunner.StartResult.UNAVAILABLE) {
                mContext.onUnavailable(request);
                return true;
            }
            if (availability
                    == DesktopExecRunner.StartResult.PERMISSION_REQUESTED) {
                return true;
            }
        }
        final DesktopLaunchRequest prepared = addTerminalHost(request);
        final Runnable execute = prepared.exec == null
                ? null : () -> execute(prepared);
        if (prepared.androidLaunch != null) {
            try {
                if (!mContext.launchAndroid(prepared, execute)) {
                    mContext.onUnavailable(prepared);
                }
            } catch (RuntimeException error) {
                mContext.onFailure(prepared, error);
            }
            return true;
        }
        if (execute != null) {
            execute.run();
            return true;
        }
        return false;
    }

    private DesktopLaunchRequest addTerminalHost(
            final DesktopLaunchRequest request) {
        if (request.exec == null
                || !request.exec.terminal
                || request.androidLaunch != null) {
            return request;
        }
        final String packageName = request.exec.backend.capabilities()
                .terminalPackageName;
        if (packageName.isEmpty()) {
            return request;
        }
        return request.withAndroidLaunch(
                AndroidLaunchSpec.defaultLaunch(
                        AppLaunchTarget.packageDefault(packageName)));
    }

    private void execute(final DesktopLaunchRequest request) {
        if (request.exec == null || mContext.isUnavailable()) {
            return;
        }
        try {
            if (request.exec.terminal) {
                if (request.exec.backend == DesktopExecBackend.SHELL) {
                    mContext.launchConsole(
                            request,
                            DesktopExecCommand.prepare(
                                    request.exec.command));
                    mContext.onStarted(request);
                    return;
                }
                final DesktopExecRunner.StartResult result =
                        DesktopExecRunner.runTermuxForeground(
                                mContext.activity(),
                                request.exec.command,
                                request.name);
                handleStartResult(request, result);
                return;
            }
            final DesktopExecRunner.StartResult result =
                    DesktopExecRunner.runBackground(
                            mContext.activity(),
                            request.exec.backend,
                            request.exec.command,
                            request.name,
                            (commandResult, error) -> {
                                if (mContext.isUnavailable()) {
                                    return;
                                }
                                if (error != null) {
                                    mContext.onFailure(request, error);
                                } else if (commandResult != null
                                        && commandResult.exitCode != 0) {
                                    mContext.onFailure(
                                            request,
                                            new IllegalStateException(
                                                    "command exited "
                                                            + commandResult
                                                                    .exitCode));
                                } else {
                                    mContext.onCompleted(request);
                                }
                            });
            handleStartResult(request, result);
        } catch (RuntimeException error) {
            mContext.onFailure(request, error);
        }
    }

    private void handleStartResult(
            final DesktopLaunchRequest request,
            final DesktopExecRunner.StartResult result) {
        if (result == DesktopExecRunner.StartResult.UNAVAILABLE) {
            mContext.onUnavailable(request);
        } else if (result == DesktopExecRunner.StartResult.STARTED) {
            mContext.onStarted(request);
        }
    }
}
