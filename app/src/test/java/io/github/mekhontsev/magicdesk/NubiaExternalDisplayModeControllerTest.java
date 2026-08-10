package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class NubiaExternalDisplayModeControllerTest {
    @Test
    public void acceptsOnlyBooleanDebugPropertyValues() {
        assertTrue(NubiaExternalDisplayModeController.isBooleanProperty(""));
        assertTrue(NubiaExternalDisplayModeController.isBooleanProperty("0"));
        assertTrue(NubiaExternalDisplayModeController.isBooleanProperty("1"));
        assertTrue(NubiaExternalDisplayModeController.isBooleanProperty("false"));
        assertTrue(NubiaExternalDisplayModeController.isBooleanProperty("true"));

        assertFalse(NubiaExternalDisplayModeController.isBooleanProperty(null));
        assertFalse(NubiaExternalDisplayModeController.isBooleanProperty("yes"));
        assertFalse(NubiaExternalDisplayModeController.isBooleanProperty(
                "1; reboot"));
    }

}
