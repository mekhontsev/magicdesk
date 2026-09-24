package io.github.mekhontsev.magicdesk;

import android.text.InputType;
import android.text.Selection;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.SurroundingText;
import android.view.inputmethod.TextSnapshot;
import android.view.inputmethod.CursorAnchorInfo;
import io.github.mekhontsev.magicdesk.hosted.HostedTextState;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/** Android owns the composition; the guest owns committed text and its selection. */
final class HostedTextInputConnection extends BaseInputConnection {
    private final HostedSurfaceOutput output;
    private final HostedTextState editor;
    private final BooleanSupplier allowed;
    private final Predicate<KeyEvent> keys;
    private boolean closed;
    private final View view;
    private boolean monitorCursor;

    HostedTextInputConnection(View view, HostedSurfaceOutput output, BooleanSupplier allowed,
            Predicate<KeyEvent> keys, EditorInfo info) {
        super(view, true);
        this.view = view;
        this.output = output; this.allowed = allowed; this.keys = keys;
        editor = output.textState();
        info.inputType = inputType(editor);
        info.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_ACTION_NONE;
        if (editor != null && (editor.hints() & HostedTextState.LATIN) != 0) info.imeOptions |= EditorInfo.IME_FLAG_FORCE_ASCII;
        if (editor != null && editor.privateText()) info.imeOptions |= EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING;
        if (editor != null && editor.surrounding() != null) {
            info.initialSelStart = editor.anchor(); info.initialSelEnd = editor.cursor();
            info.setInitialSurroundingSubText(editor.surrounding(), 0);
        }
    }

    boolean sameEditor() {
        return sameEditor(output.textState());
    }

    private boolean sameEditor(HostedTextState state) {
        return !closed && allowed.getAsBoolean() && output.supportsText()
                && (editor == null ? state == null : editor.sameEditor(state));
    }

