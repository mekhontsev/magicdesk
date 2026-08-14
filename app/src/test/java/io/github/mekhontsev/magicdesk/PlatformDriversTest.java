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
        assertTrue(driver.features().externalInputBridge);
        assertTrue(driver.features().internalAudioCapture);
        assertTrue(driver.pointer().isAvailable());
        assertTrue(driver.projection().supportsOutputConfiguration());
        assertTrue(driver.projection().ownsTransportLifecycle(
                PlatformProjectionDriver.Transport.WIRED));
        assertTrue(driver.projection().ownsTransportLifecycle(
                PlatformProjectionDriver.Transport.WIRELESS));
        assertTrue(driver.phoneUi().isAvailable());
        assertTrue(driver.windowing()
                .requiresMirrorInputFocusSynchronization());
        assertTrue(driver.windowing()
                .requiresNativeFullscreenCaptionRefresh());
        assertEquals(
                "persist.wm.debug.desktop_mode_enforce_device_restrictions",
                driver.windowing().restrictionsPropertyKey());
        assertEquals(
                "persist.wm.debug.desktop_use_rounded_corners",
                driver.windowing().roundedCornersPropertyKey());
        assertEquals(1, driver.additionalLaunchTargets().size());
    }

    @Test
    public void genericDriverUsesDirectAndroidExternalDisplays() {
        final PlatformDriver driver = PlatformDrivers.resolve(device(
                "Google", "google", "Pixel", "pixel", "pixel"));

        assertEquals("android", driver.id());
        assertTrue(driver.features().supportsDisplay(
                DesktopDisplayTarget.Kind.PHONE));
        assertTrue(driver.features().supportsDisplay(
                DesktopDisplayTarget.Kind.SIMULATED));
        assertTrue(driver.features().supportsDisplay(
                DesktopDisplayTarget.Kind.WIRED));
        assertTrue(driver.features().supportsDisplay(
                DesktopDisplayTarget.Kind.WIRELESS));
        assertFalse(driver.features().externalInputBridge);
        assertFalse(driver.features().internalAudioCapture);
        assertFalse(driver.pointer().isAvailable());
        assertFalse(driver.projection().supportsOutputConfiguration());
        assertFalse(driver.projection().ownsTransportLifecycle(
                PlatformProjectionDriver.Transport.WIRED));
        assertFalse(driver.projection().ownsTransportLifecycle(
                PlatformProjectionDriver.Transport.WIRELESS));
        assertTrue(driver.projection().setCaptionTransport(
                PlatformProjectionDriver.Transport.WIRED));
        assertTrue(driver.projection().setCaptionTransport(
                PlatformProjectionDriver.Transport.WIRELESS));
        assertFalse(driver.phoneUi().isAvailable());
        assertFalse(driver.windowing()
                .requiresMirrorInputFocusSynchronization());
        assertFalse(driver.windowing()
                .requiresNativeFullscreenCaptionRefresh());
        assertTrue(driver.additionalLaunchTargets().isEmpty());
        assertNull(driver.windowing().restrictionsPropertyKey());
        assertNull(driver.windowing().roundedCornersPropertyKey());
    }

    @Test
    public void debugOverrideSelectsStandardAndroidOnNubiaDevice() {
        final PlatformDevice device = device(
                "nubia", "nubia", "NX809J", "NX809J", "NX809J");

        assertEquals("nubia", PlatformDrivers.resolve(device).id());
        assertEquals(
                "android",
                PlatformDrivers.resolve(device, "android").id());
    }

    @Test
    public void android15MeetsPlatformBaseline() {
        final PlatformDevice genericDevice = new PlatformDevice(
                "Google", "google", "Pixel", "pixel", "pixel",
                "fingerprint", 35);
        final PlatformDevice nubiaDevice = new PlatformDevice(
                "nubia", "nubia", "NX769J", "NX769J", "NX769J",
                "fingerprint", 35);

        assertTrue(PlatformDrivers.resolve(genericDevice).supports(
                genericDevice));
        assertTrue(PlatformDrivers.resolve(nubiaDevice).supports(
                nubiaDevice));
        assertEquals("nubia", PlatformDrivers.resolve(nubiaDevice).id());
    }

    @Test
    public void android14DoesNotMeetPlatformBaseline() {
        final PlatformDevice device = new PlatformDevice(
                "Google", "google", "Pixel", "pixel", "pixel",
                "fingerprint", 34);
        final PlatformDriver driver = PlatformDrivers.resolve(device);

        assertFalse(driver.supports(device));
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
