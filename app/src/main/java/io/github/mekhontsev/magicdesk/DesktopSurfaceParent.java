package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.AttachedSurfaceControl;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** One non-interactive, display-sized parent for app-relative surfaces in a Desktop residency. */
final class DesktopSurfaceParent implements AutoCloseable {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LinkedHashSet<Lease> leases = new LinkedHashSet<>();
    private final java.util.function.Consumer<DesktopSurfaceParent> released;
    private final Runnable timeout = () -> fail(new IllegalStateException("Dependent surface parent layout timed out"));
    private WindowManager manager;
    private FrameLayout view;
    private boolean closed, added;

    DesktopSurfaceParent(java.util.function.Consumer<DesktopSurfaceParent> released) { this.released = released; }

    Lease borrow() {
        checkThread();
        if (closed) throw new IllegalStateException("Dependent surface parent is released");
        Lease lease = new Lease();
        leases.add(lease);
        publish();
        return lease;
    }

    void attach(Context context, WindowManager manager, IBinder token) {
        checkThread();
        if (closed || view != null) return;
        this.manager = manager;
        view = new FrameLayout(context);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        view.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> publish());
        view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { }
            @Override public void onViewDetachedFromWindow(View v) {
                fail(new IllegalStateException("Dependent surface parent detached"));
            }
        });
        var params = new WindowManager.LayoutParams(-1, -1,
                WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        params.token = token;
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.setFitInsetsTypes(0);
        params.setTitle("MagicDesk dependent surfaces");
        // EVENT_WAIT: Android layout provides the parent surface; expiry revokes every pending lease.
        main.postDelayed(timeout, 2_000);
        try { manager.addView(view, params); added = true; }
        catch (RuntimeException error) { fail(error); }
    }

    private void publish() {
        if (closed || view == null || view.getWidth() < 1 || view.getHeight() < 1) return;
        var root = view.getRootSurfaceControl();
        if (root == null) return;
        main.removeCallbacks(timeout);
        for (var lease : List.copyOf(leases)) lease.ready.complete(root);
    }

    void fail(Throwable error) {
        checkThread();
        if (closed) return;
        closed = true;
        main.removeCallbacks(timeout);
        released.accept(this);
        // End children while their structural parent still exists.
        for (var lease : List.copyOf(leases)) lease.end(error);
        var previous = view;
        view = null;
        try {
            if (previous != null && added) manager.removeViewImmediate(previous);
        } catch (RuntimeException cleanup) {
            android.util.Log.w("MagicDeskSurfaces", "Could not remove dependent surface parent", cleanup);
        } finally {
            added = false;
            manager = null;
        }
    }

    @Override public void close() { fail(new java.util.concurrent.CancellationException("Desktop surface parent released")); }

    final class Lease implements AutoCloseable {
        private final CompletableFuture<AttachedSurfaceControl> ready = new CompletableFuture<>();
        private final CompletableFuture<Void> ended = new CompletableFuture<>();
        CompletableFuture<AttachedSurfaceControl> ready() { return ready.copy(); }
        CompletableFuture<Void> ended() { return ended.copy(); }

        private void end(Throwable error) {
            if (!leases.remove(this)) return;
            ready.completeExceptionally(error);
            ended.completeExceptionally(error);
        }

        @Override public void close() {
            checkThread();
            end(new java.util.concurrent.CancellationException("Dependent surface lease released"));
            if (leases.isEmpty()) DesktopSurfaceParent.this.close();
        }
    }

    private static void checkThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Surface parent requires main thread");
    }
}
