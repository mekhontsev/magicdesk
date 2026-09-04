package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class DesktopTaskPresentationPolicyTest {
    @Test
    public void scaleIsResolvedAgainstTargetDisplayDensity() {
        assertEquals(
                200,
                DesktopTaskPresentationPolicy.resolveDensityDpi(
                        new AppPresentationProfile(125), 160));
        assertEquals(
                320,
                DesktopTaskPresentationPolicy.resolveDensityDpi(
                        new AppPresentationProfile(200), 160));
    }

    @Test
    public void systemProfileUsesInheritedDensity() {
        assertEquals(
                DesktopTaskDensity.INHERIT,
                DesktopTaskPresentationPolicy.resolveDensityDpi(
                        (AppPresentationProfile) null, 160));
        assertEquals(
                160,
                DesktopTaskPresentationPolicy.expectedDensityDpi(null, 160));
    }

    @Test
    public void resolvedDensityUsesFrameworkMinimumWithoutUpperTruncation() {
        assertEquals(
                DesktopTaskDensity.MIN_DENSITY_DPI,
                DesktopTaskPresentationPolicy.resolveDensityDpi(
                        new AppPresentationProfile(50), 100));
        assertEquals(
                1_600,
                DesktopTaskPresentationPolicy.resolveDensityDpi(
                        new AppPresentationProfile(200), 800));
    }
}
