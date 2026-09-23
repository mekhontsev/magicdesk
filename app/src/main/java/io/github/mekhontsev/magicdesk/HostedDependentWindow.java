package io.github.mekhontsev.magicdesk;

import android.annotation.TargetApi;
import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import android.view.AttachedSurfaceControl;
import android.view.SurfaceControl;
import android.view.SurfaceControlViewHost;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewTreeObserver;
import java.util.concurrent.CompletableFuture;

/** A borrowed child View hierarchy ordered with its application, outside the application's crop. */
@TargetApi(35)
final class HostedDependentWindow implements AutoCloseable {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SurfaceView anchor;
    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    private final CompletableFuture<Void> ended = new CompletableFuture<>();
    private final int[] origin = new int[2];
    private final ViewTreeObserver.OnPreDrawListener drawing = () -> { position(); return true; };
    private final SurfaceHolder.Callback lifecycle = new SurfaceHolder.Callback() {
        @Override public void surfaceCreated(SurfaceHolder holder) { }
        @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { position(); }
        @Override public void surfaceDestroyed(SurfaceHolder holder) {
            fail(new IllegalStateException("Dependent window lost its parent surface"));
        }
    };
    private final Runnable timeout = () -> fail(new IllegalStateException("Dependent surface attachment timed out"));
    private DesktopSurfaceParent.Lease parent;
    private SurfaceControlViewHost host;
    private SurfaceControlViewHost.SurfacePackage surfacePackage;
    private ShellBounds bounds;
    private boolean closed, attached;
    private Runnable placementChanged;
    private int lastX = Integer.MIN_VALUE, lastY = Integer.MIN_VALUE;

    /** Bounds are pixels relative to the anchor View. The caller retains protocol/content ownership. */
    HostedDependentWindow(Activity activity, SurfaceView anchor, View content, ShellBounds bounds) {
        checkThread();
        if (android.os.Build.VERSION.SDK_INT < 35) throw new UnsupportedOperationException("Desktop requires Android 15");
        this.anchor = java.util.Objects.requireNonNull(anchor);
        this.bounds = requireBounds(bounds);
        if (activity.getDisplay() == null || anchor.getDisplay() == null
                || activity.getDisplay().getDisplayId() != anchor.getDisplay().getDisplayId())
            throw new IllegalArgumentException("Dependent window and anchor must share a display");
        if (!anchor.isAttachedToWindow() || anchor.getRootSurfaceControl() == null
                || anchor.getSurfaceControl() == null || !anchor.getSurfaceControl().isValid())
            throw new IllegalStateException("Dependent window requires a live application surface");
        try {
            parent = DesktopPanelWindowController.borrowSurfaceParent(activity.getDisplay().getDisplayId());
            host = new SurfaceControlViewHost(activity, activity.getDisplay(),
                    anchor.getRootSurfaceControl().getInputTransferToken());
            host.setView(content, bounds.width(), bounds.height());
            surfacePackage = host.getSurfacePackage();
            if (surfacePackage == null) throw new IllegalStateException("Embedded surface unavailable");
            anchor.getHolder().addCallback(lifecycle);
            anchor.getViewTreeObserver().addOnPreDrawListener(drawing);
            // EVENT_WAIT: parent layout, ordering commit and reparent commit; expiry cancels this child only.
            main.postDelayed(timeout, 4_000);
            parent.ended().whenComplete((unused, error) -> {
                if (!closed) fail(error == null ? new IllegalStateException("Desktop surface parent ended") : error);
            });
            parent.ready().whenComplete((root, error) -> {
                if (closed) return;
                if (error != null) fail(error); else order(root);
            });
        } catch (RuntimeException error) {
            fail(error);
            throw error;
        }
    }

    /** Attachment is not a renderer-frame or InputDispatcher readiness receipt. */
    CompletableFuture<Void> ready() { return ready.copy(); }
    CompletableFuture<Void> ended() { return ended.copy(); }

    void placementChanged(Runnable callback) { placementChanged = callback; }

    void locateOnScreen(int[] location) {
        checkThread();
        anchor.getLocationOnScreen(location);
        location[0] += bounds.left();
        location[1] += bounds.top();
    }

