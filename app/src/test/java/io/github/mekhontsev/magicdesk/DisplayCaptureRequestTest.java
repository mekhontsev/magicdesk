package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public final class DisplayCaptureRequestTest {
    @Test public void fullDisplayIsResolvedAtCaptureTime() {
        final var request = new DisplayCaptureRequest(3, null);
        assertEquals(new DisplayCaptureRequest.Region(0, 0, 1216, 2688), request.regionFor(1216, 2688));
        assertEquals(new DisplayCaptureRequest.Region(0, 0, 2688, 1216), request.regionFor(2688, 1216));
    }

    @Test public void nonzeroOriginAndExclusiveEdgesPreserveExactPixels() {
        final var region = new DisplayCaptureRequest.Region(27, 49, 192, 201);
        assertEquals(165, region.width());
        assertEquals(152, region.height());
        assertEquals(region, new DisplayCaptureRequest(0, region).regionFor(192, 201));
        final var lastPixel = new DisplayCaptureRequest.Region(191, 200, 192, 201);
        assertEquals(1, lastPixel.width());
        assertEquals(1, lastPixel.height());
        assertEquals(lastPixel, new DisplayCaptureRequest(0, lastPixel).regionFor(192, 201));
    }

    @Test public void invalidAndPartiallyOutsideRegionsAreNeverClipped() {
        for (final int[] bounds : new int[][] {
                {-1, 0, 2, 2}, {0, -1, 2, 2}, {2, 0, 2, 2}, {0, 2, 2, 2},
                {3, 0, 2, 2}, {0, 3, 2, 2}, {Integer.MAX_VALUE, 0, Integer.MIN_VALUE, 1}}) {
            assertThrows(IllegalArgumentException.class, () -> new DisplayCaptureRequest.Region(
                    bounds[0], bounds[1], bounds[2], bounds[3]));
        }
        for (final var region : new DisplayCaptureRequest.Region[] {
                new DisplayCaptureRequest.Region(1, 2, 101, 100),
                new DisplayCaptureRequest.Region(1, 2, 100, 101),
                new DisplayCaptureRequest.Region(0, 0, Integer.MAX_VALUE, 1)}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new DisplayCaptureRequest(0, region).regionFor(100, 100));
        }
        assertThrows(IllegalArgumentException.class, () -> new DisplayCaptureRequest(-1, null));
        assertThrows(IllegalArgumentException.class, () -> new DisplayCaptureRequest(0, null).regionFor(0, 1));
    }
}
