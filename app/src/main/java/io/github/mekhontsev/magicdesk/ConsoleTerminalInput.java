package io.github.mekhontsev.magicdesk;

import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.MotionEvent;

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

    static boolean isTouch(final int toolType, final int source) {
        return toolType == MotionEvent.TOOL_TYPE_FINGER
                || (source & InputDevice.SOURCE_TOUCHSCREEN) == InputDevice.SOURCE_TOUCHSCREEN;
    }

    private static boolean isScalar(final int codePoint) {
        return Character.isValidCodePoint(codePoint)
                && (codePoint < Character.MIN_SURROGATE || codePoint > Character.MAX_SURROGATE);
    }
}
