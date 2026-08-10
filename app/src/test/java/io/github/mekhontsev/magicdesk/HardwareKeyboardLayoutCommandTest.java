package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertTrue;

import com.android.internal.inputmethod.InputMethodSubtypeSafeList;

import org.junit.Test;

import java.util.Collections;

public final class HardwareKeyboardLayoutCommandTest {
    @Test
    public void acceptsLegacySubtypeList() throws ReflectiveOperationException {
        assertTrue(HardwareKeyboardLayoutCommand
                .extractEnabledInputMethodSubtypes(Collections.emptyList())
                .isEmpty());
    }

    @Test
    public void extractsSafeSubtypeList() throws ReflectiveOperationException {
        final InputMethodSubtypeSafeList safeList =
                new InputMethodSubtypeSafeList(Collections.emptyList());

        assertTrue(HardwareKeyboardLayoutCommand
                .extractEnabledInputMethodSubtypes(safeList)
                .isEmpty());
    }
}
