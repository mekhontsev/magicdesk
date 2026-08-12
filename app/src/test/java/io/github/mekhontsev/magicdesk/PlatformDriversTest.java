package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class PlatformDriversTest {
    @Test
    public void selectsNubiaDriverFromDeviceFamilyIdentity() {
        final PlatformDriver driver = PlatformDrivers.resolve(device(
                "nubia", "nubia", "NX809J", "NX809J", "NX809J"));

        assertEquals("nubia", driver.id());
        assertTrue(driver.features().wiredDesktop);
        assertTrue(driver.features().wirelessDesktop);
        assertTrue(driver.pointer().isAvailable());
        assertTrue(driver.projection().isAvailable());
        assertTrue(driver.phoneUi().isAvailable());
        assertTrue(driver.windowing()
                .requiresMirrorInputFocusSynchronization());
        assertEquals(
                "persist.wm.debug.desktop_mode_enforce_device_restrictions",
                driver.windowing().restrictionsPropertyKey());
        assertEquals(
                "persist.wm.debug.desktop_use_rounded_corners",
                driver.windowing().roundedCornersPropertyKey());
        assertEquals(1, driver.additionalLaunchTargets().size());
    }

    @Test
    public void genericDriverKeepsUnverifiedExternalBackendsDisabled() {
        final PlatformDriver driver = PlatformDrivers.resolve(device(
                "Google", "google", "Pixel", "pixel", "pixel"));

        assertEquals("android", driver.id());
        assertTrue(driver.features().supportsDisplay(
                DesktopDisplayTarget.Kind.PHONE));
        assertTrue(driver.features().supportsDisplay(
                DesktopDisplayTarget.Kind.SIMULATED));
        assertFalse(driver.features().supportsDisplay(
                DesktopDisplayTarget.Kind.WIRED));
        assertFalse(driver.features().supportsDisplay(
                DesktopDisplayTarget.Kind.WIRELESS));
        assertFalse(driver.pointer().isAvailable());
        assertFalse(driver.projection().isAvailable());
        assertFalse(driver.phoneUi().isAvailable());
        assertFalse(driver.windowing()
                .requiresMirrorInputFocusSynchronization());
        assertTrue(driver.additionalLaunchTargets().isEmpty());
        assertNull(driver.windowing().restrictionsPropertyKey());
        assertNull(driver.windowing().roundedCornersPropertyKey());
    }

    @Test
    public void oldAndroidDoesNotMeetPlatformBaseline() {
        final PlatformDriver driver = PlatformDrivers.resolve(
                new PlatformDevice(
                        "Google", "google", "Pixel", "pixel", "pixel",
                        "fingerprint", 35));

        assertFalse(driver.supports(new PlatformDevice(
                "Google", "google", "Pixel", "pixel", "pixel",
                "fingerprint", 35)));
    }

    private static PlatformDevice device(
            final String manufacturer,
            final String brand,
            final String model,
            final String device,
            final String product) {
        return new PlatformDevice(
                manufacturer, brand, model, device, product,
                "fingerprint", 36);
    }
}
