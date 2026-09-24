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
import android.view.inputmethod.EditorInfo;

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
            result.putString("hosted_input", "PASS touch, mouse-source fingers, raw touchpad, mouse hover/click/wheel/drag, focus loss, output lifecycle, cursor shape/scale/hide/reset, IME composition/commit");
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
        verifyText(view, output);
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

    private static void verifyText(HostedSurfaceView view, Output output) {
        var connection = view.onCreateInputConnection(new EditorInfo());
        require(connection != null, "text-enabled output has an InputConnection");
        String composed = "Unicode \u0416 \ud83d\ude00";
        connection.setComposingText(composed, 1);
        require(output.preedit.equals(composed) && output.cursor == composed.length(), "Unicode preedit and UTF-16 cursor");
        require(output.commits.isEmpty(), "composition is not committed prematurely");
        connection.commitText("committed", 1);
        connection.finishComposingText();
        require(output.commits.equals(java.util.List.of("committed")), "commit is delivered exactly once");
        connection.setComposingText("finish", 1);
        connection.finishComposingText();
        require(output.commits.equals(java.util.List.of("committed", "finish")), "finish commits retained composition");
        connection.setComposingText("cancel", 1);
        connection.setComposingText("", 1);
        connection.finishComposingText();
        require(output.preedit.isEmpty() && output.commits.size() == 2, "empty preedit clears without inserting text");
        output.clientPreedit = false;
        connection.setComposingText("android", 1);
        require(output.commits.size() == 2, "non-preedit clients retain composition in Android");
        connection.finishComposingText();
        require(output.commits.get(2).equals("android"), "non-preedit client receives finished text");
        output.textEnabled = false;
        require(view.onCreateInputConnection(new EditorInfo()) == null, "unsupported client has no text editor");
        output.textEnabled = true;
        output.state = new io.github.mekhontsev.magicdesk.hosted.HostedTextState(11, 1,
                io.github.mekhontsev.magicdesk.hosted.HostedTextState.Purpose.EMAIL, 1, "a\u0416\ud83d\ude00z", 4, 2);
        EditorInfo info = new EditorInfo();
        connection = view.onCreateInputConnection(info);
        require(info.initialSelStart == 2 && info.initialSelEnd == 4, "initial guest selection");
        require((info.inputType & android.text.InputType.TYPE_MASK_VARIATION) == android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                "field purpose selects Android editor type");
        require(connection.getTextBeforeCursor(20, 0).toString().equals("a\u0416"), "surrounding prefix");
        require(connection.getSelectedText(0).toString().equals("\ud83d\ude00"), "selected supplementary character");
        require(connection.getTextAfterCursor(20, 0).toString().equals("z"), "surrounding suffix");
        require(!connection.setSelection(0, 0), "unsupported remote selection is not fabricated");
        connection.setComposingText("edit", 1);
        require(connection.getTextBeforeCursor(20, 0).toString().equals("a\u0416edit"), "preedit overlays guest selection");
        require(connection.getTextAfterCursor(20, 0).toString().equals("z"), "preedit retains suffix");
        require(connection.deleteSurroundingText(1, 1), "deletion alongside composition");
        require(output.deletion.equals("1:1:false:edit:4"), "deletion retains the guest preedit atomically");
        require(output.editedState == output.state, "deletion carries the observed revision");
        require(connection.getTextBeforeCursor(20, 0).toString().equals("a\u0416edit"), "deletion does not consume the composition");
        var nextEditor = new io.github.mekhontsev.magicdesk.hosted.HostedTextState(12, 1,
                io.github.mekhontsev.magicdesk.hosted.HostedTextState.Purpose.PIN, 0, null, -1, -1);
        output.afterTextStateRead = () -> output.state = nextEditor;
        require(connection.commitText("racing", 1), "edit dispatched after its editor check");
        require(output.editedState.editor() == 11 && output.state.editor() == 12,
                "editor change during dispatch cannot retarget a queued edit");
        require(!connection.commitText("stale", 1), "old editor cannot type into a new field");
        require(!connection.deleteSurroundingText(1, 0), "old editor cannot delete in a new field");
        info = new EditorInfo();
        connection = view.onCreateInputConnection(info);
        require((info.inputType & android.text.InputType.TYPE_MASK_CLASS) == android.text.InputType.TYPE_CLASS_NUMBER,
                "PIN requests numeric keyboard");
        require((info.imeOptions & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0, "private field disables learning");
        output.state = null;
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
        boolean clientPreedit = true, textEnabled = true;
        String preedit = "";
        String deletion = "";
        int cursor;
        io.github.mekhontsev.magicdesk.hosted.HostedTextState state;
        Runnable afterTextStateRead;
        final java.util.List<String> commits = new java.util.ArrayList<>();
        public void setSurface(Surface surface, int width, int height) { }
        public void focus() { }
        public void pointer(float x, float y) { this.x = x; }
        public void button(float x, float y, Button button, boolean down) { if (down) presses++; else releases++; }
        public void scroll(float x, float y, float h, float v) { scrolls++; }
        public void key(int key, int scan, boolean down) { }
        io.github.mekhontsev.magicdesk.hosted.HostedTextState editedState;
        public void text(io.github.mekhontsev.magicdesk.hosted.HostedTextState editor, String text) {
            editedState = editor; commits.add(text);
        }
        public boolean supportsText() { return textEnabled; }
        public io.github.mekhontsev.magicdesk.hosted.HostedTextState textState() {
            var result = state;
            var hook = afterTextStateRead;
            afterTextStateRead = null;
            if (hook != null) hook.run();
            return result;
        }
        public boolean deleteText(io.github.mekhontsev.magicdesk.hosted.HostedTextState snapshot,
                int before, int after, boolean codePoints, String preedit, int cursor) {
            editedState = snapshot;
            deletion = before + ":" + after + ":" + codePoints + ":" + preedit + ":" + cursor;
            return state != null;
        }
        public boolean preedit(io.github.mekhontsev.magicdesk.hosted.HostedTextState editor, String text, int cursor) {
            editedState = editor;
            if (!clientPreedit) return false;
            this.preedit = text; this.cursor = cursor; return true;
        }
        public void close() { closed = true; }
    }
}
