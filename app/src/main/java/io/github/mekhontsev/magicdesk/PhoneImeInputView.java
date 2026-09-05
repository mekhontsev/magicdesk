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

/** Phone-side Android IME adapter for a captured desktop editor. */
@SuppressLint("ViewConstructor")
final class PhoneImeInputView extends EditText {
    interface Dispatcher {
        boolean dispatch(
                int action,
                String text,
                int arg1,
                int arg2,
                int arg3);
    }

    private final Dispatcher mDispatcher;
    private final PhoneImeRequest mRequest;
    private final Runnable mConnectionReady;

    PhoneImeInputView(
            final Context context,
            final PhoneImeRequest request,
            final Runnable connectionReady,
            final Dispatcher dispatcher) {
        super(context);
        mRequest = request;
        mConnectionReady = connectionReady;
        mDispatcher = dispatcher;
        setSingleLine(false);
        setCursorVisible(false);
        setBackground(null);
        setAlpha(0.01f);
        setFocusable(false);
        setFocusableInTouchMode(false);
        setShowSoftInputOnFocus(false);
    }

    void updateFocusability() {
        final boolean requested = mRequest.isRequested();
        setFocusable(requested);
        setFocusableInTouchMode(requested);
        if (!requested) {
            clearFocus();
        }
    }

    @Override
    public boolean dispatchKeyEvent(final KeyEvent event) {
        return dispatchKeyEvent(event, mRequest.currentConnection());
    }

    private boolean dispatchKeyEvent(final KeyEvent event, final long connection) {
        if (!mRequest.accepts(connection)) {
            return false;
        }
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
                        connection,
                        PlatformTextInputDriver.DELETE_SURROUNDING,
                        "",
                        backward ? 1 : 0,
                        backward ? 0 : 1,
                        0);
            }
        }
        return dispatch(
                connection,
                PlatformTextInputDriver.SEND_KEY,
                "",
                event.getAction(),
                event.getKeyCode(),
                event.getMetaState());
    }

    @Override
    public InputConnection onCreateInputConnection(
            final EditorInfo editorInfo) {
        if (!mRequest.isRequested()) {
            return null;
        }
        final InputConnection viewConnection =
                super.onCreateInputConnection(editorInfo);
        if (viewConnection == null) {
            return null;
        }
        final long connection = mRequest.openConnection();
        // Finish Android's connection-creation callback before requesting IME
        // visibility. A replacement connection invalidates this queued callback.
        post(() -> {
            if (mRequest.accepts(connection)) {
                mConnectionReady.run();
            }
        });
        return new RemoteInputConnection(this, connection);
    }

    private boolean dispatch(
            final long connection,
            final int action,
            final CharSequence text,
            final int arg1,
            final int arg2,
            final int arg3) {
        return mRequest.accepts(connection) && mDispatcher != null && mDispatcher.dispatch(
                action,
                text == null ? "" : text.toString(),
                arg1,
                arg2,
                arg3);
    }

    private final class RemoteInputConnection extends BaseInputConnection {
        private final long mConnection;

        RemoteInputConnection(final View targetView, final long connection) {
            super(targetView, true);
            mConnection = connection;
        }

        @Override
        public void closeConnection() {
            super.closeConnection();
            mRequest.closeConnection(mConnection);
        }

        @Override
        public boolean commitText(
                final CharSequence text,
                final int newCursorPosition) {
            return dispatch(
                    mConnection,
                    PlatformTextInputDriver.COMMIT_TEXT,
                    text,
                    newCursorPosition,
                    0,
                    0);
        }

        @Override
        public boolean sendKeyEvent(final KeyEvent event) {
            return PhoneImeInputView.this.dispatchKeyEvent(event, mConnection);
        }

        @Override
        public boolean setComposingText(
                final CharSequence text,
                final int newCursorPosition) {
            return dispatch(
                    mConnection,
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
                    mConnection,
                    PlatformTextInputDriver.SET_COMPOSING_REGION,
                    "",
                    start,
                    end,
                    0);
        }

        @Override
        public boolean finishComposingText() {
            return dispatch(
                    mConnection,
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
                    mConnection,
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
