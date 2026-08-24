package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

public final class DesktopSelfTestFixtureAppearanceTest {
    @Test
    public void fixtureColorsAreOpaqueAndDistinct() {
        final int primary =
                DesktopSelfTestFixtureAppearance.PRIMARY.color();
        final int secondary =
                DesktopSelfTestFixtureAppearance.SECONDARY.color();
        final int transition =
                DesktopSelfTestFixtureAppearance.TRANSITION.color();

        assertEquals(0xFF000000, primary & 0xFF000000);
        assertEquals(0xFF000000, secondary & 0xFF000000);
        assertEquals(0xFF000000, transition & 0xFF000000);
        assertNotEquals(primary, secondary);
        assertNotEquals(primary, transition);
        assertNotEquals(secondary, transition);
    }
}
