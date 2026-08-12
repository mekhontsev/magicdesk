package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DesktopSelfTestTargetTest {
    @Test
    public void distinguishesDisplayByIdentityAndTransport() {
        assertTrue(DesktopSelfTestTarget.PHONE.matchesDisplay(0, null));
        assertFalse(DesktopSelfTestTarget.PHONE.matchesDisplay(
                3, DesktopDisplayTarget.Kind.WIRED));

        assertTrue(DesktopSelfTestTarget.SIMULATED.matchesDisplay(
                195, DesktopDisplayTarget.Kind.SIMULATED));
        assertFalse(DesktopSelfTestTarget.SIMULATED.matchesDisplay(
                3, DesktopDisplayTarget.Kind.WIRED));

        assertTrue(DesktopSelfTestTarget.EXTERNAL.matchesDisplay(
                3, DesktopDisplayTarget.Kind.WIRED));
        assertTrue(DesktopSelfTestTarget.EXTERNAL.matchesDisplay(
                4, DesktopDisplayTarget.Kind.WIRELESS));
        assertFalse(DesktopSelfTestTarget.EXTERNAL.matchesDisplay(
                195, DesktopDisplayTarget.Kind.SIMULATED));
    }
}
