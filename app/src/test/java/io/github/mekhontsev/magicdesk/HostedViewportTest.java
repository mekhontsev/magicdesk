package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import static org.junit.Assert.*;

public final class HostedViewportTest {
    private static final float EPSILON = 0.0001f;

    @Test public void sameAspectRatioFillsHost() {
        var viewport = HostedViewport.fit(1920, 1080, 960, 540);
        assertEquals(new HostedViewport(0, 0, 1920, 1080), viewport);
        assertEquals(.5f, viewport.contentX(960), EPSILON);
        assertEquals(.5f, viewport.contentY(540), EPSILON);
    }

    @Test public void wideContentHasTopAndBottomMargins() {
        var viewport = HostedViewport.fit(1000, 1000, 2000, 1000);
        assertEquals(new HostedViewport(0, 250, 1000, 500), viewport);
        assertEquals(0, viewport.contentY(250), EPSILON);
        assertEquals(1, viewport.contentY(750), EPSILON);
    }

    @Test public void tallContentHasSideMargins() {
        var viewport = HostedViewport.fit(1000, 1000, 1000, 2000);
        assertEquals(new HostedViewport(250, 0, 500, 1000), viewport);
        assertEquals(0, viewport.contentX(250), EPSILON);
        assertEquals(1, viewport.contentX(750), EPSILON);
    }

    @Test public void marginsRemainOutsideContentForBackendPolicy() {
        var viewport = HostedViewport.fit(1000, 1000, 1000, 2000);
        assertEquals(-.5f, viewport.contentX(0), EPSILON);
        assertEquals(1.5f, viewport.contentX(1000), EPSILON);
    }

    @Test public void fractionalCoordinatesRoundTrip() {
        for (var viewport : new HostedViewport[]{HostedViewport.fit(731, 919, 1920, 1080),
                HostedViewport.fit(1920, 1080, 731, 919)}) {
            for (float value : new float[]{-.1f, 0, .137f, .5f, 1, 1.1f}) {
                assertEquals(value, viewport.contentX(viewport.hostX(value)), EPSILON);
                assertEquals(value, viewport.contentY(viewport.hostY(value)), EPSILON);
            }
        }
    }

    @Test public void missingFrameOrHostHasNoCoordinates() {
        for (var viewport : new HostedViewport[]{HostedViewport.EMPTY, HostedViewport.fit(0, 100, 20, 30),
                HostedViewport.fit(100, 0, 20, 30), HostedViewport.fit(100, 100, 0, 30),
                HostedViewport.fit(100, 100, 20, -1)}) {
            assertFalse(viewport.available());
            assertEquals(0, viewport.contentX(50), EPSILON);
            assertEquals(0, viewport.contentY(50), EPSILON);
        }
    }
}
