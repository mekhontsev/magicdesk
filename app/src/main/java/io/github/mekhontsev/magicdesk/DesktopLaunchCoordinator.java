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

    boolean launch(final DesktopLaunchRequest source) {
        return launch(source, null);
    }

    boolean launch(
            final DesktopLaunchRequest source,
            final DesktopActivityLaunchResult.Completion completion) {
        if (source == null) {
            complete(completion, DesktopActivityLaunchResult.failed(
                    "desktop launch request is required"));
            return false;
        }
        final DesktopLaunchRequest request;
        final RecentLaunchScope recentScope;
        try {
            recentScope = RecentLaunchScope.of(mContext.destination());
            if (source.application != null) {
                source.application.requireProfile(AppProfile.current(mContext.context()));
            }
            if (GraphicalApplicationLaunch.reuse(mContext, source, completion)) return true;
            request = GraphicalApplicationLaunch.prepare(mContext, source.prepareExec());
        } catch (RuntimeException error) {
            if (completion == null) mContext.onFailure(source, error);
            complete(completion, DesktopActivityLaunchResult.failed(error));
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
                if (completion == null) mContext.onUnavailable(request);
                complete(completion, DesktopActivityLaunchResult.failed(
                        "desktop launch backend is unavailable"));
                return true;
            }
            final DesktopExecRunner.StartResult availability =
                    DesktopExecRunner.prepareBackend(
                            mContext.context(), request.exec.backend);
            if (availability == DesktopExecRunner.StartResult.UNAVAILABLE) {
                if (completion == null) mContext.onUnavailable(request);
                complete(completion, DesktopActivityLaunchResult.failed(
                        "desktop launch backend is unavailable"));
                return true;
            }
            if (availability
                    == DesktopExecRunner.StartResult.PERMISSION_REQUESTED) {
                complete(completion, DesktopActivityLaunchResult.failed(
                        "desktop launch requires user permission"));
                return true;
            }
        }
        final DesktopLaunchRequest prepared = request;
        final String sessionId = prepared.exec == null
                ? "" : DesktopExecSessionTracker.begin(prepared);
        final Runnable execute = prepared.exec == null
                ? null : () -> mContext.onMain(
                        () -> execute(prepared, sessionId, recentScope));
        if (prepared.androidLaunch != null
                || prepared.androidShortcut != null) {
            try {
                if (!mContext.launchAndroid(
                        prepared, execute, result -> {
                            if (result.succeeded() && source.exec == null)
                                RecentApplications.record(mContext.context(), source, recentScope);
                            completeActivity(prepared, completion, result);
                        })) {
                    DesktopExecSessionTracker.failed(sessionId);
                    if (completion == null) mContext.onUnavailable(prepared);
                    complete(completion, DesktopActivityLaunchResult.failed(
                            "Android Activity is unavailable"));
                }
            } catch (RuntimeException error) {
                DesktopExecSessionTracker.failed(sessionId);
                if (completion == null) mContext.onFailure(prepared, error);
                complete(completion, DesktopActivityLaunchResult.failed(error));
            }
            return true;
        }
        if (execute != null) {
            if (completion != null) {
                complete(completion, DesktopActivityLaunchResult.failed(
                        "launch request has no Android Activity"));
            }
            execute.run();
            return true;
        }
        complete(completion, DesktopActivityLaunchResult.failed(
                "launch request has no executable target"));
        return false;
    }

    private void completeActivity(
            final DesktopLaunchRequest request,
            final DesktopActivityLaunchResult.Completion completion,
            final DesktopActivityLaunchResult result) {
        if (completion == null && !result.succeeded()) {
            mContext.onMain(() -> mContext.onFailure(request, new java.io.IOException(result.error)));
        }
        complete(completion, result);
    }

    private static void complete(
            final DesktopActivityLaunchResult.Completion completion,
            final DesktopActivityLaunchResult result) {
        if (completion != null) {
            completion.onComplete(result);
        }
    }

    private void execute(
            final DesktopLaunchRequest request,
            final String sessionId,
            final RecentLaunchScope recentScope) {
        if (request.exec == null || mContext.isUnavailable()) {
            DesktopExecSessionTracker.failed(sessionId);
            return;
        }
        try {
            if (request.exec.terminal) {
                mContext.launchConsole(request);
                DesktopExecSessionTracker.delegated(sessionId);
                mContext.onStarted(request);
                RecentApplications.record(mContext.context(), request, recentScope);
                return;
            }
            final WeakReference<DesktopLaunchContext> context =
                    new WeakReference<>(mContext);
            final DesktopExecRunner.StartResult result =
                    DesktopExecRunner.runBackground(
                            mContext.context(),
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
            handleStartResult(request, sessionId, result, recentScope);
        } catch (RuntimeException error) {
            DesktopExecSessionTracker.failed(sessionId);
            mContext.onFailure(request, error);
        }
    }

    private void handleStartResult(
            final DesktopLaunchRequest request,
            final String sessionId,
            final DesktopExecRunner.StartResult result,
            final RecentLaunchScope recentScope) {
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
            RecentApplications.record(mContext.context(), request, recentScope);
        }
    }
}
