package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class ExternalDisplayLaunchSettingsTest {
    @Test
    public void nativeMapsStandardPhysicalModesToNubiaClasses() {
        assertEquals(
                ExternalDisplayLaunchSettings.VENDOR_SIZE_1080,
                resolveNative(1920, 1080));
        assertEquals(
                ExternalDisplayLaunchSettings.VENDOR_SIZE_1440,
                resolveNative(2560, 1440));
        assertEquals(
                ExternalDisplayLaunchSettings.VENDOR_SIZE_2160,
                resolveNative(3840, 2160));
    }

    @Test
    public void nativeLeavesNonStandardTimingsToThePlatformMode() {
        assertEquals(
                ExternalDisplayLaunchSettings.VENDOR_SIZE_UNCHANGED,
                resolveNative(3840, 1200));
        assertEquals(
                ExternalDisplayLaunchSettings.VENDOR_SIZE_UNCHANGED,
                resolveNative(2560, 1600));
    }

    @Test
    public void explicitModeDoesNotDependOnPhysicalDimensions() {
        assertEquals(
                ExternalDisplayLaunchSettings.VENDOR_SIZE_1440,
                ExternalDisplayLaunchSettings.resolveVendorSizeType(
                        ExternalDisplayLaunchSettings.OutputMode.P1440,
                        1920,
                        1080));
    }

    @Test
    public void systemModeDoesNotOverrideNubiaResolution() {
        assertEquals(
                ExternalDisplayLaunchSettings.VENDOR_SIZE_UNCHANGED,
                ExternalDisplayLaunchSettings.resolveVendorSizeType(
                        ExternalDisplayLaunchSettings.OutputMode.SYSTEM,
                        2560,
                        1440));
    }

    private static int resolveNative(final int width, final int height) {
        return ExternalDisplayLaunchSettings.resolveVendorSizeType(
                ExternalDisplayLaunchSettings.OutputMode.NATIVE,
                width,
                height);
    }
}
