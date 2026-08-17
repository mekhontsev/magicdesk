package io.github.mekhontsev.magicdesk;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.widget.EditText;

/** Invisible IME target that writes into the focused mirrored window. */
@SuppressLint("ViewConstructor")
final class MirrorInputEditText extends EditText {
    interface Dispatcher {
        boolean dispatch(
                int action,
                String text,
                int arg1,
                int arg2,
                int arg3);
    }

    private final Dispatcher mDispatcher;

    MirrorInputEditText(
            final Context context,
            final Dispatcher dispatcher) {
        super(context);
        mDispatcher = dispatcher;
        setSingleLine(false);
        setCursorVisible(false);
        setBackground(null);
        setAlpha(0.01f);
        setFocusable(false);
        setFocusableInTouchMode(false);
        setShowSoftInputOnFocus(false);
    }

    void setKeyboardRequested(final boolean requested) {
        setFocusable(requested);
        setFocusableInTouchMode(requested);
        setShowSoftInputOnFocus(requested);
        if (!requested) {
            clearFocus();
        }
    }

    @Override
    public boolean dispatchKeyEvent(final KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_UNKNOWN) {
            return true;
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            return false;
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_DEL
                || event.getKeyCode() == KeyEvent.KEYCODE_FORWARD_DEL) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                final boolean backward =
                        event.getKeyCode() == KeyEvent.KEYCODE_DEL;
                return dispatch(
                        PlatformTextInputDriver.DELETE_SURROUNDING,
                        "",
                        backward ? 1 : 0,
                        backward ? 0 : 1,
                        0);
            }
        }
        return dispatch(
                PlatformTextInputDriver.SEND_KEY,
                "",
                event.getAction(),
                event.getKeyCode(),
                event.getMetaState());
    }

    @Override
    public InputConnection onCreateInputConnection(
            final EditorInfo editorInfo) {
        final InputConnection viewConnection =
                super.onCreateInputConnection(editorInfo);
        if (viewConnection == null) {
            return null;
        }
        return new RemoteInputConnection(this);
    }

    private boolean dispatch(
            final int action,
            final CharSequence text,
            final int arg1,
            final int arg2,
            final int arg3) {
        return mDispatcher != null && mDispatcher.dispatch(
                action,
                text == null ? "" : text.toString(),
                arg1,
                arg2,
                arg3);
    }

    private final class RemoteInputConnection extends BaseInputConnection {
        RemoteInputConnection(final View targetView) {
            super(targetView, true);
        }

        @Override
        public boolean commitText(
                final CharSequence text,
                final int newCursorPosition) {
            return dispatch(
                    PlatformTextInputDriver.COMMIT_TEXT,
                    text,
                    newCursorPosition,
                    0,
                    0);
        }

        @Override
        public boolean sendKeyEvent(final KeyEvent event) {
            return MirrorInputEditText.this.dispatchKeyEvent(event);
        }

        @Override
        public boolean setComposingText(
                final CharSequence text,
                final int newCursorPosition) {
            return dispatch(
                    PlatformTextInputDriver.SET_COMPOSING_TEXT,
                    text,
                    newCursorPosition,
                    0,
                    0);
        }

        @Override
        public boolean setComposingRegion(
                final int start,
                final int end) {
            return dispatch(
                    PlatformTextInputDriver.SET_COMPOSING_REGION,
                    "",
                    start,
                    end,
                    0);
        }

        @Override
        public boolean finishComposingText() {
            return dispatch(
                    PlatformTextInputDriver.FINISH_COMPOSING,
                    "",
                    0,
                    0,
                    0);
        }

        @Override
        public boolean deleteSurroundingText(
                final int beforeLength,
                final int afterLength) {
            return dispatch(
                    PlatformTextInputDriver.DELETE_SURROUNDING,
                    "",
                    beforeLength,
                    afterLength,
                    0);
        }

        @Override
        public ExtractedText getExtractedText(
                final ExtractedTextRequest request,
                final int flags) {
            return null;
        }
    }
}
