package io.github.mekhontsev.magicdesk.platform.nubia;

import io.github.mekhontsev.magicdesk.PlatformDevice;
import io.github.mekhontsev.magicdesk.PlatformComponent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.EnumMap;

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
        assertTrue(NubiaFirmwareDetector.hasCompleteFirmware(
                device("nubia/PQ85A01-UN/PQ85A01:16/build:user/release-keys"),
                false));
        assertTrue(NubiaFirmwareDetector.hasCompleteFirmware(
                device("REDMAGIC/NX809J-EEA/NX809J:16/build:user/release-keys"),
                false));
    }

    @Test
    public void rejectsCustomRomWhenVendorServiceIsAbsent() {
        assertFalse(NubiaFirmwareDetector.hasCompleteFirmware(
                device("google/mustang_beta/mustang:16/build:user/release-keys"),
                false));
    }

    @Test
    public void keepsOnlyDetectedComponentsOnCustomRom() {
        final EnumMap<PlatformComponent, String> detected =
                new EnumMap<>(PlatformComponent.class);
        detected.put(
                PlatformComponent.AUDIO_CAPTURE,
                "MediaRecorder source 80 declared by the framework");

        final NubiaFirmwareDetector.Result result =
                NubiaFirmwareDetector.fromDetectedComponents(detected);

        assertTrue(result.isAvailable());
        assertTrue(result.components().contains(
                PlatformComponent.AUDIO_CAPTURE));
        assertTrue(result.components().contains(
                PlatformComponent.DIAGNOSTICS));
        assertFalse(result.components().contains(
                PlatformComponent.WINDOWING));
        assertFalse(result.components().contains(
                PlatformComponent.PROJECTION));
        assertEquals(
                "MediaRecorder source 80 declared by the framework",
                result.evidence(PlatformComponent.AUDIO_CAPTURE));
    }

    @Test
    public void rejectsCustomRomWithoutOptionalVendorApis() {
        final NubiaFirmwareDetector.Result result =
                NubiaFirmwareDetector.fromDetectedComponents(
                        new EnumMap<>(PlatformComponent.class));

        assertFalse(result.isAvailable());
        assertTrue(result.components().isEmpty());
    }

    private static PlatformDevice device(final String fingerprint) {
        return new PlatformDevice(
                "nubia", "nubia", "NX809J", "NX809J", "NX809J-UN",
                fingerprint, 36);
    }
}
