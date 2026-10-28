package io.github.mekhontsev.magicdesk;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.SparseIntArray;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

/** Android input, IME and Surface lifetime, independent of the guest display protocol. */
final class HostedSurfaceView extends SurfaceView implements SurfaceHolder.Callback2 {
    interface SurfaceBinding {
        void changed(android.view.Surface surface, int width, int height);
        default void redraw(Runnable finished) { finished.run(); }
    }
    private final HostedPointerInput pointerInput;
    private final HostedPointerCapture pointerCapture;
    private final HostedCursor cursor = new HostedCursor();
    private HostedSurfaceOutput output;
    private LinuxGamepads gamepads;
    private SurfaceBinding surfaceBinding;
    private boolean inputAllowed = true;
    private boolean keyboardAllowed = true;
    private int frameWidth, frameHeight;
    private HostedViewport viewport = HostedViewport.EMPTY;
    private boolean contentDrag;
    private Runnable beforeInteraction;
    private java.util.function.Consumer<int[]> screenOrigin;
    private boolean ownsOutput = true;
    private Runnable focusBoundary;
    private SparseIntArray keys = new SparseIntArray();
    private boolean borrowedKeyboard;
    private long keyboardGeneration;
    private HostedTextInputConnection textConnection;
    private java.util.function.Predicate<MotionEvent> windowMotion;
    private float rawX, rawY;

