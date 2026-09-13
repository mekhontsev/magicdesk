package io.github.mekhontsev.magicdesk;

import android.annotation.SuppressLint;
import android.view.Surface;
import android.view.SurfaceControl;
import java.lang.reflect.Method;

/** Android 14+ logical-scene mirroring; no display resource or task mutation. */
@SuppressLint({"PrivateApi", "BlockedPrivateApi"})
final class FrameworkDisplayMirrorApi {
    private final Object mWindows = Class.forName("android.view.WindowManagerGlobal")
            .getMethod("getWindowManagerService").invoke(null);
    private final Method mMirror = Class.forName("android.view.IWindowManager")
            .getMethod("mirrorDisplay", int.class, SurfaceControl.class);

    FrameworkDisplayMirrorApi() throws ReflectiveOperationException { }

    DisplayPresentationSurface create(int displayId) {
        return new DisplayPresentationSurface() {
            private SurfaceControl mirror;

            @Override public void attach(Surface surface, SurfaceControl parent) {
                if (parent == null || !parent.isValid()) {
                    throw new IllegalArgumentException("a live viewer parent is required");
                }
                close();
                try {
                    mirror = SurfaceControl.class.getConstructor().newInstance();
                    if (!Boolean.TRUE.equals(mMirror.invoke(mWindows, displayId, mirror))) {
                        throw new IllegalStateException("Android cannot mirror display " + displayId);
                    }
                    // SurfaceView owns the parent transform and crop. The fixed
                    // source-sized buffer establishes the same coordinate space
                    // for this child as for a directly connected virtual source.
                    try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
                        transaction.reparent(mirror, parent).setLayer(mirror, 1).setVisibility(mirror, true).apply();
                    }
                } catch (ReflectiveOperationException | RuntimeException error) {
                    try { close(); } catch (RuntimeException cleanup) { error.addSuppressed(cleanup); }
                    throw new IllegalStateException("could not mirror display " + displayId, error);
                }
            }

            @Override public void close() {
                if (mirror == null) return;
                final SurfaceControl previous = mirror;
                mirror = null;
                try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
                    if (previous.isValid()) transaction.reparent(previous, null).apply();
                } finally { previous.release(); }
            }
        };
    }
}
