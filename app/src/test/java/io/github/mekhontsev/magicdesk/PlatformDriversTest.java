package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
        assertTrue(driver.features().vendorInput);
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
