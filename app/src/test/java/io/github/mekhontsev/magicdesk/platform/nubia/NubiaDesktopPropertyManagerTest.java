package io.github.mekhontsev.magicdesk.platform.nubia;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class NubiaDesktopPropertyManagerTest {
    @Test
    public void propertyAllowlistContainsOnlyReviewedDesktopKeys() {
        final NubiaDesktopPropertyManager.Property[] properties =
                NubiaDesktopPropertyManager.Property.values();

        assertEquals(2, properties.length);
        assertEquals(
                "persist.wm.debug.desktop_mode_enforce_device_restrictions",
                NubiaDesktopPropertyManager.Property.DEVICE_RESTRICTIONS.key);
        assertEquals(
                "persist.wm.debug.desktop_use_rounded_corners",
                NubiaDesktopPropertyManager.Property.ROUNDED_CORNERS.key);
    }

    @Test
    public void valuesAreLimitedToBooleanOrEmptyRestore() {
        assertTrue(NubiaDesktopPropertyManager.isBooleanOrEmpty(""));
        assertTrue(NubiaDesktopPropertyManager.isBooleanOrEmpty("true"));
        assertTrue(NubiaDesktopPropertyManager.isBooleanOrEmpty("false"));

        assertFalse(NubiaDesktopPropertyManager.isBooleanOrEmpty(null));
        assertFalse(NubiaDesktopPropertyManager.isBooleanOrEmpty("1"));
        assertFalse(NubiaDesktopPropertyManager.isBooleanOrEmpty("false; reboot"));
    }
}
