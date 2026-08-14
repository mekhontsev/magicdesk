package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DesktopSelfTestTargetTest {
    @Test
    public void distinguishesDisplayByIdentityAndTransport() {
        assertTrue(DesktopSelfTestTarget.PHONE.matchesDisplay(
                0, DesktopDisplayTarget.phone()));
        assertFalse(DesktopSelfTestTarget.PHONE.matchesDisplay(
                3, DesktopDisplayTarget.wired(3)));

        assertTrue(DesktopSelfTestTarget.SIMULATED.matchesDisplay(
                195, DesktopDisplayTarget.simulated(195)));
        assertFalse(DesktopSelfTestTarget.SIMULATED.matchesDisplay(
                3, DesktopDisplayTarget.wired(3)));

        assertTrue(DesktopSelfTestTarget.EXTERNAL.matchesDisplay(
                3, DesktopDisplayTarget.wired(3)));
        assertTrue(DesktopSelfTestTarget.EXTERNAL.matchesDisplay(
                4, DesktopDisplayTarget.wireless(4)));
        assertFalse(DesktopSelfTestTarget.EXTERNAL.matchesDisplay(
                195, DesktopDisplayTarget.simulated(195)));
        assertFalse(DesktopSelfTestTarget.EXTERNAL.matchesDisplay(
                3, DesktopDisplayTarget.wireless(4)));
    }

    @Test
    public void platformDesktopBlocksSimulatedTestWithoutLocalActivity() {
        assertEquals(4, DesktopSelfTestController
                .findBlockingDesktopDisplay(-1, 4));
        assertEquals(3, DesktopSelfTestController
                .findBlockingDesktopDisplay(3, 4));
        assertEquals(-1, DesktopSelfTestController
                .findBlockingDesktopDisplay(-1, -1));
    }
}
