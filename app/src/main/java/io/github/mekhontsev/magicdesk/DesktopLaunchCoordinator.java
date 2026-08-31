package io.github.mekhontsev.magicdesk;

import java.lang.ref.WeakReference;

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
        return launchShortcut(
                shortcut, DesktopLaunchArguments.empty(), "");
    }

    boolean launchShortcut(
            final DesktopApplicationShortcut shortcut,
            final DesktopLaunchArguments arguments,
            final String desktopFilePath) {
        if (shortcut == null) {
            return false;
        }
        try {
            return launch(DesktopLaunchRequest.from(
                    shortcut, arguments, desktopFilePath));
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
        final DesktopLaunchRequest request;
        try {
            request = DesktopLaunchIntegrationRegistry.prepare(
                    mContext.activity(), source.prepareExec());
        } catch (IllegalArgumentException error) {
            mContext.onFailure(source, error);
            return true;
        }
        mContext.hideTransientUi();
        if (request.exec != null) {
            final DesktopExecCapabilities capabilities =
                    request.exec.backend.capabilities();
            if ((request.exec.terminal && !capabilities.terminal)
                    || (!request.exec.terminal && !capabilities.background)
                    || (!request.exec.workingDirectory.isEmpty()
                            && !capabilities.workingDirectory)) {
                mContext.onUnavailable(request);
                return true;
            }
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
        final DesktopLaunchRequest prepared = request;
        final String sessionId = prepared.exec == null
                ? "" : DesktopExecSessionTracker.begin(prepared);
        final Runnable execute = prepared.exec == null
                ? null : () -> mContext.activity().runOnUiThread(
                        () -> execute(prepared, sessionId));
        if (prepared.androidLaunch != null
                || prepared.androidShortcut != null) {
            try {
                if (!mContext.launchAndroid(prepared, execute)) {
                    DesktopExecSessionTracker.failed(sessionId);
                    mContext.onUnavailable(prepared);
                }
            } catch (RuntimeException error) {
                DesktopExecSessionTracker.failed(sessionId);
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

    private void execute(
            final DesktopLaunchRequest request,
            final String sessionId) {
        if (request.exec == null || mContext.isUnavailable()) {
            DesktopExecSessionTracker.failed(sessionId);
            return;
        }
        try {
            if (request.exec.terminal) {
                mContext.launchConsole(request);
                DesktopExecSessionTracker.delegated(sessionId);
                mContext.onStarted(request);
                return;
            }
            final WeakReference<DesktopLaunchContext> context =
                    new WeakReference<>(mContext);
            final DesktopExecRunner.StartResult result =
                    DesktopExecRunner.runBackground(
                            mContext.activity(),
                            request.exec.backend,
                            request.exec.command,
                            request.exec.workingDirectory,
                            request.name,
                            (commandResult, error) -> {
                                final DesktopLaunchContext active =
                                        context.get();
                                if (error != null) {
                                    DesktopExecSessionTracker.failed(sessionId);
                                    if (active != null
                                            && !active.isUnavailable()) {
                                        active.onFailure(request, error);
                                    }
                                } else if (commandResult != null
                                        && commandResult.exitCode != 0) {
                                    DesktopExecSessionTracker.failed(sessionId);
                                    if (active != null
                                            && !active.isUnavailable()) {
                                        active.onFailure(
                                                request,
                                                new IllegalStateException(
                                                        "command exited "
                                                                + commandResult
                                                                        .exitCode));
                                    }
                                } else {
                                    DesktopExecSessionTracker.finished(sessionId);
                                    if (active != null
                                            && !active.isUnavailable()) {
                                        active.onCompleted(request);
                                    }
                                }
                            });
            handleStartResult(request, sessionId, result);
        } catch (RuntimeException error) {
            DesktopExecSessionTracker.failed(sessionId);
            mContext.onFailure(request, error);
        }
    }

    private void handleStartResult(
            final DesktopLaunchRequest request,
            final String sessionId,
            final DesktopExecRunner.StartResult result) {
        if (result == DesktopExecRunner.StartResult.UNAVAILABLE) {
            DesktopExecSessionTracker.failed(sessionId);
            mContext.onUnavailable(request);
        } else if (result == DesktopExecRunner.StartResult.STARTED) {
            final DesktopExecCapabilities capabilities =
                    request.exec.backend.capabilities();
            if (!request.exec.terminal && capabilities.completionResult) {
                DesktopExecSessionTracker.running(sessionId);
            } else {
                DesktopExecSessionTracker.delegated(sessionId);
            }
            mContext.onStarted(request);
        }
    }
}
