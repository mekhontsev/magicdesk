package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class RedmagicFanCurveTest {
    @Test
    public void raisesLevelAtConfiguredThresholds() {
        assertEquals(0, RedmagicFanCurve.levelFor(39_000, 0));
        assertEquals(1, RedmagicFanCurve.levelFor(42_000, 0));
        assertEquals(3, RedmagicFanCurve.levelFor(65_000, 2));
        assertEquals(5, RedmagicFanCurve.levelFor(90_000, 4));
    }

    @Test
    public void appliesHysteresisWhenCooling() {
        assertEquals(3, RedmagicFanCurve.levelFor(60_000, 3));
        assertEquals(2, RedmagicFanCurve.levelFor(58_000, 3));
    }

    @Test
    public void keepsCurrentLevelWhenTemperatureIsUnavailable() {
        assertEquals(
                4,
                RedmagicFanCurve.levelFor(
                        RedmagicHardwareSnapshot.UNKNOWN, 4));
    }

    @Test
    public void appliesWhenTargetOrActualFanStateDrifts() {
        assertTrue(RedmagicFanCurve.needsApply(3, 2, 1, 2));
        assertTrue(RedmagicFanCurve.needsApply(3, 3, 0, 3));
        assertTrue(RedmagicFanCurve.needsApply(3, 3, 1, 2));
        assertTrue(RedmagicFanCurve.needsApply(0, 0, 1, 0));
        assertFalse(RedmagicFanCurve.needsApply(3, 3, 1, 3));
        assertFalse(RedmagicFanCurve.needsApply(0, 0, 0, 4));
    }
}
