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
    private final boolean ownsOutput;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ShellFrameAdmission admission = new ShellFrameAdmission();
    private final java.util.ArrayList<Runnable> redraws = new java.util.ArrayList<>();
    private android.window.SurfaceSyncGroup resizeSync;
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
    private java.util.function.Consumer<Throwable> failure;

    HostedShellSurfaceView(Context context, HostedShellOutput output) {
        this(context, output, true);
    }

    HostedShellSurfaceView(Context context, HostedShellOutput output, boolean ownsOutput) {
        super(context);
        this.output = java.util.Objects.requireNonNull(output);
        this.ownsOutput = ownsOutput;
        content = new HostedSurfaceView(context);
        content.allowKeyboard(false);
        content.getHolder().setFormat(PixelFormat.TRANSLUCENT);
        content.allowInput(false);
        addView(content, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        content.bind(output, new HostedSurfaceView.SurfaceBinding() {
            @Override public void changed(Surface surface, int width, int height) {
                surfaceChanged(surface, width, height);
            }
            @Override public void redraw(Runnable finished) { redrawNeeded(finished); }
        }, ownsOutput);
    }

    HostedSurfaceView content() { return content; }
    void failure(java.util.function.Consumer<Throwable> callback) { failure = callback; }

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
        if (HostedPointerInput.navigationKey(event)) return true;
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
        synchronizeLayout();
        invalidatePresentation();
        if (previous != null) previous.completeExceptionally(
                new java.util.concurrent.CancellationException("Shell presentation replaced"));
        return completion.copy();
    }

    private void synchronizeLayout() {
        if (resizeSync == null && getRootSurfaceControl() != null) {
            // Hold the root's geometry transaction too: a Callback2 receipt alone need not sync a local relayout.
            var sync = new android.window.SurfaceSyncGroup("MagicDesk shell frame");
            if (sync.add(getRootSurfaceControl(), null)) resizeSync = sync;
            else sync.markSyncReady();
        }
    }

    boolean inputReady() { return admission.ready(); }

    void screenOrigin(java.util.function.Consumer<int[]> origin) { content.screenOrigin(origin); }

    void placementChanged() {
        if (!closed && frame != null) invalidatePresentation();
    }

    private void surfaceChanged(Surface next, int width, int height) {
        surface = next;
        surfaceWidth = width;
        surfaceHeight = height;
        invalidatePresentation();
        if (next == null) {
            finishRedraws();
            if (!closed) output.setSurface(null, 0, 0);
        }
    }

    private void redrawNeeded(Runnable finished) {
        if (closed || surface == null || frame == null) { finished.run(); return; }
        // EVENT_WAIT: SurfaceView resize sync ends on matching pixel submission or output failure/teardown.
        // Do not wait for the window commit here: Android needs this receipt to commit that layout.
        redraws.add(finished);
        invalidatePresentation();
    }

    private void finishRedraws() {
        var sync = resizeSync;
        resizeSync = null;
        if (sync != null) sync.markSyncReady();
        if (redraws.isEmpty()) return;
        var callbacks = java.util.List.copyOf(redraws);
        redraws.clear();
        callbacks.forEach(Runnable::run);
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
        finishRedraws();
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
                if (!admission.current(generation)) return;
                if (error != null) { fail(generation, error); return; }
                finishRedraws();
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
        content.locateOnScreen(location);
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
        finishRedraws();
        var completion = pending;
        pending = null;
        if (completion != null) completion.completeExceptionally(error);
        if (failure != null) failure.accept(error);
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
        finishRedraws();
        frame = null;
        surface = null;
        if (!ownsOutput) output.setSurface(null, 0, 0);
        content.release();
        cancelPending("Shell host closed");
    }

    private static void checkThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Shell host requires main thread");
    }
}
