package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceHolder;
import android.view.View;
import java.util.function.Consumer;

/** One optional Android presentation of a protocol-owned family. No task or client ownership. */
final class HostedFamilyWindows implements AutoCloseable {
    interface Backend {
        HostedShellOutput borrow(Consumer<HostedFamilyGeometry> changed, Consumer<Throwable> failed);
        default void mounted(HostedSurfaceView surface) { }
        default void unmounted() { }
        default void released() { }
        void focus(boolean focused, boolean dependent);
    }
    private final Activity activity;
    private final HostedSurfaceView anchor;
    private final Backend backend;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final View.OnLayoutChangeListener layout = (v, l, t, r, b, ol, ot, or, ob) -> refresh();
    private final Runnable focusChanged = this::publishFocus;
    private final int[] origin = new int[2];
    private int lastX = Integer.MIN_VALUE, lastY = Integer.MIN_VALUE;
    private final android.view.ViewTreeObserver.OnPreDrawListener drawing = () -> {
        anchorOriginChanged();
        return true;
    };
    private final SurfaceHolder.Callback surfaces = new SurfaceHolder.Callback() {
        @Override public void surfaceCreated(SurfaceHolder holder) { failed = false; }
        @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { refresh(); }
        @Override public void surfaceDestroyed(SurfaceHolder holder) { release(); }
    };
    private HostedShellOutput output;
    private HostedShellSurfaceView view;
    private HostedDependentWindow window;
    private HostedFamilyGeometry geometry, presented;
    private ShellBounds placement;
    private boolean closed, failed, enabled, focused, dependentFocused;
    private long generation;

    HostedFamilyWindows(Activity activity, HostedSurfaceView anchor, Backend backend) {
        this.activity = activity;
        this.anchor = anchor;
        this.backend = backend;
        anchor.addOnLayoutChangeListener(layout);
        anchor.getHolder().addCallback(surfaces);
        anchor.getViewTreeObserver().addOnPreDrawListener(drawing);
    }

    boolean focused() { return anchor.hasWindowFocus() || view != null && view.hasWindowFocus(); }

    void focusChanged() {
        // Join Android focus callbacks from both roots before deciding that the family lost focus.
        main.removeCallbacks(focusChanged);
        main.post(focusChanged);
    }

    private void publishFocus() {
        if (closed) return;
        boolean next = focused();
        boolean dependent = view != null && view.hasWindowFocus();
        if (focused == next && dependentFocused == dependent) return;
        focused = next;
        dependentFocused = dependent;
        if (!next) {
            anchor.releaseFamilyInput();
            if (view != null) view.content().releaseFamilyInput();
        }
        backend.focus(next, dependent);
    }

    void refresh() {
        if (closed) return;
        boolean requested = eligible();
        if (enabled != requested) { enabled = requested; failed = false; }
        if (!enabled || failed || !anchor.isAttachedToWindow() || !anchor.getHolder().getSurface().isValid()) {
            release();
            return;
        }
        if (output == null) {
            long current = ++generation;
            try {
                var borrowed = backend.borrow(next -> {
                    if (closed || current != generation) return;
                    geometry = next;
                    present();
                }, error -> { if (!closed && current == generation) fail(error); });
                if (closed || failed || current != generation) { borrowed.close(); return; }
                output = borrowed;
                anchor.focusBoundary(this::focusChanged);
                focusChanged();
                present();
            } catch (RuntimeException error) { fail(error); }
        } else present();
    }

    private boolean eligible() {
        if (activity.isFinishing() || activity.isDestroyed() || activity.getDisplay() == null
                || android.os.Build.VERSION.SDK_INT < 35) return false;
        int display = activity.getDisplay().getDisplayId();
        if (!DesktopRuntimeBridge.hasWorkspace(display)) return false;
        var snapshot = MagicDeskRuntime.observedTaskSnapshot(display);
        if (snapshot == null || !snapshot.available) return false;
        var owned = MagicDeskRuntime.selectDesktopTaskSnapshot(display, snapshot);
        boolean managed = owned.available && owned.tasks.stream().anyMatch(task ->
                task.taskId == activity.getTaskId() && task.visible && activity.getPackageName().equals(task.packageName));
        return HostedChildWindowPolicy.external(android.os.Build.VERSION.SDK_INT, managed, true);
    }

    @android.annotation.TargetApi(35)
    private void present() {
        if (closed || output == null || geometry == null) return;
        if (geometry.paint().isEmpty()) { unmount(); return; }
        if (!geometry.inputComplete()) { fail(new IllegalStateException("Incomplete dependent input region")); return; }
        var layout = DesktopPanelWindowController.layoutForDisplay(activity.getDisplay().getDisplayId());
        if (layout == null) { release(); return; }
        anchor.getLocationOnScreen(origin);
        lastX = origin[0]; lastY = origin[1];
        ShellBounds bounds = geometry.place(anchor.getWidth(), anchor.getHeight(), lastX, lastY, layout.panelArea());
        if (bounds.isEmpty()) { unmount(); return; }
        if (geometry.equals(presented) && bounds.equals(placement)) return;
        try {
            if (window == null) {
                view = new HostedShellSurfaceView(activity, output, false);
                view.failure(this::fail);
                view.content().shareKeyboard(anchor);
                view.content().focusBoundary(this::focusChanged);
                view.keyboard(true);
                window = new HostedDependentWindow(activity, anchor, view, bounds);
                var current = window;
                view.screenOrigin(current::locateOnScreen);
                window.placementChanged(view::placementChanged);
                window.ended().whenComplete((ignored, error) -> {
                    if (!closed && window == current) fail(error == null
                            ? new IllegalStateException("Dependent host ended") : error);
                });
                backend.mounted(view.content());
            } else window.place(bounds);
            placement = bounds;
            presented = geometry;
            view.present(new HostedShellFrame(geometry.paint(), geometry.inputComplete(), geometry.input()));
        } catch (RuntimeException error) { fail(error); }
    }

    private void anchorOriginChanged() {
        if (closed || output == null || geometry == null || geometry.paint().isEmpty()) return;
        anchor.getLocationOnScreen(origin);
        if (origin[0] != lastX || origin[1] != lastY) present();
    }

    void cursor(android.graphics.Bitmap image, int x, int y, boolean hidden) {
        if (view != null) view.content().cursor(image, x, y, hidden);
    }

    private void fail(Throwable error) {
        if (closed || failed) return;
        failed = true;
        android.util.Log.w("HostedFamily", "Retaining in-window family presentation", error);
        release();
    }

    private void unmount() {
        var previousWindow = window;
        var previousView = view;
        if (previousView != null && !closed && enabled && !failed && focused()) backend.focus(true, false);
        window = null; view = null; placement = null; presented = null;
        if (previousView != null) backend.unmounted();
        if (previousView != null) {
            previousView.failure(null);
            previousView.content().leaveKeyboardFamily();
            previousView.content().focusBoundary(null);
            previousView.close();
        }
        if (previousWindow != null) previousWindow.close();
        focusChanged();
    }

    private void release() {
        if (output == null && window == null) return;
        ++generation;
        var previous = output;
        output = null;
        geometry = null;
        anchor.releaseFamilyInput();
        anchor.focusBoundary(null);
        unmount();
        if (previous != null) previous.close();
        backend.released();
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        anchor.removeOnLayoutChangeListener(layout);
        anchor.getHolder().removeCallback(surfaces);
        anchor.getViewTreeObserver().removeOnPreDrawListener(drawing);
        anchor.focusBoundary(null);
        release();
        main.removeCallbacks(focusChanged);
    }
}
