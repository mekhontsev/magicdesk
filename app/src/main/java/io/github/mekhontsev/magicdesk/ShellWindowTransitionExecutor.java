package io.github.mekhontsev.magicdesk;

import android.os.IBinder;

/** Owns every shell-side window transaction and raw transition handle. */
final class ShellWindowTransitionExecutor {
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
        SyncWindowContainerTransaction.applyWithoutSurfaceSync(
                activityTaskManagerService, transactionClass, transaction);
    }

    static void applySynchronized(
            final Object activityTaskManagerService,
            final Class<?> transactionClass,
            final Object transaction) throws ReflectiveOperationException {
        SyncWindowContainerTransaction.apply(
                activityTaskManagerService, transactionClass, transaction);
    }

    /**
     * Starts a transition whose surface lifecycle is played and completed by
     * WMShell. Use this only when applying a WCT directly would skip creation
     * of task surfaces such as native freeform decorations.
     */
    static void playSystemTransition(
            final int displayId,
            final SystemTransition transition,
            final Class<?> transactionClass,
            final Object transaction,
            final String reason) throws ReflectiveOperationException {
        if (displayId < 0 || transition == null
                || reason == null || reason.isEmpty()) {
            throw new IllegalArgumentException("invalid system transition");
        }
        final Object organizer = newWindowOrganizer();
        final IBinder token = (IBinder) organizer.getClass().getMethod(
                "startNewTransition", Integer.TYPE, transactionClass)
                .invoke(organizer, Integer.valueOf(transition.type), transaction);
        if (token == null) {
            throw new IllegalStateException(
                    "system transition token is unavailable: " + reason);
        }
    }

    private static Object newWindowOrganizer()
            throws ReflectiveOperationException {
        return Class.forName("android.window.WindowOrganizer")
                .getConstructor()
                .newInstance();
    }
}
