package io.github.mekhontsev.magicdesk;

import android.view.View;
import android.view.KeyEvent;
import android.view.inputmethod.BaseInputConnection;
import com.termux.terminal.KeyHandler;

/** Android owns connection lifetime; the window attachment rejects late callbacks. */
final class ConsoleTerminalInputConnection extends BaseInputConnection {
    private final View mView;
    private final ConsoleTerminalSession mSession;
    private final java.util.function.BooleanSupplier mAttached;
    private final Runnable mOnInput;
    private boolean mClosed;
    private String mComposingText = "";

    ConsoleTerminalInputConnection(View view, ConsoleTerminalSession session,
            java.util.function.BooleanSupplier attached, Runnable onInput) {
        super(view, false);
        mView = view;
        mSession = session;
        mAttached = attached;
        mOnInput = onInput;
    }

    private boolean isActive() {
        // Creating another connection does not close this one. Android owns its
        // lifetime; the attachment token also rejects callbacks after detach/rebind.
        return !mClosed && mAttached.getAsBoolean();
    }

    @Override
    public void closeConnection() {
        mClosed = true;
        super.closeConnection();
    }

    @Override
    public boolean commitText(
            final CharSequence text, final int newCursorPosition) {
        if (!isActive()) { return false; }
        replaceComposingText(text);
        mComposingText = "";
        return true;
    }

    @Override
    public boolean setComposingText(
            final CharSequence text, final int newCursorPosition) {
        if (!isActive()) { return false; }
        replaceComposingText(text);
        mComposingText = text == null ? "" : text.toString();
        return true;
    }

    @Override
    public boolean finishComposingText() {
        mComposingText = "";
        return isActive();
    }

    private void replaceComposingText(final CharSequence text) {
        final int previousCodePoints = mComposingText.codePointCount(
                0, mComposingText.length());
        for (int index = 0; index < previousCodePoints; index++) {
            mSession.write(new byte[]{0x7F});
        }
        if (text != null) {
            mSession.write(text.toString());
            mOnInput.run();
        }
    }

    @Override
    public boolean deleteSurroundingText(
            final int beforeLength, final int afterLength) {
        if (!isActive()) { return false; }
        mComposingText = "";
        for (int index = 0; index < beforeLength; index++) {
            mSession.write(new byte[]{0x7F});
        }
        if (afterLength > 0) {
            final String delete = KeyHandler.getCode(
                    KeyEvent.KEYCODE_FORWARD_DEL,
                    0,
                    mSession.emulator().isCursorKeysApplicationMode(),
                    mSession.emulator().isKeypadApplicationMode());
            for (int index = 0; index < afterLength; index++) {
                mSession.write(delete);
            }
        }
        return true;
    }

    @Override
    public boolean sendKeyEvent(final KeyEvent event) {
        return isActive() && mView.dispatchKeyEvent(event);
    }

    @Override
    public boolean performEditorAction(final int actionCode) {
        if (!isActive()) { return false; }
        mSession.write("\r");
        return true;
    }

}
