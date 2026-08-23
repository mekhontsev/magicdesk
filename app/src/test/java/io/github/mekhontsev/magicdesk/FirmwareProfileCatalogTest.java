package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class FirmwareProfileCatalogTest {
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
    public void classifiesExactKnownFirmwareFingerprints() throws Exception {
        final FirmwareProfileCatalog catalog = catalog();

        assertEquals(
                PlatformSupportLevel.MAINTAINER_VERIFIED,
                catalog.find("nubia", device(
                        "NX809J", "NX809J", EEA_FINGERPRINT)).supportLevel);
        assertEquals(
                PlatformSupportLevel.COMMUNITY_TESTED,
                catalog.find("nubia", device(
                        "NX809J", "NX809J", GLOBAL_FINGERPRINT)).supportLevel);
        assertEquals(
                PlatformSupportLevel.COMMUNITY_TESTED,
                catalog.find("nubia", device(
                        "NX741J", "PQ85A01", Z80_ULTRA_FINGERPRINT)).supportLevel);
    }

    @Test
    public void rejectsPlatformModelAndOtaMismatches() throws Exception {
        final FirmwareProfileCatalog catalog = catalog();

        assertNull(catalog.find("android", device(
                "NX809J", "NX809J", EEA_FINGERPRINT)));
        assertNull(catalog.find("nubia", device(
                "NX999J", "NX999J", GLOBAL_FINGERPRINT)));
        assertNull(catalog.find("nubia", device(
                "NX741J", "PQ85A01", Z80_ULTRA_FINGERPRINT + ".new")));
    }

    private static FirmwareProfileCatalog catalog() throws Exception {
        return FirmwareProfileCatalog.parse(Files.readString(
                Path.of("src", "main", "assets", "compatibility",
                        "firmware-profiles.json"),
                StandardCharsets.UTF_8));
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
