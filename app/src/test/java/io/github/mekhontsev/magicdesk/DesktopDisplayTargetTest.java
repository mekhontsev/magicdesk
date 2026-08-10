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

    @Test
    public void profileMetadataIsExplicitAndImmutable() {
        final DesktopDisplayTarget target = DesktopDisplayTarget.wired(7)
                .withProfile(3, "display:wired:local:123");

        assertEquals(7, target.displayId);
        assertEquals(3, target.profileDisplayId);
        assertEquals("display:wired:local:123", target.profileKey);
    }

    @Test(expected = IllegalArgumentException.class)
    public void secondaryTargetRejectsDefaultDisplay() {
        DesktopDisplayTarget.wireless(0);
    }
}
