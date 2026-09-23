package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Region;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import java.util.concurrent.CompletableFuture;

/** One bounded shell child window. Its owner supplies placement, trust and keyboard policy. */
@android.annotation.SuppressLint("ViewConstructor") // A borrowed output is required; not an XML View.
final class HostedShellSurfaceView extends FrameLayout implements AutoCloseable {
    private final HostedSurfaceView content;
    private final HostedShellOutput output;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ShellFrameAdmission admission = new ShellFrameAdmission();
    private final Region region = new Region();
    private final ViewTreeObserver.OnPreDrawListener drawing = this::beforeDraw;
    private HostedShellFrame frame;
    private Surface surface;
    private int surfaceWidth, surfaceHeight;
    private long scheduled;
    private boolean closed;
    private CompletableFuture<Void> pending;
    private IInputRegionReceipt inputReceipt;
    private Runnable releaseKeyboard;
    private boolean keyboardFocused;

    HostedShellSurfaceView(Context context, HostedShellOutput output) {
        super(context);
        this.output = java.util.Objects.requireNonNull(output);
        content = new HostedSurfaceView(context);
        content.allowKeyboard(false);
        content.getHolder().setFormat(PixelFormat.TRANSLUCENT);
        content.allowInput(false);
        addView(content, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        content.bind(output, this::surfaceChanged);
    }

    void keyboardRequests(Runnable request, Runnable release) {
        content.beforeInteraction(request);
        releaseKeyboard = release;
    }

    void keyboard(boolean enabled) {
        if (!enabled) keyboardFocused = false;
        content.allowKeyboard(enabled);
        if (enabled) content.requestFocus();
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) keyboardFocused = true;
        else if (keyboardFocused) {
            keyboardFocused = false;
            if (releaseKeyboard != null) releaseKeyboard.run();
        }
    }

    @Override public boolean dispatchTouchEvent(android.view.MotionEvent event) {
        if (event.getActionMasked() == android.view.MotionEvent.ACTION_OUTSIDE) {
            if (releaseKeyboard != null) releaseKeyboard.run();
            return true;
        }
        return super.dispatchTouchEvent(event);
    }

    @Override public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        if (event.getKeyCode() == android.view.KeyEvent.KEYCODE_BACK && releaseKeyboard != null) {
            if (event.getAction() == android.view.KeyEvent.ACTION_UP) releaseKeyboard.run();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    /** Completes after pixels are submitted and Android acknowledges the exact input region. */
    CompletableFuture<Void> present(HostedShellFrame next) {
        checkThread();
        if (closed) throw new IllegalStateException("Shell host is closed");
        java.util.Objects.requireNonNull(next);
        var previous = pending;
        var completion = new CompletableFuture<Void>();
        pending = completion;
        frame = next;
        invalidatePresentation();
        if (previous != null) previous.completeExceptionally(
                new java.util.concurrent.CancellationException("Shell presentation replaced"));
        return completion.copy();
    }

    boolean inputReady() { return admission.ready(); }

    private void surfaceChanged(Surface next, int width, int height) {
        surface = next;
        surfaceWidth = width;
        surfaceHeight = height;
        invalidatePresentation();
        if (next == null && !closed) output.setSurface(null, 0, 0);
    }

    private void invalidatePresentation() {
        admission.revoke();
        clearInput();
        if (!closed && frame != null) admission.begin();
        invalidate();
    }

    private void clearInput() {
        var previous = inputReceipt;
        inputReceipt = null;
        if (previous != null) {
            try { previous.cancel(); }
            catch (android.os.RemoteException ignored) { /* Service death also releases the observation. */ }
        }
        content.allowInput(false);
        region.setEmpty();
        var root = getRootSurfaceControl();
        if (root != null) root.setTouchableRegion(region);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnPreDrawListener(drawing);
        invalidatePresentation();
    }

    @Override protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnPreDrawListener(drawing);
        admission.revoke();
        clearInput();
        cancelPending("Shell window detached");
        super.onDetachedFromWindow();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        invalidatePresentation();
    }

    private boolean beforeDraw() {
        if (closed || admission.phase() != ShellFrameAdmission.Phase.PRESENTING
                || surface == null || !surface.isValid() || surfaceWidth != getWidth()
                || surfaceHeight != getHeight() || getWidth() < 1 || getHeight() < 1) return true;
        long generation = admission.generation();
        if (scheduled == generation) return true;
        if (!isHardwareAccelerated()) {
            fail(generation, new IllegalStateException("Shell host requires frame-commit observation"));
            return true;
        }
        scheduled = generation;
        content.frame(getWidth(), getHeight());
        getViewTreeObserver().registerFrameCommitCallback(() -> main.post(() -> {
            if (admission.layout(generation)) publishRegion(generation);
        }));
        // SurfaceView's first window draw can itself wait for a buffer. Join, never serialize, these receipts.
        render(generation);
        return true;
    }

    private void render(long generation) {
        if (!admission.current(generation)) return;
        try {
            output.present(surface, frame.viewport()).whenComplete((ignored, error) -> main.post(() -> {
                if (error != null) { fail(generation, error); return; }
                if (admission.pixels(generation)) publishRegion(generation);
            }));
        } catch (RuntimeException error) { fail(generation, error); }
    }

    private void publishRegion(long generation) {
        var root = getRootSurfaceControl();
        if (root == null) { fail(generation, new IllegalStateException("Shell window lost its Surface")); return; }
        region.setEmpty();
        for (var rect : frame.inputPixels(getWidth(), getHeight()))
            region.op(rect.left(), rect.top(), rect.right(), rect.bottom(), Region.Op.UNION);
        root.setTouchableRegion(region);
        int[] location = new int[2];
        getLocationOnScreen(location);
        var expected = new Region(region);
        expected.translate(location[0], location[1]);
        var window = getWindowToken();
        int display = getDisplay().getDisplayId();
        try {
            inputReceipt = ShellAccess.observeWindowInputRegion(window, display, expected,
                    new IInputRegionCallback.Stub() {
                        @Override public void completed(String error) {
                            main.post(() -> {
                                if (error != null) { fail(generation, new IllegalStateException(error)); return; }
                                if (!admission.region(generation)) return;
                                inputReceipt = null;
                                content.allowInput(frame.inputComplete());
                                var completion = pending;
                                pending = null;
                                if (completion != null) completion.complete(null);
                            });
                        }
                    });
        } catch (java.io.IOException error) { fail(generation, error); }
        invalidate();
    }

    private void fail(long generation, Throwable error) {
        if (!admission.current(generation)) return;
        admission.revoke();
        clearInput();
        var completion = pending;
        pending = null;
        if (completion != null) completion.completeExceptionally(error);
    }

    private void cancelPending(String reason) {
        var completion = pending;
        pending = null;
        if (completion != null) completion.completeExceptionally(new java.util.concurrent.CancellationException(reason));
    }

    @Override public void close() {
        checkThread();
        if (closed) return;
        closed = true;
        admission.revoke();
        clearInput();
        frame = null;
        surface = null;
        content.release();
        cancelPending("Shell host closed");
    }

    private static void checkThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Shell host requires main thread");
    }
}
