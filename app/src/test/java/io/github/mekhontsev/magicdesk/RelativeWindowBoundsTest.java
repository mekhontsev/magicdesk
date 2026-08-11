package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

public final class RelativeWindowBoundsTest {
    @Test
    public void roundTripPreservesBoundsOnSameWorkArea() {
        final RelativeWindowBounds relative =
                RelativeWindowBounds.from(
                        1080, 120, 1800, 840,
                        0, 0, 1920, 1016);

        assertArrayEquals(
                new int[] {1080, 120, 1800, 840},
                relative.resolve(0, 0, 1920, 1016));
    }

    @Test
    public void rightAlignedWindowRemainsRightAlignedOnAnotherDisplay() {
        final RelativeWindowBounds relative = RelativeWindowBounds.from(
                1120, 100, 1920, 900,
                0, 0, 1920, 1000);

        assertArrayEquals(
                new int[] {747, 100, 1280, 900},
                relative.resolve(0, 0, 1280, 1000));
    }

    @Test
    public void resolvedBoundsStayInsideOffsetWorkArea() {
        final RelativeWindowBounds relative =
                new RelativeWindowBounds(10_000, 10_000, 8000, 8000);

        assertArrayEquals(
                new int[] {300, 200, 1100, 1000},
                relative.resolve(100, 0, 1100, 1000));
    }
}
