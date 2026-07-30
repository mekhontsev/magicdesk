package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class DisplayDensityPolicyTest {
    @Test
    public void recommendedDpiKeepsAboutFullHdLogicalSpace() {
        assertEquals(108,
                DisplayDensityPolicy.recommendedExternalDpi(1280, 720, 520));
        assertEquals(160,
                DisplayDensityPolicy.recommendedExternalDpi(1920, 1080, 520));
        assertEquals(176,
                DisplayDensityPolicy.recommendedExternalDpi(1920, 1200, 520));
        assertEquals(212,
                DisplayDensityPolicy.recommendedExternalDpi(2560, 1440, 520));
        assertEquals(320,
                DisplayDensityPolicy.recommendedExternalDpi(3840, 2160, 520));
    }

    @Test
    public void invalidResolutionFallsBackToLegacyExternalDensity() {
        assertEquals(192,
                DisplayDensityPolicy.recommendedExternalDpi(0, 0, 520));
    }
}
