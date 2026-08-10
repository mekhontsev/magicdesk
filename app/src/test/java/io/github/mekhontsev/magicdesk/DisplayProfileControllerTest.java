package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class DisplayProfileControllerTest {
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
