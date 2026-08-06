package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class ExternalDisplayLaunchSettingsTest {
    @Test
    public void mapsStandardPhysicalModesToNubiaClasses() {
        assertEquals(
                ExternalDisplayLaunchSettings.VENDOR_SIZE_1080,
                resolve(1920, 1080));
        assertEquals(
                ExternalDisplayLaunchSettings.VENDOR_SIZE_1440,
                resolve(2560, 1440));
        assertEquals(
                ExternalDisplayLaunchSettings.VENDOR_SIZE_2160,
                resolve(3840, 2160));
    }

    @Test
    public void mapsNonStandardTimingsToTheNearestNubiaTier() {
        assertEquals(
                ExternalDisplayLaunchSettings.VENDOR_SIZE_1080,
                resolve(3840, 1200));
        assertEquals(
                ExternalDisplayLaunchSettings.VENDOR_SIZE_1440,
                resolve(2560, 1600));
    }

    @Test
    public void rejectsMissingPhysicalDimensions() {
        assertEquals(
                ExternalDisplayLaunchSettings.VENDOR_SIZE_UNCHANGED,
                resolve(0, 0));
    }

    private static int resolve(final int width, final int height) {
        return ExternalDisplayLaunchSettings.resolveVendorSizeType(
                width,
                height);
    }
}
