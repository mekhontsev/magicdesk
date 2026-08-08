package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class DeviceSetupFirmwareProfileTest {
    private static final String EEA_FINGERPRINT =
            "REDMAGIC/NX809J-EEA/NX809J:16/"
                    + "BQ2A.250705.001-BP2A.250605.031.A3/"
                    + "20260204.221845:user/release-keys";
    private static final String GLOBAL_FINGERPRINT =
            "REDMAGIC/NX809J-UN/NX809J:16/"
                    + "BQ2A.250705.001-BP2A.250605.031.A3/"
                    + "20260625.022314:user/release-keys";

    @Test
    public void classifiesExactKnownFirmwareFingerprints() {
        assertEquals(
                DeviceSetupManager.FirmwareSupport.MAINTAINER_VERIFIED,
                DeviceSetupManager.classifyFirmware(
                        "NX809J", "NX809J", EEA_FINGERPRINT));
        assertEquals(
                DeviceSetupManager.FirmwareSupport.COMMUNITY_TESTED,
                DeviceSetupManager.classifyFirmware(
                        "NX809J", "NX809J", GLOBAL_FINGERPRINT));
    }

    @Test
    public void treatsModelOrOtaMismatchAsUnverified() {
        assertEquals(
                DeviceSetupManager.FirmwareSupport.UNVERIFIED,
                DeviceSetupManager.classifyFirmware(
                        "NX809J", "NX809J", GLOBAL_FINGERPRINT + ".new"));
        assertEquals(
                DeviceSetupManager.FirmwareSupport.UNVERIFIED,
                DeviceSetupManager.classifyFirmware(
                        "NX999J", "NX999J", GLOBAL_FINGERPRINT));
    }
}
