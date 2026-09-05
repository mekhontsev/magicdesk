package io.github.mekhontsev.magicdesk;

import android.os.IBinder;

/** Central submission boundary for shell-side window transactions. */
final class ShellWindowTransitionExecutor {
    private static Object sWindowOrganizer;

    enum SystemTransition {
        OPEN(1),
        TO_FRONT(3),
        CHANGE(6);

        final int type;

        SystemTransition(final int transitionType) {
            type = transitionType;
        }
    }

    private ShellWindowTransitionExecutor() {
    }

    static void applyAtomic(
            final Object activityTaskManagerService,
            final Class<?> transactionClass,
            final Object transaction) throws ReflectiveOperationException {
        prepareSurfaceTransactions();
        SyncWindowContainerTransaction.applyWithoutSurfaceSync(
                activityTaskManagerService, transactionClass, transaction);
    }

    static void applySynchronized(
            final Object activityTaskManagerService,
            final Class<?> transactionClass,
            final Object transaction) throws ReflectiveOperationException {
        prepareSurfaceTransactions();
        SyncWindowContainerTransaction.apply(
                activityTaskManagerService, transactionClass, transaction);
    }

    static void applySelection(
            final Object activityTaskManagerService,
            final int displayId,
            final Class<?> transactionClass,
            final Object transaction,
            final int windowingMode) throws ReflectiveOperationException {
        if (selectionRequiresSystemTransition(windowingMode)) {
            // A plain WCT, even with a sync callback, can change the root-task
            // hierarchy without assigning its new surface layers. WM's native
            // transition owns that assignment for ordinary freeform tasks.
            // Submit once: a later focus/raise would be a competing selection.
            startForShellAdoption(displayId, SystemTransition.TO_FRONT,
                    transactionClass, transaction, "select-freeform-task");
            // Return only after the framework's transition/input barrier.
            // The topology owner commits its final plane layers next; doing
            // that before WM's finish transaction lets WM overwrite them.
            FrameworkWindowCommitBarrier.awaitSystemTransitions();
        } else {
            applyAtomic(activityTaskManagerService, transactionClass, transaction);
        }
    }

    static boolean selectionRequiresSystemTransition(final int windowingMode) {
        // Covered tasks need the same surface handoff as visible peers. Owned
        // fullscreen planes retain their explicit surface composition path.
        return windowingMode == FrameworkTaskSnapshot.WINDOWING_MODE_FREEFORM;
    }

    /**
     * Starts a transition directly in WMCore. This bypasses WMShell's local
     * pending-transition registration; current WMShell versions adopt the
     * token when it becomes ready, then play and finish its surface lifecycle.
     * Use this when applying a WCT directly would skip task-surface creation
     * or layer assignment. This is the submission itself, not a second pass
     * after an atomic or synchronized commit of the same transaction.
     */
    static IBinder startForShellAdoption(
            final int displayId,
            final SystemTransition transition,
            final Class<?> transactionClass,
            final Object transaction,
            final String reason) throws ReflectiveOperationException {
        if (displayId < 0 || transition == null
                || reason == null || reason.isEmpty()) {
            throw new IllegalArgumentException("invalid system transition");
        }
        final Object organizer = windowOrganizer();
        final IBinder token = (IBinder) organizer.getClass().getMethod(
                "startNewTransition", Integer.TYPE, transactionClass)
                .invoke(organizer, Integer.valueOf(transition.type), transaction);
        if (token == null) {
            throw new IllegalStateException(
                    "system transition token is unavailable: " + reason);
        }
        return token;
    }

    static void prepareSurfaceTransactions()
            throws ReflectiveOperationException {
        windowOrganizer();
    }

    static synchronized String surfaceTransactionQueueState() {
        return sWindowOrganizer == null ? "not_initialized" : "shared_with_wm";
    }

    private static synchronized Object windowOrganizer()
            throws ReflectiveOperationException {
        if (sWindowOrganizer == null) {
            final Object organizer = Class.forName("android.window.WindowOrganizer")
                    .getConstructor().newInstance();
            shareSurfaceTransactionQueue(organizer);
            sWindowOrganizer = organizer;
        }
        return sWindowOrganizer;
    }

    static void shareSurfaceTransactionQueue(final Object organizer)
            throws ReflectiveOperationException {
        // Match WMShell initialization: framework sync callbacks and our plane
        // commits must use WM's apply token, not an independent process queue.
        if (!Boolean.TRUE.equals(organizer.getClass()
                .getMethod("shareTransactionQueue").invoke(organizer))) {
            throw new IllegalStateException(
                    "WindowManager surface transaction queue is unavailable");
        }
    }
}
