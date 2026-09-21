package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.Surface;
import android.view.ViewConfiguration;

/** Exercises the actual Android view entry points without Desktop or injected system input. */
public final class HostedInputInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
            runOnMainSync(() -> {
                try { verify(); }
                catch (RuntimeException | AssertionError error) { failure.set(error); }
            });
            if (failure.get() != null) throw new AssertionError(failure.get());
            result.putString("hosted_input", "PASS touch, mouse-source fingers, raw touchpad, mouse hover/click/wheel/drag, focus loss, output lifecycle, cursor shape/scale/hide/reset");
            finish(Activity.RESULT_OK, result);
        } catch (RuntimeException | AssertionError error) {
            result.putString("hosted_input", "FAIL " + error);
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void verify() {
        HostedSurfaceView view = new HostedSurfaceView(getTargetContext());
        view.layout(0, 0, 1000, 1000);
        Output output = new Output();
        view.bind(output);
        view.frame(1000, 1000);
        float distance = ViewConfiguration.get(getTargetContext()).getScaledVerticalScrollFactor() * 2;
        for (int source : new int[] {InputDevice.SOURCE_TOUCHSCREEN, InputDevice.SOURCE_MOUSE, InputDevice.SOURCE_TOUCHPAD}) {
            int oldScroll = output.scrolls;
            send(view, source, 1, 0, 0, 1, 200, 200);
            require(output.presses == 0, "premature press before gesture recognition");
            send(view, source, 1, 5 | (1 << 8), 0, 2, 200, 200);
            send(view, source, 1, 2, 0, 2, 200, 200 + distance);
            send(view, source, 1, 6 | (1 << 8), 0, 2, 200, 200 + distance);
            send(view, source, 1, 1, 0, 1, 200, 200 + distance);
            require(output.scrolls > oldScroll && output.presses == 0, "two fingers must scroll, not select");
        }
        send(view, InputDevice.SOURCE_TOUCHSCREEN, 1, 0, 0, 1, 200, 200);
        send(view, InputDevice.SOURCE_TOUCHSCREEN, 1, 1, 0, 1, 200, 200);
        require(output.presses == 1 && output.releases == 1, "touch tap");
        send(view, InputDevice.SOURCE_MOUSE, 3, 0, 1, 1, 200, 200);
        send(view, InputDevice.SOURCE_MOUSE, 3, 11, 1, 1, 200, 200);
        send(view, InputDevice.SOURCE_MOUSE, 3, 2, 1, 1, 400, 300);
        require(output.x == .4f && view.canStartContentDrag(), "mouse drag");
        view.onWindowFocusChanged(false);
        send(view, InputDevice.SOURCE_MOUSE, 3, 2, 1, 1, 500, 300);
        send(view, InputDevice.SOURCE_MOUSE, 3, 1, 0, 1, 500, 300);
        require(output.presses == 2 && output.releases == 2, "focus loss releases once");
        int oldScroll = output.scrolls;
        send(view, InputDevice.SOURCE_MOUSE, 3, 8, 0, 1, 300, 300);
        require(output.scrolls == oldScroll + 1, "ordinary wheel");
        verifyHoverClick(view, output);
        verifyCursor(view);
        view.release();
        require(output.closed, "output released");
        require(view.getPointerIcon() == null, "output release resets cursor");
        Output replacement = new Output();
        view.bind(replacement);
        send(view, InputDevice.SOURCE_TOUCHSCREEN, 1, 0, 0, 1, 200, 200);
        send(view, InputDevice.SOURCE_TOUCHSCREEN, 1, 1, 0, 1, 200, 200);
        require(replacement.presses == 0, "new output cannot use stale geometry");
        view.release();
    }

    private static void verifyHoverClick(HostedSurfaceView view, Output output) {
        int presses = output.presses, releases = output.releases;
        send(view, InputDevice.SOURCE_MOUSE, 3, MotionEvent.ACTION_HOVER_ENTER, 0, 1, 200, 200);
        send(view, InputDevice.SOURCE_MOUSE, 3, MotionEvent.ACTION_HOVER_MOVE, 0, 1, 300, 200);
        send(view, InputDevice.SOURCE_MOUSE, 3, MotionEvent.ACTION_HOVER_EXIT, 1, 1, 300, 200);
        require(output.presses == presses && output.releases == releases, "hover exit is not a press");
        send(view, InputDevice.SOURCE_MOUSE, 3, MotionEvent.ACTION_DOWN, 1, 1, 300, 200);
        send(view, InputDevice.SOURCE_MOUSE, 3, MotionEvent.ACTION_BUTTON_PRESS, 1, 1, 300, 200);
        send(view, InputDevice.SOURCE_MOUSE, 3, MotionEvent.ACTION_BUTTON_RELEASE, 0, 1, 300, 200);
        send(view, InputDevice.SOURCE_MOUSE, 3, MotionEvent.ACTION_UP, 0, 1, 300, 200);
        send(view, InputDevice.SOURCE_MOUSE, 3, MotionEvent.ACTION_HOVER_ENTER, 0, 1, 300, 200);
        require(output.presses == presses + 1 && output.releases == releases + 1, "one physical click, no duplicate edges");
    }

    private void verifyCursor(HostedSurfaceView view) {
        Bitmap image = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
        image.eraseColor(0x80ff0000);
        view.cursor(image, 31, 31, false);
        PointerIcon icon = view.getPointerIcon();
        require(icon != null, "custom pointer installed");
        require(resolveCursor(view, 500, 500) == icon, "content uses custom pointer");
        view.frame(2000, 1000);
        PointerIcon scaled = view.getPointerIcon();
        require(scaled != null && scaled != icon, "content scaling updates pointer");
        require(resolveCursor(view, 500, 100) == null, "letterbox uses Android default");
        require(resolveCursor(view, 500, 500) == scaled, "scaled content uses custom pointer");
        view.beginContentDrag();
        require(resolveCursor(view, 500, 500) == null, "Android owns drag pointer");
        view.endContentDrag();
        view.cursor(null, 0, 0, true);
        require(view.getPointerIcon().equals(PointerIcon.getSystemIcon(getTargetContext(), PointerIcon.TYPE_NULL)),
                "guest hides existing Android pointer");
        view.cursor(null, 0, 0, false);
        require(view.getPointerIcon() == null, "default cursor restored");
        view.cursor(image, 0, 0, false);
    }

    private static PointerIcon resolveCursor(HostedSurfaceView view, float x, float y) {
        MotionEvent event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_HOVER_MOVE, x, y, 0);
        try { return view.onResolvePointerIcon(event, 0); }
        finally { event.recycle(); }
    }

    private static void send(HostedSurfaceView view, int source, int tool, int action, int buttons, int count, float x, float y) {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[count];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[count];
        for (int i = 0; i < count; i++) {
            properties[i] = new MotionEvent.PointerProperties();
            properties[i].id = i;
            properties[i].toolType = tool;
            coords[i] = new MotionEvent.PointerCoords();
            coords[i].x = x + i * 100;
            coords[i].y = y;
            coords[i].pressure = 1;
            if (action == MotionEvent.ACTION_SCROLL) coords[i].setAxisValue(MotionEvent.AXIS_VSCROLL, 1);
        }
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action, count, properties, coords, 0, buttons,
                1, 1, 0, 0, source, 0);
        try {
            if ((source & InputDevice.SOURCE_CLASS_POSITION) != 0 || action >= MotionEvent.ACTION_SCROLL)
                view.onGenericMotionEvent(event);
            else view.onTouchEvent(event);
        } finally { event.recycle(); }
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static final class Output implements HostedSurfaceOutput {
        int presses, releases, scrolls;
        float x;
        boolean closed;
        public void setSurface(Surface surface, int width, int height) { }
        public void focus() { }
        public void pointer(float x, float y) { this.x = x; }
        public void button(float x, float y, Button button, boolean down) { if (down) presses++; else releases++; }
        public void scroll(float x, float y, float h, float v) { scrolls++; }
        public void key(int key, int scan, boolean down) { }
        public void text(String text) { }
        public void close() { closed = true; }
    }
}