    void place(ShellBounds next) {
        checkThread();
        if (closed) throw new IllegalStateException("Dependent window is closed");
        requireBounds(next);
        if (bounds.width() != next.width() || bounds.height() != next.height())
            host.relayout(next.width(), next.height());
        bounds = next;
        position();
    }

    private void order(AttachedSurfaceControl root) {
        SurfaceControl target;
        try { target = retain(surfacePackage.getSurfaceControl()); }
        catch (RuntimeException error) { fail(error); return; }
        SurfaceControl relative;
        try { relative = retain(anchor.getSurfaceControl()); }
        catch (RuntimeException error) { target.release(); fail(error); return; }
        try {
            TaskCommandQueue.execute(() -> {
                Throwable failure = null;
                try { ShellAccess.orderHostedSurface(target, relative); }
                catch (Exception error) { failure = error; }
                finally { target.release(); relative.release(); }
                final Throwable result = failure;
                main.post(() -> {
                    if (closed) return;
                    if (result != null) { fail(result); return; }
                    try (var transaction = root.buildReparentTransaction(surfacePackage.getSurfaceControl())) {
                        if (transaction == null) throw new IllegalStateException("Desktop surface parent lost");
                        anchor.getLocationOnScreen(origin);
                        lastX = origin[0] + bounds.left(); lastY = origin[1] + bounds.top();
                        transaction.setPosition(surfacePackage.getSurfaceControl(), lastX, lastY);
                        transaction.setVisibility(surfacePackage.getSurfaceControl(), true);
                        transaction.addTransactionCommittedListener(main::post, () -> {
                            if (closed) return;
                            attached = true;
                            main.removeCallbacks(timeout);
                            position();
                            if (placementChanged != null) placementChanged.run();
                            ready.complete(null);
                        });
                        transaction.apply();
                    } catch (RuntimeException error) { fail(error); }
                });
            });
        } catch (RuntimeException error) { target.release(); relative.release(); fail(error); }
    }

    private void position() {
        if (closed || !attached) return;
        anchor.getLocationOnScreen(origin);
        int x = origin[0] + bounds.left(), y = origin[1] + bounds.top();
        if (x == lastX && y == lastY) return;
        try (var transaction = new SurfaceControl.Transaction()) {
            transaction.setPosition(surfacePackage.getSurfaceControl(), x, y).apply();
            lastX = x; lastY = y;
            if (placementChanged != null) placementChanged.run();
        } catch (RuntimeException error) { fail(error); }
    }

    private void fail(Throwable error) {
        checkThread();
        if (closed) return;
        closed = true;
        main.removeCallbacks(timeout);
        anchor.getHolder().removeCallback(lifecycle);
        anchor.getViewTreeObserver().removeOnPreDrawListener(drawing);
        var previousHost = host;
        var previousPackage = surfacePackage;
        var previousParent = parent;
        host = null;
        surfacePackage = null;
        parent = null;
        try { if (previousHost != null) previousHost.release(); }
        catch (RuntimeException cleanup) { error.addSuppressed(cleanup); }
        try { if (previousPackage != null) previousPackage.release(); }
        catch (RuntimeException cleanup) { error.addSuppressed(cleanup); }
        try { if (previousParent != null) previousParent.close(); }
        catch (RuntimeException cleanup) { error.addSuppressed(cleanup); }
        ready.completeExceptionally(error);
        ended.completeExceptionally(error);
    }

    @Override public void close() { fail(new java.util.concurrent.CancellationException("Dependent window released")); }

    private static ShellBounds requireBounds(ShellBounds value) {
        if (value == null || value.isEmpty() || value.width() > 16384 || value.height() > 16384)
            throw new IllegalArgumentException("Invalid dependent window bounds");
        return value;
    }

    /** Borrowed View/SurfacePackage handles may be released while the command queue is busy. */
    private static SurfaceControl retain(SurfaceControl surface) {
        if (surface == null || !surface.isValid()) throw new IllegalStateException("Surface unavailable");
        Parcel parcel = Parcel.obtain();
        try {
            surface.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            return SurfaceControl.CREATOR.createFromParcel(parcel);
        } finally { parcel.recycle(); }
    }

    private static void checkThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Dependent window requires main thread");
    }
}
