package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class RedmagicHardwareSnapshotTest {
    @Test
    public void parsesControlNodesAndRealThermalSensors() {
        final RedmagicHardwareSnapshot snapshot =
                RedmagicHardwareSnapshot.parse(
                        "node.fan_enable=1\n"
                        + "node.fan_level=3\n"
                        + "node.fan_rpm=7200\n"
                        + "node.pump_enable=0\n"
                        + "node.pump_frequency=4\n"
                        + "node.pump_speed=80\n"
                        + "thermal=cpullc-0-0|41000\n"
                        + "thermal=cpu-0-3-0|43000\n"
                        + "thermal=gpuss-0|39000\n"
                        + "thermal=skin-msm-therm|32000\n"
                        + "thermal=battery|31000\n");

        assertTrue(snapshot.fanAvailable);
        assertEquals(1, snapshot.fanEnabled);
        assertEquals(3, snapshot.fanLevel);
        assertEquals(7200, snapshot.fanRpm);
        assertTrue(snapshot.pumpAvailable);
        assertEquals(43_000, snapshot.cpuMilliCelsius);
        assertEquals(39_000, snapshot.gpuMilliCelsius);
        assertEquals(32_000, snapshot.skinMilliCelsius);
        assertEquals(31_000, snapshot.batteryMilliCelsius);
    }

    @Test
    public void rejectsThresholdsAndNonTemperatureValues() {
        final RedmagicHardwareSnapshot snapshot =
                RedmagicHardwareSnapshot.parse(
                        "thermal=cpu-hw-trip-0|105000\n"
                        + "thermal=pmh0101-bcl-lvl0|0\n"
                        + "thermal=vbat|3803\n"
                        + "thermal=gpuss-0|-273000\n");

        assertFalse(snapshot.isAvailable());
        assertEquals(
                RedmagicHardwareSnapshot.UNKNOWN,
                snapshot.cpuMilliCelsius);
        assertEquals(
                RedmagicHardwareSnapshot.UNKNOWN,
                snapshot.gpuMilliCelsius);
    }

    @Test
    public void acceptsThermalMonitoringWithoutRootControlNodes() {
        final RedmagicHardwareSnapshot snapshot =
                RedmagicHardwareSnapshot.parse(
                        "thermal=skin-msm-therm|33000\n"
                        + "thermal=battery|31500\n");

        assertTrue(snapshot.isAvailable());
        assertFalse(snapshot.fanAvailable);
        assertFalse(snapshot.pumpAvailable);
        assertEquals(33_000, snapshot.skinMilliCelsius);
        assertEquals(31_500, snapshot.batteryMilliCelsius);
    }
}
