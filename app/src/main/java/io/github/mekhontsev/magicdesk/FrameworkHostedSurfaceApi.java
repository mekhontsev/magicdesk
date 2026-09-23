package io.github.mekhontsev.magicdesk;

import android.view.SurfaceControl;
import java.lang.reflect.Method;

/** Optional ordering for borrowed app-owned surfaces; never reparents an Android task. */
final class FrameworkHostedSurfaceApi {
    private final Method relativeLayer;

    FrameworkHostedSurfaceApi() throws ReflectiveOperationException {
        relativeLayer = SurfaceControl.Transaction.class.getMethod("setRelativeLayer",
                SurfaceControl.class, SurfaceControl.class, int.class);
    }

    void order(SurfaceControl surface, SurfaceControl relative) throws ReflectiveOperationException {
        if (surface == null || relative == null || !surface.isValid() || !relative.isValid())
            throw new IllegalArgumentException("Hosted surface or parent anchor is unavailable");
        try (var transaction = new SurfaceControl.Transaction()) {
            var committed = new java.util.concurrent.CountDownLatch(1);
            relativeLayer.invoke(transaction, surface, relative, 1);
            transaction.addTransactionCommittedListener(Runnable::run, committed::countDown);
            transaction.apply();
            try {
                // EVENT_WAIT: ordering must commit before another process reveals the child; expiry rejects admission.
                EventDrivenWaits.noteFrameworkWait(EventDrivenWaits.Reason.WINDOW_TRANSITION_COMMIT);
                if (!committed.await(2, java.util.concurrent.TimeUnit.SECONDS))
                    throw new IllegalStateException("Hosted surface ordering commit timed out");
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Hosted surface ordering interrupted", error);
            }
        }
    }
}
