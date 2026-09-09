package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

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

    @Test
    public void recognizesMeasuredFixtureUnderSystemShadow() {
        final DesktopSelfTestFixtureAppearance green =
                DesktopSelfTestFixtureAppearance.SECONDARY;
        assertTrue(green.matchesRenderedColor(0xFF176B3A));
        assertTrue(green.matchesRenderedColor(0xFF12542E));
        assertTrue(green.matchesRenderedColor(0xFF135B31));
        assertTrue(green.matchesRenderedColor(0xFF145C32));
        assertTrue(DesktopSelfTestFixtureAppearance.PRIMARY
                .matchesRenderedColor(0xFF822222));
    }

    @Test
    public void shadedFixturesRemainDistinctFromEveryOtherFixture() {
        for (final DesktopSelfTestFixtureAppearance expected
                : DesktopSelfTestFixtureAppearance.values()) {
            for (final DesktopSelfTestFixtureAppearance rendered
                    : DesktopSelfTestFixtureAppearance.values()) {
                for (int percent = 55; percent <= 100; percent++) {
                    final int pixel = shade(rendered.color(), percent);
                    assertEquals(expected + " vs " + rendered + " at " + percent,
                            expected == rendered,
                            expected.matchesRenderedColor(pixel));
                }
            }
        }
    }

    @Test
    public void rejectsMissingObscuredOrRecoloredFixture() {
        for (final DesktopSelfTestFixtureAppearance fixture
                : DesktopSelfTestFixtureAppearance.values()) {
            for (final int pixel : new int[] {
                    0xFF000000, 0xFFFFFFFF, 0xFF777777, 0xFF111827,
                    0xFF0C1416, 0xFF293134, 0xFF151D21,
                    shade(fixture.color(), 40), fixture.color() & 0x00FFFFFF}) {
                assertFalse(fixture + " accepted " + Integer.toHexString(pixel),
                        fixture.matchesRenderedColor(pixel));
            }
            assertFalse(fixture.matchesRenderedColor(fixture.color() + 0x00141414));
        }
    }

    private static int shade(final int color, final int percent) {
        int result = 0xFF000000;
        for (int shift = 0; shift <= 16; shift += 8) {
            result |= Math.round(((color >>> shift) & 0xFF) * percent / 100f) << shift;
        }
        return result;
    }
}
