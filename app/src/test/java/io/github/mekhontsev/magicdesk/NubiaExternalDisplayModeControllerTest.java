package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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

    @Test
    public void parsesDisplayManagerNativeAndPreferredModes() {
        final NubiaExternalDisplayModeController.PhysicalMode boot =
                NubiaExternalDisplayModeController.parsePhysicalMode(
                        "Boot display mode: 2560 1600 120.00001");
        assertNotNull(boot);
        assertTrue(boot.sameMode(
                new NubiaExternalDisplayModeController.PhysicalMode(
                        2560, 1600, 120.00001f)));

        final NubiaExternalDisplayModeController.PhysicalMode preferred =
                NubiaExternalDisplayModeController.parsePhysicalMode(
                        "User preferred display mode: 2560 1440 180.0");
        assertNotNull(preferred);
        assertTrue(preferred.sameMode(
                new NubiaExternalDisplayModeController.PhysicalMode(
                        2560, 1440, 180f)));
    }

    @Test
    public void rejectsMissingOrClearedDisplayModes() {
        assertNull(NubiaExternalDisplayModeController.parsePhysicalMode(null));
        assertNull(NubiaExternalDisplayModeController.parsePhysicalMode(
                "User preferred display mode: -1 -1 0.0"));
        assertNull(NubiaExternalDisplayModeController.parsePhysicalMode(
                "Boot display mode: null"));
    }
}
