package io.github.mekhontsev.magicdesk;

import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import java.util.function.BooleanSupplier;

/** One phone keyboard session bound to an editor in a MagicDesk window. */
final class LocalTextInputSession implements AutoCloseable {
    private final InputConnection mConnection;
    private final BooleanSupplier mTargetAvailable;
    private boolean mClosed;

    LocalTextInputSession(
            final InputConnection connection,
            final BooleanSupplier targetAvailable) {
        mConnection = connection;
        mTargetAvailable = targetAvailable;
    }

    static LocalTextInputSession capture(final View editor, final int displayId) {
        if (!isAvailable(editor, displayId) || !editor.onCheckIsTextEditor()) {
            return null;
        }
        final InputConnection connection = editor.onCreateInputConnection(new EditorInfo());
        return connection == null ? null : new LocalTextInputSession(
                connection, () -> isAvailable(editor, displayId));
    }

    private static boolean isAvailable(final View editor, final int displayId) {
        // Window focus may move to the phone IME, but the captured editor must
        // remain attached, selected and on the original destination display.
        return editor != null && editor.isAttachedToWindow() && editor.isShown()
                && editor.isEnabled() && editor.hasFocus()
                && editor.getDisplay() != null
                && editor.getDisplay().getDisplayId() == displayId;
    }

    boolean dispatch(final int action, final String text,
            final int arg1, final int arg2, final int arg3) {
        if (mClosed) {
            return false;
        }
        if (!mTargetAvailable.getAsBoolean()) {
            close();
            return false;
        }
        final String safeText = text == null ? "" : text;
        switch (action) {
            case PlatformTextInputDriver.COMMIT_TEXT:
                return mConnection.commitText(safeText, arg1);
            case PlatformTextInputDriver.SEND_KEY:
                final long eventTime = SystemClock.uptimeMillis();
                return mConnection.sendKeyEvent(new KeyEvent(
                        eventTime, eventTime, arg1, arg2, 0, arg3));
            case PlatformTextInputDriver.SET_COMPOSING_TEXT:
                return mConnection.setComposingText(safeText, arg1);
            case PlatformTextInputDriver.SET_COMPOSING_REGION:
                return mConnection.setComposingRegion(arg1, arg2);
            case PlatformTextInputDriver.FINISH_COMPOSING:
                return mConnection.finishComposingText();
            case PlatformTextInputDriver.DELETE_SURROUNDING:
                return mConnection.deleteSurroundingText(arg1, arg2);
            default:
                return false;
        }
    }

    @Override
    public void close() {
        if (!mClosed) {
            mClosed = true;
            mConnection.closeConnection();
        }
    }
}
