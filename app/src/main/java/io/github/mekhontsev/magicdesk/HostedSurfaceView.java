package io.github.mekhontsev.magicdesk;

import android.annotation.SuppressLint;
import android.content.Context;
import android.text.InputType;
import android.util.SparseIntArray;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

/** Android input, IME and Surface lifetime, independent of the guest display protocol. */
final class HostedSurfaceView extends SurfaceView implements SurfaceHolder.Callback {
    interface SurfaceBinding {
        void changed(android.view.Surface surface, int width, int height);
    }
    private final HostedPointerInput pointerInput;
    private final HostedCursor cursor = new HostedCursor();
    private HostedSurfaceOutput output;
    private SurfaceBinding surfaceBinding;
    private boolean inputAllowed = true;
    private int frameWidth, frameHeight;
    private HostedViewport viewport = HostedViewport.EMPTY;
    private boolean contentDrag;
    private Runnable beforeInteraction;
    private final SparseIntArray keys = new SparseIntArray();

    HostedSurfaceView(Context context) {
        super(context);
        pointerInput = new HostedPointerInput(this);
        setFocusable(true);
        setFocusableInTouchMode(true);
        getHolder().addCallback(this);
    }

    void bind(HostedSurfaceOutput next) {
        bind(next, next == null ? null : next::setSurface);
    }

    void bind(HostedSurfaceOutput next, SurfaceBinding surfaces) {
        release();
        output = next;
        surfaceBinding = next == null ? null : java.util.Objects.requireNonNull(surfaces);
        pointerInput.bind(next);
        if (output != null && getHolder().getSurface().isValid() && getWidth() > 0 && getHeight() > 0)
            attachSurface(getHolder(), getWidth(), getHeight());
    }

    void frame(int width, int height) {
        frameWidth = width;
        frameHeight = height;
        viewport = HostedViewport.fit(getWidth(), getHeight(), width, height);
        pointerInput.viewport(viewport);
        updateCursor();
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
        getLocationOnScreen(location);
        float left = location[0] + viewport.left(), top = location[1] + viewport.top();
        return new Geometry(frameWidth, frameHeight, left, top, left + viewport.width(), top + viewport.height());
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        viewport = HostedViewport.fit(width, height, frameWidth, frameHeight);
        pointerInput.viewport(viewport);
        updateCursor();
    }

    void release() {
        releaseInput();
        if (output != null) output.close();
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
    }

    private void releaseInput() {
        if (!contentDrag) pointerInput.release();
        if (output != null) {
            for (int i = 0; i < keys.size(); i++) output.key(keys.keyAt(i), keys.valueAt(i), false);
        }
        keys.clear();
    }

    @Override protected void onFocusChanged(boolean gain, int direction, android.graphics.Rect previous) {
        super.onFocusChanged(gain, direction, previous);
        if (gain && inputAllowed && output != null) output.focus();
        else if (!gain) releaseInput();
    }

    @Override public void onWindowFocusChanged(boolean gain) {
        super.onWindowFocusChanged(gain);
        if (!gain) releaseInput();
        else if (inputAllowed && output != null) output.focus();
    }

    android.graphics.PointF contentPoint(float x, float y) {
        return new android.graphics.PointF(viewport.contentX(x), viewport.contentY(y));
    }

    boolean canStartContentDrag() { return output != null && !contentDrag && pointerInput.dragging(); }
    void beforeInteraction(Runnable action) { beforeInteraction = action; }
    void beginContentDrag() { contentDrag = true; pointerInput.handoff(); }
    void endContentDrag() { contentDrag = false; pointerInput.handoff(); }

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
        if (contentDrag) return true;
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN || event.getActionMasked() == MotionEvent.ACTION_BUTTON_PRESS) {
            if (beforeInteraction != null) beforeInteraction.run();
            requestFocus();
        }
        return pointerInput.event(event);
    }

    @Override public boolean onKeyDown(int key, KeyEvent event) { return key(event, true) || super.onKeyDown(key, event); }
    @Override public boolean onKeyUp(int key, KeyEvent event) { return key(event, false) || super.onKeyUp(key, event); }

    private boolean key(KeyEvent event, boolean down) {
        if (!inputAllowed || output == null || event.getKeyCode() == KeyEvent.KEYCODE_BACK || event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP
                || event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN) return false;
        int scan = event.getScanCode();
        if (down) keys.put(event.getKeyCode(), scan);
        else keys.delete(event.getKeyCode());
        output.key(event.getKeyCode(), scan, down);
        return true;
    }

    private void press(int key) {
        if (inputAllowed && output != null) { output.key(key, 0, true); output.key(key, 0, false); }
    }

    @Override public boolean onCheckIsTextEditor() { return inputAllowed && (output == null || output.supportsText()); }

    @Override public InputConnection onCreateInputConnection(EditorInfo info) {
        if (!onCheckIsTextEditor()) return null;
        info.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        info.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_ACTION_NONE;
        return new BaseInputConnection(this, true) {
            @Override public boolean commitText(CharSequence text, int cursor) {
                if (inputAllowed && output != null) output.text(text.toString());
                getEditable().clear();
                return true;
            }
            @Override public boolean finishComposingText() {
                if (inputAllowed && output != null && getEditable().length() > 0) output.text(getEditable().toString());
                getEditable().clear();
                return super.finishComposingText();
            }
            @Override public boolean deleteSurroundingText(int before, int after) {
                if (getEditable().length() > 0) return super.deleteSurroundingText(before, after);
                for (int i = 0; i < Math.min(1024, before); i++) press(KeyEvent.KEYCODE_DEL);
                for (int i = 0; i < Math.min(1024, after); i++) press(KeyEvent.KEYCODE_FORWARD_DEL);
                return true;
            }
            @Override public boolean sendKeyEvent(KeyEvent event) { return key(event, event.getAction() == KeyEvent.ACTION_DOWN); }
            @Override public boolean performEditorAction(int action) { press(KeyEvent.KEYCODE_ENTER); return true; }
        };
    }

    @Override public void surfaceCreated(SurfaceHolder holder) { }
    private void attachSurface(SurfaceHolder holder, int width, int height) {
        if (output == null) return;
        surfaceBinding.changed(holder.getSurface(), width, height);
        // Window focus may arrive before the first Surface, or remain held while it is replaced.
        if (inputAllowed && hasWindowFocus() && isFocused()) output.focus();
    }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        attachSurface(holder, width, height);
    }
    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        releaseInput();
        if (surfaceBinding != null) surfaceBinding.changed(null, 0, 0);
    }
}
