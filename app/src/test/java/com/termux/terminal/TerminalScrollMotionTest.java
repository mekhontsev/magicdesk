package com.termux.terminal;

import org.junit.Test;

import static org.junit.Assert.*;

public final class TerminalScrollMotionTest {
    @Test public void retargetingPreservesPositionAndVelocity() {
        TerminalScrollMotion motion = new TerminalScrollMotion();
        motion.add(300, 2000, 32, 0);
        motion.advance(37);
        double remaining = motion.remainingPixels(), velocity = motion.pixelsPerMillis();
        assertTrue(velocity > 0);
        motion.add(300, 2000, 32, 37);
        assertEquals(remaining + 300, motion.remainingPixels(), 1e-9);
        assertEquals(velocity, motion.pixelsPerMillis(), 1e-9);
    }

    @Test public void splitEditsHaveTheSameMotionAsOnePacket() {
        TerminalScrollMotion single = new TerminalScrollMotion(), split = new TerminalScrollMotion();
        for (int time : new int[] {0, 95, 207, 330}) {
            single.add(300, 2000, 32, time);
            for (int i = 0; i < 5; i++) split.add(60, 2000, 32, time);
            for (int dt = 0; dt < 80; dt += 8) {
                single.advance(time + dt);
                split.advance(time + dt);
                assertEquals(single.remainingPixels(), split.remainingPixels(), 1e-8);
                assertEquals(single.pixelsPerMillis(), split.pixelsPerMillis(), 1e-8);
            }
        }
    }

    @Test public void framesDoNotDetermineTheMotionCurve() {
        TerminalScrollMotion fine = new TerminalScrollMotion(), coarse = new TerminalScrollMotion();
        fine.add(1200, 2000, 32, 0);
        coarse.add(1200, 2000, 32, 0);
        for (int time = 1; time <= 180; time++) fine.advance(time);
        coarse.advance(180);
        assertEquals(coarse.remainingPixels(), fine.remainingPixels(), 1e-8);
        assertEquals(coarse.pixelsPerMillis(), fine.pixelsPerMillis(), 1e-8);
    }

    @Test public void repeatedPacketsDoNotStopBetweenEdits() {
        for (int interval : new int[] {80, 120, 160}) {
            TerminalScrollMotion motion = new TerminalScrollMotion();
            for (int packet = 0; packet < 12; packet++) {
                int time = packet * interval;
                motion.add(300, 2000, 32, time);
                for (int dt = 8; dt < interval; dt += 8) {
                    assertTrue("stopped between packets", motion.advance(time + dt));
                    assertTrue("barely moving between packets", motion.pixelsPerMillis() > 0.25);
                }
            }
            assertFalse("kept moving after output stopped", motion.advance(12L * interval + 400));
        }
    }

    @Test public void motionStaysBoundedMonotonicAndSettlesAfterABurst() {
        TerminalScrollMotion motion = new TerminalScrollMotion();
        for (int time = 0; time < 1000; time++) {
            motion.add(300, 2000, 32, time);
            double before = motion.remainingPixels();
            assertTrue(before <= 2000);
            motion.advance(time + 1);
            assertTrue(motion.remainingPixels() >= 0 && motion.remainingPixels() <= before);
            assertTrue(motion.pixelsPerMillis() >= 0);
        }
        assertFalse(motion.advance(1400));
        assertEquals(0, motion.remainingPixels(), 0);
        assertEquals(0, motion.pixelsPerMillis(), 0);
    }

    @Test public void aSingleEditStartsPromptlyAndStopsWithoutExtrapolation() {
        for (int pixels : new int[] {20, 300, 5000}) {
            TerminalScrollMotion motion = new TerminalScrollMotion();
            motion.add(pixels, 5000, 32, 0);
            assertTrue(motion.advance(16));
            assertTrue(motion.remainingPixels() < pixels - 1);
            assertFalse(motion.advance(400));
            assertFalse(motion.advance(800));
        }
    }

    @Test public void resetDropsVelocityAndOldClock() {
        TerminalScrollMotion motion = new TerminalScrollMotion();
        motion.add(300, 2000, 32, 0);
        motion.advance(40);
        motion.reset();
        assertFalse(motion.advance(41));
        motion.add(60, 2000, 32, 1000);
        assertEquals(60, motion.remainingPixels(), 0);
        assertEquals(0, motion.pixelsPerMillis(), 0);
    }

    @Test public void cadenceFollowsInputRatherThanTerminalPacketsOrFixedMilliseconds() {
        for (int period : new int[] {75, 150, 300}) {
            TerminalScrollMotion motion = new TerminalScrollMotion();
            for (int step = 0; step < 8; step++) {
                int time = step * period;
                motion.input(-1, true, 8, time);
                for (int edit = 0; edit < 5; edit++) motion.add(60, 2000, 8, time);
                for (int t = time + 8; t < time + period; t += 8) {
                    motion.advance(t);
                    if (step > 1) assertTrue("stopped within a continuous gesture", motion.pixelsPerMillis() > 0.5);
                }
            }
            long end = 8L * period;
            motion.endInput(end);
            assertFalse("gesture release retained a slow catch-up", motion.advance(end + 200));
        }
    }

    @Test public void idleWheelGapDoesNotBecomeAPlaybackDuration() {
        TerminalScrollMotion motion = new TerminalScrollMotion();
        motion.input(-1, false, 16, 0);
        motion.add(300, 2000, 16, 0);
        motion.advance(1000);
        motion.input(-1, false, 16, 60000);
        motion.add(300, 2000, 16, 60000);
        assertFalse(motion.advance(60300));
    }

    @Test public void cadenceChangesPreserveVelocityWithoutOvershooting() {
        TerminalScrollMotion motion = new TerminalScrollMotion();
        for (int time : new int[] {0, 120, 240, 256, 272, 480, 900, 916}) {
            motion.advance(time);
            double velocity = motion.pixelsPerMillis();
            motion.input(-1, true, 8, time);
            assertEquals(velocity, motion.pixelsPerMillis(), 1e-9);
            motion.add(300, 2000, 8, time);
            double previous = motion.remainingPixels();
            for (int t = time + 1; t < time + 16; t++) {
                motion.advance(t);
                assertTrue(motion.remainingPixels() >= 0 && motion.remainingPixels() <= previous);
                previous = motion.remainingPixels();
            }
        }
    }

    @Test public void stationaryFingerDoesNotTurnItsPauseIntoSlowPlayback() {
        TerminalScrollMotion motion = new TerminalScrollMotion();
        for (int time : new int[] {0, 120, 240}) {
            motion.input(-1, true, 8, time);
            motion.add(300, 2000, 8, time);
        }
        motion.advance(1000);
        motion.input(-1, true, 8, 60000);
        motion.add(300, 2000, 8, 60000);
        assertFalse(motion.advance(60200));
        // A resumed gesture can acquire its new cadence again.
        motion.input(-1, true, 8, 60300);
        motion.add(300, 2000, 8, 60300);
        assertTrue(motion.advance(60500));
        motion.endInput(60500);
        assertFalse(motion.advance(60700));
    }
}
