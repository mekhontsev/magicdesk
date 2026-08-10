package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public final class DisplayProfileControllerTest {
    private static final String FIRST_HASH =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String SECOND_HASH =
            "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";

    @Test
    public void parsesSingleConnectedConnector() {
        assertEquals(
                FIRST_HASH,
                DisplayProfileController.parseSingleConnectedEdidHash(
                        FIRST_HASH + " /sys/class/drm/card0-DP-1"));
    }

    @Test
    public void rejectsMultipleConnectedConnectors() {
        assertNull(DisplayProfileController.parseSingleConnectedEdidHash(
                FIRST_HASH + " /sys/class/drm/card0-DP-1\n"
                        + SECOND_HASH + " /sys/class/drm/card0-DP-2"));
    }

    @Test
    public void ignoresDiagnosticsButRequiresHash() {
        assertEquals(
                FIRST_HASH,
                DisplayProfileController.parseSingleConnectedEdidHash(
                        "permission warning\n"
                                + FIRST_HASH + " /sys/class/drm/card0-DP-1"));
        assertNull(DisplayProfileController.parseSingleConnectedEdidHash(
                "no connected display"));
    }

    @Test
    public void stableProfilePrefersDisplayUniqueId() {
        assertEquals(
                "display:wireless:wifi:aa:bb:cc",
                DisplayProfileController.stableProfileKey(
                        DesktopDisplayTarget.Kind.WIRELESS,
                        "wifi:aa:bb:cc",
                        "Living room",
                        null));
    }

    @Test
    public void stableProfileFallbackDoesNotUseLogicalDisplayId() {
        assertEquals(
                "display:simulated:MagicDesk test|unknown",
                DisplayProfileController.stableProfileKey(
                        DesktopDisplayTarget.Kind.SIMULATED,
                        "",
                        "MagicDesk test",
                        null));
    }
}
