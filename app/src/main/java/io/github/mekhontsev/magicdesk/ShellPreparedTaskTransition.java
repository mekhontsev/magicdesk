package io.github.mekhontsev.magicdesk;

import android.annotation.SuppressLint;
import android.graphics.Rect;

/**
 * Owns task visibility boundaries used while shell changes window hierarchy.
 *
 * <p>A prepared task is hidden synchronously and is revealed only by the
 * transition that establishes its final mode, parent, and bounds. Callers own
 * the higher-level lifecycle and must roll a hidden task back on failure.
 */
@SuppressLint({"BlockedPrivateApi", "PrivateApi"})
final class ShellPreparedTaskTransition {
    private static final int WINDOWING_MODE_FULLSCREEN = 1;
    private static final int WINDOWING_MODE_FREEFORM = 5;

    private enum FreeformApplication {
        TRANSITION,
        OPEN_TRANSITION,
        HIDE_SYNC,
        SHOW_TRANSITION,
        DETACH_AND_SHOW_TRANSITION
    }

    private enum FullscreenApplication {
        HIDE_SYNC,
        SHOW_SYNC,
        SHOW_TRANSITION,
        DETACH_HIDE_SYNC,
        DETACH_SYNC
    }

    private ShellPreparedTaskTransition() {
    }

    static void applyFreeform(
            final Object service,
            final int displayId,
            final int taskId,
            final Rect bounds) throws ReflectiveOperationException {
        applyFreeform(
                service,
                displayId,
                taskId,
                bounds,
                FreeformApplication.TRANSITION);
    }

    static void revealFreeform(
            final Object service,
            final int displayId,
            final int taskId,
            final Rect bounds) throws ReflectiveOperationException {
        applyFreeform(
                service,
                displayId,
                taskId,
                bounds,
                FreeformApplication.OPEN_TRANSITION);
    }

    static void prepareFreeform(
            final Object service,
            final int displayId,
            final int taskId,
            final Rect bounds) throws ReflectiveOperationException {
        applyFreeform(
                service,
                displayId,
                taskId,
                bounds,
                FreeformApplication.HIDE_SYNC);
    }

    static void showPreparedFreeform(
            final Object service,
            final int displayId,
            final int taskId,
            final Rect bounds) throws ReflectiveOperationException {
        applyFreeform(
                service,
                displayId,
                taskId,
                bounds,
                FreeformApplication.SHOW_TRANSITION);
    }

    static void detachAndShowFreeform(
            final Object service,
            final int displayId,
            final int taskId,
            final Rect bounds) throws ReflectiveOperationException {
        detachAndShowFreeform(service, displayId, taskId, bounds, null);
    }

    static void detachAndShowFreeform(
            final Object service,
            final int displayId,
            final int taskId,
            final Rect bounds,
            final Object targetParentToken)
            throws ReflectiveOperationException {
        applyFreeform(
                service,
                displayId,
                taskId,
                bounds,
                FreeformApplication.DETACH_AND_SHOW_TRANSITION,
                targetParentToken);
    }

    static void prepareFullscreen(
            final Object service,
            final int displayId,
            final int taskId) throws ReflectiveOperationException {
        applyPreparedFullscreen(
                service,
                displayId,
                taskId,
                FullscreenApplication.HIDE_SYNC);
    }

    static void hideCurrentTask(
            final Object service,
            final int displayId,
            final int taskId) throws ReflectiveOperationException {
        final Object taskToken = HiddenTaskApi.requireTaskToken(
                service, displayId, taskId);
        final Class<?> tokenClass =
                Class.forName("android.window.WindowContainerToken");
        final Class<?> transactionClass =
                Class.forName("android.window.WindowContainerTransaction");
        final Object transaction =
                transactionClass.getConstructor().newInstance();
        // Preserve the current mode so the reveal transition retains a real
        // freeform/fullscreen boundary for WMShell to rebuild decorations.
        transactionClass.getMethod(
                "setHidden", tokenClass, Boolean.TYPE)
                .invoke(transaction, taskToken, Boolean.TRUE);
        ShellWindowTransitionExecutor.applySynchronized(
                service, transactionClass, transaction);
    }

    static void prepareDetachedFullscreen(
            final Object service,
            final int displayId,
            final int taskId) throws ReflectiveOperationException {
        prepareDetachedFullscreen(service, displayId, taskId, null);
    }

    static void prepareDetachedFullscreen(
            final Object service,
            final int displayId,
            final int taskId,
            final Object targetParentToken)
            throws ReflectiveOperationException {
        applyPreparedFullscreen(
                service,
                displayId,
                taskId,
                FullscreenApplication.DETACH_HIDE_SYNC,
                targetParentToken);
    }

