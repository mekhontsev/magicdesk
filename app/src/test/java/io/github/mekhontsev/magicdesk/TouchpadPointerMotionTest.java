package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class TouchpadPointerMotionTest {
    @Test
    public void positionIsCalculatedFromStableGestureAnchor() {
        final TouchpadPointerMotion motion = startedMotion(1.0f, false);

        assertTrue(motion.move(110.0f, 96.0f, 0.0));
        assertEquals(510, motion.outputX());
        assertEquals(396, motion.outputY());

        motion.move(111.0f, 97.0f, 0.0);
        assertEquals(511, motion.outputX());
        assertEquals(397, motion.outputY());
    }

    @Test
    public void velocityScaleChangeReanchorsWithoutJump() {
        final TouchpadPointerMotion motion = startedMotion(1.0f, false);

        motion.move(110.0f, 100.0f, 2_000.0);
        assertEquals(510, motion.outputX());

        motion.move(111.0f, 100.0f, 2_000.0);
        assertEquals(512, motion.outputX());
    }

    @Test
    public void sensitivityAndDisplayBoundsAreApplied() {
        final TouchpadPointerMotion motion = startedMotion(0.5f, false);

        motion.move(300.0f, -1_000.0f, 0.0);
        assertEquals(600, motion.outputX());
        assertEquals(0, motion.outputY());

        motion.move(5_000.0f, 5_000.0f, 0.0);
        assertEquals(1_919, motion.outputX());
        assertEquals(1_079, motion.outputY());
    }

    @Test
    public void nubiaVelocityCurvesArePreserved() {
        assertEquals(1, TouchpadPointerMotion.velocityScale(1_500.0, false));
        assertEquals(2, TouchpadPointerMotion.velocityScale(1_500.1, false));
        assertEquals(4, TouchpadPointerMotion.velocityScale(2_500.1, false));
        assertEquals(6, TouchpadPointerMotion.velocityScale(3_500.1, false));

        assertEquals(1, TouchpadPointerMotion.velocityScale(1_000.0, true));
        assertEquals(3, TouchpadPointerMotion.velocityScale(1_000.1, true));
        assertEquals(6, TouchpadPointerMotion.velocityScale(2_000.1, true));
    }

    @Test
    public void stoppedMotionRejectsUpdates() {
        final TouchpadPointerMotion motion = startedMotion(1.0f, false);
        motion.stop();

        assertFalse(motion.move(110.0f, 100.0f, 0.0));
    }

    private static TouchpadPointerMotion startedMotion(
            final float sensitivity,
            final boolean dragging) {
        final TouchpadPointerMotion motion = new TouchpadPointerMotion();
        motion.start(
                100.0f, 100.0f,
                500, 400,
                1_919, 1_079,
                sensitivity,
                dragging);
        return motion;
    }
}