    private static int inputType(HostedTextState state) {
        if (state == null) return InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        int type = switch (state.purpose()) {
            case DIGITS -> InputType.TYPE_CLASS_NUMBER;
            case NUMBER -> InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED;
            case PIN -> InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD;
            case PHONE -> InputType.TYPE_CLASS_PHONE;
            case URL -> InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI;
            case EMAIL -> InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS;
            case NAME -> InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PERSON_NAME;
            case PASSWORD -> InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD;
            case DATE -> InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_DATE;
            case TIME -> InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_TIME;
            case DATETIME -> InputType.TYPE_CLASS_DATETIME;
            default -> InputType.TYPE_CLASS_TEXT;
        };
        if ((type & InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT) {
            int hints = state.hints();
            if ((hints & HostedTextState.HIDDEN) != 0) type = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD;
            if (state.privateText() || (hints & (HostedTextState.COMPLETION | HostedTextState.SPELLCHECK)) == 0)
                type |= InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
            else type |= InputType.TYPE_TEXT_FLAG_AUTO_CORRECT;
            if ((hints & HostedTextState.UPPERCASE) != 0) type |= InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS;
            else if ((hints & HostedTextState.TITLECASE) != 0) type |= InputType.TYPE_TEXT_FLAG_CAP_WORDS;
            else if ((hints & HostedTextState.AUTO_CAPITALIZE) != 0) type |= InputType.TYPE_TEXT_FLAG_CAP_SENTENCES;
            if ((hints & HostedTextState.MULTILINE) != 0) type |= InputType.TYPE_TEXT_FLAG_MULTI_LINE;
        }
        return type;
    }

    @Override public boolean setComposingText(CharSequence text, int cursor) {
        if (!sameEditor()) return false;
        boolean result = super.setComposingText(text, cursor);
        publishPreedit();
        return result;
    }
    private void publishPreedit() {
        output.preedit(editor, getEditable().toString(), Math.max(0, Selection.getSelectionEnd(getEditable())));
    }
    @Override public boolean commitText(CharSequence text, int cursor) {
        if (!sameEditor()) return false;
        output.text(editor, text.toString());
        getEditable().clear();
        return true;
    }
    @Override public boolean finishComposingText() {
        if (!sameEditor()) { getEditable().clear(); return false; }
        if (getEditable().length() > 0) output.text(editor, getEditable().toString());
        getEditable().clear();
        return super.finishComposingText();
    }
    @Override public void closeConnection() { closed = true; super.closeConnection(); }

    @Override public boolean deleteSurroundingText(int before, int after) { return delete(before, after, false); }
    @Override public boolean deleteSurroundingTextInCodePoints(int before, int after) { return delete(before, after, true); }
    private boolean delete(int before, int after, boolean codePoints) {
        var state = output.textState();
        if (!sameEditor(state) || before < 0 || after < 0) return false;
        // Android excludes the composition from surrounding-text deletion.
        // Preserve the guest preview in the same protocol edit as the deletion.
        if (output.deleteText(state, before, after, codePoints, getEditable().toString(),
                Math.max(0, Selection.getSelectionEnd(getEditable())))) return true;
        for (int i = 0; i < Math.min(1024, before); i++) press(KeyEvent.KEYCODE_DEL);
        for (int i = 0; i < Math.min(1024, after); i++) press(KeyEvent.KEYCODE_FORWARD_DEL);
        return true;
    }

    private record Context(String text, int anchor, int cursor, int composingStart, int composingEnd) { }
    private Context context() {
        var state = output.textState();
        if (!sameEditor(state)) state = null;
        String composition = getEditable().toString();
        int cursor = Math.max(0, Selection.getSelectionEnd(getEditable()));
        if (state == null || state.surrounding() == null)
            return new Context(composition, cursor, cursor, composition.isEmpty() ? -1 : 0,
                    composition.isEmpty() ? -1 : composition.length());
        if (composition.isEmpty()) return new Context(state.surrounding(), state.anchor(), state.cursor(), -1, -1);
        int start = state.start();
        return new Context(state.surrounding().substring(0, start) + composition + state.surrounding().substring(state.end()),
                start + cursor, start + cursor, start, start + composition.length());
    }
    @Override public CharSequence getTextBeforeCursor(int length, int flags) {
        var c = context(); int end = Math.min(c.anchor, c.cursor);
        return c.text.substring(end - Math.min(Math.max(length, 0), end), end);
    }
    @Override public CharSequence getTextAfterCursor(int length, int flags) {
        var c = context(); int start = Math.max(c.anchor, c.cursor);
        return c.text.substring(start, start + Math.min(Math.max(length, 0), c.text.length() - start));
    }
    @Override public CharSequence getSelectedText(int flags) {
        var c = context(); return c.anchor == c.cursor ? null : c.text.substring(Math.min(c.anchor, c.cursor), Math.max(c.anchor, c.cursor));
    }
    @Override public SurroundingText getSurroundingText(int before, int after, int flags) {
        var c = context();
        int low = Math.min(c.anchor, c.cursor), high = Math.max(c.anchor, c.cursor);
        int start = low - Math.min(Math.max(0, before), low);
        int end = high + Math.min(Math.max(0, after), c.text.length() - high);
        return new SurroundingText(c.text.substring(start, end), c.anchor - start, c.cursor - start, start);
    }
    @Override public ExtractedText getExtractedText(ExtractedTextRequest request, int flags) {
        var c = context(); var text = new ExtractedText();
        text.text = c.text; text.selectionStart = c.anchor; text.selectionEnd = c.cursor;
        text.partialStartOffset = text.partialEndOffset = -1;
        return text;
    }
    @Override public TextSnapshot takeSnapshot() {
        var c = context();
        return new TextSnapshot(new SurroundingText(c.text, c.anchor, c.cursor, 0), c.composingStart, c.composingEnd,
                TextUtils.getCapsMode(c.text, c.cursor, InputType.TYPE_TEXT_FLAG_CAP_SENTENCES));
    }
    @Override public int getCursorCapsMode(int modes) { var c = context(); return TextUtils.getCapsMode(c.text, c.cursor, modes); }
    @Override public boolean setSelection(int start, int end) {
        if (!sameEditor()) return false;
        var c = context();
        if (start == c.anchor && end == c.cursor) return true;
        if (start != end || c.composingStart < 0 || start < c.composingStart || end > c.composingEnd) return false;
        boolean result = super.setSelection(start - c.composingStart, end - c.composingStart);
        publishPreedit(); return result;
    }
    @Override public boolean setComposingRegion(int start, int end) { return false; }

    void update(InputMethodManager manager, View view) {
        var c = context();
        manager.updateSelection(view, c.anchor, c.cursor, c.composingStart, c.composingEnd);
        manager.invalidateInput(view);
        cursorChanged();
    }
    @Override public boolean requestCursorUpdates(int mode) { return requestCursorUpdates(mode, 0); }

    @Override public boolean requestCursorUpdates(int mode, int filter) {
        if (!sameEditor() || !(view instanceof HostedSurfaceView)
                || output.textState() == null
                || (mode & ~(CURSOR_UPDATE_IMMEDIATE | CURSOR_UPDATE_MONITOR)) != 0
                || (filter & ~CURSOR_UPDATE_FILTER_INSERTION_MARKER) != 0) return false;
        monitorCursor = (mode & CURSOR_UPDATE_MONITOR) != 0;
        if ((mode & CURSOR_UPDATE_IMMEDIATE) != 0) publishCursor();
        return true;
    }

    void cursorChanged() { if (monitorCursor) publishCursor(); }

    CursorAnchorInfo cursorInfo() {
        var state = output.textState();
        if (!sameEditor(state) || state == null || state.caret() == null
                || !(view instanceof HostedSurfaceView surface)) return null;
        var geometry = surface.geometry();
        if (geometry.right() <= geometry.left() || geometry.bottom() <= geometry.top()) return null;
        var caret = state.caret();
        var matrix = new android.graphics.Matrix();
        matrix.setScale(geometry.right() - geometry.left(), geometry.bottom() - geometry.top());
        matrix.postTranslate(geometry.left(), geometry.top());
        boolean visible = caret.left() >= 0 && caret.left() <= 1 && caret.top() >= 0 && caret.bottom() <= 1;
        var context = context();
        var builder = new CursorAnchorInfo.Builder().setMatrix(matrix)
                .setSelectionRange(context.anchor, context.cursor)
                .setInsertionMarkerLocation(caret.left(), caret.top(), Float.NaN, caret.bottom(),
                        visible ? CursorAnchorInfo.FLAG_HAS_VISIBLE_REGION : CursorAnchorInfo.FLAG_HAS_INVISIBLE_REGION);
        if (context.composingStart >= 0 && !state.privateText())
            builder.setComposingText(context.composingStart,
                    context.text.substring(context.composingStart, context.composingEnd));
        return builder.build();
    }

    private void publishCursor() {
        var info = cursorInfo();
        var manager = view.getContext().getSystemService(InputMethodManager.class);
        if (info != null && manager != null && view.hasWindowFocus()) manager.updateCursorAnchorInfo(view, info);
    }
    private void press(int key) { output.key(key, 0, true); output.key(key, 0, false); }
    @Override public boolean sendKeyEvent(KeyEvent event) { return sameEditor() && keys.test(event); }
    @Override public boolean performEditorAction(int action) {
        if (!sameEditor()) return false;
        press(KeyEvent.KEYCODE_ENTER); return true;
    }
}
