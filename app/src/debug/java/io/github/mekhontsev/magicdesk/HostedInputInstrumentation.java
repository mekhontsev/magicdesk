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
import io.github.mekhontsev.magicdesk.hosted.HostedTextState;

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
            result.putString("hosted_input", "PASS direct touch/pressure/cancel, pen/eraser/tilt/buttons, touchpad fallback, mouse hover/click/wheel/drag, focus loss, output lifecycle, cursor shape/scale/hide/reset, IME composition/commit");
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
        verifyComposingRegion(view, output);
        verifyDelayedTextAcknowledgements(view, output);
        view.release();
        require(output.closed, "output released");
        require(view.getPointerIcon() == null, "output release resets cursor");
        Output replacement = new Output();
        view.bind(replacement);
        send(view, InputDevice.SOURCE_TOUCHSCREEN, 1, 0, 0, 1, 200, 200);
        send(view, InputDevice.SOURCE_TOUCHSCREEN, 1, 1, 0, 1, 200, 200);
        require(replacement.presses == 0, "new output cannot use stale geometry");
        view.release();
        verifyDirect();
    }

    private void verifyDirect() {
        HostedSurfaceView view = new HostedSurfaceView(getTargetContext());
        view.layout(0, 0, 1000, 1000);
        Output output = new Output(); output.direct = true;
        view.bind(output); view.frame(1000, 1000);
        send(view, InputDevice.SOURCE_TOUCHSCREEN, 1, MotionEvent.ACTION_DOWN, 0, 1, 200, 300, .4f, 0, 0);
        send(view, InputDevice.SOURCE_TOUCHSCREEN, 1, MotionEvent.ACTION_POINTER_DOWN | 1 << 8, 0, 2, 200, 300, .7f, 0, 0);
        require(output.contacts == 3 && output.presses == 0 && output.pressure == .7f, "two direct contacts with pressure, no host mouse emulation");
        send(view, InputDevice.SOURCE_TOUCHSCREEN, 1, MotionEvent.ACTION_CANCEL, 0, 2, 200, 300);
        require(output.contacts == 0 && output.cancels == 1, "cancel releases all contacts");
        send(view, InputDevice.SOURCE_TOUCHSCREEN, 1, MotionEvent.ACTION_MOVE, 0, 1, 200, 300);
        require(output.contacts == 0, "late motion cannot restart touch");
        send(view, InputDevice.SOURCE_STYLUS, 2, MotionEvent.ACTION_HOVER_ENTER, 0, 1, 200, 300);
        require(output.proximity && output.pressure == 0 && output.tabletButtons == 0, "pen proximity without tip");
        send(view, InputDevice.SOURCE_STYLUS, 2, MotionEvent.ACTION_DOWN, MotionEvent.BUTTON_STYLUS_PRIMARY, 1, 200, 300,
                .6f, (float)Math.PI / 4, (float)Math.PI / 2);
        require(output.tabletButtons == 3 && output.pressure == .6f && Math.abs(output.tiltX + (float)Math.PI / 4) < .001f
                && Math.abs(output.tiltY) < .001f, "pen pressure, tilt and barrel button");
        send(view, InputDevice.SOURCE_STYLUS, 4, MotionEvent.ACTION_MOVE, 0, 1, 300, 400);
        require(output.eraser && view.canStartContentDrag(), "eraser tip and drag ownership");
        view.onWindowFocusChanged(false);
        require(!output.proximity && output.cancels == 2, "focus loss releases pen");
        int count = output.tablets;
        send(view, InputDevice.SOURCE_STYLUS, 4, MotionEvent.ACTION_UP, 0, 1, 300, 400);
        require(output.tablets == count, "late pen release cannot recreate proximity");
        send(view, InputDevice.SOURCE_TOUCHPAD, 1, MotionEvent.ACTION_DOWN, 0, 1, 200, 200);
        send(view, InputDevice.SOURCE_TOUCHPAD, 1, MotionEvent.ACTION_UP, 0, 1, 200, 200);
        require(output.presses == 1 && output.releases == 1 && output.contacts == 0, "touchpad retains pointer semantics");
        view.release();
    }

    private static void verifyText(HostedSurfaceView view, Output output) {
        var connection = view.onCreateInputConnection(new EditorInfo());
        require(connection != null, "text-enabled output has an InputConnection");
        String composed = "Unicode \u0416 \ud83d\ude00";
        connection.setComposingText(composed, 1);
        require(output.preedit.equals(composed) && output.cursor == composed.length(), "Unicode preedit and UTF-16 cursor");
        require(output.commits.isEmpty(), "composition is not committed prematurely");
        var candidate = view.onCreateInputConnection(new EditorInfo());
        require(connection.setComposingText(composed, 1), "candidate creation does not close Android's active connection");
        require(candidate.getTextBeforeCursor(100, 0).toString().equals(composed), "candidate observes the same editor composition");
        connection.closeConnection();
        require(!connection.commitText("closed", 1), "closed transport rejects edits");
        require(candidate.getTextBeforeCursor(100, 0).toString().equals(composed), "transport closure retains editor composition");
        connection = candidate;
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
        output.state = new io.github.mekhontsev.magicdesk.hosted.HostedTextState(11, 2,
                output.state.purpose(), output.state.hints(), output.state.surrounding(), 4, 2,
                new io.github.mekhontsev.magicdesk.hosted.HostedTextState.Caret(.25f, .5f, .26f, .6f));
        require(connection.requestCursorUpdates(android.view.inputmethod.InputConnection.CURSOR_UPDATE_MONITOR,
                android.view.inputmethod.InputConnection.CURSOR_UPDATE_FILTER_INSERTION_MARKER), "caret subscription");
        var caret = ((HostedTextInputConnection)connection).cursorInfo();
        require(caret != null && caret.getInsertionMarkerHorizontal() == .25f, "guest caret position");
        float[] point = {.25f, .5f};
        caret.getMatrix().mapPoints(point);
        var geometry = view.geometry();
        require(Math.abs(point[0] - (geometry.left() + (geometry.right() - geometry.left()) * .25f)) < .01f,
                "caret follows rendered viewport including letterbox");
        require(!connection.requestCursorUpdates(0, android.view.inputmethod.InputConnection.CURSOR_UPDATE_FILTER_CHARACTER_BOUNDS),
                "unavailable glyph geometry is not fabricated");
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
        output.state = new io.github.mekhontsev.magicdesk.hosted.HostedTextState(12, 2,
                io.github.mekhontsev.magicdesk.hosted.HostedTextState.Purpose.PIN, 0, "1234", 4, 4);
        require(connection.getTextBeforeCursor(100, 0).length() == 0, "guest PIN text is not published to Android queries");
        require(connection.takeSnapshot().getSurroundingText().getText().length() == 0, "guest PIN snapshot stays private");
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
        send(view, source, tool, action, buttons, count, x, y, 1, 0, 0);
    }

    private static void send(HostedSurfaceView view, int source, int tool, int action, int buttons, int count,
            float x, float y, float pressure, float tilt, float orientation) {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[count];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[count];
        for (int i = 0; i < count; i++) {
            properties[i] = new MotionEvent.PointerProperties();
            properties[i].id = i;
            properties[i].toolType = tool;
            coords[i] = new MotionEvent.PointerCoords();
            coords[i].x = x + i * 100;
            coords[i].y = y;
            coords[i].pressure = pressure;
            coords[i].setAxisValue(MotionEvent.AXIS_TILT, tilt);
            coords[i].setAxisValue(MotionEvent.AXIS_ORIENTATION, orientation);
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

    private static void verifyComposingRegion(HostedSurfaceView view, Output output) {
        output.state = new HostedTextState(20, 1, HostedTextState.Purpose.NORMAL,
                HostedTextState.SPELLCHECK, "тест раз раз", 12, 12);
        var connection = view.onCreateInputConnection(new EditorInfo());
        int commits = output.commits.size();
        output.deletion = "";
        require(connection.setComposingRegion(9, 12), "resume an existing word");
        require(connection.getTextBeforeCursor(20, 0).toString().equals("тест раз раз"), "marking does not duplicate the word");
        require(output.deletion.isEmpty(), "marking does not edit the guest");
        require(connection.finishComposingText() && output.commits.size() == commits, "finishing an untouched word does not insert it");

        require(connection.setComposingRegion(9, 12), "resume the word before Backspace");
        require(connection.setComposingText("ра", 1), "shorten the resumed word");
        require(output.deletion.equals("3:0:false:ра:2"), "Backspace replaces the original word with shortened preedit");
        require(connection.getTextBeforeCursor(20, 0).toString().equals("тест раз ра"), "old guest snapshot does not duplicate preedit");
        output.state = new HostedTextState(20, 2, HostedTextState.Purpose.NORMAL,
                HostedTextState.SPELLCHECK, "тест раз ", 9, 9);
        require(connection.setComposingText("р", 1), "another Backspace shortens the preedit");
        require(connection.setComposingText("", 1), "last Backspace clears the preedit");
        require(connection.finishComposingText() && output.commits.size() == commits, "no text inserted after deleting a word");

        output.state = new HostedTextState(20, 3, HostedTextState.Purpose.NORMAL,
                HostedTextState.SPELLCHECK, "а😀б", 3, 3);
        require(!connection.setComposingRegion(2, 4), "reject a region that splits a surrogate pair");
        require(connection.setComposingRegion(1, 4), "resume a word around the caret");
        require(connection.commitText("в", 1), "apply a correction without creating a preview");
        require(output.replacement.equals("2:1:в") && output.commits.size() == commits,
                "direct correction deletes the marked range and inserts once");

        require(!connection.setComposingRegion(1, 4), "cannot claim the same snapshot twice before acknowledgement");
        output.state = new HostedTextState(20, 4, HostedTextState.Purpose.NORMAL,
                HostedTextState.SPELLCHECK, "ав", 2, 2, null, true);
        require(connection.setComposingRegion(0, 2), "mark a region that may become stale");
        output.state = new HostedTextState(20, 5, HostedTextState.Purpose.NORMAL,
                HostedTextState.SPELLCHECK, "другой", 6, 6);
        require(!connection.commitText("старый", 1), "a stale range cannot insert or delete text");
        require(!connection.finishComposingText() && !connection.commitText("queued", 1)
                && output.commits.size() == commits, "queued calls on the retired connection cannot edit the new caret");
        connection = view.onCreateInputConnection(new EditorInfo());
        require(connection.getTextBeforeCursor(20, 0).toString().equals("другой")
                && connection.commitText("!", 1) && output.commits.get(commits).equals("!"),
                "fresh typing does not require manually finishing the stale word");
    }

    private static void verifyDelayedTextAcknowledgements(HostedSurfaceView view, Output output) {
        output.state = new HostedTextState(21, 1, HostedTextState.Purpose.NORMAL,
                HostedTextState.SPELLCHECK, "", 0, 0, null, true);
        var connection = view.onCreateInputConnection(new EditorInfo());
        connection.commitText("тест", 1);
        connection.commitText(" ", 1);
        var nextConnection = view.onCreateInputConnection(new EditorInfo());
        connection.closeConnection();
        connection.getTextBeforeCursor(20, 0);
        connection = nextConnection;
        connection.setComposingText("р", 1);
        require(connection.getTextBeforeCursor(20, 0).toString().equals("тест р"),
                "next word uses caret after both unacknowledged commits");
        output.state = new HostedTextState(21, 2, HostedTextState.Purpose.NORMAL,
                HostedTextState.SPELLCHECK, "тест", 4, 4, null, true);
        require(connection.getTextBeforeCursor(20, 0).toString().equals("тест р"),
                "partial acknowledgement does not move the next word backwards");
        output.state = new HostedTextState(21, 3, HostedTextState.Purpose.NORMAL,
                HostedTextState.SPELLCHECK, "тест ", 5, 5, null, true);
        require(connection.getTextBeforeCursor(20, 0).toString().equals("тест р"),
                "final acknowledgement preserves the active word");
        connection.setComposingText("раз", 1);
        connection.setComposingText("ра", 1);
        connection.finishComposingText();
        require(connection.getTextBeforeCursor(20, 0).toString().equals("тест ра"),
                "finalization predicts the shortened word only once");
        output.state = new HostedTextState(21, 4, HostedTextState.Purpose.NORMAL,
                HostedTextState.SPELLCHECK, "новый текст", 0, 0, null, false);
        require(connection.getTextAfterCursor(20, 0).toString().equals("новый текст"),
                "external change overrides predictions");
    }

    private static final class Output implements HostedSurfaceOutput {
        int presses, releases, scrolls;
        float x;
        boolean closed;
        boolean direct, proximity, eraser;
        int contacts, cancels, tablets, tabletButtons;
        float pressure, tiltX, tiltY;
        public boolean supportsTouch() { return direct; }
        public boolean supportsTablet() { return direct; }
        public void touch(int id, io.github.mekhontsev.magicdesk.hosted.HostedTouchPhase phase, float x, float y, float pressure) {
            if (phase == io.github.mekhontsev.magicdesk.hosted.HostedTouchPhase.END) contacts &= ~(1 << id);
            else contacts |= 1 << id;
            this.pressure = pressure;
        }
        public void tablet(boolean eraser, boolean proximity, float x, float y, float pressure, float tiltX, float tiltY, int buttons) {
            tablets++; this.eraser = eraser; this.proximity = proximity; this.pressure = pressure;
            this.tiltX = tiltX; this.tiltY = tiltY; tabletButtons = buttons;
        }
        public void cancelContacts() { cancels++; contacts = 0; proximity = false; tabletButtons = 0; }
        boolean clientPreedit = true, textEnabled = true;
        String preedit = "";
        String deletion = "";
        String replacement = "";
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
        public boolean replaceText(HostedTextState snapshot, int before, int after, String text) {
            editedState = snapshot;
            replacement = before + ":" + after + ":" + text;
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
