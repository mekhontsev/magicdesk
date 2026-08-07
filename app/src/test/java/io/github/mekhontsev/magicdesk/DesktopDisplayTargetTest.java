package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class DesktopDisplayTargetTest {
    @Test
    public void factoriesPreserveKindAndDisplay() {
        assertEquals(
                DesktopDisplayTarget.Kind.WIRED,
                DesktopDisplayTarget.wired(7).kind);
        assertEquals(
                DesktopDisplayTarget.Kind.WIRELESS,
                DesktopDisplayTarget.wireless(8).kind);
        assertEquals(
                DesktopDisplayTarget.Kind.SIMULATED,
                DesktopDisplayTarget.simulated(9).kind);
        assertEquals(8, DesktopDisplayTarget.wireless(8).displayId);
    }

    @Test(expected = IllegalArgumentException.class)
    public void secondaryTargetRejectsDefaultDisplay() {
        DesktopDisplayTarget.wireless(0);
    }
}
