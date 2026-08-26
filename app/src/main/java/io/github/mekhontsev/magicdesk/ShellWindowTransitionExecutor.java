package io.github.mekhontsev.magicdesk;

import android.os.IBinder;
import android.os.SystemClock;

import java.util.HashMap;
import java.util.Map;

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

    private static final Object LOCK = new Object();
    private static final Map<IBinder, OpeningTransition> ACTIVE =
            new HashMap<>();

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

    static OpeningTransition beginOpening(
            final int displayId,
            final int transitionType,
            final Class<?> transactionClass,
            final Object transaction,
            final String reason) throws ReflectiveOperationException {
        if (displayId < 0 || reason == null || reason.isEmpty()) {
            throw new IllegalArgumentException("invalid opening transition");
        }
        final Object organizer = newWindowOrganizer();
        final IBinder token = (IBinder) organizer.getClass().getMethod(
                "startNewTransition", Integer.TYPE, transactionClass)
                .invoke(organizer, Integer.valueOf(transitionType), transaction);
        if (token == null) {
            throw new IllegalStateException("transition token is unavailable");
        }
        final OpeningTransition opening = new OpeningTransition(
                organizer, token, displayId, reason, SystemClock.uptimeMillis());
        synchronized (LOCK) {
            ACTIVE.put(token, opening);
            LOCK.notifyAll();
        }
        return opening;
    }

    static void continueOpening(
            final OpeningTransition transition,
            final Class<?> transactionClass,
            final Object transaction) throws ReflectiveOperationException {
        requireActive(transition);
        try {
            transition.organizer().getClass().getMethod(
                    "startTransition", IBinder.class, transactionClass)
                    .invoke(
                            transition.organizer(),
                            transition.token(),
                            transaction);
        } finally {
            releaseOpening(transition);
        }
    }

    static void releaseOpening(final OpeningTransition transition) {
        if (transition == null || !transition.markReleased()) {
            return;
        }
        synchronized (LOCK) {
            ACTIVE.remove(transition.token());
            LOCK.notifyAll();
        }
    }

    static boolean awaitIdle(
            final int displayId,
            final long timeoutMillis) {
        if (displayId < 0 || timeoutMillis < 0L) {
            throw new IllegalArgumentException("invalid transition wait");
        }
        final long deadline = SystemClock.uptimeMillis() + timeoutMillis;
        synchronized (LOCK) {
            while (hasActiveTransitionLocked(displayId)) {
                final long remaining = deadline - SystemClock.uptimeMillis();
                if (remaining <= 0L) {
                    return false;
                }
                try {
                    LOCK.wait(remaining);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }
    }

    static Snapshot snapshot() {
        synchronized (LOCK) {
            int oldestAgeMillis = 0;
            final long now = SystemClock.uptimeMillis();
            for (final OpeningTransition transition : ACTIVE.values()) {
                oldestAgeMillis = Math.max(
                        oldestAgeMillis,
                        (int) Math.min(
                                Integer.MAX_VALUE,
                                now - transition.startedAtMillis()));
            }
            return new Snapshot(ACTIVE.size(), oldestAgeMillis);
        }
    }

    private static void requireActive(final OpeningTransition transition) {
        if (transition == null || transition.isReleased()) {
            throw new IllegalArgumentException(
                    "opening transition is unavailable");
        }
        synchronized (LOCK) {
            if (ACTIVE.get(transition.token()) != transition) {
                throw new IllegalStateException(
                        "opening transition is not registered");
            }
        }
    }

    private static boolean hasActiveTransitionLocked(final int displayId) {
        for (final OpeningTransition transition : ACTIVE.values()) {
            if (transition.displayId() == displayId) {
                return true;
            }
        }
        return false;
    }

    private static Object newWindowOrganizer()
            throws ReflectiveOperationException {
        return Class.forName("android.window.WindowOrganizer")
                .getConstructor()
                .newInstance();
    }

    static final class OpeningTransition {
        private final Object mOrganizer;
        private final IBinder mToken;
        private final int mDisplayId;
        private final String mReason;
        private final long mStartedAtMillis;
        private boolean mReleased;

        OpeningTransition(
                final Object organizer,
                final IBinder token,
                final int displayId,
                final String reason,
                final long startedAtMillis) {
            mOrganizer = organizer;
            mToken = token;
            mDisplayId = displayId;
            mReason = reason;
            mStartedAtMillis = startedAtMillis;
        }

        Object organizer() {
            return mOrganizer;
        }

        IBinder token() {
            return mToken;
        }

        int displayId() {
            return mDisplayId;
        }

        String reason() {
            return mReason;
        }

        long startedAtMillis() {
            return mStartedAtMillis;
        }

        synchronized boolean markReleased() {
            if (mReleased) {
                return false;
            }
            mReleased = true;
            return true;
        }

        synchronized boolean isReleased() {
            return mReleased;
        }
    }

    static final class Snapshot {
        final int activeCount;
        final int oldestAgeMillis;

        Snapshot(final int activeCount, final int oldestAgeMillis) {
            this.activeCount = activeCount;
            this.oldestAgeMillis = oldestAgeMillis;
        }
    }
}
