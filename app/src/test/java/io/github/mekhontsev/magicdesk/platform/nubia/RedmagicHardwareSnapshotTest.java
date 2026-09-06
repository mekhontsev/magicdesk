package io.github.mekhontsev.magicdesk.platform.nubia;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class RedmagicHardwareSnapshotTest {
    @Test
    public void readsPumpFlowFromTheResolvedControlNamespace() {
        for (final String namespace : new String[] {"system", "global"}) {
            final String[] flows = {"low", "mid", "fast"};
            for (int index = 0; index < flows.length; index++) {
                final RedmagicHardwareSnapshot snapshot = RedmagicHardwareSnapshot.parse(
                        "node.pump_enable=1\n" + pumpSettings(namespace, "0|" + flows[index]));
                assertTrue(snapshot.pumpAvailable);
                assertEquals(60 + index * 10, snapshot.pumpSpeed);
            }
        }
    }

    @Test
    public void unreadableOrUnknownFlowDoesNotBecomeFast() {
        for (final String flow : new String[] {"0|null", "0|", "0|unknown", "1|denied"}) {
            final RedmagicHardwareSnapshot snapshot = RedmagicHardwareSnapshot.parse(
                    "node.pump_enable=1\n" + pumpSettings("global", flow));
            assertEquals(1, snapshot.pumpEnabled);
            assertEquals(RedmagicHardwareSnapshot.UNKNOWN, snapshot.pumpSpeed);
            assertFalse(snapshot.pumpAvailable);
        }
    }

    @Test
    public void ambiguousFlowNamespacesRemainUnknown() {
        final RedmagicHardwareSnapshot snapshot = RedmagicHardwareSnapshot.parse(
                "node.pump_enable=1\n" + pumpSettings("global", "0|low")
                        + pumpSettings("system", "0|fast"));
        assertEquals(RedmagicHardwareSnapshot.UNKNOWN, snapshot.pumpSpeed);
        assertFalse(snapshot.pumpAvailable);
    }

    private static String pumpSettings(final String namespace, final String flow) {
        return "setting." + namespace + ".liquid_cooling_main_switch=0|1\n"
                + "setting." + namespace + ".liquid_cooling_flow_speed_mode=" + flow + "\n";
    }

    @Test
    public void parsesVendorPolicyAndRealThermalSensors() {
        final RedmagicHardwareSnapshot snapshot =
                RedmagicHardwareSnapshot.parse(
                        "node.fan_enable=1\n"
                        + "node.pump_enable=0\n"
                        + "node.pump_speed=80\n"
                        + "thermal=cpullc-0-0|41000\n"
                        + "thermal=cpu-0-3-0|43000\n"
                        + "thermal=cpuss-0|45000\n"
                        + "thermal=gpuss-0|39000\n"
                        + "thermal=skin-msm-therm|32000\n"
                        + "thermal=battery|31000\n");

        assertTrue(snapshot.fanAvailable);
        assertEquals(1, snapshot.fanEnabled);
        assertTrue(snapshot.pumpAvailable);
        assertEquals(45_000, snapshot.cpuMilliCelsius);
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
    public void acceptsThermalMonitoringWithoutVendorPolicyState() {
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
