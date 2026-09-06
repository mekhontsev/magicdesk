package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

public final class SystemMonitorReaderTest {
    @Test
    public void cpuTotalDoesNotCountGuestTimeTwice() throws Exception {
        final SystemMonitorReader.Cpu cpu = SystemMonitorReader.parseCpuStat(
                "cpu 100 20 30 400 5 6 7 8 40 10");
        assertEquals(576L, cpu.total);
        assertEquals(405L, cpu.idle);
    }

    @Test
    public void cpuStatAcceptsBaseCountersAndWhitespace() throws Exception {
        final SystemMonitorReader.Cpu cpu = SystemMonitorReader.parseCpuStat(
                "  cpu\t1 2 3 4  ");
        assertEquals(10L, cpu.total);
        assertEquals(4L, cpu.idle);
    }

    @Test
    public void malformedCpuCountersRemainUnavailable() {
        for (String row : new String[]{null, "", "cpu0 1 2 3 4", "cpu 1 2 3",
                "cpu 1 2 -3 4", "cpu x 2 3 4", "cpu 9223372036854775807 1 0 0"}) {
            assertThrows(java.io.IOException.class, () -> SystemMonitorReader.parseCpuStat(row));
        }
    }

    @Test
    public void overflowingProcessCpuIsNotPublishedAsInfinity() {
        final Map<String, SystemMonitorReader.MutableProcess> processes = new LinkedHashMap<>();
        SystemMonitorReader.parseCpuInfo("9".repeat(400) + "% 100/com.example.app: user", processes);
        assertTrue(processes.isEmpty());
    }

    @Test
    public void parsesCpuForMainAndNamedProcesses() {
        final Map<String, SystemMonitorReader.MutableProcess> processes =
                new LinkedHashMap<>();

        SystemMonitorReader.parseCpuInfo(
                "CPU usage from 100ms to 0ms ago:\n"
                        + "  12% 100/com.example.app: 8% user + 4% kernel\n"
                        + "  3.5% 101/com.example.app:worker: 2% user\n"
                        + "  malformed row\n",
                processes);

        assertEquals(12f, processes.get("com.example.app").cpuPercent, 0.001f);
        assertEquals(
                3.5f,
                processes.get("com.example.app:worker").cpuPercent,
                0.001f);
    }

    @Test
    public void parsesOnlyTotalPssProcessSection() {
        final Map<String, SystemMonitorReader.MutableProcess> processes =
                new LinkedHashMap<>();

        SystemMonitorReader.parseProcessMemory(
                "Total RSS by process:\n"
                        + "  900,000K: com.example.app (pid 100)\n"
                        + "Total PSS by process:\n"
                        + "  123,456K: com.example.app (pid 100 / activities)\n"
                        + "   10,000K: com.example.app:worker (pid 101)\n"
                        + "Total PSS by OOM adjustment:\n"
                        + "  999,999K: com.example.app (pid 100)\n",
                processes);

        assertEquals(123456L, processes.get("com.example.app").pssKb);
        assertEquals(10000L, processes.get("com.example.app:worker").pssKb);
        assertEquals(2, processes.size());
    }

    @Test
    public void ignoresMissingSectionsAndMalformedRows() {
        final Map<String, SystemMonitorReader.MutableProcess> processes =
                new LinkedHashMap<>();

        SystemMonitorReader.parseProcessMemory(
                "Applications Memory Usage\nnot a process row\n",
                processes);

        assertTrue(processes.isEmpty());
    }
}
