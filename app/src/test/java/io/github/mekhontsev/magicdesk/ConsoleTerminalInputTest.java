package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.MotionEvent;

import org.junit.Test;

public final class ConsoleTerminalInputTest {
    private static final int ACUTE = 0xB4;

    @Test
    public void androidDeadKeyFlagIsNeverPassedToCharacterEncoding() {
        final int deadKey = KeyCharacterMap.COMBINING_ACCENT | ACUTE;
        assertThrows(IllegalArgumentException.class, () -> Character.toChars(deadKey));
        final ConsoleTerminalInput input = input();
        assertEquals("", input.text(deadKey));
        assertEquals("\u00e9", input.text('e'));
        assertEquals("x", input.text('x'));
    }

    @Test
    public void unsupportedCompositionPreservesBothCharacters() {
        final ConsoleTerminalInput input = input();
        assertEquals("", input.text(KeyCharacterMap.COMBINING_ACCENT | ACUTE));
        assertEquals("\u00b4x", input.text('x'));
    }

    @Test
    public void repeatedAccentAndSpaceUseThePlatformComposerResult() {
        for (final int next : new int[]{' ', KeyCharacterMap.COMBINING_ACCENT | ACUTE}) {
            final ConsoleTerminalInput input = input();
            assertEquals("", input.text(KeyCharacterMap.COMBINING_ACCENT | ACUTE));
            assertEquals("\u00b4", input.text(next));
            assertEquals("e", input.text('e'));
        }
    }

    @Test
    public void pendingAccentCanBeFlushedBeforeANonTextKey() {
        final ConsoleTerminalInput input = input();
        input.text(KeyCharacterMap.COMBINING_ACCENT | ACUTE);
        assertEquals("\u00b4", input.flushAccent());
        assertEquals("", input.flushAccent());
        assertEquals("e", input.text('e'));
    }

    @Test
    public void ordinaryControlsAndSupplementaryCharactersStayIntact() {
        final ConsoleTerminalInput input = input();
        assertEquals("A", input.text('A'));
        assertEquals("\0", input.text(0));
        assertEquals("\u001b", input.text(27));
        assertEquals("\ud83d\ude80", input.text(0x1F680));
    }

    @Test
    public void invalidUnicodeCannotCrashOrEmitBrokenSurrogates() {
        final ConsoleTerminalInput input = input();
        for (final int invalid : new int[]{-1, 0x110000, 0xD800, 0xDFFF}) {
            assertEquals("", input.text(invalid));
        }
    }

    @Test
    public void sourceClassPointerAloneDoesNotMakeAMouseATouchscreen() {
        assertTrue((InputDevice.SOURCE_MOUSE & InputDevice.SOURCE_TOUCHSCREEN) != 0);
        assertFalse(ConsoleTerminalInput.isTouch(MotionEvent.TOOL_TYPE_MOUSE,
                InputDevice.SOURCE_MOUSE));
        assertFalse(ConsoleTerminalInput.isTouch(MotionEvent.TOOL_TYPE_UNKNOWN,
                InputDevice.SOURCE_CLASS_POINTER));
        assertTrue(ConsoleTerminalInput.isTouch(MotionEvent.TOOL_TYPE_UNKNOWN,
                InputDevice.SOURCE_TOUCHSCREEN));
        assertTrue(ConsoleTerminalInput.isTouch(MotionEvent.TOOL_TYPE_FINGER,
                InputDevice.SOURCE_TOUCHPAD));
        assertTrue(ConsoleTerminalInput.isTouch(MotionEvent.TOOL_TYPE_STYLUS,
                InputDevice.SOURCE_TOUCHSCREEN | InputDevice.SOURCE_STYLUS));
    }

    private static ConsoleTerminalInput input() {
        // Android owns the composition table. This test supplies its result to
        // exercise flag decoding and pending-state transitions without a device.
        return new ConsoleTerminalInput((accent, base) -> {
            assertEquals(ACUTE, accent);
            if (base == 'e') {
                return 0xE9;
            }
            return base == ' ' || base == ACUTE ? ACUTE : 0;
        });
    }
}
