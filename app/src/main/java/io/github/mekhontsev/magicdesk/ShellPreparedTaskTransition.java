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
                DesktopTaskDensity.UNCHANGED);
    }

    static void applyFreeform(
            final Object service,
            final int displayId,
            final int taskId,
            final Rect bounds,
            final int densityDpi) throws ReflectiveOperationException {
        applyFreeform(
                service,
                displayId,
                taskId,
                bounds,
                FreeformApplication.TRANSITION,
                densityDpi);
    }

    static void revealFreeform(
            final Object service,
            final int displayId,
            final int taskId,
            final Rect bounds) throws ReflectiveOperationException {
        revealFreeform(
                service,
                displayId,
                taskId,
                bounds,
                DesktopTaskDensity.UNCHANGED);
    }

    static void revealFreeform(
            final Object service,
            final int displayId,
            final int taskId,
            final Rect bounds,
            final int densityDpi) throws ReflectiveOperationException {
        applyFreeform(
                service,
                displayId,
                taskId,
                bounds,
                FreeformApplication.OPEN_TRANSITION,
                densityDpi);
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
                FreeformApplication.HIDE_SYNC,
                DesktopTaskDensity.UNCHANGED);
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
                FreeformApplication.SHOW_TRANSITION,
                DesktopTaskDensity.UNCHANGED);
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
        detachAndShowFreeform(
                service,
                displayId,
                taskId,
                bounds,
                null,
                targetParentToken);
    }

    static void detachAndShowFreeform(
            final Object service,
            final int displayId,
            final int taskId,
            final Rect bounds,
            final Object sourceParentToken,
            final Object targetParentToken)
            throws ReflectiveOperationException {
        detachAndShowFreeform(
                service,
                displayId,
                taskId,
                bounds,
                sourceParentToken,
                targetParentToken,
                DesktopTaskDensity.UNCHANGED);
    }

    static void detachAndShowFreeform(
            final Object service,
            final int displayId,
            final int taskId,
            final Rect bounds,
            final Object sourceParentToken,
            final Object targetParentToken,
            final int densityDpi)
            throws ReflectiveOperationException {
        applyFreeform(
                service,
                displayId,
                taskId,
                bounds,
                FreeformApplication.DETACH_AND_SHOW_TRANSITION,
                densityDpi,
                sourceParentToken,
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
        final FrameworkWindowingApi windowing =
                FrameworkRuntime.current().windowing();
        final Class<?> transactionClass = windowing.transactionClass();
        final Object transaction = windowing.newTransaction();
        // Preserve the current mode so the reveal transition retains a real
        // freeform/fullscreen boundary for WMShell to rebuild decorations.
        windowing.setHidden(transaction, taskToken, true);
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
        final FrameworkWindowingApi windowing =
                FrameworkRuntime.current().windowing();
        final Class<?> transactionClass = windowing.transactionClass();
        final Object transaction = windowing.newTransaction();
        windowing.setWindowingMode(transaction, taskToken, windowingMode);
        windowing.setBounds(transaction, taskToken, new Rect(bounds));
        windowing.setHidden(transaction, taskToken, false);
        windowing.reorder(transaction, taskToken, true, true);
        TaskCaptionInsetsCommand.addCaptionInsetOperation(
                transaction,
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
                DesktopTaskDensity.UNCHANGED);
    }

    private static void applyFreeform(
            final Object service,
            final int displayId,
            final int taskId,
            final Rect bounds,
            final FreeformApplication application,
            final int densityDpi)
            throws ReflectiveOperationException {
        applyFreeform(
                service,
                displayId,
                taskId,
                bounds,
                application,
                densityDpi,
                null,
                null);
    }

    private static void applyFreeform(
            final Object service,
            final int displayId,
            final int taskId,
            final Rect bounds,
            final FreeformApplication application,
            final int densityDpi,
            final Object sourceParentToken,
            final Object targetParentToken)
            throws ReflectiveOperationException {
        final Object taskToken = HiddenTaskApi.requireTaskToken(
                service, displayId, taskId);
        final FrameworkWindowingApi windowing =
                FrameworkRuntime.current().windowing();
        final Class<?> transactionClass = windowing.transactionClass();
        final Object transaction = windowing.newTransaction();
        windowing.setWindowingMode(
                transaction, taskToken, WINDOWING_MODE_FREEFORM);
        windowing.setBounds(transaction, taskToken, bounds);
        DesktopTaskDensity.apply(
                windowing, transaction, taskToken, densityDpi);
        windowing.setForceTranslucent(transaction, taskToken, false);
        if (application == FreeformApplication.HIDE_SYNC
                || application == FreeformApplication.SHOW_TRANSITION
                || application
                        == FreeformApplication.DETACH_AND_SHOW_TRANSITION) {
            windowing.setHidden(
                    transaction,
                    taskToken,
                    application == FreeformApplication.HIDE_SYNC);
        }
        if (application
                == FreeformApplication.DETACH_AND_SHOW_TRANSITION) {
            // The fullscreen parent belongs to the long-lived shell observer.
            // Reparent, expose the destination workspace, and reveal the task
            // in one WMShell transition. A second task-selection transaction
            // can pause the client between its fullscreen-exit relayout and
            // first freeform frame.
            if (sourceParentToken != null) {
                windowing.setFocusable(
                        transaction, sourceParentToken, false);
            }
            if (targetParentToken != null) {
                windowing.setFocusable(
                        transaction, targetParentToken, true);
            }
            windowing.reparent(
                    transaction, taskToken, targetParentToken, true);
            if (targetParentToken != null) {
                windowing.reorder(transaction, targetParentToken, true);
            }
        }
        windowing.reorder(transaction, taskToken, true, true);
        TaskCaptionInsetsCommand.addCaptionInsetOperation(
                transaction, taskToken, false);
        if (application == FreeformApplication.HIDE_SYNC) {
            ShellWindowTransitionExecutor.applySynchronized(
                    service, transactionClass, transaction);
        } else {
            final ShellWindowTransitionExecutor.SystemTransition
                    transitionType = application
                    == FreeformApplication.OPEN_TRANSITION
                            ? ShellWindowTransitionExecutor.SystemTransition.OPEN
                            : ShellWindowTransitionExecutor.SystemTransition.CHANGE;
            ShellWindowTransitionExecutor.startForShellAdoption(
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
                service,
                displayId,
                taskId,
                application,
                null);
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
        final FrameworkWindowingApi windowing =
                FrameworkRuntime.current().windowing();
        final Class<?> transactionClass = windowing.transactionClass();
        final Object transaction = windowing.newTransaction();
        windowing.setWindowingMode(
                transaction, taskToken, WINDOWING_MODE_FULLSCREEN);
        windowing.setBounds(transaction, taskToken, new Rect());
        // Preparation preserves density; the destination transition owns it.
        if (!detachFromParent) {
            windowing.setForceTranslucent(transaction, taskToken, false);
        }
        windowing.setHidden(transaction, taskToken, hidden);
        if (detachFromParent) {
            windowing.reparent(
                    transaction, taskToken, targetParentToken, true);
        } else {
            windowing.reorder(transaction, taskToken, true, true);
        }
        TaskCaptionInsetsCommand.addCaptionInsetOperation(
                transaction,
                taskToken,
                true);
        if (application != FullscreenApplication.SHOW_TRANSITION) {
            ShellWindowTransitionExecutor.applySynchronized(
                    service, transactionClass, transaction);
        } else {
            ShellWindowTransitionExecutor.startForShellAdoption(
                    displayId,
                    ShellWindowTransitionExecutor.SystemTransition.TO_FRONT,
                    transactionClass,
                    transaction,
                    "show-prepared-fullscreen");
        }
    }
}
