package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class PlatformDriversTest {
    @Test
    public void selectsNubiaDriverFromDeviceFamilyIdentity() {
        final PlatformDriver driver = resolve(device(
                "nubia", "nubia", "NX809J", "NX809J", "NX809J"), true);

        assertEquals("nubia", driver.id());
        assertEquals("android", driver.selection().baselineId());
        assertEquals("nubia", driver.selection().extensionId());
        assertEquals("nubia", driver.selection()
                .provider(PlatformComponent.PROJECTION).id);
        assertTrue(driver.features().wiredDesktop);
        assertTrue(driver.features().wirelessDesktop);
        assertTrue(driver.features().externalInputBridge);
        assertFalse(driver.audioCapture().availability()
                == PlatformAudioCaptureDriver.Availability.UNSUPPORTED);
        assertTrue(driver.textInput().isAvailable());
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
        assertTrue(driver.windowing().requiresPhoneTaskRecovery());
        assertTrue(driver.windowing()
                .requiresStalePhoneFreeformTaskCleanup());
        assertTrue(driver.phoneUi().requiresPhoneUiReconciliation());
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
        final PlatformDriver driver = resolve(device(
                "Google", "google", "Pixel", "pixel", "pixel"), false);

        assertEquals("android", driver.id());
        assertEquals("", driver.selection().extensionId());
        assertEquals("android", driver.selection()
                .provider(PlatformComponent.PROJECTION).id);
        assertTrue(driver.features().supportsDisplay(
                DesktopDisplayTarget.Kind.PHONE));
        assertTrue(driver.features().supportsDisplay(
                DesktopDisplayTarget.Kind.SIMULATED));
        assertTrue(driver.features().supportsDisplay(
                DesktopDisplayTarget.Kind.WIRED));
        assertTrue(driver.features().supportsDisplay(
                DesktopDisplayTarget.Kind.WIRELESS));
        assertFalse(driver.features().externalInputBridge);
        assertFalse(driver.audioCapture().isAvailable());
        assertFalse(driver.textInput().isAvailable());
        assertFalse(driver.pointer().isAvailable());
        assertFalse(driver.projection().supportsOutputConfiguration());
        assertFalse(driver.projection().hasWirelessConnectionUi(null));
        assertFalse(driver.projection().openWirelessConnectionUi(null));
        assertFalse(driver.projection().ownsTransportLifecycle(
                PlatformProjectionDriver.Transport.WIRED));
        assertFalse(driver.projection().ownsTransportLifecycle(
                PlatformProjectionDriver.Transport.WIRELESS));
        assertTrue(driver.projection().setCaptionTransport(
                PlatformProjectionDriver.Transport.WIRED));
        assertTrue(driver.projection().setCaptionTransport(
                PlatformProjectionDriver.Transport.WIRELESS));
        assertFalse(driver.phoneUi().isAvailable());
        assertFalse(driver.phoneUi().requiresPhoneUiReconciliation());
        assertFalse(driver.windowing()
                .requiresMirrorInputFocusSynchronization());
        assertFalse(driver.windowing()
                .requiresNativeFullscreenCaptionRefresh());
        assertFalse(driver.windowing().requiresPhoneTaskRecovery());
        assertFalse(driver.windowing()
                .requiresStalePhoneFreeformTaskCleanup());
        assertTrue(driver.additionalLaunchTargets().isEmpty());
        assertNull(driver.windowing().restrictionsPropertyKey());
        assertNull(driver.windowing().roundedCornersPropertyKey());
    }

    @Test
    public void zteBrandedDeviceDoesNotAssumeNubiaFirmware() {
        final PlatformDriver driver = resolve(device(
                "ZTE", "zte", "ZTE A2026", "zte_device", "zte_product"),
                true);

        assertEquals("android", driver.id());
    }

    @Test
    public void nubiaHardwareOnCustomRomUsesGenericAndroid() {
        final PlatformDevice evolutionX = new PlatformDevice(
                "nubia",
                "nubia",
                "NX809J",
                "NX809J",
                "NX809J-UN",
                "google/mustang_beta/mustang:16/build:user/release-keys",
                36);

        assertEquals("android", resolve(evolutionX, false).id());
    }

    @Test
    public void debugOverrideSelectsStandardAndroidOnNubiaDevice() {
        final PlatformDevice device = device(
                "nubia", "nubia", "NX809J", "NX809J", "NX809J");

        assertEquals("nubia", resolve(device, true).id());
        assertEquals(
                "android",
                PlatformDrivers.resolve(device, "android", true).id());
    }

    @Test
    public void android15MeetsPlatformBaseline() {
        final PlatformDevice genericDevice = new PlatformDevice(
                "Google", "google", "Pixel", "pixel", "pixel",
                "fingerprint", 35);
        final PlatformDevice nubiaDevice = new PlatformDevice(
                "nubia", "nubia", "NX769J", "NX769J", "NX769J",
                "fingerprint", 35);

        assertTrue(resolve(genericDevice, false).supports(
                genericDevice));
        assertTrue(resolve(nubiaDevice, true).supports(
                nubiaDevice));
        assertEquals("nubia", resolve(nubiaDevice, true).id());
    }

    @Test
    public void android14DoesNotMeetPlatformBaseline() {
        final PlatformDevice device = new PlatformDevice(
                "Google", "google", "Pixel", "pixel", "pixel",
                "fingerprint", 34);
        final PlatformDriver driver = resolve(device, false);

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

    private static PlatformDriver resolve(
            final PlatformDevice device,
            final boolean nubiaFirmwareAvailable) {
        return PlatformDrivers.resolve(device, "", nubiaFirmwareAvailable);
    }
}