    static void showPreparedFullscreen(
            final Object service,
            final int displayId,
            final int taskId) throws ReflectiveOperationException {
        applyPreparedFullscreen(
                service,
                displayId,
                taskId,
                FullscreenApplication.SHOW_TRANSITION);
    }

    static void showMovedFullscreen(
            final Object service,
            final int displayId,
            final int taskId) throws ReflectiveOperationException {
        // The display move already committed the hidden task's hierarchy.
        // Reveal that state synchronously before a normal focus transition;
        // an independent asynchronous reveal can be dropped while WMShell is
        // still finishing the cross-display transition.
        applyPreparedFullscreen(
                service,
                displayId,
                taskId,
                FullscreenApplication.SHOW_SYNC);
        TaskWindowingCommand.focusTasks(
                service, displayId, new int[]{taskId});
    }

    static void detachFullscreenParent(
            final Object service,
            final int displayId,
            final int taskId) throws ReflectiveOperationException {
        detachFullscreenParent(service, displayId, taskId, null);
    }

    static void detachFullscreenParent(
            final Object service,
            final int displayId,
            final int taskId,
            final Object targetParentToken)
            throws ReflectiveOperationException {
        applyPreparedFullscreen(
                service,
                displayId,
                taskId,
                FullscreenApplication.DETACH_SYNC,
                targetParentToken);
    }

    static void restorePreparedTask(
            final Object service,
            final int displayId,
            final int taskId,
            final int windowingMode,
            final Rect bounds) throws ReflectiveOperationException {
        final Object taskToken = HiddenTaskApi.requireTaskToken(
                service, displayId, taskId);
        final Class<?> tokenClass =
                Class.forName("android.window.WindowContainerToken");
        final Class<?> transactionClass =
                Class.forName("android.window.WindowContainerTransaction");
        final Object transaction =
                transactionClass.getConstructor().newInstance();
        transactionClass.getMethod(
                "setWindowingMode", tokenClass, Integer.TYPE)
                .invoke(
                        transaction,
                        taskToken,
                        Integer.valueOf(windowingMode));
        transactionClass.getMethod("setBounds", tokenClass, Rect.class)
                .invoke(transaction, taskToken, new Rect(bounds));
        transactionClass.getMethod(
                "setHidden", tokenClass, Boolean.TYPE)
                .invoke(transaction, taskToken, Boolean.FALSE);
        transactionClass.getMethod(
                "reorder", tokenClass, Boolean.TYPE, Boolean.TYPE)
                .invoke(
                        transaction,
                        taskToken,
                        Boolean.TRUE,
                        Boolean.TRUE);
        TaskCaptionInsetsCommand.addCaptionInsetOperation(
                transactionClass,
                transaction,
                tokenClass,
                taskToken,
                windowingMode != WINDOWING_MODE_FREEFORM);
        ShellWindowTransitionExecutor.applySynchronized(
                service, transactionClass, transaction);
    }

    private static void applyFreeform(
            final Object service,
            final int displayId,
            final int taskId,
            final Rect bounds,
            final FreeformApplication application)
            throws ReflectiveOperationException {
        applyFreeform(
                service,
                displayId,
                taskId,
                bounds,
                application,
                null);
    }

    private static void applyFreeform(
            final Object service,
            final int displayId,
            final int taskId,
            final Rect bounds,
            final FreeformApplication application,
            final Object targetParentToken)
            throws ReflectiveOperationException {
        final Object taskToken = HiddenTaskApi.requireTaskToken(
                service, displayId, taskId);
        final Class<?> tokenClass =
                Class.forName("android.window.WindowContainerToken");
        final Class<?> transactionClass =
                Class.forName("android.window.WindowContainerTransaction");
        final Object transaction =
                transactionClass.getConstructor().newInstance();
        transactionClass.getMethod("setWindowingMode", tokenClass, Integer.TYPE)
                .invoke(
                        transaction,
                        taskToken,
                        Integer.valueOf(WINDOWING_MODE_FREEFORM));
        transactionClass.getMethod("setBounds", tokenClass, Rect.class)
                .invoke(transaction, taskToken, bounds);
        transactionClass.getMethod(
                "setForceTranslucent", tokenClass, Boolean.TYPE)
                .invoke(transaction, taskToken, Boolean.FALSE);
        if (application == FreeformApplication.HIDE_SYNC
                || application == FreeformApplication.SHOW_TRANSITION
                || application
                        == FreeformApplication.DETACH_AND_SHOW_TRANSITION) {
            transactionClass.getMethod(
                    "setHidden", tokenClass, Boolean.TYPE)
                    .invoke(
                            transaction,
                            taskToken,
                            Boolean.valueOf(
                                    application
                                            == FreeformApplication.HIDE_SYNC));
        }
        if (application
                == FreeformApplication.DETACH_AND_SHOW_TRANSITION) {
            // The fullscreen parent belongs to the long-lived shell observer.
            // Reparent and reveal in the same WMShell transition so no
            // intermediate parent or fullscreen frame becomes visible.
            transactionClass.getMethod(
                    "reparent", tokenClass, tokenClass, Boolean.TYPE)
                    .invoke(transaction, new Object[]{
                            taskToken, targetParentToken, Boolean.TRUE});
        }
        transactionClass.getMethod(
                "reorder", tokenClass, Boolean.TYPE, Boolean.TYPE)
                .invoke(
                        transaction,
                        taskToken,
                        Boolean.TRUE,
                        Boolean.TRUE);
        TaskCaptionInsetsCommand.addCaptionInsetOperation(
                transactionClass, transaction, tokenClass, taskToken, false);
        if (application == FreeformApplication.HIDE_SYNC) {
            ShellWindowTransitionExecutor.applySynchronized(
                    service, transactionClass, transaction);
        } else {
            final ShellWindowTransitionExecutor.SystemTransition
                    transitionType = application
                    == FreeformApplication.OPEN_TRANSITION
                            ? ShellWindowTransitionExecutor.SystemTransition.OPEN
                            : ShellWindowTransitionExecutor.SystemTransition.CHANGE;
            ShellWindowTransitionExecutor.playSystemTransition(
                    displayId,
                    transitionType,
                    transactionClass,
                    transaction,
                    "freeform-" + application.name());
        }
    }

