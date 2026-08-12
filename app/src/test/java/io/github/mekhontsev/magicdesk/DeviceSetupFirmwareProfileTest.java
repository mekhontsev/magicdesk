package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class DeviceSetupFirmwareProfileTest {
    private static final NubiaPlatformDriver DRIVER =
            new NubiaPlatformDriver();
    private static final String EEA_FINGERPRINT =
            "REDMAGIC/NX809J-EEA/NX809J:16/"
                    + "BQ2A.250705.001-BP2A.250605.031.A3/"
                    + "20260204.221845:user/release-keys";
    private static final String GLOBAL_FINGERPRINT =
            "REDMAGIC/NX809J-UN/NX809J:16/"
                    + "BQ2A.250705.001-BP2A.250605.031.A3/"
                    + "20260625.022314:user/release-keys";
    private static final String Z80_ULTRA_FINGERPRINT =
            "nubia/PQ85A01-UN/PQ85A01:16/"
                    + "BQ2A.250705.001-BP2A.250605.031.A3/"
                    + "20251229.234747:user/release-keys";

    @Test
    public void classifiesExactKnownFirmwareFingerprints() {
        assertEquals(
                PlatformSupportLevel.MAINTAINER_VERIFIED,
                DRIVER.supportLevel(device(
                        "NX809J", "NX809J", EEA_FINGERPRINT)));
        assertEquals(
                PlatformSupportLevel.COMMUNITY_TESTED,
                DRIVER.supportLevel(device(
                        "NX809J", "NX809J", GLOBAL_FINGERPRINT)));
        assertEquals(
                PlatformSupportLevel.COMMUNITY_TESTED,
                DRIVER.supportLevel(device(
                        "NX741J", "PQ85A01", Z80_ULTRA_FINGERPRINT)));
    }

    @Test
    public void treatsModelOrOtaMismatchAsUnverified() {
        assertEquals(
                PlatformSupportLevel.UNVERIFIED,
                DRIVER.supportLevel(device(
                        "NX809J", "NX809J", GLOBAL_FINGERPRINT + ".new")));
        assertEquals(
                PlatformSupportLevel.UNVERIFIED,
                DRIVER.supportLevel(device(
                        "NX999J", "NX999J", GLOBAL_FINGERPRINT)));
        assertEquals(
                PlatformSupportLevel.UNVERIFIED,
                DRIVER.supportLevel(device(
                        "NX741J", "PQ85A01",
                        Z80_ULTRA_FINGERPRINT + ".new")));
    }

    private static PlatformDevice device(
            final String model,
            final String device,
            final String fingerprint) {
        return new PlatformDevice(
                "nubia", "nubia", model, device, device,
                fingerprint, 36);
    }
}
