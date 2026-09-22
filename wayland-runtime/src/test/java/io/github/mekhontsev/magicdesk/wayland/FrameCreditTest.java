package io.github.mekhontsev.magicdesk.wayland;

import org.junit.Test;
import static org.junit.Assert.*;

public final class FrameCreditTest {
    @Test public void renderAdmissionDoesNotConsumeCreditWithoutAFrame() {
        var credit = new FrameCredit();
        assertTrue(credit.canRender());
        assertTrue(credit.canRender());
        assertEquals(0, credit.pending());
        long serial = credit.offer();
        assertFalse(credit.canRender());
        assertTrue(credit.acknowledge(serial));
        assertTrue(credit.canRender());
    }

    @Test public void onlyOneUnacknowledgedFrame() {
        var credit = new FrameCredit();
        long first = credit.offer();
        assertTrue(first > 0);
        assertEquals(0, credit.offer());
        assertEquals(0, credit.offer());
        assertEquals(first, credit.pending());
        assertTrue(credit.acknowledge(first));
        assertEquals(0, credit.pending());
        assertTrue(credit.offer() > first);
    }

    @Test public void staleAcknowledgementCannotReleaseNewFrame() {
        var credit = new FrameCredit();
        long first = credit.offer();
        assertFalse(credit.acknowledge(first));
        long second = credit.offer();
        assertFalse(credit.acknowledge(first));
        assertFalse(credit.acknowledge(0));
        assertEquals(second, credit.pending());
        assertEquals(0, credit.offer());
        assertTrue(credit.acknowledge(second));
        assertFalse(credit.acknowledge(second));
    }
}
