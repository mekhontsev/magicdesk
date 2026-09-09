package io.github.mekhontsev.magicdesk;

import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.MotionEvent;
import android.view.KeyEvent;
import com.termux.terminal.KeyHandler;
import com.termux.terminal.TerminalEmulator;

import java.util.function.IntBinaryOperator;

/** Pure translation of Android input flags before they reach the terminal. */
final class ConsoleTerminalInput {
    private final IntBinaryOperator mComposeAccent;
    private int mPendingAccent;

    ConsoleTerminalInput(final IntBinaryOperator composeAccent) {
        mComposeAccent = composeAccent;
    }

    String text(final int unicode) {
        final boolean deadKey = (unicode & KeyCharacterMap.COMBINING_ACCENT) != 0;
        final int codePoint = unicode & KeyCharacterMap.COMBINING_ACCENT_MASK;
        if (!isScalar(codePoint)) {
            return "";
        }
        String prefix = "";
        if (mPendingAccent != 0) {
            final int composed = mComposeAccent.applyAsInt(mPendingAccent, codePoint);
            if (composed > 0 && isScalar(composed)) {
                mPendingAccent = 0;
                return new String(Character.toChars(composed));
            }
            prefix = flushAccent();
        }
        if (deadKey) {
            mPendingAccent = codePoint;
            return prefix;
        }
        return prefix + new String(Character.toChars(codePoint));
    }

    String flushAccent() {
        if (mPendingAccent == 0) {
            return "";
        }
        final String accent = new String(Character.toChars(mPendingAccent));
        mPendingAccent = 0;
        return accent;
    }

    String key(final KeyEvent event, final TerminalEmulator emulator) {
        final int keyCode = event.getKeyCode();
        final String sequence = KeyHandler.getCode(keyCode, keyModifiers(event),
                emulator.isCursorKeysApplicationMode(), emulator.isKeypadApplicationMode());
        if (sequence != null) { return flushAccent() + sequence; }
        final int control = controlCode(keyCode, event);
        final int unicode = control >= 0 ? control : event.getUnicodeChar(event.getMetaState());
        if (unicode == 0 && control < 0) { return null; }
        final String prefix = control >= 0 ? flushAccent() : "";
        final String text = text(unicode);
        return prefix + (event.isAltPressed() && !text.isEmpty() ? "\u001b" : "") + text;
    }

    private static int keyModifiers(final KeyEvent event) {
        int modifiers = 0;
        if (event.isAltPressed()) {
            modifiers |= KeyHandler.KEYMOD_ALT;
        }
        if (event.isCtrlPressed()) {
            modifiers |= KeyHandler.KEYMOD_CTRL;
        }
        if (event.isShiftPressed()) {
            modifiers |= KeyHandler.KEYMOD_SHIFT;
        }
        if (event.isNumLockOn()) {
            modifiers |= KeyHandler.KEYMOD_NUM_LOCK;
        }
        return modifiers;
    }

    private static int controlCode(
            final int keyCode, final KeyEvent event) {
        if (!event.isCtrlPressed()) {
            return -1;
        }
        if (keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z) {
            return keyCode - KeyEvent.KEYCODE_A + 1;
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_2:
                return 0;
            case KeyEvent.KEYCODE_LEFT_BRACKET:
                return 27;
            case KeyEvent.KEYCODE_BACKSLASH:
                return 28;
            case KeyEvent.KEYCODE_RIGHT_BRACKET:
                return 29;
            case KeyEvent.KEYCODE_6:
                return 30;
            case KeyEvent.KEYCODE_MINUS:
                return 31;
            default:
                return -1;
        }
    }

    static boolean isTouch(final int toolType, final int source) {
        return toolType == MotionEvent.TOOL_TYPE_FINGER
                || (source & InputDevice.SOURCE_TOUCHSCREEN) == InputDevice.SOURCE_TOUCHSCREEN;
    }

    private static boolean isScalar(final int codePoint) {
        return Character.isValidCodePoint(codePoint)
                && (codePoint < Character.MIN_SURROGATE || codePoint > Character.MAX_SURROGATE);
    }
}
