package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ShellFrameAdmissionTest {
    @Test public void inputRequiresBothPresentationReceiptsBeforeTheRegion() {
        var admission = new ShellFrameAdmission();
        long generation = admission.begin();
        assertFalse(admission.pixels(generation));
        assertFalse(admission.region(generation));
        assertTrue(admission.layout(generation));
        assertFalse(admission.ready());
        assertFalse(admission.pixels(generation));
        assertFalse(admission.ready());
        assertTrue(admission.region(generation));
        assertTrue(admission.ready());
        assertFalse(admission.region(generation));
    }

    @Test public void replacingAtEveryPhaseRejectsOldCallbacks() {
        for (int stage = 0; stage < 4; stage++) {
            var admission = new ShellFrameAdmission();
            long old = admission.begin();
            if (stage > 0) admission.layout(old);
            if (stage > 1) admission.pixels(old);
            if (stage > 2) admission.region(old);
            long current = admission.begin();
            assertFalse(admission.current(old));
            assertFalse(admission.layout(old));
            assertFalse(admission.pixels(old));
            assertFalse(admission.region(old));
            assertFalse(admission.ready());
            assertFalse(admission.layout(current));
            assertTrue(admission.pixels(current));
            assertTrue(admission.region(current));
        }
    }

    @Test public void windowAndPixelsCanArriveInEitherOrderWithoutDuplicatePublication() {
        var admission = new ShellFrameAdmission();
        long first = admission.begin();
        assertFalse(admission.layout(first));
        assertFalse(admission.layout(first));
        assertTrue(admission.pixels(first));
        assertFalse(admission.pixels(first));
        assertTrue(admission.region(first));
        long next = admission.begin();
        assertFalse(admission.pixels(next));
        assertFalse(admission.pixels(next));
        assertTrue(admission.layout(next));
        assertFalse(admission.layout(next));
        assertTrue(admission.region(next));
    }

    @Test public void releaseAndFailureCannotBeRevivedByLateReceipts() {
        var admission = new ShellFrameAdmission();
        long old = admission.begin();
        admission.layout(old);
        admission.revoke();
        assertEquals(ShellFrameAdmission.Phase.IDLE, admission.phase());
        assertFalse(admission.current(old));
        assertFalse(admission.pixels(old));
        assertFalse(admission.region(old));
        assertTrue(admission.begin() > old);
    }
}
