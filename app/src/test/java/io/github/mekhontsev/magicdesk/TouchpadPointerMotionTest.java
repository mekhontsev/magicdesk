package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class TouchpadPointerMotionTest {
    @Test
    public void deltasAreCalculatedFromStableGestureAnchor() {
        final TouchpadPointerMotion motion = startedMotion();

        assertTrue(motion.move(110.0f, 96.0f));
        assertEquals(10.0f, motion.deltaX(), 0.001f);
        assertEquals(-4.0f, motion.deltaY(), 0.001f);

        motion.move(111.0f, 97.0f);
        assertEquals(1.0f, motion.deltaX(), 0.001f);
        assertEquals(1.0f, motion.deltaY(), 0.001f);
    }

    @Test
    public void subpixelMotionIsPreservedWithoutAdditionalGain() {
        final TouchpadPointerMotion motion = startedMotion();

        motion.move(100.25f, 99.5f);
        assertEquals(0.25f, motion.deltaX(), 0.001f);
        assertEquals(-0.5f, motion.deltaY(), 0.001f);
    }

    @Test
    public void stoppedMotionRejectsUpdates() {
        final TouchpadPointerMotion motion = startedMotion();
        motion.stop();

        assertFalse(motion.move(110.0f, 100.0f));
    }

    private static TouchpadPointerMotion startedMotion() {
        final TouchpadPointerMotion motion = new TouchpadPointerMotion();
        motion.start(100.0f, 100.0f);
        return motion;
    }
}