    HostedSurfaceView(Context context) {
        super(context);
        pointerInput = new HostedPointerInput(this);
        pointerCapture = new HostedPointerCapture(new HostedPointerCapture.Host() {
            @Override public void request() { requestPointerCapture(); }
            @Override public void release() { releasePointerCapture(); }
        });
        setFocusable(true);
        setFocusableInTouchMode(true);
        addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (textConnection != null) textConnection.cursorChanged();
        });
        getHolder().addCallback(this);
    }

    void bind(HostedSurfaceOutput next) {
        bind(next, next == null ? null : next::setSurface);
    }

    void bind(HostedSurfaceOutput next, SurfaceBinding surfaces) {
        bind(next, surfaces, true);
    }

    void bind(HostedSurfaceOutput next, SurfaceBinding surfaces, boolean ownsOutput) {
        release();
        this.ownsOutput = ownsOutput;
        output = next;
        surfaceBinding = next == null ? null : java.util.Objects.requireNonNull(surfaces);
        pointerInput.bind(next);
        updatePointerCapture();
        pointerCapture.bind(next);
        if (output != null && getHolder().getSurface().isValid() && getWidth() > 0 && getHeight() > 0)
            attachSurface(getHolder(), getWidth(), getHeight());
    }

    void frame(int width, int height) {
        frameWidth = width;
        frameHeight = height;
        var next = HostedViewport.fit(getWidth(), getHeight(), width, height);
        boolean moved = !next.equals(viewport);
        viewport = next;
        pointerInput.viewport(viewport);
        updatePointerCapture();
        updateCursor();
        if (moved && textConnection != null) textConnection.cursorChanged();
    }

    void gamepads(LinuxGamepads lease) {
        if (gamepads != null) gamepads.focus(this, false);
        gamepads = lease;
        updateGamepadFocus();
    }

    private void updateGamepadFocus() {
        if (gamepads != null) gamepads.focus(this,
                inputAllowed && output != null && isFocused() && hasWindowFocus());
    }

    private void updatePointerCapture() {
        if (pointerCapture != null) pointerCapture.eligible(inputAllowed && keyboardAllowed && !contentDrag
                && output != null && isAttachedToWindow() && isShown() && isFocused()
                && hasWindowFocus() && viewport.available() && getHolder().getSurface().isValid());
    }

    @Override public void onPointerCaptureChange(boolean captured) {
        super.onPointerCaptureChange(captured);
        pointerInput.release();
        pointerCapture.changed(captured);
        DesktopAutomationEventJournal.record("hosted_input", "pointer_capture", true,
                "captured=" + captured + " serial=" + pointerCapture.serial());
    }

    @Override public boolean onCapturedPointerEvent(MotionEvent event) {
        return inputAllowed && pointerInput.captured(event, pointerCapture.serial());
    }

    @Override protected void onDetachedFromWindow() {
        pointerCapture.eligible(false);
        super.onDetachedFromWindow();
    }

    @Override protected void onVisibilityChanged(android.view.View changed, int visibility) {
        super.onVisibilityChanged(changed, visibility);
        updatePointerCapture();
    }

    void cursor(android.graphics.Bitmap image, int hotspotX, int hotspotY, boolean hidden) {
        cursor.set(image, hotspotX, hotspotY, hidden);
        updateCursor();
    }

    private void updateCursor() {
        setPointerIcon(cursor.icon(getContext(), frameWidth > 0 ? viewport.width() / frameWidth : 0));
    }

    @Override public PointerIcon onResolvePointerIcon(MotionEvent event, int pointerIndex) {
        float x = viewport.contentX(event.getX(pointerIndex)), y = viewport.contentY(event.getY(pointerIndex));
        if (contentDrag || !viewport.available() || x < 0 || y < 0 || x >= 1 || y >= 1) return null;
        return getPointerIcon();
    }

    record Geometry(int contentWidth, int contentHeight, float left, float top, float right, float bottom) { }

    /** On-demand UI-thread observation; coordinates are on the containing Android display. */
    Geometry geometry() {
        int[] location = new int[2];
        locateOnScreen(location);
        float left = location[0] + viewport.left(), top = location[1] + viewport.top();
        return new Geometry(frameWidth, frameHeight, left, top, left + viewport.width(), top + viewport.height());
    }

    /** Embedded View roots have local coordinates; their placement owner supplies the display origin. */
    void screenOrigin(java.util.function.Consumer<int[]> origin) { screenOrigin = origin; }

    void locateOnScreen(int[] location) {
        if (screenOrigin == null) getLocationOnScreen(location);
        else screenOrigin.accept(location);
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        viewport = HostedViewport.fit(width, height, frameWidth, frameHeight);
        pointerInput.viewport(viewport);
        updateCursor();
    }

    void release() {
        if (textConnection != null) { textConnection.dispose(); textConnection = null; }
        releaseInput();
        pointerCapture.bind(null);
        gamepads = null;
        if (output != null) {
            if (ownsOutput) output.close();
            else if (surfaceBinding != null) surfaceBinding.changed(null, 0, 0);
        }
        output = null;
        surfaceBinding = null;
        pointerInput.bind(null);
        frameWidth = frameHeight = 0;
        viewport = HostedViewport.EMPTY;
        pointerInput.viewport(viewport);
        cursor(null, 0, 0, false);
    }

    void allowInput(boolean allowed) {
        if (inputAllowed && !allowed) releaseInput();
        inputAllowed = allowed;
        updatePointerCapture();
        updateGamepadFocus();
    }

    void allowKeyboard(boolean allowed) {
        if (keyboardAllowed == allowed) return;
        keyboardAllowed = allowed;
        updatePointerCapture();
        if (!allowed) {
            releaseKeys();
            if (focusBoundary != null) focusBoundary.run();
            else if (output != null) output.blur();
            clearFocus();
        }
        setFocusable(allowed);
        setFocusableInTouchMode(allowed);
    }

    private void releaseInput() {
        pointerCapture.cancel();
        if (gamepads != null) gamepads.focus(this, false);
        if (!contentDrag) pointerInput.release();
        releaseKeys();
    }

    private void releaseKeys() {
        if (focusBoundary != null) return;
        releaseHeldKeys();
    }

    private void releaseHeldKeys() {
        keyboardGeneration++;
        if (output != null) {
            for (int i = 0; i < keys.size(); i++) output.key(keys.keyAt(i), keys.valueAt(i), false);
        }
        keys.clear();
    }

    @Override protected void onFocusChanged(boolean gain, int direction, android.graphics.Rect previous) {
        super.onFocusChanged(gain, direction, previous);
        updatePointerCapture();
        updateGamepadFocus();
        if (gain && keyboardAllowed && inputAllowed && output != null && hasWindowFocus()) {
            if (focusBoundary != null) focusBoundary.run(); else output.focus();
        }
        else if (!gain && focusBoundary == null) releaseInput();
        if (!gain && focusBoundary != null) focusBoundary.run();
    }

    @Override public void onWindowFocusChanged(boolean gain) {
        super.onWindowFocusChanged(gain);
        updatePointerCapture();
        updateGamepadFocus();
        if (!gain) {
            if (focusBoundary == null) releaseInput();
            if (focusBoundary == null && output != null) output.blur();
        } else if (focusBoundary == null && keyboardAllowed && inputAllowed && output != null && isFocused()) output.focus();
        if (focusBoundary != null) focusBoundary.run();
    }

    void focusBoundary(Runnable callback) { focusBoundary = callback; }

    void shareKeyboard(HostedSurfaceView owner) {
        keys = owner.keys;
        borrowedKeyboard = true;
    }

    void leaveKeyboardFamily() {
        if (borrowedKeyboard) keys = new SparseIntArray();
        borrowedKeyboard = false;
    }

    void releaseFamilyInput() {
        pointerCapture.cancel();
        if (!contentDrag) pointerInput.release();
        releaseHeldKeys();
    }

    android.graphics.PointF contentPoint(float x, float y) {
        return new android.graphics.PointF(viewport.contentX(x), viewport.contentY(y));
    }

    boolean canStartContentDrag() { return output != null && !contentDrag && pointerInput.dragging(); }
    void beforeInteraction(Runnable action) { beforeInteraction = action; }
    void beginContentDrag() { contentDrag = true; updatePointerCapture(); pointerInput.handoff(); }
    void endContentDrag() { contentDrag = false; updatePointerCapture(); pointerInput.handoff(); }

    @SuppressLint("ClickableViewAccessibility") // HostedPointerInput calls performClick for completed taps.
    @Override public boolean onTouchEvent(MotionEvent event) {
        return motion(event);
    }

    @Override public boolean performClick() { super.performClick(); return true; }

    @Override public boolean onGenericMotionEvent(MotionEvent event) {
        return motion(event) || super.onGenericMotionEvent(event);
    }

    private boolean motion(MotionEvent event) {
        if (!inputAllowed) return false;
        if (output != null && gamepads != null && gamepads.motion(this, event)) return true;
        rawX = event.getRawX(); rawY = event.getRawY();
        if (windowMotion != null && windowMotion.test(event)) return true;
        if (contentDrag) return true;
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN || event.getActionMasked() == MotionEvent.ACTION_BUTTON_PRESS) {
            if (beforeInteraction != null) beforeInteraction.run();
            if (keyboardAllowed) requestFocus();
        }
        return pointerInput.event(event);
    }

    @Override public boolean onKeyDown(int key, KeyEvent event) { return key(event, true) || super.onKeyDown(key, event); }
    @Override public boolean onKeyUp(int key, KeyEvent event) { return key(event, false) || super.onKeyUp(key, event); }

    HostedKeyboardShortcuts.Target keyboardShortcuts() {
        if (!keyboardAllowed || !inputAllowed || output == null || !isAttachedToWindow()
                || !isShown() || !isFocused()) return null;
        final long generation = keyboardGeneration;
        return new HostedKeyboardShortcuts.Target() {
            private final java.util.Map<Integer, KeyEvent> held = new java.util.LinkedHashMap<>();
            private boolean valid() {
                return keyboardGeneration == generation && keyboardAllowed && inputAllowed
                        && output != null && isAttachedToWindow() && isShown() && isFocused();
            }
            @Override public boolean key(KeyEvent event) {
                if (!valid()) return false;
                boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
                if (down) held.put(event.getKeyCode(), new KeyEvent(event));
                else held.remove(event.getKeyCode());
                sendKey(event, down);
                return true;
            }
            @Override public void release() {
                if (keyboardGeneration == generation && output != null)
                    for (KeyEvent event : held.values()) sendKey(event, false);
                held.clear();
            }
        };
    }

    private boolean key(KeyEvent event, boolean down) {
        if (inputAllowed && output != null && gamepads != null && gamepads.key(this, event)) return true;
        if (!keyboardAllowed || !inputAllowed || output == null || event.getKeyCode() == KeyEvent.KEYCODE_BACK || event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP
                || event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN) return false;
        sendKey(event, down);
        return true;
    }

    private void sendKey(KeyEvent event, boolean down) {
        int scan = event.getScanCode();
        if (down) keys.put(event.getKeyCode(), scan);
        else keys.delete(event.getKeyCode());
        output.key(event.getKeyCode(), scan, down);
    }

    @Override public boolean onCheckIsTextEditor() { return keyboardAllowed && inputAllowed && output != null && output.supportsText(); }

    void windowMotion(java.util.function.Predicate<MotionEvent> receiver) { windowMotion = receiver; }
    android.graphics.PointF pressedPointer() {
        return pointerInput.dragging() ? new android.graphics.PointF(rawX, rawY) : null;
    }
    void cancelPointer() { pointerInput.release(); }
    void beginWindowGesture() { pointerInput.windowGesture(); }

    void textInputChanged() {
        android.view.inputmethod.InputMethodManager manager = getContext().getSystemService(
                android.view.inputmethod.InputMethodManager.class);
        if (manager != null && hasWindowFocus()) {
            if (textConnection != null && textConnection.sameEditor()) {
                textConnection.update(manager, this);
                return;
            }
            manager.restartInput(this);
            if (onCheckIsTextEditor()) manager.showSoftInput(this, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            else manager.hideSoftInputFromWindow(getWindowToken(), 0);
        }
    }

    @Override public InputConnection onCreateInputConnection(EditorInfo info) {
        if (!onCheckIsTextEditor()) return null;
        HostedSurfaceOutput target = output;
        textConnection = new HostedTextInputConnection(this, target,
                () -> keyboardAllowed && inputAllowed && output == target,
                event -> key(event, event.getAction() == KeyEvent.ACTION_DOWN), info, textConnection);
        return textConnection;
    }

    @Override public void surfaceCreated(SurfaceHolder holder) { }
    private void attachSurface(SurfaceHolder holder, int width, int height) {
        if (output == null) return;
        surfaceBinding.changed(holder.getSurface(), width, height);
        updatePointerCapture();
        // Window focus may arrive before the first Surface, or remain held while it is replaced.
        if (keyboardAllowed && inputAllowed && hasWindowFocus() && isFocused()) {
            if (focusBoundary != null) focusBoundary.run(); else output.focus();
        }
    }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        attachSurface(holder, width, height);
    }
    @Override public void surfaceRedrawNeeded(SurfaceHolder holder) {
        surfaceRedrawNeededAsync(holder, () -> {});
    }
    @Override public void surfaceRedrawNeededAsync(SurfaceHolder holder, Runnable finished) {
        if (surfaceBinding == null) finished.run();
        else surfaceBinding.redraw(finished);
    }
    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        releaseInput();
        if (surfaceBinding != null) surfaceBinding.changed(null, 0, 0);
    }
}
