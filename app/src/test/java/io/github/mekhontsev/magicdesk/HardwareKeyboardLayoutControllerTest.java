package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class HardwareKeyboardLayoutControllerTest {
    @Test
    public void recognizesMissingKeyboardAsNormalResponse() {
        assertTrue(HardwareKeyboardLayoutController.isNoExternalKeyboard(
                "status=no_external_keyboard\nphysicalDevices=0\nlayouts=0"));
        assertFalse(HardwareKeyboardLayoutController.isNoExternalKeyboard(
                "descriptor=example\nlayouts=2"));
        assertFalse(HardwareKeyboardLayoutController.isNoExternalKeyboard(
                "status=failed"));
    }
}
