package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

public final class ShellTransientTaskBoundsControllerTest {
    @Test
    public void pulseDirectionKeepsBoundsInsideDisplay() {
        final int[] directions = new int[] {
                select(78, 75, 1518, 885),
                select(0, 75, 1440, 885),
                select(0, 75, 1920, 1080),
                select(0, 0, 1920, 885),
                select(0, 0, 1920, 1080)
        };
        assertArrayEquals(new int[] {0, 1, 2, 3, -1}, directions);
    }

    private static int select(
            final int left,
            final int top,
            final int right,
            final int bottom) {
        return ShellTransientTaskBoundsController.selectPulseDirection(
                left, top, right, bottom, 0, 0, 1920, 1080);
    }
}
