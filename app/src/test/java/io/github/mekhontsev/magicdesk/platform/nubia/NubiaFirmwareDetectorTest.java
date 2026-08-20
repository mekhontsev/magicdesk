package io.github.mekhontsev.magicdesk.platform.nubia;

import io.github.mekhontsev.magicdesk.PlatformDevice;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class NubiaFirmwareDetectorTest {
    @Test
    public void recognizesPresentPlatformService() {
        assertTrue(NubiaFirmwareDetector.reportsPresent(
                "Service redmagic.app.manager: found\n"));
    }

    @Test
    public void rejectsMissingOrUnrelatedServices() {
        assertFalse(NubiaFirmwareDetector.reportsPresent(
                "Service redmagic.app.manager: not found\n"));
        assertFalse(NubiaFirmwareDetector.reportsPresent(
                "Service another.service: found\n"));
        assertFalse(NubiaFirmwareDetector.reportsPresent(null));
    }

    @Test
    public void recognizesOfficialNubiaFirmwareWithoutRedmagicService() {
        assertTrue(NubiaFirmwareDetector.isAvailable(
                device("nubia/PQ85A01-UN/PQ85A01:16/build:user/release-keys"),
                false));
        assertTrue(NubiaFirmwareDetector.isAvailable(
                device("REDMAGIC/NX809J-EEA/NX809J:16/build:user/release-keys"),
                false));
    }

    @Test
    public void rejectsCustomRomWhenVendorServiceIsAbsent() {
        assertFalse(NubiaFirmwareDetector.isAvailable(
                device("google/mustang_beta/mustang:16/build:user/release-keys"),
                false));
    }

    private static PlatformDevice device(final String fingerprint) {
        return new PlatformDevice(
                "nubia", "nubia", "NX809J", "NX809J", "NX809J-UN",
                fingerprint, 36);
    }
}
