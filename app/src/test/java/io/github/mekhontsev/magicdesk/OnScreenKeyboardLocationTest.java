package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class OnScreenKeyboardLocationTest {
    @Test
    public void storedValueDefaultsToPhone() {
        assertEquals(
                OnScreenKeyboardLocation.PHONE,
                OnScreenKeyboardLocation.fromStoredValue(null));
        assertEquals(
                OnScreenKeyboardLocation.PHONE,
                OnScreenKeyboardLocation.fromStoredValue("unknown"));
    }

    @Test
    public void storedDesktopValueIsRecognized() {
        assertEquals(
                OnScreenKeyboardLocation.DESKTOP,
                OnScreenKeyboardLocation.fromStoredValue("desktop"));
    }
}
