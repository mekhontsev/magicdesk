package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.ViewTreeObserver;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

/** Keyboard-inert shell content inside an Android view hierarchy, not a separate input window. */
@android.annotation.SuppressLint({"ViewConstructor", "ClickableViewAccessibility"}) // Borrowed output; HostedPointerInput calls performClick.
final class HostedShellTextureView extends TextureView implements TextureView.SurfaceTextureListener, AutoCloseable {
    private final HostedShellOutput output;
    private final HostedPointerInput pointer;
    private final java.util.function.Consumer<Throwable> unavailable;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ShellFrameAdmission admission = new ShellFrameAdmission();
    private final ViewTreeObserver.OnPreDrawListener drawing = this::beforeDraw;
    private HostedShellFrame frame;
    private Surface surface;
    private List<ShellBounds> input = List.of();
    private CompletableFuture<Void> pending;
    private long scheduled;
    private boolean closed, contact;

    HostedShellTextureView(Context context, HostedShellOutput output, java.util.function.Consumer<Throwable> unavailable) {
        super(context);
        this.output = java.util.Objects.requireNonNull(output);
        this.unavailable = java.util.Objects.requireNonNull(unavailable);
        pointer = new HostedPointerInput(this);
        pointer.bind(output);
        setOpaque(false);
        setFocusable(false);
        setSurfaceTextureListener(this);
    }

    CompletableFuture<Void> present(HostedShellFrame next) {
        if (closed) throw new IllegalStateException("Shell view is closed");
        cancelPending("Shell presentation replaced");
        frame = java.util.Objects.requireNonNull(next);
        pending = new CompletableFuture<>();
        invalidatePresentation();
        return pending.copy();
    }

    private void invalidatePresentation() {
        admission.revoke();
        input = List.of();
        contact = false;
        pointer.release();
        if (!closed && frame != null) admission.begin();
        invalidate();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnPreDrawListener(drawing);
        invalidatePresentation();
    }

    @Override protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnPreDrawListener(drawing);
        close();
        super.onDetachedFromWindow();
        unavailable.accept(null);
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
        if (closed) return;
        surface = new Surface(texture);
        invalidatePresentation();
    }

    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
        invalidatePresentation();
    }

    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
        invalidatePresentation();
        if (!closed) output.setSurface(null, 0, 0);
        releaseSurface();
        return true;
    }

    @Override public void onSurfaceTextureUpdated(SurfaceTexture texture) { }

    private boolean beforeDraw() {
        if (closed || admission.phase() != ShellFrameAdmission.Phase.PRESENTING
                || surface == null || !surface.isValid() || getWidth() < 1 || getHeight() < 1) return true;
        long generation = admission.generation();
        if (scheduled == generation) return true;
        scheduled = generation;
        if (!isHardwareAccelerated()) {
            fail(generation, new IllegalStateException("Shell view requires hardware composition"));
            return true;
        }
        pointer.viewport(new HostedViewport(0, 0, getWidth(), getHeight()));
        try {
            output.present(surface, frame.viewport()).whenComplete((ignored, error) -> main.post(() -> {
                if (!admission.current(generation)) return;
                if (error != null) { fail(generation, error); return; }
                admission.pixels(generation);
                // TextureView participates in the HOME frame, unlike a separate SurfaceView window.
                // Observe a layout commit after the matching buffer submission; this is not a scanout receipt.
                getViewTreeObserver().registerFrameCommitCallback(() -> main.post(() -> {
                    if (!admission.layout(generation)) return;
                    input = frame.inputPixels(getWidth(), getHeight());
                    admission.region(generation);
                    var completion = pending;
                    pending = null;
                    if (completion != null) completion.complete(null);
                }));
                invalidate();
            }));
        } catch (RuntimeException error) { fail(generation, error); }
        return true;
    }

    private boolean contains(float x, float y) {
        for (var bounds : input) if (x >= bounds.left() && x < bounds.right()
                && y >= bounds.top() && y < bounds.bottom()) return true;
        return false;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!admission.ready()) return false;
        // Only the start is hit-tested. Android retains the complete accepted gesture, including UP outside.
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            contact = contains(event.getX(), event.getY());
            if (!contact) return false;
        }
        if (!contact) return false;
        boolean handled = pointer.event(event);
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) contact = false;
        return handled;
    }

    @Override public boolean onGenericMotionEvent(MotionEvent event) {
        if (!admission.ready()) return false;
        int action = event.getActionMasked();
        // ViewGroup re-hit-tests generic button events. A native sibling can own DOWN in the same bounds.
        if ((action == MotionEvent.ACTION_BUTTON_PRESS || action == MotionEvent.ACTION_BUTTON_RELEASE)
                && !contact) return false;
        if (action != MotionEvent.ACTION_HOVER_EXIT && !contact
                && !contains(event.getX(), event.getY())) return false;
        return pointer.event(event);
    }

    @Override public boolean performClick() { super.performClick(); return true; }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (!focused) { contact = false; pointer.release(); }
    }

    private void fail(long generation, Throwable error) {
        if (!admission.current(generation)) return;
        admission.revoke();
        input = List.of();
        contact = false;
        pointer.release();
        var completion = pending;
        pending = null;
        if (completion != null) completion.completeExceptionally(error);
        unavailable.accept(error);
    }

    private void cancelPending(String reason) {
        var completion = pending;
        pending = null;
        if (completion != null) completion.completeExceptionally(new CancellationException(reason));
    }

    private void releaseSurface() {
        if (surface != null) surface.release();
        surface = null;
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        admission.revoke();
        input = List.of();
        contact = false;
        pointer.bind(null);
        output.close();
        releaseSurface();
        cancelPending("Shell view released");
    }
}