    private static void applyPreparedFullscreen(
            final Object service,
            final int displayId,
            final int taskId,
            final FullscreenApplication application)
            throws ReflectiveOperationException {
        applyPreparedFullscreen(
                service, displayId, taskId, application, null);
    }

    private static void applyPreparedFullscreen(
            final Object service,
            final int displayId,
            final int taskId,
            final FullscreenApplication application,
            final Object targetParentToken)
            throws ReflectiveOperationException {
        final boolean hidden = application == FullscreenApplication.HIDE_SYNC
                || application == FullscreenApplication.DETACH_HIDE_SYNC;
        final boolean detachFromParent =
                application == FullscreenApplication.DETACH_HIDE_SYNC
                        || application == FullscreenApplication.DETACH_SYNC;
        final Object taskToken = HiddenTaskApi.requireTaskToken(
                service, displayId, taskId);
        final Class<?> tokenClass =
                Class.forName("android.window.WindowContainerToken");
        final Class<?> transactionClass =
                Class.forName("android.window.WindowContainerTransaction");
        final Object transaction =
                transactionClass.getConstructor().newInstance();
        transactionClass.getMethod(
                "setWindowingMode", tokenClass, Integer.TYPE)
                .invoke(transaction, taskToken,
                        Integer.valueOf(WINDOWING_MODE_FULLSCREEN));
        transactionClass.getMethod("setBounds", tokenClass, Rect.class)
                .invoke(transaction, taskToken, new Rect());
        if (!detachFromParent) {
            // Cross-display fullscreen moves normalize task overrides. An app
            // fullscreen restore must preserve the existing client density and
            // translucency while only rebuilding its window hierarchy.
            transactionClass.getMethod(
                    "setDensityDpi", tokenClass, Integer.TYPE)
                    .invoke(transaction, taskToken, Integer.valueOf(0));
            transactionClass.getMethod(
                    "setForceTranslucent", tokenClass, Boolean.TYPE)
                    .invoke(transaction, taskToken, Boolean.FALSE);
        }
        transactionClass.getMethod(
                "setHidden", tokenClass, Boolean.TYPE)
                .invoke(transaction, taskToken, Boolean.valueOf(hidden));
        if (detachFromParent) {
            transactionClass.getMethod(
                    "reparent", tokenClass, tokenClass, Boolean.TYPE)
                    .invoke(transaction, new Object[]{
                            taskToken, targetParentToken, Boolean.TRUE});
        } else {
            transactionClass.getMethod(
                    "reorder", tokenClass, Boolean.TYPE, Boolean.TYPE)
                    .invoke(transaction, taskToken, Boolean.TRUE, Boolean.TRUE);
        }
        TaskCaptionInsetsCommand.addCaptionInsetOperation(
                transactionClass,
                transaction,
                tokenClass,
                taskToken,
                true);
        if (application != FullscreenApplication.SHOW_TRANSITION) {
            ShellWindowTransitionExecutor.applySynchronized(
                    service, transactionClass, transaction);
        } else {
            ShellWindowTransitionExecutor.playSystemTransition(
                    displayId,
                    ShellWindowTransitionExecutor.SystemTransition.TO_FRONT,
                    transactionClass,
                    transaction,
                    "show-prepared-fullscreen");
        }
    }
}
