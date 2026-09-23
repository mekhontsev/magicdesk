package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Process;
import android.view.SurfaceControl;

import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Optional input-occlusion capability for explicitly owned shell surfaces. */
final class FrameworkSurfaceInputApi {
    static final String PERMISSION = "android.permission.ACCESS_SURFACE_FLINGER";
    private final Method mSetTrustedOverlay;

    FrameworkSurfaceInputApi() throws ReflectiveOperationException {
        mSetTrustedOverlay = SurfaceControl.Transaction.class.getMethod(
                "setTrustedOverlay", SurfaceControl.class, boolean.class);
    }

    void trustOwnedOverlay(final Context context, final Object ownedSurface)
            throws ReflectiveOperationException {
        if (context.checkPermission(PERMISSION, Process.myPid(), Process.myUid())
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Shell surface input requires " + PERMISSION);
        }
        if (!(ownedSurface instanceof SurfaceControl surface) || !surface.isValid()) {
            throw new IllegalArgumentException("Owned shell surface is unavailable");
        }
        final CountDownLatch committed = new CountDownLatch(1);
        try (var transaction = new SurfaceControl.Transaction()) {
            mSetTrustedOverlay.invoke(transaction, surface, true);
            transaction.addTransactionCommittedListener(Runnable::run, committed::countDown);
            transaction.apply();
            try {
                // SurfaceFlinger commit, not a presentation or input-region acknowledgement.
                if (!committed.await(2, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Shell surface input commit timed out");
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Shell surface input commit interrupted", error);
            }
        }
    }
}
