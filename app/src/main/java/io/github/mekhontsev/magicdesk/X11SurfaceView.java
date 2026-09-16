package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.text.InputType;
import android.util.SparseIntArray;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import com.termux.x11.X11Session;

/** Public Android input and Surface lifetime for one borrowed X output. */
final class X11SurfaceView extends SurfaceView implements SurfaceHolder.Callback {
    private X11Session.Output output;
    private int frameWidth, frameHeight, buttons;
    private boolean touching;
    private boolean contentDrag;
    private Runnable beforeInteraction;
    private final SparseIntArray keys = new SparseIntArray();
    private float lastX, lastY;

    X11SurfaceView(Context context) {
        super(context);
        setFocusable(true);
        setFocusableInTouchMode(true);
        getHolder().addCallback(this);
    }

    void bind(X11Session.Output next) {
        release();
        output = next;
        if (output != null && getHolder().getSurface().isValid() && getWidth() > 0 && getHeight() > 0)
            output.setSurface(getHolder().getSurface(), getWidth(), getHeight());
    }

    void frame(int width, int height) { frameWidth = width; frameHeight = height; }

    void release() {
        releaseInput();
        if (output != null) output.close();
        output = null;
        frameWidth = frameHeight = 0;
    }

    private void releaseInput() {
        if (!contentDrag) {
            mouseButtons(0);
            if (touching && output != null) output.pointer(lastX, lastY, 1, false);
            touching = false;
        }
        if (output != null) {
            for (int i = 0; i < keys.size(); i++) output.key(keys.keyAt(i), keys.valueAt(i), false);
        }
        keys.clear();
    }

    @Override protected void onFocusChanged(boolean gain, int direction, android.graphics.Rect previous) {
        super.onFocusChanged(gain, direction, previous);
        if (gain && output != null) output.focus();
        else if (!gain) releaseInput();
    }

    @Override public void onWindowFocusChanged(boolean gain) {
        super.onWindowFocusChanged(gain);
        if (!gain) releaseInput();
        else if (output != null) output.focus();
    }

    private boolean pointer(MotionEvent event) {
        if (output == null || frameWidth < 1 || frameHeight < 1 || getWidth() < 1 || getHeight() < 1) return false;
        android.graphics.PointF point = contentPoint(event.getX(), event.getY());
        lastX = point.x;
        lastY = point.y;
        output.pointer(lastX, lastY, 0, false);
        return true;
    }

    android.graphics.PointF contentPoint(float x, float y) {
        if (frameWidth < 1 || frameHeight < 1 || getWidth() < 1 || getHeight() < 1) return new android.graphics.PointF();
        float scale = Math.min(getWidth() / (float) frameWidth, getHeight() / (float) frameHeight);
        float width = frameWidth * scale, height = frameHeight * scale;
        return new android.graphics.PointF((x - (getWidth() - width) / 2) / width,
                (y - (getHeight() - height) / 2) / height);
    }

    boolean canStartContentDrag() { return output != null && !contentDrag && (touching || (buttons & MotionEvent.BUTTON_PRIMARY) != 0); }
    void beforeInteraction(Runnable action) { beforeInteraction = action; }
    void beginContentDrag() { contentDrag = true; }
    void endContentDrag() { contentDrag = false; touching = false; buttons = 0; }

    private void mouseButtons(int next) {
        for (int button = 1; button <= 3; button++) {
            int mask = button == 1 ? MotionEvent.BUTTON_PRIMARY
                    : button == 2 ? MotionEvent.BUTTON_TERTIARY : MotionEvent.BUTTON_SECONDARY;
            if (output != null && ((buttons ^ next) & mask) != 0)
                output.pointer(lastX, lastY, button, (next & mask) != 0);
        }
        buttons = next;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (contentDrag) return true;
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN && beforeInteraction != null) beforeInteraction.run();
        if (!pointer(event)) return false;
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) requestFocus();
        if (event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE) {
            mouseButtons(action == MotionEvent.ACTION_CANCEL ? 0 : event.getButtonState());
        } else if (action == MotionEvent.ACTION_DOWN) {
            touching = true;
            output.pointer(lastX, lastY, 1, true);
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (touching) output.pointer(lastX, lastY, 1, false);
            touching = false;
            if (action == MotionEvent.ACTION_UP) performClick();
        }
        return true;
    }

    @Override public boolean performClick() { super.performClick(); return true; }

    @Override public boolean onGenericMotionEvent(MotionEvent event) {
        if (contentDrag) return true;
        if (event.getActionMasked() == MotionEvent.ACTION_BUTTON_PRESS && beforeInteraction != null) beforeInteraction.run();
        if (!pointer(event)) return super.onGenericMotionEvent(event);
        if (event.getActionMasked() == MotionEvent.ACTION_BUTTON_PRESS) requestFocus();
        mouseButtons(event.getButtonState());
        if (event.getActionMasked() == MotionEvent.ACTION_SCROLL) {
            wheel(event.getAxisValue(MotionEvent.AXIS_VSCROLL), 4, 5);
            wheel(event.getAxisValue(MotionEvent.AXIS_HSCROLL), 7, 6);
        }
        return true;
    }

    private void wheel(float amount, int positive, int negative) {
        int count = Math.min(32, (int) Math.ceil(Math.abs(amount)));
        int button = amount > 0 ? positive : negative;
        for (int i = 0; i < count; i++) {
            output.pointer(lastX, lastY, button, true);
            output.pointer(lastX, lastY, button, false);
        }
    }

    @Override public boolean onKeyDown(int key, KeyEvent event) { return key(event, true) || super.onKeyDown(key, event); }
    @Override public boolean onKeyUp(int key, KeyEvent event) { return key(event, false) || super.onKeyUp(key, event); }

    private boolean key(KeyEvent event, boolean down) {
        if (output == null || event.getKeyCode() == KeyEvent.KEYCODE_BACK || event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP
                || event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN) return false;
        int scan = event.getScanCode();
        // X11 keycodes are eight-bit; unsupported evdev codes use the Android mapping.
        if (scan < 0 || scan > 247) scan = 0;
        if (down) keys.put(event.getKeyCode(), scan);
        else keys.delete(event.getKeyCode());
        output.key(event.getKeyCode(), scan, down);
        return true;
    }

    private void press(int key) {
        if (output != null) { output.key(key, 0, true); output.key(key, 0, false); }
    }

    @Override public boolean onCheckIsTextEditor() { return true; }

    @Override public InputConnection onCreateInputConnection(EditorInfo info) {
        info.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        info.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_ACTION_NONE;
        return new BaseInputConnection(this, true) {
            @Override public boolean commitText(CharSequence text, int cursor) {
                if (output != null) output.text(text.toString());
                getEditable().clear();
                return true;
            }
            @Override public boolean finishComposingText() {
                if (output != null && getEditable().length() > 0) output.text(getEditable().toString());
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
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (output != null) output.setSurface(holder.getSurface(), width, height);
    }
    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        releaseInput();
        if (output != null) output.setSurface(null, 0, 0);
    }
}
