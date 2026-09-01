package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Test;

public final class DesktopSelfTestCancellationTest {
    @After
    public void resetCancellation() {
        DesktopSelfTestCancellation.finishRun();
    }

    @Test
    public void acceptsOnlyOneRequestDuringActiveRun() {
        assertFalse(DesktopSelfTestCancellation.request());

        DesktopSelfTestCancellation.beginRun();
        assertTrue(DesktopSelfTestCancellation.request());
        assertFalse(DesktopSelfTestCancellation.request());
        assertTrue(DesktopSelfTestCancellation.isRequested());
    }

    @Test
    public void checkpointStopsRequestedRun() {
        DesktopSelfTestCancellation.beginRun();
        DesktopSelfTestCancellation.checkpoint();
        DesktopSelfTestCancellation.request();

        try {
            DesktopSelfTestCancellation.checkpoint();
            fail("cancellation checkpoint did not stop the run");
        } catch (DesktopSelfTestCancellation.Cancelled expected) {
            assertTrue(DesktopSelfTestCancellation.isRequested());
        }
    }
}
